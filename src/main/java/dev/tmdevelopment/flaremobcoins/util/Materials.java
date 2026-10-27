package dev.tmdevelopment.flaremobcoins.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Material names that work on every version: modern names (PURPLE_STAINED_GLASS_PANE, PLAYER_HEAD...) also on 1.8-1.12,
 * old names with data (STAINED_GLASS_PANE:10) also on 1.13+.
 */
public final class Materials {

    private static final Map<String, String> TO_OLD = new HashMap<>();
    private static final Map<String, String> TO_NEW = new HashMap<>();
    private static final Set<String> warned = new HashSet<>();
    private static Logger logger;
    private static final String[] COLORS = {"WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"};

    private static final String[][] RENAMED = {
            {"EXPERIENCE_BOTTLE", "EXP_BOTTLE:0"},
            {"ENCHANTED_GOLDEN_APPLE", "GOLDEN_APPLE:1"},
            {"CLOCK", "WATCH:0"},
            {"SUNFLOWER", "DOUBLE_PLANT:0"},
            {"LILAC", "DOUBLE_PLANT:1"},
            {"ROSE_BUSH", "DOUBLE_PLANT:4"},
            {"PEONY", "DOUBLE_PLANT:5"},
            {"OAK_PLANKS", "WOOD:0"},
            {"SPRUCE_PLANKS", "WOOD:1"},
            {"BIRCH_PLANKS", "WOOD:2"},
            {"JUNGLE_PLANKS", "WOOD:3"},
            {"ACACIA_PLANKS", "WOOD:4"},
            {"DARK_OAK_PLANKS", "WOOD:5"},
            {"OAK_LOG", "LOG:0"},
            {"SPRUCE_LOG", "LOG:1"},
            {"BIRCH_LOG", "LOG:2"},
            {"JUNGLE_LOG", "LOG:3"},
            {"ACACIA_LOG", "LOG_2:0"},
            {"DARK_OAK_LOG", "LOG_2:1"},
            {"OAK_SAPLING", "SAPLING:0"},
            {"OAK_LEAVES", "LEAVES:0"},
            {"CRAFTING_TABLE", "WORKBENCH:0"},
            {"ENDER_EYE", "EYE_OF_ENDER:0"},
            {"FIREWORK_ROCKET", "FIREWORK:0"},
            {"FIREWORK_STAR", "FIREWORK_CHARGE:0"},
            {"FIRE_CHARGE", "FIREBALL:0"},
            {"GUNPOWDER", "SULPHUR:0"},
            {"MAP", "EMPTY_MAP:0"},
            {"FILLED_MAP", "MAP:0"},
            {"COMMAND_BLOCK", "COMMAND:0"},
            {"OAK_SIGN", "SIGN:0"},
            {"OAK_DOOR", "WOOD_DOOR:0"},
            {"IRON_BARS", "IRON_FENCE:0"},
            {"LILY_PAD", "WATER_LILY:0"},
            {"MELON_SLICE", "MELON:0"},
            {"MELON", "MELON_BLOCK:0"},
            {"GLISTERING_MELON_SLICE", "SPECKLED_MELON:0"},
            {"WRITABLE_BOOK", "BOOK_AND_QUILL:0"},
            {"ENCHANTING_TABLE", "ENCHANTMENT_TABLE:0"},
            {"END_STONE", "ENDER_STONE:0"},
            {"NETHER_BRICKS", "NETHER_BRICK:0"},
            {"NETHER_BRICK", "NETHER_BRICK_ITEM:0"},
            {"NETHER_WART", "NETHER_STALK:0"},
            {"COBWEB", "WEB:0"},
            {"GRASS_BLOCK", "GRASS:0"},
            {"SHORT_GRASS", "LONG_GRASS:1"},
            {"FERN", "LONG_GRASS:2"},
            {"DEAD_BUSH", "DEAD_BUSH:0"},
            {"SPAWNER", "MOB_SPAWNER:0"},
            {"KNOWLEDGE_BOOK", "ENCHANTED_BOOK:0"},
            {"BARREL", "CHEST:0"},
            {"TOTEM_OF_UNDYING", "TOTEM:0"},
            {"SNOWBALL", "SNOW_BALL:0"},
            {"POPPY", "RED_ROSE:0"},
            {"BLUE_ORCHID", "RED_ROSE:1"},
            {"ALLIUM", "RED_ROSE:2"},
            {"DANDELION", "YELLOW_FLOWER:0"},
            {"PORKCHOP", "PORK:0"},
            {"COOKED_PORKCHOP", "GRILLED_PORK:0"},
            {"BEEF", "RAW_BEEF:0"},
            {"CHICKEN", "RAW_CHICKEN:0"},
            {"COD", "RAW_FISH:0"},
            {"SALMON", "RAW_FISH:1"},
            {"COOKED_COD", "COOKED_FISH:0"},
            {"MUSHROOM_STEW", "MUSHROOM_SOUP:0"},
            {"CHEST_MINECART", "STORAGE_MINECART:0"},
            {"FURNACE_MINECART", "POWERED_MINECART:0"},
            {"TNT_MINECART", "EXPLOSIVE_MINECART:0"},
            {"HOPPER_MINECART", "HOPPER_MINECART:0"},
            {"OAK_BOAT", "BOAT:0"},
            {"CARROT", "CARROT_ITEM:0"},
            {"POTATO", "POTATO_ITEM:0"},
            {"GOLDEN_CARROT", "GOLDEN_CARROT:0"},
            {"WHEAT_SEEDS", "SEEDS:0"},
            {"BRICKS", "BRICK:0"},
            {"BRICK", "CLAY_BRICK:0"},
            {"STONE_BRICKS", "SMOOTH_BRICK:0"},
            {"OAK_FENCE", "FENCE:0"},
            {"OAK_FENCE_GATE", "FENCE_GATE:0"},
            {"OAK_SLAB", "WOOD_STEP:0"},
            {"STONE_SLAB", "STEP:0"},
            {"SMOOTH_STONE", "STEP:0"},
            {"PISTON", "PISTON_BASE:0"},
            {"STICKY_PISTON", "PISTON_STICKY_BASE:0"},
            {"REPEATER", "DIODE:0"},
            {"COMPARATOR", "REDSTONE_COMPARATOR:0"},
            {"CARVED_PUMPKIN", "PUMPKIN:0"},
            {"MYCELIUM", "MYCEL:0"},
            {"WET_SPONGE", "SPONGE:1"},
            {"NETHER_QUARTZ_ORE", "QUARTZ_ORE:0"},
            {"LEAD", "LEASH:0"},
            {"OAK_PRESSURE_PLATE", "WOOD_PLATE:0"},
            {"STONE_PRESSURE_PLATE", "STONE_PLATE:0"},
            {"OAK_BUTTON", "WOOD_BUTTON:0"},
            {"OAK_TRAPDOOR", "TRAP_DOOR:0"},
            {"OAK_STAIRS", "WOOD_STAIRS:0"},
            {"COBBLESTONE_WALL", "COBBLE_WALL:0"},
            {"MOSSY_COBBLESTONE", "MOSSY_COBBLESTONE:0"},
            {"RED_SAND", "SAND:1"},
            {"GRANITE", "STONE:1"},
            {"DIORITE", "STONE:3"},
            {"ANDESITE", "STONE:5"},
            {"PRISMARINE_BRICKS", "PRISMARINE:1"},
            {"DARK_PRISMARINE", "PRISMARINE:2"},
            {"CHISELED_QUARTZ_BLOCK", "QUARTZ_BLOCK:1"},
            {"SLIME_BLOCK", "SLIME_BLOCK:0"},
            {"RABBIT_HIDE", "RABBIT_HIDE:0"},
            {"TRIPWIRE_HOOK", "TRIPWIRE_HOOK:0"},
            {"FLOWER_POT", "FLOWER_POT_ITEM:0"},
            {"CAULDRON", "CAULDRON_ITEM:0"},
            {"BREWING_STAND", "BREWING_STAND_ITEM:0"},
            {"CAKE", "CAKE:0"},
            {"REDSTONE_TORCH", "REDSTONE_TORCH_ON:0"},
            {"REDSTONE_LAMP", "REDSTONE_LAMP_OFF:0"},
            {"POPPED_CHORUS_FRUIT", "CHORUS_FRUIT_POPPED:0"},
            {"DRAGON_BREATH", "DRAGONS_BREATH:0"},
            {"MUSIC_DISC_13", "GOLD_RECORD:0"},
            {"MUSIC_DISC_CAT", "GREEN_RECORD:0"},
            {"ZOMBIFIED_PIGLIN_SPAWN_EGG", "MONSTER_EGG:57"},
            {"OAK_WOOD", "LOG:0"}
    };

    static {
        for (int i = 0; i < 16; i++) {
            String c = COLORS[i];
            map(c + "_WOOL", "WOOL:" + i);
            map(c + "_STAINED_GLASS", "STAINED_GLASS:" + i);
            map(c + "_STAINED_GLASS_PANE", "STAINED_GLASS_PANE:" + i);
            map(c + "_TERRACOTTA", "STAINED_CLAY:" + i);
            map(c + "_CARPET", "CARPET:" + i);
            map(c + "_CONCRETE", "CONCRETE:" + i);
            map(c + "_CONCRETE_POWDER", "CONCRETE_POWDER:" + i);
            map(c + "_BANNER", "BANNER:" + (15 - i));
            map(c + "_BED", "BED:" + i);
        }
        String[] dyes = {"INK_SAC", "RED_DYE", "GREEN_DYE", "COCOA_BEANS", "LAPIS_LAZULI", "PURPLE_DYE", "CYAN_DYE",
                "LIGHT_GRAY_DYE", "GRAY_DYE", "PINK_DYE", "LIME_DYE", "YELLOW_DYE", "LIGHT_BLUE_DYE", "MAGENTA_DYE",
                "ORANGE_DYE", "BONE_MEAL"};
        for (int i = 0; i < dyes.length; i++) map(dyes[i], "INK_SACK:" + i);
        map("BLACK_DYE", "INK_SACK:0");
        map("BLUE_DYE", "INK_SACK:4");
        map("BROWN_DYE", "INK_SACK:3");
        map("WHITE_DYE", "INK_SACK:15");
        map("TERRACOTTA", "HARD_CLAY:0");
        map("GLASS_PANE", "THIN_GLASS:0");
        map("SKELETON_SKULL", "SKULL_ITEM:0");
        map("WITHER_SKELETON_SKULL", "SKULL_ITEM:1");
        map("ZOMBIE_HEAD", "SKULL_ITEM:2");
        map("PLAYER_HEAD", "SKULL_ITEM:3");
        map("CREEPER_HEAD", "SKULL_ITEM:4");
        map("DRAGON_HEAD", "SKULL_ITEM:5");
        for (String tool : new String[]{"SWORD", "PICKAXE", "AXE", "HOE"}) {
            map("GOLDEN_" + tool, "GOLD_" + tool + ":0");
            map("WOODEN_" + tool, "WOOD_" + tool + ":0");
        }
        map("GOLDEN_SHOVEL", "GOLD_SPADE:0");
        map("WOODEN_SHOVEL", "WOOD_SPADE:0");
        map("STONE_SHOVEL", "STONE_SPADE:0");
        map("IRON_SHOVEL", "IRON_SPADE:0");
        map("DIAMOND_SHOVEL", "DIAMOND_SPADE:0");
        for (String armor : new String[]{"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) map("GOLDEN_" + armor, "GOLD_" + armor + ":0");
        map("GOLDEN_HORSE_ARMOR", "GOLD_BARDING:0");
        map("IRON_HORSE_ARMOR", "IRON_BARDING:0");
        map("DIAMOND_HORSE_ARMOR", "DIAMOND_BARDING:0");
        for (String[] r : RENAMED) map(r[0], r[1]);
    }

    private Materials() {
    }

    private static void map(String modern, String old) {
        TO_OLD.put(modern, old);
        TO_NEW.putIfAbsent(old, modern);
        String base = old.contains(":") ? old.substring(0, old.indexOf(':')) : old;
        if (old.endsWith(":0")) TO_NEW.putIfAbsent(base, modern);
    }

    public static void init(Logger log) {
        logger = log;
    }

    /** Item of this material on this version, or null if it doesn't exist. data = old damage value (-1 = none). */
    @SuppressWarnings("deprecation")
    public static ItemStack item(String name, int data) {
        if (name == null) return null;
        String n = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("MINECRAFT:", "");
        int dataValue = -1;
        int colon = n.indexOf(':');
        if (colon > 0) {
            try {
                dataValue = Integer.parseInt(n.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
            n = n.substring(0, colon);
        }
        if (Ver.flat()) {
            Material m = Material.matchMaterial(n);
            if (m == null) {
                String modern = dataValue >= 0 ? TO_NEW.get(n + ":" + dataValue) : null;
                if (modern == null) modern = TO_NEW.get(n);
                if (modern != null) m = Material.matchMaterial(modern);
            }
            return m != null && (!isAir(m) || n.contains("AIR")) ? new ItemStack(m) : null;
        }
        String old = TO_OLD.get(n);
        Material m;
        if (old != null) {
            int c = old.indexOf(':');
            m = Material.matchMaterial(old.substring(0, c));
            if (dataValue < 0) dataValue = Integer.parseInt(old.substring(c + 1));
        } else {
            m = Material.matchMaterial(n);
        }
        if (m == null) return null;
        if (dataValue < 0 && data >= 0) dataValue = data;
        ItemStack item = new ItemStack(m, 1);
        if (dataValue > 0) item.setDurability((short) dataValue);
        return item;
    }

    /** Like {@link #item(String, int)}, a barrier (with one warning in the console) if the material doesn't exist. */
    public static ItemStack itemOrFallback(String name, int data, String where) {
        ItemStack item = item(name, data);
        if (item != null) return item;
        if (logger != null && warned.add(name + "@" + where)) {
            logger.warning("Unknown material '" + name + "' in " + where + " on this version (" + Ver.text() + "), showing a barrier.");
        }
        ItemStack barrier = item("BARRIER", -1);
        return barrier != null ? barrier : new ItemStack(Material.STONE);
    }

    public static boolean isAir(Material m) {
        return m == null || m.name().endsWith("AIR");
    }

    @SuppressWarnings("deprecation")
    public static boolean isPlayerHead(ItemStack item) {
        if (item == null) return false;
        String n = item.getType().name();
        return n.equals("PLAYER_HEAD") || n.equals("PLAYER_WALL_HEAD") || n.equals("SKULL_ITEM") && item.getDurability() == 3;
    }

    public static ItemStack playerHead() {
        return item("PLAYER_HEAD", -1);
    }
}
