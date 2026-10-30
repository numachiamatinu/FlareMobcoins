package dev.tmdevelopment.flaremobcoins.command;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.data.StatsManager;
import dev.tmdevelopment.flaremobcoins.data.Storage;
import dev.tmdevelopment.flaremobcoins.feature.Drops;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Time;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /mobcoins booster | event | withdraw | sell | stats (added in 4.1.0). */
final class ExtraCommands {

    private final FlareMobcoins plugin;

    ExtraCommands(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private static boolean perm(CommandSender s, String node) {
        return s.hasPermission("flaremobcoins.command." + node) || s.hasPermission("flaremobcoins.admin");
    }

    private static String nameOf(Storage.Account acc, String typed) {
        if (acc.name != null && !acc.name.isEmpty()) return acc.name;
        OfflinePlayer op = Bukkit.getOfflinePlayer(acc.uuid);
        return op.getName() != null ? op.getName() : typed;
    }

    // ------------------------------------------------------------------ booster

    /** booster give|item|remove|check ... ; without arguments: your boosters. */
    void booster(CommandSender sender, String label, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.isEmpty() || sub.equals("check") || sub.equals("info")) {
            String who = args.length >= 3 ? args[2] : null;
            if (who != null && !perm(sender, "booster")) {
                lang().send(sender, "general.no-permission");
                return;
            }
            if (who == null) {
                if (!(sender instanceof Player)) {
                    lang().send(sender, "usage.booster", "{label}", label);
                    return;
                }
                who = sender.getName();
            }
            String typed = who;
            plugin.coins().find(who).thenAccept(acc -> {
                if (acc == null) {
                    lang().send(sender, "general.player-not-found", "{target}", typed);
                    return;
                }
                plugin.stats().fetch(acc.uuid).thenAccept(values -> {
                    List<double[]> active = plugin.boosts().active(values);
                    boolean self = sender instanceof Player && ((Player) sender).getUniqueId().equals(acc.uuid);
                    String target = nameOf(acc, typed);
                    if (active.isEmpty()) {
                        if (self) lang().send(sender, "booster.none");
                        else lang().send(sender, "booster.none-other", "{target}", target);
                        if (self && perm(sender, "booster")) lang().send(sender, "usage.booster", "{label}", label);
                        return;
                    }
                    lang().send(sender, "booster.list-header", "{target}", target);
                    long now = System.currentTimeMillis();
                    for (double[] b : active) {
                        lang().send(sender, "booster.list-line", "{multiplier}", Num.plain(b[0]), "{time}", Time.format((long) b[1] - now));
                    }
                });
            });
            return;
        }
        if (!perm(sender, "booster")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        switch (sub) {
            case "give":
            case "start":
            case "item": {
                if (args.length < 5) break;
                double mult = Num.parseDouble(args[3].replace("x", ""), -1);
                long ms = Time.parse(args[4]);
                if (mult <= 0 || mult > 1000) {
                    lang().send(sender, "multiplier.invalid", "{input}", args[3]);
                    return;
                }
                if (ms <= 0) {
                    lang().send(sender, "general.invalid-time", "{input}", args[4]);
                    return;
                }
                if (sub.equals("item")) {
                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        lang().send(sender, "general.player-not-found", "{target}", args[2]);
                        return;
                    }
                    int amount = args.length >= 6 ? Math.max(1, Math.min(64, Num.parseInt(args[5], 1))) : 1;
                    ItemStack item = plugin.boosts().item(mult, ms, amount);
                    for (ItemStack rest : target.getInventory().addItem(item).values()) {
                        target.getWorld().dropItemNaturally(target.getLocation(), rest);
                    }
                    lang().send(sender, "booster.item-given", "{target}", target.getName(), "{amount}", String.valueOf(amount),
                            "{multiplier}", Num.plain(mult), "{time}", Time.format(ms));
                    if (sender != target) lang().send(target, "booster.item-received", "{amount}", String.valueOf(amount),
                            "{multiplier}", Num.plain(mult), "{time}", Time.format(ms));
                    return;
                }
                String who = args[2];
                plugin.coins().find(who).thenAccept(acc -> {
                    if (acc == null) {
                        lang().send(sender, "general.player-not-found", "{target}", who);
                        return;
                    }
                    String name = nameOf(acc, who);
                    plugin.boosts().give(acc.uuid, mult, ms).thenAccept(until -> {
                        String left = Time.format(until - System.currentTimeMillis());
                        lang().send(sender, "booster.given", "{target}", name, "{multiplier}", Num.plain(mult), "{time}", left);
                        Player online = Bukkit.getPlayer(acc.uuid);
                        if (online != null && online != sender) {
                            lang().send(online, "booster.received", "{multiplier}", Num.plain(mult), "{time}", left);
                            Actions.sound(online, "sounds.receive");
                        }
                    });
                });
                return;
            }
            case "remove":
            case "reset":
            case "clear": {
                if (args.length < 3) break;
                String who = args[2];
                plugin.coins().find(who).thenAccept(acc -> {
                    if (acc == null) {
                        lang().send(sender, "general.player-not-found", "{target}", who);
                        return;
                    }
                    plugin.boosts().remove(acc.uuid, n -> {
                        lang().send(sender, "booster.removed", "{target}", nameOf(acc, who), "{count}", String.valueOf(n));
                        Player online = Bukkit.getPlayer(acc.uuid);
                        if (online != null && online != sender && n > 0) lang().send(online, "booster.removed-you");
                    });
                });
                return;
            }
            default:
                break;
        }
        lang().send(sender, "usage.booster", "{label}", label);
    }

    // ------------------------------------------------------------------ event

    void event(CommandSender sender, String label, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.isEmpty() || sub.equals("info")) {
            if (plugin.boosts().eventActive()) {
                lang().send(sender, "event.info", "{multiplier}", Num.plain(plugin.boosts().eventMultiplier()),
                        "{time}", Time.format(plugin.boosts().eventLeft()));
            } else {
                lang().send(sender, "event.none", "{label}", label);
            }
            if (perm(sender, "event") && sub.isEmpty()) lang().send(sender, "usage.event", "{label}", label);
            return;
        }
        if (!perm(sender, "event")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (sub.equals("start")) {
            if (args.length < 4) {
                lang().send(sender, "usage.event", "{label}", label);
                return;
            }
            double mult = Num.parseDouble(args[2].replace("x", ""), -1);
            long ms = Time.parse(args[3]);
            if (mult <= 0 || mult > 1000) {
                lang().send(sender, "multiplier.invalid", "{input}", args[2]);
                return;
            }
            if (ms <= 0) {
                lang().send(sender, "general.invalid-time", "{input}", args[3]);
                return;
            }
            plugin.boosts().startEvent(mult, ms);
            if (!(sender instanceof Player)) lang().send(sender, "event.started-console", "{multiplier}", Num.plain(mult), "{time}", Time.format(ms));
            return;
        }
        if (sub.equals("stop") || sub.equals("end")) {
            if (!plugin.boosts().eventActive()) {
                lang().send(sender, "event.none", "{label}", label);
                return;
            }
            plugin.boosts().stopEvent();
            return;
        }
        lang().send(sender, "usage.event", "{label}", label);
    }

    // ------------------------------------------------------------------ withdraw

    void withdraw(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "withdraw")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player)) {
            lang().send(sender, "general.players-only");
            return;
        }
        Player p = (Player) sender;
        if (!plugin.notes().enabled()) {
            lang().send(p, "withdraw.disabled");
            return;
        }
        if (args.length < 2) {
            lang().send(p, "usage.withdraw", "{label}", label);
            return;
        }
        double amount = Num.parse(args[1]);
        if (amount <= 0) {
            lang().send(p, "general.invalid-amount", "{input}", args[1]);
            return;
        }
        amount = Num.round(amount);
        if (amount < plugin.notes().min()) {
            lang().send(p, "withdraw.min", "{min}", Num.commas(plugin.notes().min()));
            return;
        }
        if ("CREATIVE".equals(String.valueOf(p.getGameMode())) && !p.hasPermission("flaremobcoins.redeem.creative")) {
            lang().send(p, "withdraw.creative");
            return;
        }
        if (!plugin.coins().spend(p, amount, "withdraw")) {
            lang().send(p, "withdraw.not-enough", "{amount}", Num.plain(amount), "{amount_commas}", Num.commas(amount));
            Actions.sound(p, "sounds.error");
            return;
        }
        plugin.notes().hand(p, plugin.notes().create(p, amount));
        lang().send(p, "withdraw.done", "{amount}", Num.plain(amount), "{amount_commas}", Num.commas(amount));
        Actions.sound(p, "sounds.pay");
    }

    // ------------------------------------------------------------------ sell

    void sell(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "sell")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player)) {
            lang().send(sender, "general.players-only");
            return;
        }
        Player p = (Player) sender;
        if (!plugin.sell().enabled()) {
            lang().send(p, "sell.disabled");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "all":
            case "inventory":
                plugin.sell().sellAll(p);
                return;
            case "hand":
                plugin.sell().sellHand(p);
                return;
            case "prices":
            case "list":
                lang().send(p, "sell.prices", "{prices}", plugin.sell().list());
                return;
            default:
                plugin.sell().open(p);
        }
    }

    // ------------------------------------------------------------------ stats

    void stats(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "stats") || !plugin.menus().canOpen(sender, "stats")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        String who;
        if (args.length >= 2) {
            if (!perm(sender, "stats.others") && !args[1].equalsIgnoreCase(sender.getName())) {
                lang().send(sender, "general.no-permission");
                return;
            }
            who = args[1];
        } else if (sender instanceof Player) {
            who = sender.getName();
        } else {
            lang().send(sender, "usage.stats", "{label}", label);
            return;
        }
        String typed = who;
        plugin.coins().find(who).thenAccept(acc -> {
            if (acc == null) {
                lang().send(sender, "general.player-not-found", "{target}", typed);
                return;
            }
            plugin.stats().fetch(acc.uuid).thenAccept(values -> show(sender, label, acc.uuid, nameOf(acc, typed), values));
        });
    }

    private void show(CommandSender sender, String label, UUID id, String name, Map<String, Double> v) {
        String week = StatsManager.week(), month = StatsManager.month();
        // most killed mobs
        List<Map.Entry<String, Double>> mobs = new ArrayList<>();
        for (Map.Entry<String, Double> e : v.entrySet()) if (e.getKey().startsWith(StatsManager.KILLS + ":")) mobs.add(e);
        mobs.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder top = new StringBuilder();
        String sep = lang().raw("stats.mob-separator");
        for (int i = 0; i < Math.min(3, mobs.size()); i++) {
            String key = mobs.get(i).getKey().substring(StatsManager.KILLS.length() + 1);
            if (key.startsWith("mythic:")) key = key.substring(7);
            if (top.length() > 0) top.append(sep);
            top.append(lang().raw("stats.mob").replace("{mob}", Drops.pretty(key))
                    .replace("{kills}", Num.commas(mobs.get(i).getValue())));
        }
        if (top.length() == 0) top.append(lang().raw("stats.no-mobs"));
        List<double[]> boosters = plugin.boosts().active(v);
        String booster = boosters.isEmpty() ? lang().raw("stats.no-booster")
                : lang().raw("stats.booster").replace("{multiplier}", Num.plain(boosters.get(0)[0]))
                .replace("{time}", Time.format((long) boosters.get(0)[1] - System.currentTimeMillis()));
        String[] pairs = {"{label}", label, "{target}", name,
                "{kills}", Num.commas(v.getOrDefault(StatsManager.KILLS, 0d)),
                "{golden}", Num.commas(v.getOrDefault(StatsManager.GOLDEN, 0d)),
                "{earned}", Num.commas(v.getOrDefault(StatsManager.EARNED, 0d)),
                "{weekly}", Num.commas(v.getOrDefault(week, 0d)),
                "{monthly}", Num.commas(v.getOrDefault(month, 0d)),
                "{weekly_position}", position(plugin.stats().weekly(), id),
                "{monthly_position}", position(plugin.stats().monthly(), id),
                "{best_combo}", Num.commas(v.getOrDefault(StatsManager.BEST_COMBO, 0d)),
                "{booster}", booster, "{top_mobs}", top.toString()};
        lang().send(sender, "stats.lines", pairs);
    }

    static String position(List<Storage.Account> list, UUID id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).uuid.equals(id)) return String.valueOf(i + 1);
        return "-";
    }

    // ------------------------------------------------------------------ tab

    List<String> tab(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "booster":
            case "boost":
                if (!perm(sender, "booster")) return Collections.emptyList();
                if (args.length == 2) return MobcoinsCommand.filterStatic(Arrays.asList("give", "item", "remove", "check"), args[1]);
                if (args.length == 3) return MobcoinsCommand.playersStatic(args[2]);
                if (args.length == 4 && !args[1].equalsIgnoreCase("remove") && !args[1].equalsIgnoreCase("check")) return Arrays.asList("1.5", "2", "3");
                if (args.length == 5 && !args[1].equalsIgnoreCase("remove") && !args[1].equalsIgnoreCase("check")) return Arrays.asList("30m", "1h", "1d");
                if (args.length == 6 && args[1].equalsIgnoreCase("item")) return Arrays.asList("1", "5", "10");
                return Collections.emptyList();
            case "event":
                if (!perm(sender, "event")) return Collections.emptyList();
                if (args.length == 2) return MobcoinsCommand.filterStatic(Arrays.asList("start", "stop", "info"), args[1]);
                if (args.length == 3 && args[1].equalsIgnoreCase("start")) return Arrays.asList("2", "1.5", "3");
                if (args.length == 4 && args[1].equalsIgnoreCase("start")) return Arrays.asList("30m", "1h", "2h");
                return Collections.emptyList();
            case "withdraw":
                return args.length == 2 ? Arrays.asList("10", "100", "1000") : Collections.<String>emptyList();
            case "sell":
                return args.length == 2 ? MobcoinsCommand.filterStatic(Arrays.asList("hand", "all", "prices"), args[1]) : Collections.<String>emptyList();
            case "stats":
                return args.length == 2 && perm(sender, "stats.others") ? MobcoinsCommand.playersStatic(args[1]) : Collections.<String>emptyList();
            default:
                return Collections.emptyList();
        }
    }
}
