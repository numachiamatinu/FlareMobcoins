package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Tags;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * drops.yml > golden-mobs: a few mobs spawn "golden" (name, shining outline on 1.9+) and give more mobcoins.
 * The mark is saved with the mob on 1.10+ (scoreboard tag), so a golden mob stays golden after a restart.
 */
final class GoldenMobs {

    static final String TAG = "flaremobcoins-golden";
    /** Natural spawns only: no eggs, breeding, commands or other plugins (MythicMobs ...). */
    private static final Set<String> NATURAL = new HashSet<>(Arrays.asList("NATURAL", "CHUNK_GEN", "DEFAULT", "JOCKEY",
            "MOUNT", "REINFORCEMENTS", "PATROL", "RAID", "VILLAGE_INVASION", "TRAP", "LIGHTNING", "NETHER_PORTAL", "SLIME_SPLIT"));

    private final FlareMobcoins plugin;
    private boolean enabled;
    private double chance;
    private double multiplier;
    private boolean fromSpawners;
    private boolean glow;
    private String name;
    private Set<String> mobs = Collections.emptySet();
    private List<String> actions = Collections.emptyList();

    GoldenMobs(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    void load(YamlConfiguration y) {
        enabled = y.getBoolean("golden-mobs.enabled", false);
        chance = Math.max(0, Math.min(100, y.getDouble("golden-mobs.chance", 1)));
        multiplier = Math.max(0, y.getDouble("golden-mobs.multiplier", 5));
        fromSpawners = y.getBoolean("golden-mobs.from-spawners", false);
        glow = y.getBoolean("golden-mobs.glow", true);
        name = y.getString("golden-mobs.name", "");
        mobs = new HashSet<>();
        for (String m : y.getStringList("golden-mobs.mobs")) mobs.add(m.trim().toUpperCase(Locale.ROOT));
        actions = y.getStringList("golden-mobs.actions");
    }

    boolean enabled() {
        return enabled;
    }

    double multiplier() {
        return multiplier;
    }

    List<String> actions() {
        return actions;
    }

    private boolean allowed(LivingEntity mob) {
        String type = mob.getType().name();
        if (type.equals("ARMOR_STAND") || type.equals("PLAYER")) return false;
        if (mobs.contains(type) || mobs.contains("ALL") || mobs.contains("DEFAULT")) return true;
        boolean hostile = Drops.isHostile(mob);
        return hostile ? mobs.contains("HOSTILE") : mobs.contains("PASSIVE");
    }

    void spawn(CreatureSpawnEvent e) {
        if (!enabled || chance <= 0) return;
        String reason = e.getSpawnReason().name();
        if (!NATURAL.contains(reason) && !(fromSpawners && reason.equals("SPAWNER"))) return;
        LivingEntity mob = e.getEntity();
        if (mob.getCustomName() != null || !allowed(mob)) return;
        if (ThreadLocalRandom.current().nextDouble(100) >= chance) return;
        Tags.tag(plugin, mob, TAG);
        if (name != null && !name.isEmpty()) {
            mob.setCustomName(Text.legacy(Placeholders.apply(name, null, "{mob}", Drops.pretty(mob.getType().name()))));
            mob.setCustomNameVisible(true);
            // a name must not keep it in the world forever: it still despawns like the others
            mob.setRemoveWhenFarAway(true);
        }
        if (glow) Tags.glow(mob);
    }

    static boolean is(LivingEntity mob) {
        return Tags.hasTag(mob, TAG);
    }
}
