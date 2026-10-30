package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.hook.MythicHook;
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
import org.bukkit.scheduler.BukkitTask;

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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MobCoins for killing mobs (drops.yml). Reads the new format (entries by mob name) and the 3.x one
 * (entity / entities / drop_value / drop_chance / drop_action / requirements).
 */
public final class Drops implements Listener {

    private static final String SPAWNER = "flaremobcoins-spawner";
    /** Mobs made by players (eggs, breeding, golems): they never raise the combo. */
    private static final String FARM = "flaremobcoins-farm";
    private static final Set<String> FARM_REASONS = new HashSet<>(Arrays.asList("SPAWNER_EGG", "EGG", "DISPENSE_EGG", "BREEDING",
            "BUILD_IRONGOLEM", "BUILD_SNOWMAN", "CURED", "INFECTION", "DUPLICATION", "OMINOUS_TRIAL_SPAWNER", "TRIAL_SPAWNER"));
    /** Sections of drops.yml added by 4.1.0: added to old files by themselves. */
    private static final Set<String> NEW_SECTIONS = new HashSet<>(Arrays.asList("golden-mobs", "combo", "anti-farm", "mythicmobs"));
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
    private final GoldenMobs golden;
    private final Combo combo;
    private final FarmGuard farm;
    private boolean mythicNormal = true;
    private double mythicLevelBonus;
    private BukkitTask ticker;
    private int ticks;

    public Drops(FlareMobcoins plugin) {
        this.plugin = plugin;
        this.golden = new GoldenMobs(plugin);
        this.combo = new Combo(plugin);
        this.farm = new FarmGuard(plugin);
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "drops.yml");
        if (!file.exists()) plugin.saveResource("drops.yml", false);
        Headers.sync(plugin, file, "drops.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        y = addNewOptions(file, y);
        golden.load(y);
        combo.load(y);
        farm.load(y);
        mythicNormal = y.getBoolean("mythicmobs.normal-entries", true);
        mythicLevelBonus = Math.max(0, y.getDouble("mythicmobs.level-bonus", 0));
        if (ticker != null) ticker.cancel();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            combo.tick();
            if (++ticks % 60 == 0) farm.clean();
        }, 20L, 20L);
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
            // MythicMobs: "mythic: Name" or a list, also "mythic:Name" as entity or as the key
            if (sec.isList("mythic")) for (String m : sec.getStringList("mythic")) addMythic(d, m);
            else if (sec.isString("mythic")) addMythic(d, sec.getString("mythic"));
            if (sec.isList("entities")) {
                d.group = EntityConfiguration.EntityGroup.LIST;
                for (String e : sec.getStringList("entities")) addTarget(d, e);
            } else if (sec.isString("entity") || d.mythic.isEmpty()) {
                String target = sec.isString("entity") ? sec.getString("entity") : key;
                d.group = group(target);
                if (target.trim().equalsIgnoreCase("MYTHIC") || target.trim().equalsIgnoreCase("MYTHICMOBS")) d.anyMythic = true;
                else if (d.group == EntityConfiguration.EntityGroup.LIST) addTarget(d, target);
            } else {
                d.group = EntityConfiguration.EntityGroup.LIST;
            }
            drops.add(d);
        }
    }

    private static void addTarget(Drop d, String raw) {
        String t = raw.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("mythic:")) addMythic(d, t.substring(7));
        else d.entities.add(t.toUpperCase(Locale.ROOT));
    }

    private static void addMythic(Drop d, String name) {
        String n = name.trim();
        if (n.isEmpty()) return;
        d.mythic.add(n.toUpperCase(Locale.ROOT));
        d.mythicNames.add(n);
    }

    /**
     * New sections of this version (golden mobs, combo, anti-farm, MythicMobs) are added to the drops.yml of the
     * server with their comments. The mobs and multipliers of the server are never touched.
     */
    private YamlConfiguration addNewOptions(File file, YamlConfiguration y) {
        String text = dev.tmdevelopment.flaremobcoins.config.ConfigUpdater.resource(plugin, "drops.yml");
        if (text == null) return y;
        YamlConfiguration defaults = new YamlConfiguration();
        try {
            defaults.loadFromString(text);
        } catch (Exception e) {
            return y;
        }
        List<String> missing = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || y.isSet(key)) continue;
            String top = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
            if (!NEW_SECTIONS.contains(top)) continue;
            // the steps of the combo are a list of the server: only added with the whole section
            if (key.startsWith("combo.steps.") && y.isConfigurationSection("combo.steps")) continue;
            missing.add(key);
        }
        if (missing.isEmpty()) return y;
        if (dev.tmdevelopment.flaremobcoins.config.ConfigUpdater.addMissing(file, text, missing, plugin.getLogger())) {
            // a server that updates keeps its economy: golden mobs start off (on for new servers)
            if (missing.contains("golden-mobs.enabled")) dev.tmdevelopment.flaremobcoins.config.ConfigWriter.set(plugin, "drops.yml", "golden-mobs.enabled", false);
            plugin.getLogger().info("drops.yml updated with the new options of this version.");
            return YamlConfiguration.loadConfiguration(file);
        }
        return y;
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

    /** Drops shown in /mobcoins drops: MythicMobs entries only when MythicMobs is installed. */
    public List<Drop> visible() {
        List<Drop> out = new ArrayList<>();
        boolean mythic = MythicHook.available();
        for (Drop d : drops) {
            boolean onlyMythic = d.anyMythic || d.entities.isEmpty() && !d.mythic.isEmpty() && d.group == EntityConfiguration.EntityGroup.LIST;
            if (!onlyMythic || mythic) out.add(d);
        }
        return out;
    }

    /**
     * Most precise drop for a mob: its own name, then HOSTILE / PASSIVE, then DEFAULT / ALL.
     * MythicMobs mobs: their own entry, then MYTHIC, then (mythicmobs.normal-entries) the normal ones.
     */
    Drop find(LivingEntity mob, String mythic) {
        if (mythic != null) {
            String name = mythic.toUpperCase(Locale.ROOT);
            Drop any = null;
            for (Drop d : drops) {
                if (d.mythic.contains(name)) return d;
                if (d.anyMythic && any == null) any = d;
            }
            if (any != null) return any;
            if (!mythicNormal) return null;
        }
        String type = mob.getType().name();
        Drop group = null, all = null;
        boolean hostile = isHostile(mob);
        // armor stands are not mobs: only an entry with their name counts
        boolean groups = !type.equals("ARMOR_STAND");
        for (Drop d : drops) {
            if (d.anyMythic) continue;
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
        if (!enabled) return;
        // spawner mobs are marked for ignore-spawner-mobs and for the combo
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) e.getEntity().setMetadata(SPAWNER, new FixedMetadataValue(plugin, true));
        else if (FARM_REASONS.contains(e.getSpawnReason().name())) e.getEntity().setMetadata(FARM, new FixedMetadataValue(plugin, true));
        golden.spawn(e);
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        combo.reset(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        combo.reset(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!enabled) return;
        // Paper: another plugin can cancel a death
        if (e instanceof org.bukkit.event.Cancellable && ((org.bukkit.event.Cancellable) e).isCancelled()) return;
        LivingEntity mob = e.getEntity();
        if (mob instanceof Player) return;
        Player killer = mob.getKiller();
        if (killer == null) return;
        if (disabledWorlds.contains(mob.getWorld().getName().toLowerCase(Locale.ROOT))) return;
        boolean spawner = mob.hasMetadata(SPAWNER);
        String mythic = MythicHook.available() ? MythicHook.type(mob) : null;
        // {mob} stays the type of the mob (ZOMBIE ...) like before; {mythic} is the MythicMobs name
        String mobName = mob.getType().name();
        boolean isGolden = golden.enabled() && GoldenMobs.is(mob);
        UUID id = killer.getUniqueId();
        plugin.stats().kill(id, mythic != null ? "mythic:" + mythic : mobName, isGolden);
        farm.record(id, mob.getLocation());
        if (ignoreSpawners && spawner) return;
        Drop drop = find(mob, mythic);
        if (drop == null) return;
        int comboNow = combo.kill(killer, spawner, mob.hasMetadata(FARM), farm.count(id, mob.getLocation(), FarmGuard.COMBO_WINDOW));
        Actions.Context check = new Actions.Context("drop " + mobName, null, "{mob}", mobName, "{mythic}", mythic == null ? "" : mythic);
        if (!drop.requirements.isEmpty() && !Actions.check(killer, drop.requirements, check)) return;
        if (drop.chance < 100 && ThreadLocalRandom.current().nextDouble(100) >= drop.chance) return;

        double amount = Num.roll(drop.amount);
        double best = 1;
        for (Map.Entry<String, Double> m : multipliers.entrySet()) {
            // only when the permission is really given: Bukkit gives every undeclared permission to OPs
            if (m.getValue() > best && killer.isPermissionSet(m.getKey()) && killer.hasPermission(m.getKey())) best = m.getValue();
        }
        double comboMult = comboNow > 0 ? combo.multiplier(comboNow) : 1;
        amount *= best * plugin.coins().multiplier(id) * plugin.coins().globalMultiplier() * plugin.boosts().multiplier(killer) * comboMult;
        if (isGolden) amount *= golden.multiplier();
        if (mythic != null && mythicLevelBonus > 0) amount *= 1 + Math.max(0, MythicHook.level(mob) - 1) * mythicLevelBonus / 100.0;
        if (stackers) amount *= Stackers.size(mob);
        amount = farm.limit(killer, mob.getLocation(), Num.round(amount));
        if (amount <= 0) return;

        // 3.x event: other plugins can cancel or change the drop
        MobCoinReceiveEvent event = new MobCoinReceiveEvent(killer, new MobcoinsPlayer(id,
                plugin.coins().balance(id), plugin.coins().multiplier(id)),
                mobName, drop.legacy(), amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        amount = Num.round(event.getDroppedAmount());
        if (amount <= 0) return;
        farm.earned(id, amount);

        String value = Num.plain(amount);
        String pretty = mythic != null ? pretty(mythic) : pretty(mobName);
        String[] pairs = {"{amount}", value, "{amount_commas}", Num.commas(amount), "{mobcoins}", value, "{mob}", mobName,
                "{mob_name}", pretty, "{mythic}", mythic == null ? "" : mythic, "{combo}", String.valueOf(comboNow),
                "{combo_multiplier}", Num.plain(comboMult)};
        if (isGolden && !golden.actions().isEmpty()) Actions.run(killer, golden.actions(), new Actions.Context("drop " + mobName, null, pairs));
        if (plugin.coinsOnGround().enabled()) {
            plugin.coinsOnGround().drop(killer, mob.getLocation(), amount);
            return;
        }
        if (drop.give) {
            plugin.coins().give(id, killer.getName(), amount, "drop " + mobName);
            plugin.stats().earned(id, amount);
        }
        if (!drop.actions.isEmpty()) {
            Actions.run(killer, drop.actions, new Actions.Context("drop " + mobName, null, pairs));
        } else if (drop.give) {
            // with a combo the action bar says it too
            String msg = plugin.lang().raw(comboNow >= 2 ? "combo.received" : "drops.received");
            if (!msg.isEmpty()) Compat.actionBar(killer, Text.legacy(Placeholders.apply(msg, killer, pairs)));
        }
    }

    public boolean comboEnabled() {
        return combo.enabled();
    }

    public int combo(UUID id) {
        return combo.count(id);
    }

    public double comboMultiplier(UUID id) {
        int c = combo.count(id);
        return c > 0 ? combo.multiplier(c) : 1;
    }

    public boolean goldenEnabled() {
        return golden.enabled();
    }

    public boolean antiFarmEnabled() {
        return farm.enabled();
    }

    /** Mobcoins a player can still get from mobs this hour, -1 = no limit. */
    public double leftThisHour(UUID id) {
        return farm.leftThisHour(id);
    }

    /** A coin on the ground was picked up: it counts for the stats. */
    public void pickedUp(Player p, double amount) {
        plugin.stats().earned(p.getUniqueId(), amount);
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
    }

    /** ZOMBIE_VILLAGER -> Zombie Villager, SkeletonKing stays SkeletonKing. */
    public static String pretty(String s) {
        if (!s.equals(s.toUpperCase(Locale.ROOT))) return s.replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : s.replace('-', '_').split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** A drop of drops.yml. */
    public static final class Drop {
        String key;
        EntityConfiguration.EntityGroup group;
        final Set<String> entities = new LinkedHashSet<>();
        /** MythicMobs internal names (upper case) */
        final Set<String> mythic = new LinkedHashSet<>();
        /** the same names as written in the file (shown in /mobcoins drops) */
        final Set<String> mythicNames = new LinkedHashSet<>();
        /** the MYTHIC entry: every MythicMobs mob without its own entry */
        boolean anyMythic;
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

        public Set<String> mythic() {
            return mythicNames;
        }

        public boolean anyMythic() {
            return anyMythic;
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
