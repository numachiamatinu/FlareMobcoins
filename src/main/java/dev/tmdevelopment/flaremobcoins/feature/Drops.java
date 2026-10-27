package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.hook.Stackers;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Headers;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import net.flares.flaremobcoins.API.MobCoinReceiveEvent;
import net.flares.flaremobcoins.API.MobcoinsPlayer;
import net.flares.flaremobcoins.drops.EntityConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MobCoins for killing mobs (drops.yml). Reads the new format (entries by mob name) and the 3.x one
 * (entity / entities / drop_value / drop_chance / drop_action / requirements).
 */
public final class Drops implements Listener {

    private static final String SPAWNER = "flaremobcoins-spawner";
    /** Hostile mobs that are not a Monster in the API, plus the 3.x list. */
    private static final Set<String> HOSTILE_EXTRA = new HashSet<>(Arrays.asList("SLIME", "MAGMA_CUBE", "GHAST", "PHANTOM",
            "SHULKER", "HOGLIN", "ENDER_DRAGON", "BREEZE", "BLAZE", "CREEPER", "DROWNED", "ELDER_GUARDIAN", "ENDERMITE",
            "EVOKER", "GIANT", "GUARDIAN", "HUSK", "ILLUSIONER", "PIGLIN_BRUTE", "PILLAGER", "RAVAGER", "SILVERFISH",
            "SKELETON", "SPIDER", "STRAY", "VEX", "VINDICATOR", "WITCH", "WITHER", "WITHER_SKELETON", "ZOGLIN", "ZOMBIE",
            "ZOMBIE_VILLAGER", "WARDEN", "BOGGED", "CREAKING"));

    private final FlareMobcoins plugin;
    private boolean enabled;
    private boolean ignoreSpawners;
    private boolean stackers;
    private Set<String> disabledWorlds = Collections.emptySet();
    private final Map<String, Double> multipliers = new HashMap<>();
    private final List<Drop> drops = new ArrayList<>();

    public Drops(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "drops.yml");
        if (!file.exists()) plugin.saveResource("drops.yml", false);
        Headers.sync(plugin, file, "drops.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        enabled = y.getBoolean("enabled", true);
        ignoreSpawners = y.getBoolean("ignore-spawner-mobs", false);
        stackers = y.getBoolean("stackers", false);
        disabledWorlds = new HashSet<>();
        for (String w : y.getStringList("disabled-worlds")) disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        multipliers.clear();
        ConfigurationSection mult = y.getConfigurationSection("multipliers");
        if (mult != null) {
            for (String k : mult.getKeys(true)) {
                if (!mult.isConfigurationSection(k)) multipliers.put(k, mult.getDouble(k, 1));
            }
        }
        drops.clear();
        ConfigurationSection entity = y.getConfigurationSection("entity");
        if (entity == null) return;
        for (String key : entity.getKeys(false)) {
            ConfigurationSection sec = entity.getConfigurationSection(key);
            if (sec == null) continue;
            Drop d = new Drop();
            d.key = key;
            d.chance = sec.isSet("chance") ? sec.getDouble("chance") : sec.getDouble("drop_chance", 100);
            d.amount = sec.getString("amount", sec.getString("drop_value", "1"));
            boolean oldActions = !sec.isSet("actions") && sec.isSet("drop_action");
            d.actions = sec.isList("actions") ? sec.getStringList("actions") : sec.getStringList("drop_action");
            d.give = sec.getBoolean("give", !oldActions);
            if (sec.isList("requirements")) d.requirements = sec.getStringList("requirements");
            else if (sec.isList("requirement_list")) d.requirements = sec.getStringList("requirement_list");
            else if (sec.isString("requirement")) d.requirements = Collections.singletonList(sec.getString("requirement"));
            d.icon = sec.getString("icon");
            if (sec.isList("entities")) {
                d.group = EntityConfiguration.EntityGroup.LIST;
                for (String e : sec.getStringList("entities")) d.entities.add(e.trim().toUpperCase(Locale.ROOT));
            } else {
                String target = sec.isString("entity") ? sec.getString("entity") : key;
                d.group = group(target);
                if (d.group == EntityConfiguration.EntityGroup.LIST) d.entities.add(target.trim().toUpperCase(Locale.ROOT));
            }
            drops.add(d);
        }
    }

    private static EntityConfiguration.EntityGroup group(String name) {
        switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "ALL":
            case "DEFAULT":
                return EntityConfiguration.EntityGroup.ALL;
            case "HOSTILE":
                return EntityConfiguration.EntityGroup.HOSTILE;
            case "PASSIVE":
                return EntityConfiguration.EntityGroup.PASSIVE;
            default:
                return EntityConfiguration.EntityGroup.LIST;
        }
    }

    public boolean ignoreSpawners() {
        return ignoreSpawners;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Drop> list() {
        return Collections.unmodifiableList(drops);
    }

    public int size() {
        return drops.size();
    }

    /** Most precise drop for a mob: its own name, then HOSTILE / PASSIVE, then DEFAULT / ALL. */
    Drop find(LivingEntity mob) {
        String type = mob.getType().name();
        Drop group = null, all = null;
        boolean hostile = isHostile(mob);
        // armor stands are not mobs: only an entry with their name counts
        boolean groups = !type.equals("ARMOR_STAND");
        for (Drop d : drops) {
            if (!groups && d.group != EntityConfiguration.EntityGroup.LIST) continue;
            switch (d.group) {
                case LIST:
                    if (d.entities.contains(type)) return d;
                    break;
                case HOSTILE:
                    if (hostile && group == null) group = d;
                    break;
                case PASSIVE:
                    if (!hostile && group == null) group = d;
                    break;
                default:
                    if (all == null) all = d;
            }
        }
        return group != null ? group : all;
    }

    static boolean isHostile(LivingEntity mob) {
        return mob instanceof Monster || HOSTILE_EXTRA.contains(mob.getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (enabled && ignoreSpawners && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            e.getEntity().setMetadata(SPAWNER, new FixedMetadataValue(plugin, true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!enabled) return;
        LivingEntity mob = e.getEntity();
        if (mob instanceof Player) return;
        Player killer = mob.getKiller();
        if (killer == null) return;
        if (disabledWorlds.contains(mob.getWorld().getName().toLowerCase(Locale.ROOT))) return;
        if (ignoreSpawners && mob.hasMetadata(SPAWNER)) return;
        Drop drop = find(mob);
        if (drop == null) return;
        String mobName = mob.getType().name();
        Actions.Context check = new Actions.Context("drop " + mobName, null, "{mob}", mobName);
        if (!drop.requirements.isEmpty() && !Actions.check(killer, drop.requirements, check)) return;
        if (drop.chance < 100 && ThreadLocalRandom.current().nextDouble(100) >= drop.chance) return;

        double amount = Num.roll(drop.amount);
        double best = 1;
        for (Map.Entry<String, Double> m : multipliers.entrySet()) {
            // only when the permission is really given: Bukkit gives every undeclared permission to OPs
            if (m.getValue() > best && killer.isPermissionSet(m.getKey()) && killer.hasPermission(m.getKey())) best = m.getValue();
        }
        amount *= best * plugin.coins().multiplier(killer.getUniqueId()) * plugin.coins().globalMultiplier();
        if (stackers) amount *= Stackers.size(mob);
        amount = Num.round(amount);
        if (amount <= 0) return;

        // 3.x event: other plugins can cancel or change the drop
        MobCoinReceiveEvent event = new MobCoinReceiveEvent(killer, new MobcoinsPlayer(killer.getUniqueId(),
                plugin.coins().balance(killer.getUniqueId()), plugin.coins().multiplier(killer.getUniqueId())),
                mobName, drop.legacy(), amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        amount = Num.round(event.getDroppedAmount());
        if (amount <= 0) return;

        if (plugin.coinsOnGround().enabled()) {
            plugin.coinsOnGround().drop(killer, mob.getLocation(), amount);
            return;
        }
        String value = Num.plain(amount);
        if (drop.give) plugin.coins().give(killer.getUniqueId(), killer.getName(), amount, "drop " + mobName);
        if (!drop.actions.isEmpty()) {
            Actions.run(killer, drop.actions, new Actions.Context("drop " + mobName, null, "{amount}", value,
                    "{amount_commas}", Num.commas(amount), "{mobcoins}", value, "{mob}", mobName));
        } else if (drop.give) {
            String msg = plugin.lang().raw("drops.received");
            if (!msg.isEmpty()) Compat.actionBar(killer, Text.legacy(Placeholders.apply(msg, killer, "{amount}", value, "{mob}", mobName)));
        }
    }

    /** A drop of drops.yml. */
    public static final class Drop {
        String key;
        EntityConfiguration.EntityGroup group;
        final Set<String> entities = new LinkedHashSet<>();
        double chance;
        String amount;
        List<String> actions = Collections.emptyList();
        boolean give;
        List<String> requirements = Collections.emptyList();
        String icon;

        public String key() {
            return key;
        }

        public EntityConfiguration.EntityGroup group() {
            return group;
        }

        public Set<String> entities() {
            return entities;
        }

        public double chance() {
            return chance;
        }

        public String amount() {
            return amount;
        }

        public String icon() {
            return icon;
        }

        EntityConfiguration legacy() {
            return new EntityConfiguration(group, new ArrayList<>(entities), requirements, actions, amount, chance, "entity." + key);
        }
    }
}
