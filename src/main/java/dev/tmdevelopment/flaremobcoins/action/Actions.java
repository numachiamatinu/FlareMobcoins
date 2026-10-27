package dev.tmdevelopment.flaremobcoins.action;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.hook.TokensHook;
import dev.tmdevelopment.flaremobcoins.hook.VaultHook;
import dev.tmdevelopment.flaremobcoins.menu.MenuSession;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the action lists of shops, drops and coins: "[TYPE] value" lines, checks and if / do / else / end blocks.
 * A failed check or a failed purchase stops the lines after it.
 */
public final class Actions {

    private static final Pattern TAG = Pattern.compile("^\\s*\\[([^\\]]+)]\\s?(.*)$", Pattern.DOTALL);
    private static final Pattern KV = Pattern.compile("(\\w+)=(\"[^\"]*\"|'[^']*'|\\S+)");
    private static final Pattern OLD_PERMISSION = Pattern.compile("(?i)\\[?(require|check)\\((!)?permission,(.*?)\\)]?");
    private static final Pattern OLD_EXPRESSION_1 = Pattern.compile("(?i)\\[?(require|check)\\(expression;(.*?);(.*?);(.*?)\\)]?");
    private static final Pattern OLD_EXPRESSION_2 = Pattern.compile("(?i)\\[?(require|check)\\(expression\\)]?\\s*(\\S*) (\\S*) (\\S*)");
    private static final Pattern OLD_MONEY_1 = Pattern.compile("(?i)\\[?(require|check)\\(money;(.*?)\\)]?");
    private static final Pattern OLD_MONEY_2 = Pattern.compile("(?i)\\[?(require|check)\\(money\\)]?\\s*amount=(\\S+)(\\s+remove=(\\S+))?");
    private static final Pattern OLD_COINS = Pattern.compile("(?i)\\[?(require|check)\\((mobcoins|tokens);(.*?)\\)]?");
    private static final Set<String> warned = Collections.synchronizedSet(new HashSet<>());

    private Actions() {
    }

    /** @return true if every line ran (no check or purchase stopped them). */
    public static boolean run(Player player, List<String> lines, Context ctx) {
        if (lines == null || lines.isEmpty() || player == null) return true;
        return run(player, compile(lines), 0, ctx);
    }

    private static boolean run(Player player, List<Step> steps, int from, Context ctx) {
        for (int i = from; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.delay > 0) {
                int next = i + 1;
                Bukkit.getScheduler().runTaskLater(FlareMobcoins.get(), () -> {
                    if (player.isOnline()) run(player, steps, next, ctx);
                }, step.delay);
                return true;
            }
            if (step.block != null) {
                boolean ok = true;
                for (String cond : step.block.conditions) {
                    if (!condition(player, cond, ctx)) {
                        ok = false;
                        break;
                    }
                }
                List<String> branch = ok ? step.block.then : step.block.otherwise;
                if (!branch.isEmpty() && !run(player, compile(branch), 0, ctx)) return false;
                continue;
            }
            if (isCondition(step.line)) {
                if (!condition(player, step.line, ctx)) return false;
            } else if (action(player, step.line, ctx) == State.STOP) {
                return false;
            }
        }
        return true;
    }

    /** true if every check passes (empty list = true). */
    public static boolean check(Player player, List<String> checks, Context ctx) {
        if (checks == null) return true;
        for (String c : checks) if (!condition(player, c, ctx)) return false;
        return true;
    }

    private static List<Step> compile(List<String> lines) {
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("if")) {
                Block block = new Block();
                int depth = 0;
                int part = 0;
                for (i++; i < lines.size(); i++) {
                    String l = lines.get(i) == null ? "" : lines.get(i).trim();
                    if (l.equalsIgnoreCase("if")) depth++;
                    if (depth == 0 && part == 0 && l.equalsIgnoreCase("do")) {
                        part = 1;
                        continue;
                    }
                    if (depth == 0 && part == 1 && l.equalsIgnoreCase("else")) {
                        part = 2;
                        continue;
                    }
                    if (l.equalsIgnoreCase("end")) {
                        if (depth == 0) break;
                        depth--;
                    }
                    (part == 0 ? block.conditions : part == 1 ? block.then : block.otherwise).add(l);
                }
                Step s = new Step();
                s.block = block;
                out.add(s);
                continue;
            }
            Matcher m = TAG.matcher(line);
            if (m.find() && m.group(1).trim().equalsIgnoreCase("delay")) {
                Step s = new Step();
                s.delay = Math.max(1, Num.parseInt(m.group(2), 20));
                out.add(s);
                continue;
            }
            Step s = new Step();
            s.line = line;
            out.add(s);
        }
        return out;
    }

    private static boolean isCondition(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        if (l.startsWith("check(") || l.startsWith("require(") || l.startsWith("[check(") || l.startsWith("[require(")) return true;
        Matcher m = TAG.matcher(line);
        if (!m.find()) return false;
        String tag = m.group(1).trim().toLowerCase(Locale.ROOT);
        if (tag.startsWith("!")) tag = tag.substring(1);
        switch (tag) {
            case "has_mobcoins":
            case "has_tokens":
            case "has_money":
            case "has_permission":
            case "permission":
            case "expression":
            case "check":
                return true;
            default:
                return false;
        }
    }

    public static boolean condition(Player player, String line, Context ctx) {
        String text = resolve(player, line, ctx).trim();
        FlareMobcoins plugin = FlareMobcoins.get();
        Matcher m;
        if ((m = OLD_PERMISSION.matcher(text)).find()) {
            boolean has = player.hasPermission(m.group(3).trim());
            return m.group(2) != null ? !has : has;
        }
        if ((m = OLD_EXPRESSION_1.matcher(text)).find()) return compare(m.group(2).trim(), m.group(3).trim(), m.group(4).trim());
        if ((m = OLD_EXPRESSION_2.matcher(text)).find()) return compare(m.group(2).trim(), m.group(3).trim(), m.group(4).trim());
        if ((m = OLD_MONEY_2.matcher(text)).find()) {
            double amount = Num.parseDouble(m.group(2), 0);
            if (!VaultHook.has(player, amount)) return false;
            if (m.group(4) != null && Boolean.parseBoolean(m.group(4))) VaultHook.withdraw(player, amount);
            return true;
        }
        if ((m = OLD_MONEY_1.matcher(text)).find()) return VaultHook.has(player, Num.parseDouble(m.group(2), 0));
        if ((m = OLD_COINS.matcher(text)).find()) {
            double need = Num.parse(m.group(3));
            if (m.group(2).equalsIgnoreCase("tokens")) return TokensHook.balance(player) >= need;
            return plugin.coins().balance(player.getUniqueId()) + 0.0001 >= need;
        }
        if (text.toLowerCase(Locale.ROOT).contains("javascript")) {
            warnOnce("javascript", "check(javascript,...) is not supported anymore (Java removed its JavaScript engine). Use check(expression;a;op;b).");
            return true;
        }
        m = TAG.matcher(text);
        if (!m.find()) return true;
        String tag = m.group(1).trim().toLowerCase(Locale.ROOT);
        String value = m.group(2).trim();
        boolean negate = tag.startsWith("!");
        if (negate) tag = tag.substring(1);
        boolean result;
        switch (tag) {
            case "has_mobcoins":
                result = plugin.coins().balance(player.getUniqueId()) + 0.0001 >= Math.max(0, Num.parse(value));
                break;
            case "has_tokens":
                result = TokensHook.balance(player) >= Math.max(0, Num.parse(value));
                break;
            case "has_money":
                result = VaultHook.has(player, Num.parseDouble(value, 0));
                break;
            case "has_permission":
            case "permission":
                result = player.hasPermission(value);
                break;
            case "expression":
            case "check": {
                String[] parts = value.split("\\s+");
                result = parts.length >= 3 && compare(parts[0], parts[1], join(parts, 2));
                break;
            }
            default:
                return true;
        }
        return negate != result;
    }

    private static String join(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static boolean compare(String left, String op, String right) {
        String a = Text.plain(left).replace(",", "");
        String b = Text.plain(right).replace(",", "");
        BigDecimal na = number(a), nb = number(b);
        switch (op) {
            case "==":
            case "=":
                return na != null && nb != null ? na.compareTo(nb) == 0 : a.equals(b);
            case "!=":
                return na != null && nb != null ? na.compareTo(nb) != 0 : !a.equals(b);
            case "equalsignorecase":
                return a.equalsIgnoreCase(b);
            default:
                if (na == null || nb == null) return false;
                int c = na.compareTo(nb);
                switch (op) {
                    case ">": return c > 0;
                    case "<": return c < 0;
                    case ">=": return c >= 0;
                    case "<=": return c <= 0;
                    default:
                        warnOnce("op:" + op, "Unknown operator '" + op + "' in a check (use == != > < >= <=).");
                        return false;
                }
        }
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolve(Player player, String text, Context ctx) {
        return Placeholders.apply(text, player, ctx == null ? new String[0] : ctx.pairs());
    }

    // ------------------------------------------------------------------ actions

    private static State action(Player player, String line, Context ctx) {
        Matcher m = TAG.matcher(line);
        if (!m.find()) {
            warnOnce("noTag:" + line, "Action without [type]: '" + line + "' (example: \"[CONSOLE] say hi\").");
            return State.NEXT;
        }
        String tag = m.group(1).trim();
        String type = tag.toLowerCase(Locale.ROOT).replace(" ", "");
        String value = m.group(2);
        FlareMobcoins plugin = FlareMobcoins.get();
        String cause = ctx == null ? "action" : ctx.cause;

        if (type.startsWith("buy")) return buy(player, type, resolve(player, value, ctx).trim(), line, cause);

        if (type.startsWith("mobcoins_") || type.startsWith("mobcoin_")) {
            String what = type.substring(type.indexOf('_') + 1);
            double amount = Num.roll(resolve(player, value, ctx));
            switch (what) {
                case "give":
                case "add":
                    plugin.coins().give(player.getUniqueId(), player.getName(), amount, cause);
                    break;
                case "remove":
                case "take":
                    plugin.coins().take(player.getUniqueId(), player.getName(), amount, false, cause);
                    break;
                case "set":
                    plugin.coins().set(player.getUniqueId(), player.getName(), amount, cause);
                    break;
                default:
                    return bad(line);
            }
            return State.NEXT;
        }
        if (type.startsWith("tokens_") || type.startsWith("token_")) {
            if (!TokensHook.available()) {
                warnOnce("tokens", "[tokens_...] needs FlareTokens 4.x.");
                return State.NEXT;
            }
            long amount = Math.round(Num.roll(resolve(player, value, ctx)));
            String what = type.substring(type.indexOf('_') + 1);
            if (what.equals("give") || what.equals("add")) TokensHook.give(player, amount);
            else if (what.equals("remove") || what.equals("take")) TokensHook.remove(player, amount);
            else if (what.equals("set")) TokensHook.set(player, amount);
            else return bad(line);
            return State.NEXT;
        }
        if (type.startsWith("money_")) {
            if (!VaultHook.available()) {
                warnOnce("vault", "[money_...] needs Vault and an economy plugin.");
                return State.NEXT;
            }
            double amount = Num.parseDouble(resolve(player, value, ctx), 0);
            String what = type.substring(6);
            if (what.equals("give") || what.equals("add")) VaultHook.deposit(player, amount);
            else if (what.equals("remove") || what.equals("take")) VaultHook.withdraw(player, amount);
            else if (what.equals("set")) VaultHook.set(player, amount);
            return State.NEXT;
        }
        if (type.startsWith("message(")) {
            String opt = type.substring(8);
            if (opt.contains("broadcast")) Lang.broadcast(resolve(player, value, ctx));
            else Lang.deliver(player, Placeholders.pairs(value, ctx == null ? new String[0] : ctx.pairs()).replace("{player}", player.getName()));
            return State.NEXT;
        }
        switch (type) {
            case "console":
            case "console_command":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(resolve(player, value, ctx)));
                return State.NEXT;
            case "player":
            case "command":
            case "player_command":
                player.performCommand(stripSlash(resolve(player, value, ctx)));
                return State.NEXT;
            case "sudo":
            case "chat":
                player.chat(resolve(player, value, ctx));
                return State.NEXT;
            case "message":
            case "msg":
                Lang.deliver(player, resolve(player, value, ctx));
                return State.NEXT;
            case "broadcast":
                Lang.broadcast(resolve(player, value, ctx));
                return State.NEXT;
            case "actionbar":
            case "action_bar":
                Compat.actionBar(player, Text.legacy(resolve(player, value, ctx)));
                return State.NEXT;
            case "title":
                title(player, resolve(player, value, ctx));
                return State.NEXT;
            case "sound":
                soundAction(player, resolve(player, value, ctx));
                return State.NEXT;
            case "effect":
                effect(player, resolve(player, value, ctx), line);
                return State.NEXT;
            case "bossbar":
            case "boss_bar":
                BossBars.show(player, resolve(player, value, ctx));
                return State.NEXT;
            case "switch_server":
            case "server":
            case "connect":
                connect(player, resolve(player, value, ctx).trim());
                return State.NEXT;
            case "close":
            case "exit":
            case "close_menu":
            case "bye":
            case "leave":
            case "stop":
                closeLater(player);
                return State.NEXT;
            case "refresh":
            case "update":
                if (ctx != null && ctx.session != null) ctx.session.refresh();
                return State.NEXT;
            case "open":
            case "open_menu":
            case "open_shop":
            case "openmenu":
            case "openshop":
            case "menu":
            case "shop": {
                String shop = resolve(player, value, ctx).trim();
                plugin.menus().openLater(player, shop, ctx == null ? null : ctx.session);
                return State.NEXT;
            }
            case "back":
                if (ctx != null && ctx.session != null) ctx.session.back();
                else closeLater(player);
                return State.NEXT;
            case "op":
                warnOnce("op", "[op] is not supported for safety reasons. Use [CONSOLE] with {player}.");
                return State.NEXT;
            default:
                if (type.startsWith("op(")) {
                    warnOnce("op", "[op(...)] is not supported for safety reasons. Use [CONSOLE] with {player}.");
                } else {
                    warnOnce("unknown:" + type, "Unknown action [" + tag + "] in: " + line);
                }
                return State.NEXT;
        }
    }

    private static State buy(Player player, String type, String value, String line, String cause) {
        FlareMobcoins plugin = FlareMobcoins.get();
        String currency = type.contains("(")
                ? type.substring(type.indexOf('(') + 1, type.lastIndexOf(')') < 0 ? type.length() : type.lastIndexOf(')'))
                : "mobcoins";
        // 3.x: "[buy(mobcoins)] 100 in_rotating_shop='20'" (the stock is handled by the shop now)
        String price = value.replaceAll("\\s*in_rotating_shop=.*$", "").trim();
        switch (currency) {
            case "money": {
                double amount = Num.parseDouble(price, -1);
                if (amount < 0) return bad(line);
                if (!VaultHook.available()) {
                    warnOnce("vault", "[buy(money)] needs Vault and an economy plugin.");
                    return State.STOP;
                }
                if (!VaultHook.has(player, amount)) {
                    plugin.lang().send(player, "shop.not-enough-money", "{price}", Num.commas(amount), "{amount}", price);
                    sound(player, "sounds.error");
                    return State.STOP;
                }
                VaultHook.withdraw(player, amount);
                return State.NEXT;
            }
            case "tokens":
            case "token": {
                long amount = Math.round(Num.parse(price));
                if (amount < 0) return bad(line);
                if (!TokensHook.available()) {
                    warnOnce("tokens", "[buy(tokens)] needs FlareTokens 4.x.");
                    return State.STOP;
                }
                if (!TokensHook.take(player, amount)) {
                    plugin.lang().send(player, "shop.not-enough-tokens", "{price}", Num.commas(amount), "{amount}", price);
                    sound(player, "sounds.error");
                    return State.STOP;
                }
                return State.NEXT;
            }
            case "mobcoins":
            case "mobcoin":
            case "coins": {
                double amount = Num.parse(price);
                if (amount < 0) return bad(line);
                if (!plugin.coins().spend(player, amount, cause)) {
                    double has = plugin.coins().balance(player.getUniqueId());
                    plugin.lang().send(player, "shop.not-enough", "{price}", Num.commas(amount), "{amount}", Num.commas(amount),
                            "{missing}", Num.commas(Math.max(0, amount - has)));
                    sound(player, "sounds.error");
                    return State.STOP;
                }
                return State.NEXT;
            }
            default:
                warnOnce("buy:" + currency, "[buy(" + currency + ")] is not supported: use [buy(mobcoins)], [buy(money)] or [buy(tokens)].");
                return State.STOP;
        }
    }

    private static State bad(String line) {
        warnOnce("bad:" + line, "Wrong value in the action: " + line);
        return State.STOP;
    }

    private static String stripSlash(String s) {
        String t = s.trim();
        return t.startsWith("/") ? t.substring(1) : t;
    }

    /** Plays a sound of config.yml > sounds. */
    /** Closes the menu on the next tick, only if the player still has the same menu open. */
    public static void closeLater(Player player) {
        InventoryHolder holder = Compat.topHolder(player);
        Bukkit.getScheduler().runTask(FlareMobcoins.get(), () -> {
            if (player.isOnline() && Compat.topHolder(player) == holder) player.closeInventory();
        });
    }

    public static void sound(Player player, String path) {
        FlareMobcoins plugin = FlareMobcoins.get();
        if (!plugin.getConfig().getBoolean("sounds.enabled", true)) return;
        String name = plugin.getConfig().getString(path, "");
        if (name != null && !name.isEmpty()) Compat.sound(player, name, 1f, 1f);
    }

    /** "title;subtitle" or "title;subtitle;fadeIn;stay;fadeOut" (seconds) or the 3.x "title=.. subtitle=..". */
    private static void title(Player player, String value) {
        String title, subtitle = "";
        int fadeIn = 10, stay = 40, fadeOut = 10;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("title=") || lower.startsWith("subtitle=")) {
            int sub = lower.indexOf("subtitle=");
            if (lower.startsWith("subtitle=")) {
                title = "";
                subtitle = value.substring(9);
            } else if (sub > 0) {
                title = value.substring(6, sub).trim();
                subtitle = value.substring(sub + 9).trim();
            } else {
                title = value.substring(6);
            }
        } else {
            String[] parts = value.split(";");
            title = parts[0];
            if (parts.length > 1) subtitle = parts[1];
            if (parts.length > 4) {
                fadeIn = (int) (Num.parseDouble(parts[2], 0.5) * 20);
                stay = (int) (Num.parseDouble(parts[3], 2) * 20);
                fadeOut = (int) (Num.parseDouble(parts[4], 0.5) * 20);
            }
        }
        Compat.title(player, Text.legacy(title), Text.legacy(subtitle), fadeIn, stay, fadeOut);
    }

    /** "NAME volume pitch" or the 3.x "sound=NAME volume=1 pitch=1". */
    private static void soundAction(Player player, String value) {
        String name;
        float volume = 1, pitch = 1;
        if (value.toLowerCase(Locale.ROOT).contains("sound=")) {
            Matcher m = KV.matcher(value);
            name = null;
            while (m.find()) {
                String k = m.group(1).toLowerCase(Locale.ROOT);
                String v = unquote(m.group(2));
                if (k.equals("sound")) name = v;
                else if (k.equals("volume")) volume = (float) Num.parseDouble(v, 1);
                else if (k.equals("pitch")) pitch = (float) Num.parseDouble(v, 1);
            }
        } else {
            String[] parts = value.trim().split("[;\\s]+");
            name = parts[0];
            if (parts.length > 1) volume = (float) Num.parseDouble(parts[1], 1);
            if (parts.length > 2) pitch = (float) Num.parseDouble(parts[2], 1);
        }
        if (name != null) Compat.sound(player, name, volume, pitch);
    }

    /** "SPEED;seconds;level" or the 3.x "effect=SPEED duration=200 amplifier=0" (ticks). */
    private static void effect(Player player, String value, String line) {
        String name;
        int duration = 200, amplifier = 0;
        if (value.toLowerCase(Locale.ROOT).contains("effect=")) {
            Matcher m = KV.matcher(value);
            name = null;
            while (m.find()) {
                String k = m.group(1).toLowerCase(Locale.ROOT);
                String v = unquote(m.group(2));
                if (k.equals("effect")) name = v;
                else if (k.equals("duration")) duration = (int) Num.parseDouble(v, 200);
                else if (k.equals("amplifier")) amplifier = (int) Num.parseDouble(v, 0);
            }
        } else {
            String[] parts = value.trim().split("[;\\s]+");
            name = parts[0];
            if (parts.length > 1) duration = (int) (Num.parseDouble(parts[1], 10) * 20);
            if (parts.length > 2) amplifier = Math.max(0, (int) Num.parseDouble(parts[2], 1) - 1);
        }
        PotionEffectType type = Compat.effect(name);
        if (type == null) {
            warnOnce("effect:" + name, "Unknown effect in: " + line);
            return;
        }
        player.addPotionEffect(new PotionEffect(type, Math.max(1, duration), Math.max(0, amplifier)), true);
    }

    private static void connect(Player player, String server) {
        if (server.isEmpty()) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(FlareMobcoins.get(), "BungeeCord", bytes.toByteArray());
        } catch (Exception e) {
            warnOnce("connect", "Could not send the player to " + server + ": " + e.getMessage());
        }
    }

    static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static void warnOnce(String key, String message) {
        if (warned.add(key)) FlareMobcoins.get().getLogger().warning(message);
    }

    // ------------------------------------------------------------------ model

    private static final class Block {
        final List<String> conditions = new ArrayList<>();
        final List<String> then = new ArrayList<>();
        final List<String> otherwise = new ArrayList<>();
    }

    private static final class Step {
        String line;
        Block block;
        long delay;
    }

    private enum State {
        NEXT, STOP
    }

    /** Where the actions run: cause (for logs), the open shop (or null) and extra placeholders. */
    public static final class Context {
        final String cause;
        final MenuSession session;
        final String[] pairs;

        public Context(String cause, MenuSession session, String... pairs) {
            this.cause = cause;
            this.session = session;
            this.pairs = pairs;
        }

        String[] pairs() {
            if (session == null) return pairs;
            String[] extra = session.pairs();
            String[] all = new String[pairs.length + extra.length];
            System.arraycopy(pairs, 0, all, 0, pairs.length);
            System.arraycopy(extra, 0, all, pairs.length, extra.length);
            return all;
        }
    }
}
