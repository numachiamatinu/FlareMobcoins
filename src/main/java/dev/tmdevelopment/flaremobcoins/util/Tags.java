package dev.tmdevelopment.flaremobcoins.util;

import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Data hidden in items and mobs, from 1.8 to the latest version:
 * items keep it in their item data on 1.14+ and in an invisible lore line before;
 * mobs keep it in a scoreboard tag on 1.10+ (saved with the mob) and in memory before.
 */
public final class Tags {

    /** Start of the invisible lore line (1.8 - 1.13). */
    private static final String MARK = "§2§7§4§9§r";
    private static boolean pdcChecked;
    private static Constructor<?> keyNew;
    private static Method metaPdc, pdcSet, pdcGet;
    private static Object stringType;
    private static boolean entityChecked;
    private static Method addTag, getTags, setGlowing;
    private static boolean handChecked;
    private static Method getHand;

    private Tags() {
    }

    // ------------------------------------------------------------------ items

    private static void pdc() {
        if (pdcChecked) return;
        pdcChecked = true;
        if (!Ver.atLeast(14)) return;
        try {
            Class<?> keyClass = Class.forName("org.bukkit.NamespacedKey");
            Class<?> pdcClass = Class.forName("org.bukkit.persistence.PersistentDataContainer");
            Class<?> typeClass = Class.forName("org.bukkit.persistence.PersistentDataType");
            keyNew = keyClass.getConstructor(Plugin.class, String.class);
            metaPdc = ItemMeta.class.getMethod("getPersistentDataContainer");
            pdcSet = pdcClass.getMethod("set", keyClass, typeClass, Object.class);
            pdcGet = pdcClass.getMethod("get", keyClass, typeClass);
            Field f = typeClass.getField("STRING");
            stringType = f.get(null);
        } catch (Throwable t) {
            keyNew = null;
        }
    }

    /** Writes a value in the item (call setItemMeta after). */
    public static void set(Plugin plugin, ItemMeta meta, String key, String value) {
        pdc();
        if (keyNew != null) {
            try {
                pdcSet.invoke(metaPdc.invoke(meta), keyNew.newInstance(plugin, key), stringType, value);
                return;
            } catch (Throwable ignored) {
                // old way below
            }
        }
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
        StringBuilder line = new StringBuilder(MARK);
        for (byte b : (key + "=" + value).getBytes(StandardCharsets.UTF_8)) {
            String hex = String.format("%02x", b & 0xff);
            line.append('§').append(hex.charAt(0)).append('§').append(hex.charAt(1));
        }
        lore.add(line.toString());
        meta.setLore(lore);
    }

    /** The value written by set, null if the item has none. */
    public static String get(Plugin plugin, ItemStack item, String key) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        pdc();
        if (keyNew != null) {
            try {
                Object v = pdcGet.invoke(metaPdc.invoke(meta), keyNew.newInstance(plugin, key), stringType);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
            }
        }
        if (!meta.hasLore() || meta.getLore() == null) return null;
        for (String line : meta.getLore()) {
            if (!line.startsWith(MARK)) continue;
            String hex = line.substring(MARK.length()).replace("§", "");
            if (hex.length() % 2 != 0) continue;
            byte[] bytes = new byte[hex.length() / 2];
            try {
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                continue;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith(key + "=")) return text.substring(key.length() + 1);
        }
        return null;
    }

    // ------------------------------------------------------------------ mobs

    private static void entity() {
        if (entityChecked) return;
        entityChecked = true;
        try {
            addTag = Entity.class.getMethod("addScoreboardTag", String.class);
            getTags = Entity.class.getMethod("getScoreboardTags");
        } catch (Throwable ignored) {
            addTag = null;
        }
        try {
            setGlowing = Entity.class.getMethod("setGlowing", boolean.class);
        } catch (Throwable ignored) {
            setGlowing = null;
        }
    }

    public static void tag(Plugin plugin, Entity e, String tag) {
        entity();
        if (addTag != null) {
            try {
                addTag.invoke(e, tag);
                return;
            } catch (Throwable ignored) {
            }
        }
        e.setMetadata(tag, new FixedMetadataValue(plugin, true));
    }

    public static boolean hasTag(Entity e, String tag) {
        entity();
        if (addTag != null) {
            try {
                Object tags = getTags.invoke(e);
                if (tags instanceof Collection && ((Collection<?>) tags).contains(tag)) return true;
            } catch (Throwable ignored) {
            }
        }
        return e.hasMetadata(tag);
    }

    /** Shining outline, 1.9+ (nothing before). */
    public static void glow(Entity e) {
        entity();
        if (setGlowing == null) return;
        try {
            setGlowing.invoke(e, true);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ clicks

    /** 1.9+ calls the interact event once per hand: true for the main hand (always true on 1.8). */
    public static boolean mainHand(PlayerInteractEvent e) {
        if (!handChecked) {
            handChecked = true;
            try {
                getHand = PlayerInteractEvent.class.getMethod("getHand");
            } catch (Throwable t) {
                getHand = null;
            }
        }
        if (getHand == null) return true;
        try {
            Object hand = getHand.invoke(e);
            return hand == null || "HAND".equals(String.valueOf(hand));
        } catch (Throwable t) {
            return true;
        }
    }
}
