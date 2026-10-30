package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Kills of every player per chunk (last minutes) and mobcoins from mobs in the last hour.
 * Used by the combo (farm kills don't raise it) and by drops.yml > anti-farm (optional limits).
 * Everything is in memory and cleaned every minute.
 */
final class FarmGuard {

    /** The combo looks at the last 10 minutes. */
    static final long COMBO_WINDOW = 600_000L;
    private static final int MAX_PER_CHUNK = 1000;

    private final FlareMobcoins plugin;
    private final Map<UUID, Map<String, ArrayDeque<Long>>> kills = new HashMap<>();
    /** per player: 60 buckets of one minute {minute, amount} */
    private final Map<UUID, double[][]> hour = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> warned = new HashMap<>();
    private boolean enabled;
    private double maxPerHour;
    private int chunkKills;
    private long chunkMillis;
    private double chunkPercent;

    FarmGuard(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    void load(YamlConfiguration y) {
        enabled = y.getBoolean("anti-farm.enabled", false);
        maxPerHour = Math.max(0, y.getDouble("anti-farm.max-per-hour", 0));
        chunkKills = Math.max(0, y.getInt("anti-farm.chunk-kills", 50));
        chunkMillis = Math.max(1, y.getLong("anti-farm.chunk-minutes", 10)) * 60_000L;
        chunkPercent = Math.max(0, Math.min(100, y.getDouble("anti-farm.chunk-percent", 25)));
    }

    boolean enabled() {
        return enabled;
    }

    private long window() {
        return Math.max(COMBO_WINDOW, enabled ? chunkMillis : 0);
    }

    private static String chunk(Location l) {
        return l.getWorld().getName() + ":" + (l.getBlockX() >> 4) + ":" + (l.getBlockZ() >> 4);
    }

    /** Writes the kill. */
    void record(UUID id, Location at) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = kills.computeIfAbsent(id, k -> new HashMap<>()).computeIfAbsent(chunk(at), k -> new ArrayDeque<>());
        times.addLast(now);
        while (times.size() > MAX_PER_CHUNK) times.pollFirst();
    }

    /** Kills of the player in this chunk in the last millis (the last one included). */
    int count(UUID id, Location at, long millis) {
        Map<String, ArrayDeque<Long>> m = kills.get(id);
        if (m == null) return 0;
        ArrayDeque<Long> times = m.get(chunk(at));
        if (times == null) return 0;
        long from = System.currentTimeMillis() - millis;
        int n = 0;
        Iterator<Long> it = times.descendingIterator();
        while (it.hasNext() && it.next() >= from) n++;
        return n;
    }

    /** anti-farm: the amount after the chunk and hour limits (0 = nothing). */
    double limit(Player p, Location at, double amount) {
        if (!enabled || amount <= 0) return amount;
        double a = amount;
        if (chunkKills > 0 && count(p.getUniqueId(), at, chunkMillis) > chunkKills) a = a * chunkPercent / 100.0;
        if (maxPerHour > 0) {
            double left = maxPerHour - lastHour(p.getUniqueId());
            if (left <= 0.0001) {
                warn(p);
                return 0;
            }
            if (a > left) a = left;
        }
        return Num.round(a);
    }

    /** Mobcoins given from a mob (for the hour limit). */
    void earned(UUID id, double amount) {
        if (!enabled || maxPerHour <= 0 || amount <= 0) return;
        long minute = System.currentTimeMillis() / 60_000L;
        double[][] b = hour.computeIfAbsent(id, k -> new double[60][2]);
        double[] slot = b[(int) (minute % 60)];
        if ((long) slot[0] != minute) {
            slot[0] = minute;
            slot[1] = 0;
        }
        slot[1] += amount;
    }

    double lastHour(UUID id) {
        double[][] b = hour.get(id);
        if (b == null) return 0;
        long minute = System.currentTimeMillis() / 60_000L;
        double sum = 0;
        for (double[] slot : b) if (minute - (long) slot[0] < 60) sum += slot[1];
        return sum;
    }

    /** Mobcoins the player can still get from mobs this hour, -1 = no limit. */
    double leftThisHour(UUID id) {
        if (!enabled || maxPerHour <= 0) return -1;
        return Math.max(0, maxPerHour - lastHour(id));
    }

    private void warn(Player p) {
        long now = System.currentTimeMillis();
        Long last = warned.get(p.getUniqueId());
        if (last != null && now - last < 60_000L) return;
        warned.put(p.getUniqueId(), now);
        String msg = plugin.lang().raw("anti-farm.limit");
        if (!msg.isEmpty()) Compat.actionBar(p, Text.legacy(Placeholders.apply(msg, p, "{max}", Num.commas(maxPerHour))));
    }

    /** Every minute: old kills and hours are removed. */
    void clean() {
        long from = System.currentTimeMillis() - window();
        Iterator<Map.Entry<UUID, Map<String, ArrayDeque<Long>>>> players = kills.entrySet().iterator();
        while (players.hasNext()) {
            Map<String, ArrayDeque<Long>> chunks = players.next().getValue();
            chunks.values().removeIf(times -> {
                while (!times.isEmpty() && times.peekFirst() < from) times.pollFirst();
                return times.isEmpty();
            });
            if (chunks.isEmpty()) players.remove();
        }
        long minute = System.currentTimeMillis() / 60_000L;
        hour.values().removeIf(b -> {
            for (double[] slot : b) if (minute - (long) slot[0] < 60) return false;
            return true;
        });
        warned.values().removeIf(t -> System.currentTimeMillis() - t > 120_000L);
    }
}
