package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.data.StatsManager;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * drops.yml > combo: kills in a row raise the multiplier for a few seconds.
 * Against mob farms: mobs from spawners don't count, and kills over the limit in the same chunk (last 10 minutes)
 * don't count. A kill that doesn't count gets no combo multiplier and doesn't keep the combo alive.
 */
final class Combo {

    private final FlareMobcoins plugin;
    private final Map<UUID, long[]> states = new java.util.concurrent.ConcurrentHashMap<>();
    /** replaced (not changed) on reload: placeholders may read it from another thread */
    private volatile TreeMap<Integer, Double> steps = new TreeMap<>();
    private boolean enabled;
    private long timeout;
    private boolean countSpawners;
    private int maxPerChunk;

    Combo(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    void load(YamlConfiguration y) {
        enabled = y.getBoolean("combo.enabled", false);
        timeout = Math.max(1, y.getLong("combo.seconds", 6)) * 1000L;
        countSpawners = y.getBoolean("combo.count-spawner-mobs", false);
        maxPerChunk = Math.max(0, y.getInt("combo.max-kills-per-chunk", 20));
        TreeMap<Integer, Double> next = new TreeMap<>();
        ConfigurationSection s = y.getConfigurationSection("combo.steps");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                int kills = Num.parseInt(k, -1);
                double m = s.getDouble(k, 1);
                if (kills > 0 && m > 0) next.put(kills, m);
            }
        }
        steps = next;
        if (!enabled) states.clear();
    }

    boolean enabled() {
        return enabled;
    }

    /** Current combo of a player (0 = none). */
    int count(UUID id) {
        long[] s = states.get(id);
        if (s == null || System.currentTimeMillis() - s[1] > timeout) return 0;
        return (int) s[0];
    }

    double multiplier(int count) {
        Map.Entry<Integer, Double> e = steps.floorEntry(count);
        return e == null ? 1 : e.getValue();
    }

    /**
     * A kill of a mob that gives mobcoins. chunkKills = kills of the player in this chunk in the last 10 minutes.
     * spawner = from a spawner, farm = from eggs, breeding or built (iron golems, snow golems).
     * @return the combo of this kill (0 = this kill doesn't count)
     */
    int kill(Player p, boolean spawner, boolean farm, int chunkKills) {
        if (!enabled) return 0;
        if (spawner && !countSpawners || farm) return 0;
        if (maxPerChunk > 0 && chunkKills > maxPerChunk) return 0;
        long now = System.currentTimeMillis();
        long[] s = states.get(p.getUniqueId());
        if (s == null || now - s[1] > timeout) {
            if (s != null) lost(p, (int) s[0]);
            s = new long[]{0, now};
            states.put(p.getUniqueId(), s);
        }
        s[0]++;
        s[1] = now;
        int combo = (int) s[0];
        plugin.stats().max(p.getUniqueId(), StatsManager.BEST_COMBO, combo);
        return combo;
    }

    void reset(UUID id) {
        states.remove(id);
    }

    /** Every second: combos that ran out of time end (with a message). */
    void tick() {
        if (states.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, long[]>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, long[]> e = it.next();
            if (now - e.getValue()[1] <= timeout) continue;
            it.remove();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) lost(p, (int) e.getValue()[0]);
        }
    }

    private void lost(Player p, int combo) {
        TreeMap<Integer, Double> st = steps;
        if (st.isEmpty() || combo < st.firstKey()) return;
        String msg = plugin.lang().raw("combo.lost");
        if (!msg.isEmpty()) Compat.actionBar(p, Text.legacy(Placeholders.apply(msg, p, "{combo}", String.valueOf(combo))));
    }
}
