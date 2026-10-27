package dev.tmdevelopment.flaremobcoins.command;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.data.CoinManager;
import dev.tmdevelopment.flaremobcoins.data.Storage;
import dev.tmdevelopment.flaremobcoins.menu.ChestBuilder;
import dev.tmdevelopment.flaremobcoins.menu.EditorSession;
import dev.tmdevelopment.flaremobcoins.menu.LayoutListMenu;
import dev.tmdevelopment.flaremobcoins.menu.MenuDef;
import dev.tmdevelopment.flaremobcoins.menu.SettingsMenu;
import dev.tmdevelopment.flaremobcoins.update.UpdateChecker;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Ver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** /mobcoins [balance|pay|shop|drops|top|give|remove|set|multiplier|settings|editor|reload|migrate|version|help] */
public final class MobcoinsCommand implements CommandExecutor, TabCompleter, Listener {

    private static final List<String> SUBS = Arrays.asList("help", "balance", "pay", "menu", "shop", "drops", "top", "give",
            "remove", "set", "multiplier", "settings", "editor", "reload", "migrate", "about", "version");
    private final FlareMobcoins plugin;
    private final Map<UUID, Long> payCooldown = new HashMap<>();
    private final Map<UUID, Long> lastLookup = new HashMap<>();

    public MobcoinsCommand(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private CoinManager coins() {
        return plugin.coins();
    }

    /** Players can look up offline balances once per second (each lookup can read the database). */
    private boolean throttled(CommandSender sender) {
        if (!(sender instanceof Player) || sender.hasPermission("flaremobcoins.admin")) return false;
        UUID id = ((Player) sender).getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastLookup.get(id);
        if (last != null && now - last < 1000) {
            lang().send(sender, "general.wait");
            return true;
        }
        lastLookup.put(id, now);
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastLookup.remove(e.getPlayer().getUniqueId());
        payCooldown.remove(e.getPlayer().getUniqueId());
    }

    /** Commands that can be turned off for normal players (config.yml > player-access). */
    private static boolean isPlayerFeature(String sub) {
        return sub.equals("menu") || sub.equals("shop") || sub.equals("drops") || sub.equals("top") || sub.equals("pay");
    }

    /** The player has the permission and the command is not turned off for him. */
    private boolean allowed(CommandSender s, String sub) {
        return perm(s, sub) && (!isPlayerFeature(sub) || plugin.menus().canOpen(s, sub));
    }

    private static boolean perm(CommandSender s, String node) {
        return s.hasPermission("flaremobcoins.command." + node) || s.hasPermission("flaremobcoins.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String rawLabel, String[] args) {
        String label = rawLabel.toLowerCase(Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (args.length == 0) {
            if (sender instanceof Player && perm(sender, "balance")) {
                lang().send(sender, "balance.self", "{label}", label);
                lang().send(sender, "balance.hint", "{label}", label);
            } else {
                help(sender, label);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help":
            case "?":
                help(sender, label);
                return true;
            case "balance":
            case "bal":
                balance(sender, label, args);
                return true;
            case "pay":
            case "send":
                pay(sender, label, args);
                return true;
            case "menu":
            case "gui":
                menu(sender);
                return true;
            case "shop":
            case "shops":
            case "open":
                shop(sender, label, args);
                return true;
            case "drops":
            case "drop":
                drops(sender, args);
                return true;
            case "top":
            case "baltop":
                top(sender, label, args);
                return true;
            case "settings":
            case "admin":
                if (!perm(sender, "settings")) {
                    lang().send(sender, "general.no-permission");
                } else if (!(sender instanceof Player)) {
                    lang().send(sender, "general.players-only");
                } else {
                    new SettingsMenu(plugin, (Player) sender).open();
                }
                return true;
            case "editor":
            case "edit":
            case "layout":
                editor(sender, label, args);
                return true;
            case "give":
            case "add":
                change(sender, label, args, "give");
                return true;
            case "remove":
            case "take":
                change(sender, label, args, "remove");
                return true;
            case "set":
                change(sender, label, args, "set");
                return true;
            case "multiplier":
            case "multi":
                multiplier(sender, label, args);
                return true;
            case "reload":
                if (!perm(sender, "reload")) {
                    lang().send(sender, "general.no-permission");
                    return true;
                }
                plugin.reloadAll();
                lang().send(sender, "general.reloaded", "{shops}", String.valueOf(plugin.menus().size()),
                        "{drops}", String.valueOf(plugin.drops().size()));
                return true;
            case "migrate":
            case "import":
                migrate(sender, label, args);
                return true;
            case "about":
            case "version":
            case "ver":
                version(sender, label, args);
                return true;
            default:
                if (args.length == 1 && perm(sender, "balance.others") && Bukkit.getPlayerExact(args[0]) != null) {
                    balance(sender, label, new String[]{"balance", args[0]});
                } else {
                    lang().send(sender, "general.unknown-command", "{label}", label);
                }
                return true;
        }
    }

    private void help(CommandSender sender, String label) {
        if (!perm(sender, "help") && !perm(sender, "balance")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        lang().send(sender, "help.header", "{label}", label);
        boolean admin = false;
        for (String sub : new String[]{"balance", "pay", "menu", "shop", "drops", "top", "give", "remove", "set", "multiplier",
                "settings", "editor", "layout", "reload", "migrate", "version"}) {
            if (!allowed(sender, sub.equals("layout") ? "editor" : sub)) continue;
            if (sub.equals("give") || sub.equals("remove") || sub.equals("set")) admin = true;
            lang().send(sender, "help.lines." + sub, "{label}", label);
        }
        if (admin) lang().send(sender, "help.footer", "{label}", label);
    }

    private static String[] amountPairs(double amount) {
        return new String[]{"{amount}", Num.plain(amount), "{amount_commas}", Num.commas(amount), "{amount_formatted}", Num.shortFormat(amount)};
    }

    private static String[] join(String[] a, String... b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void balance(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "balance")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            if (sender instanceof Player) lang().send(sender, "balance.self", "{label}", label);
            else lang().send(sender, "usage.balance", "{label}", label);
            return;
        }
        if (!perm(sender, "balance.others") && !perm(sender, "balance.other")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        String name = args[1];
        if (throttled(sender)) return;
        coins().find(name).thenAccept(acc -> {
            if (acc == null) {
                lang().send(sender, "general.player-not-found", "{target}", name);
                return;
            }
            coins().fetch(acc.uuid).thenAccept(bal -> lang().send(sender, "balance.other",
                    join(amountPairs(bal), "{target}", nameOf(acc, name))));
        });
    }

    private void pay(CommandSender sender, String label, String[] args) {
        if (!allowed(sender, "pay")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player)) {
            lang().send(sender, "general.players-only");
            return;
        }
        Player p = (Player) sender;
        if (!plugin.getConfig().getBoolean("pay.enabled", true)) {
            lang().send(p, "pay.disabled");
            return;
        }
        if (args.length < 3) {
            lang().send(p, "usage.pay", "{label}", label);
            return;
        }
        String target = args[1], rawAmount = args[2];
        // 3.x help said "pay [amount] [player]": both orders work
        if (Num.parse(target) >= 0 && Num.parse(rawAmount) < 0) {
            target = args[2];
            rawAmount = args[1];
        }
        double amount = Num.parse(rawAmount);
        if (amount <= 0) {
            lang().send(p, "general.invalid-amount", "{input}", rawAmount);
            return;
        }
        double min = Math.max(0.01, plugin.getConfig().getDouble("pay.min-amount", 1));
        if (amount < min) {
            lang().send(p, "pay.min", "{min}", Num.commas(min));
            return;
        }
        if (target.equalsIgnoreCase(p.getName())) {
            lang().send(p, "pay.self");
            return;
        }
        long cooldown = plugin.getConfig().getLong("pay.cooldown-seconds", 0) * 1000L;
        Long last = payCooldown.get(p.getUniqueId());
        if (cooldown > 0 && last != null && System.currentTimeMillis() - last < cooldown && !p.hasPermission("flaremobcoins.bypass.cooldown")) {
            long left = (cooldown - (System.currentTimeMillis() - last) + 999) / 1000;
            lang().send(p, "pay.cooldown", "{seconds}", String.valueOf(left));
            return;
        }
        if (throttled(p)) return;
        String typed = target;
        coins().find(target).thenAccept(acc -> {
            if (!p.isOnline()) return;
            if (acc == null) {
                lang().send(p, "general.player-not-found", "{target}", typed);
                return;
            }
            if (acc.uuid.equals(p.getUniqueId())) {
                lang().send(p, "pay.self");
                return;
            }
            Player online = Bukkit.getPlayer(acc.uuid);
            String name = nameOf(acc, typed);
            if (online == null && !plugin.getConfig().getBoolean("pay.allow-offline", true)) {
                lang().send(p, "pay.offline", "{target}", name);
                return;
            }
            if (!coins().spend(p, amount, "pay to " + name)) {
                lang().send(p, "pay.not-enough", amountPairs(amount));
                Actions.sound(p, "sounds.error");
                return;
            }
            payCooldown.put(p.getUniqueId(), System.currentTimeMillis());
            double after = coins().give(acc.uuid, name, amount, "pay from " + p.getName()).getNow(0d);
            if (after == CoinManager.CANCELLED) {
                coins().give(p.getUniqueId(), p.getName(), amount, "pay refund");
                lang().send(p, "general.cancelled");
                return;
            }
            lang().send(p, "pay.sent", join(amountPairs(amount), "{target}", name));
            Actions.sound(p, "sounds.pay");
            if (online != null) {
                lang().send(online, "pay.received", join(amountPairs(amount), "{player}", p.getName()));
                Actions.sound(online, "sounds.receive");
            }
        });
    }

    private void shop(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "shop")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        List<String> a = new ArrayList<>(Arrays.asList(args));
        // 3.x: /mobcoins shop list | /mobcoins shop open <shop> [player]
        if (a.size() >= 2 && a.get(1).equalsIgnoreCase("list")) {
            lang().send(sender, "shop.list-header", "{count}", String.valueOf(plugin.menus().size()), "{label}", label);
            for (String s : plugin.menus().names()) lang().send(sender, "shop.list-line", "{shop}", s, "{label}", label);
            return;
        }
        if (a.size() >= 3 && a.get(1).equalsIgnoreCase("open") && plugin.menus().get("open") == null) a.remove(1);
        String shop = plugin.menus().mainShop();
        Player target = sender instanceof Player ? (Player) sender : null;
        if (a.size() >= 2) {
            Player named = Bukkit.getPlayerExact(a.get(1));
            if (plugin.menus().get(a.get(1)) == null && named != null) {
                target = named;
                if (a.size() >= 3) shop = a.get(2);
            } else {
                shop = a.get(1);
                if (a.size() >= 3) target = Bukkit.getPlayerExact(a.get(2));
            }
            if (a.size() >= 3 && target == null) {
                lang().send(sender, "general.player-not-found", "{target}", a.get(2));
                return;
            }
        }
        if (target == null) {
            lang().send(sender, "general.players-only");
            return;
        }
        boolean forOther = !(sender instanceof Player) || !((Player) sender).getUniqueId().equals(target.getUniqueId());
        if (forOther && sender instanceof Player && !perm(sender, "shop.others")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (!forOther && !plugin.menus().canOpen(sender, "shop")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        boolean opened = plugin.menus().open(target, shop, !forOther);
        if (opened && forOther) lang().send(sender, "shop.opened-for", "{shop}", shop, "{target}", target.getName());
    }

    private void drops(CommandSender sender, String[] args) {
        if (!perm(sender, "drops")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        Player target = sender instanceof Player ? (Player) sender : null;
        if (args.length >= 2) {
            if (!perm(sender, "drops.other")) {
                lang().send(sender, "general.no-permission");
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                lang().send(sender, "general.player-not-found", "{target}", args[1]);
                return;
            }
        }
        if (target == null) {
            lang().send(sender, "general.players-only");
            return;
        }
        boolean forOther = !(sender instanceof Player) || !((Player) sender).getUniqueId().equals(target.getUniqueId());
        if (!forOther && !plugin.menus().canOpen(sender, "drops")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        plugin.menus().open(target, "drops", !forOther);
    }

    private void top(CommandSender sender, String label, String[] args) {
        if (!allowed(sender, "top")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        // players get the menu (menus/top.yml), "/mobcoins top chat" and the console the chat list
        if (sender instanceof Player && (args.length < 2 || !args[1].equalsIgnoreCase("chat"))) {
            MenuDef menu = plugin.menus().builtin("top");
            if (menu != null && menu.enabled()) {
                plugin.menus().open((Player) sender, "top", true);
                return;
            }
        }
        List<Storage.Account> list = coins().top();
        lang().send(sender, "top.header", "{label}", label);
        if (list.isEmpty()) {
            lang().send(sender, "top.empty");
        } else {
            int n = Math.min(list.size(), Math.max(1, plugin.getConfig().getInt("top.size", 10)));
            for (int i = 0; i < n; i++) {
                Storage.Account a = list.get(i);
                lang().send(sender, "top.line", join(amountPairs(a.mobcoins), "{position}", String.valueOf(i + 1),
                        "{name}", nameOf(a, "?")));
            }
        }
        lang().send(sender, "top.footer", "{label}", label);
    }

    private void change(CommandSender sender, String label, String[] args, String what) {
        if (!perm(sender, what)) {
            lang().send(sender, "general.no-permission");
            return;
        }
        List<String> a = new ArrayList<>(Arrays.asList(args));
        boolean silent = a.removeIf(s -> s.equalsIgnoreCase("-s"));
        if (a.size() < 3) {
            lang().send(sender, "usage." + what, "{label}", label);
            return;
        }
        String who = a.get(1);
        double amount = Num.parse(a.get(2));
        if (amount < 0 || amount == 0 && !what.equals("set")) {
            lang().send(sender, "general.invalid-amount", "{input}", a.get(2));
            return;
        }
        String cause = "command by " + sender.getName();
        if (who.equals("*") || who.equalsIgnoreCase("@a")) {
            if (what.equals("set")) {
                lang().send(sender, "usage.set", "{label}", label);
                return;
            }
            int count = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                count++;
                if (what.equals("give")) coins().give(p.getUniqueId(), p.getName(), amount, cause);
                else coins().take(p.getUniqueId(), p.getName(), amount, false, cause);
                if (!silent) notify(p, what, amount);
            }
            lang().send(sender, what.equals("give") ? "admin.give-all" : "admin.remove-all",
                    join(amountPairs(amount), "{count}", String.valueOf(count)));
            return;
        }
        coins().find(who).thenAccept(acc -> {
            if (acc == null) {
                lang().send(sender, "general.player-not-found", "{target}", who);
                return;
            }
            String name = nameOf(acc, who);
            CompletableFuture<Double> f;
            if (what.equals("give")) f = coins().give(acc.uuid, name, amount, cause);
            else if (what.equals("remove")) f = coins().take(acc.uuid, name, amount, false, cause);
            else f = coins().set(acc.uuid, name, amount, cause);
            f.thenAccept(after -> {
                if (after == CoinManager.CANCELLED) {
                    lang().send(sender, "general.cancelled");
                    return;
                }
                lang().send(sender, "admin." + what, join(amountPairs(amount), "{target}", name,
                        "{mobcoins_after}", Num.commas(Math.max(0, after))));
                Player online = Bukkit.getPlayer(acc.uuid);
                boolean self = sender instanceof Player && ((Player) sender).getUniqueId().equals(acc.uuid);
                if (online != null && !silent && !(self && what.equals("set"))) {
                    notify(online, what, what.equals("set") ? Math.max(0, after) : amount);
                }
            });
        });
    }

    private void notify(Player p, String what, double amount) {
        lang().send(p, "received." + what, amountPairs(amount));
        if (what.equals("give")) Actions.sound(p, "sounds.receive");
    }

    /** multiplier set &lt;player&gt; &lt;value&gt; | reset &lt;player&gt; | global &lt;value&gt; | check [player] */
    private void multiplier(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "multiplier")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "global": {
                if (args.length < 3) break;
                double v = Num.parseDouble(args[2].replace("x", ""), -1);
                if (v <= 0) {
                    lang().send(sender, "multiplier.invalid", "{input}", args[2]);
                    return;
                }
                coins().setGlobalMultiplier(v);
                lang().send(sender, "multiplier.global", "{value}", Num.plain(v));
                return;
            }
            case "set":
            case "reset": {
                if (args.length < 3 || sub.equals("set") && args.length < 4) break;
                double v = sub.equals("reset") ? 1 : Num.parseDouble(args[3].replace("x", ""), -1);
                if (v <= 0) {
                    lang().send(sender, "multiplier.invalid", "{input}", args[3]);
                    return;
                }
                String who = args[2];
                coins().find(who).thenAccept(acc -> {
                    if (acc == null) {
                        lang().send(sender, "general.player-not-found", "{target}", who);
                        return;
                    }
                    String name = nameOf(acc, who);
                    coins().setMultiplier(acc.uuid, name, v).thenAccept(done -> {
                        lang().send(sender, "multiplier.set", "{target}", name, "{value}", Num.plain(done));
                        Player online = Bukkit.getPlayer(acc.uuid);
                        boolean self = sender instanceof Player && ((Player) sender).getUniqueId().equals(acc.uuid);
                        if (online != null && !self) lang().send(online, "multiplier.received", "{value}", Num.plain(done));
                    });
                });
                return;
            }
            case "check":
            case "info": {
                String who = args.length >= 3 ? args[2] : sender.getName();
                coins().find(who).thenAccept(acc -> {
                    if (acc == null) {
                        lang().send(sender, "general.player-not-found", "{target}", who);
                        return;
                    }
                    coins().fetchMultiplier(acc.uuid).thenAccept(m -> lang().send(sender, "multiplier.check",
                            "{target}", nameOf(acc, who), "{value}", Num.plain(m)));
                });
                return;
            }
            default:
                break;
        }
        lang().send(sender, "usage.multiplier", "{label}", label);
    }

    private void migrate(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "migrate")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            lang().send(sender, "usage.migrate", "{label}", label);
            return;
        }
        String to = args[1].toLowerCase(Locale.ROOT);
        if (to.equals("file")) to = "yaml";
        if (to.equals("mariadb")) to = "mysql";
        if (!to.equals("yaml") && !to.equals("sqlite") && !to.equals("mysql")) {
            lang().send(sender, "usage.migrate", "{label}", label);
            return;
        }
        String now = coins().storage().type();
        if (now.equals(to) || to.equals("mysql") && now.equals("mariadb")) {
            lang().send(sender, "admin.migrate-same", "{target}", to);
            return;
        }
        Storage target = plugin.createStorage(to);
        String name = to;
        lang().send(sender, "admin.migrate-start", "{target}", name);
        coins().migrate(target, result -> {
            if (result.startsWith("error: ")) lang().send(sender, "admin.migrate-error", "{error}", result.substring(7));
            else lang().send(sender, "admin.migrate-done", "{target}", name, "{count}", result);
        });
    }

    /** /mobcoins editor [menu]: the list of menus and shops, or the editor of one. */
    private void editor(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "editor")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player)) {
            lang().send(sender, "general.players-only");
            return;
        }
        Player p = (Player) sender;
        if (args.length < 2) {
            new LayoutListMenu(plugin, p).open();
            return;
        }
        if (ChestBuilder.isBuilderCommand(args[1])) {
            // chest builder: /mobcoins layout add|import|chest|remove <name>
            plugin.chestBuilder().run(p, label + " " + args[0].toLowerCase(Locale.ROOT), Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        MenuDef def = plugin.menus().def(args[1]);
        if (def == null) lang().send(p, "shop.not-found", "{shop}", args[1]);
        else new EditorSession(plugin, p, def).open();
    }

    /** /mobcoins menu: balance, shop, drops and top in one menu (menus/mobcoins.yml). */
    private void menu(CommandSender sender) {
        if (!(sender instanceof Player)) {
            lang().send(sender, "general.players-only");
            return;
        }
        if (!perm(sender, "menu") || !plugin.menus().canOpen(sender, "menu")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        plugin.menus().open((Player) sender, "mobcoins", true);
    }

    private void version(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "version")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (args.length > 1 && args[1].toLowerCase(Locale.ROOT).startsWith("perm")) {
            permissions(sender);
            return;
        }
        boolean[] answered = {false};
        plugin.updates().get().thenAccept(r -> {
            answered[0] = true;
            if (sender instanceof Player && !((Player) sender).isOnline()) return;
            about(sender, label, r);
        });
        if (!answered[0]) lang().send(sender, "about.checking");
    }

    /** /mobcoins about: version (green = latest, red = outdated), server, update notifier, hooks, permissions. */
    private void about(CommandSender sender, String label, UpdateChecker.Result r) {
        UpdateChecker u = plugin.updates();
        String[] base = join(new String[]{"{label}", label, "{version}", plugin.getDescription().getVersion()}, u.pairs(r));
        lang().send(sender, "about.header", base);
        switch (r.state) {
            case OUTDATED:
                lang().send(sender, sender instanceof Player ? "about.outdated" : "about.outdated-console", base);
                break;
            case LATEST:
                lang().send(sender, "about.latest", base);
                break;
            case OFF:
                lang().send(sender, "about.check-off", base);
                break;
            default:
                lang().send(sender, "about.failed", base);
        }
        String sep = lang().raw("about.separator");
        StringBuilder hooks = new StringBuilder();
        String[][] list = {{"PlaceholderAPI", String.valueOf(dev.tmdevelopment.flaremobcoins.util.Placeholders.papi())},
                {"Vault", String.valueOf(dev.tmdevelopment.flaremobcoins.hook.VaultHook.available())},
                {"FlareTokens", String.valueOf(dev.tmdevelopment.flaremobcoins.hook.TokensHook.available())},
                {"ItemsAdder", String.valueOf(enabled("ItemsAdder"))},
                {"ExecutableItems", String.valueOf(enabled("ExecutableItems"))},
                {"RoseStacker", String.valueOf(enabled("RoseStacker"))},
                {"WildStacker", String.valueOf(enabled("WildStacker"))}};
        for (String[] h : list) {
            if (hooks.length() > 0) hooks.append(sep);
            hooks.append(lang().raw(Boolean.parseBoolean(h[1]) ? "about.hooked" : "about.missing").replace("{hook}", h[0]));
        }
        lang().send(sender, "about.info", "{label}", label, "{version}", plugin.getDescription().getVersion(),
                "{server}", UpdateChecker.serverSoftware(), "{minecraft}", Ver.text(),
                "{java}", UpdateChecker.javaVersion(), "{storage}", coins().storage().type(),
                "{notifier}", lang().raw(u.enabled() ? "about.notifier-on" : "about.notifier-off"), "{hooks}", hooks.toString());
    }

    private static boolean enabled(String name) {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }

    /** Every permission of plugin.yml with its description and who has it by default. */
    private void permissions(CommandSender sender) {
        lang().send(sender, "about.permissions.header");
        for (org.bukkit.permissions.Permission perm : plugin.getDescription().getPermissions()) {
            String def;
            switch (perm.getDefault()) {
                case TRUE:
                    def = lang().raw("about.permissions.everyone");
                    break;
                case OP:
                    def = lang().raw("about.permissions.op");
                    break;
                case NOT_OP:
                    def = lang().raw("about.permissions.not-op");
                    break;
                default:
                    def = lang().raw("about.permissions.nobody");
            }
            String desc = perm.getDescription() == null || perm.getDescription().isEmpty() ? "-" : perm.getDescription();
            lang().send(sender, "about.permissions.line", "{permission}", perm.getName(), "{description}", desc, "{default}", def);
        }
    }

    private static String nameOf(Storage.Account acc, String typed) {
        if (acc.name != null && !acc.name.isEmpty()) return acc.name;
        OfflinePlayer op = Bukkit.getOfflinePlayer(acc.uuid);
        return op.getName() != null ? op.getName() : typed;
    }

    // ------------------------------------------------------------------ tab

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                String need = s.equals("about") ? "version" : s;
                if ((s.equals("help") || allowed(sender, need)) && s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "shop":
                case "open": {
                    List<String> names = new ArrayList<>(plugin.menus().names());
                    names.add("list");
                    return filter(names, args[1]);
                }
                case "migrate":
                    return filter(Arrays.asList("yaml", "sqlite", "mysql"), args[1]);
                case "editor":
                case "edit":
                case "layout": {
                    if (!perm(sender, "editor")) return Collections.emptyList();
                    List<String> names = new ArrayList<>(plugin.menus().names());
                    if (sub.equals("layout")) names.addAll(0, ChestBuilder.subs());
                    names.add("mobcoins");
                    names.add("top");
                    names.add("drops");
                    names.add("confirm");
                    return filter(names, args[1]);
                }
                case "settings":
                    return Collections.emptyList();
                case "multiplier":
                    return filter(Arrays.asList("set", "reset", "global", "check"), args[1]);
                case "about":
                case "version":
                case "ver":
                    return perm(sender, "version") ? filter(Collections.singletonList("permissions"), args[1]) : Collections.<String>emptyList();
                case "menu":
                case "gui":
                case "top":
                case "reload":
                case "help":
                    return Collections.emptyList();
                default: {
                    List<String> p = players(args[1]);
                    if ((sub.equals("give") || sub.equals("add") || sub.equals("remove") || sub.equals("take"))
                            && perm(sender, sub.equals("add") ? "give" : sub.equals("take") ? "remove" : sub)) p.add(0, "*");
                    return p;
                }
            }
        }
        if (args.length >= 3 && (sub.equals("layout") || sub.equals("editor") || sub.equals("edit"))
                && ChestBuilder.isBuilderCommand(args[1])) {
            return perm(sender, "editor") ? plugin.chestBuilder().tab(Arrays.copyOfRange(args, 1, args.length)) : Collections.<String>emptyList();
        }
        if (args.length == 3) {
            switch (sub) {
                case "pay":
                case "give":
                case "add":
                case "remove":
                case "take":
                case "set":
                    return Arrays.asList("10", "100", "1000");
                case "shop":
                case "open":
                    return players(args[2]);
                case "multiplier":
                    return args[1].equalsIgnoreCase("global") ? Arrays.asList("1", "1.5", "2") : players(args[2]);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 4) {
            if (sub.equals("multiplier") && args[1].equalsIgnoreCase("set")) return Arrays.asList("1.5", "2", "3");
            if (sub.equals("give") || sub.equals("remove") || sub.equals("set") || sub.equals("add") || sub.equals("take")) {
                return Collections.singletonList("-s");
            }
        }
        return Collections.emptyList();
    }

    private static List<String> players(String start) {
        List<String> out = new ArrayList<>();
        String s = start.toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(s)) out.add(p.getName());
        return out;
    }

    private static List<String> filter(List<String> options, String start) {
        List<String> out = new ArrayList<>();
        String s = start.toLowerCase(Locale.ROOT);
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(s)) out.add(o);
        return out;
    }
}
