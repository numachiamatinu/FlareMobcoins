package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.data.StateFile;
import dev.tmdevelopment.flaremobcoins.data.StatsManager;
import dev.tmdevelopment.flaremobcoins.data.Storage;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Weekly and monthly top: mobcoins got from mobs in the week (from Monday) and in the month.
 * When a week / month ends the best players get the rewards of config.yml > tops (once: with MySQL only one server
 * of the network gives them). A server that was off at that moment gives them when it starts.
 */
public final class PeriodTops {

    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";
    private static final Pattern RANGE = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");
    private static final Pattern TAG = Pattern.compile("^\\s*\\[([^\\]]+)]\\s?(.*)$");

    private final FlareMobcoins plugin;
    private final StateFile state;
    private BukkitTask task;

    public PeriodTops(FlareMobcoins plugin, StateFile state) {
        this.plugin = plugin;
        this.state = state;
    }

    public void start() {
        if (task != null) task.cancel();
        // 30 seconds after the start, then every minute
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 600L, 1200L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    public boolean enabled(String type) {
        return plugin.getConfig().getBoolean("tops." + type + ".enabled", true);
    }

    public static String current(String type) {
        return type.equals(WEEKLY) ? StatsManager.week() : StatsManager.month();
    }

    /** Is there a new week / month since the last check? Then the old one gets its rewards. */
    void check() {
        boolean cleaned = false;
        for (String type : new String[]{WEEKLY, MONTHLY}) {
            String now = current(type);
            String path = "period-tops." + type;
            String last = state.yaml().getString(path);
            if (!now.equals(last)) {
                state.yaml().set(path, now);
                if (last != null && enabled(type) && plugin.getConfig().getBoolean("tops." + type + ".rewards-enabled", false)) {
                    // given 90 seconds later: every server of the network has written its last kills of the period by
                    // then. Saved, so a restart in the meantime still gives them.
                    state.yaml().set("period-tops.pending." + type, last);
                    state.yaml().set("period-tops.pending-time." + type, System.currentTimeMillis() + 90_000L);
                }
                state.save();
                if (last != null && !cleaned) {
                    cleaned = true;
                    // after the rewards were read: old weeks and months go
                    Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.stats().cleanOld(), 6000L);
                }
            }
            String pending = state.yaml().getString("period-tops.pending." + type);
            if (pending != null && System.currentTimeMillis() >= state.yaml().getLong("period-tops.pending-time." + type, 0)) {
                state.yaml().set("period-tops.pending." + type, null);
                state.yaml().set("period-tops.pending-time." + type, null);
                state.save();
                reward(type, pending);
            }
        }
    }

    private void reward(String type, String period) {
        ConfigurationSection rewards = plugin.getConfig().getConfigurationSection("tops." + type + ".rewards");
        if (rewards == null || rewards.getKeys(false).isEmpty()) return;
        int last = 0;
        for (String k : rewards.getKeys(false)) last = Math.max(last, range(k)[1]);
        int size = Math.max(3, last);
        // one server only (the purchases table is shared with MySQL)
        plugin.coins().reserve("*", "top-reward-" + type, period, 1).thenAccept(first -> {
            if (!first) return;
            plugin.stats().top(period, size, list -> give(type, period, list, rewards));
        });
    }

    private void give(String type, String period, List<Storage.Account> list, ConfigurationSection rewards) {
        if (list.isEmpty()) return;
        String[] winners = new String[6];
        for (int i = 0; i < 3; i++) {
            Storage.Account a = i < list.size() ? list.get(i) : null;
            winners[i * 2] = a == null ? "-" : name(a);
            winners[i * 2 + 1] = a == null ? "0" : Num.commas(a.mobcoins);
        }
        String[] top = {"{1_name}", winners[0], "{1_amount}", winners[1], "{2_name}", winners[2], "{2_amount}", winners[3],
                "{3_name}", winners[4], "{3_amount}", winners[5], "{period}", period.substring(period.indexOf(':') + 1)};
        for (String line : plugin.lang().lines("tops." + type + "-ended")) Lang.broadcast(Placeholders.apply(line, null, top));
        for (int i = 0; i < list.size(); i++) {
            Storage.Account a = list.get(i);
            int position = i + 1;
            List<String> lines = linesFor(rewards, position);
            if (lines.isEmpty()) continue;
            String name = name(a);
            String[] pairs = {"{player}", name, "{uuid}", a.uuid.toString(), "{position}", String.valueOf(position),
                    "{amount}", Num.plain(a.mobcoins), "{amount_commas}", Num.commas(a.mobcoins)};
            for (String line : lines) run(a, name, Placeholders.pairs(line, pairs));
            plugin.getLogger().info("Top " + type + " " + period + ": #" + position + " " + name + " got the rewards.");
        }
    }

    /** [mobcoins] amount · [console] command · [message] text (only if online) · [broadcast] text */
    private void run(Storage.Account a, String name, String line) {
        Matcher m = TAG.matcher(line);
        if (!m.find()) return;
        String type = m.group(1).trim().toLowerCase(Locale.ROOT);
        String value = m.group(2);
        Player online = Bukkit.getPlayer(a.uuid);
        switch (type) {
            case "mobcoins":
            case "mobcoins_give":
                plugin.coins().give(a.uuid, name, Num.roll(value), "top reward");
                break;
            case "console":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), value.startsWith("/") ? value.substring(1) : value);
                break;
            case "message":
                if (online != null) Lang.deliver(online, Placeholders.apply(value, online));
                break;
            case "broadcast":
                Lang.broadcast(Placeholders.apply(value, online));
                break;
            default:
                plugin.getLogger().warning("Unknown reward line in config.yml > tops: " + line + " (use [mobcoins], [console], [message] or [broadcast])");
        }
    }

    private static List<String> linesFor(ConfigurationSection rewards, int position) {
        List<String> out = new ArrayList<>();
        for (String k : rewards.getKeys(false)) {
            int[] r = range(k);
            if (position >= r[0] && position <= r[1]) out.addAll(rewards.getStringList(k));
        }
        return out;
    }

    /** "1" -> 1..1, "4-10" -> 4..10 */
    private static int[] range(String key) {
        Matcher m = RANGE.matcher(key);
        if (m.matches()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        int n = Num.parseInt(key.trim(), -1);
        return new int[]{n, n};
    }

    private static String name(Storage.Account a) {
        if (a.name != null && !a.name.isEmpty()) return a.name;
        String n = Bukkit.getOfflinePlayer(a.uuid).getName();
        return n == null ? "?" : n;
    }
}
