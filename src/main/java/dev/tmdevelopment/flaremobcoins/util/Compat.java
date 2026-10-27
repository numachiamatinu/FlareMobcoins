package dev.tmdevelopment.flaremobcoins.util;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that is done differently from 1.8 to the latest version. Newer methods are used through
 * reflection, with the old way (or nothing) as fallback.
 */
public final class Compat {

    private static final Pattern URL_IN_JSON = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"");
    private static final Map<String, String> OLD_SOUNDS = new HashMap<>();
    private static CommandMap commandMap;
    private static Method title5, title2;
    private static boolean titleChecked;
    private static Object actionBarType;
    private static Method actionBarSend;
    private static boolean actionChecked;
    private static Method mainHandGet, mainHandSet;
    private static boolean handChecked;

    static {
        OLD_SOUNDS.put("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
        OLD_SOUNDS.put("ENTITY_PLAYER_LEVELUP", "LEVEL_UP");
        OLD_SOUNDS.put("UI_BUTTON_CLICK", "CLICK");
        OLD_SOUNDS.put("BLOCK_NOTE_BLOCK_PLING", "NOTE_PLING");
        OLD_SOUNDS.put("BLOCK_NOTE_PLING", "NOTE_PLING");
        OLD_SOUNDS.put("BLOCK_NOTE_BLOCK_BELL", "NOTE_PLING");
        OLD_SOUNDS.put("BLOCK_NOTE_BLOCK_HARP", "NOTE_PIANO");
        OLD_SOUNDS.put("ENTITY_VILLAGER_NO", "VILLAGER_NO");
        OLD_SOUNDS.put("ENTITY_VILLAGER_YES", "VILLAGER_YES");
        OLD_SOUNDS.put("ENTITY_ITEM_PICKUP", "ITEM_PICKUP");
        OLD_SOUNDS.put("BLOCK_CHEST_OPEN", "CHEST_OPEN");
        OLD_SOUNDS.put("ENTITY_ENDERMAN_TELEPORT", "ENDERMAN_TELEPORT");
        OLD_SOUNDS.put("ENTITY_ARROW_HIT_PLAYER", "SUCCESSFUL_HIT");
    }

    private Compat() {
    }

    // ------------------------------------------------------------------ commands

    public static CommandMap commandMap() {
        if (commandMap != null) return commandMap;
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
            commandMap = (CommandMap) m.invoke(Bukkit.getServer());
        } catch (Throwable t) {
            throw new IllegalStateException("Can't reach the command map of this server", t);
        }
        return commandMap;
    }

    /** Sends the new command list to the players (needed on 1.13+ for commands added after startup). */
    public static void syncCommands() {
        try {
            Method m = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            m.setAccessible(true);
            m.invoke(Bukkit.getServer());
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    public static void removeKnown(Command command) {
        try {
            CommandMap map = commandMap();
            command.unregister(map);
            Map<String, Command> known = null;
            try {
                known = (Map<String, Command>) map.getClass().getMethod("getKnownCommands").invoke(map);
            } catch (NoSuchMethodException e) {
                Class<?> c = map.getClass();
                while (c != null && known == null) {
                    try {
                        Field f = c.getDeclaredField("knownCommands");
                        f.setAccessible(true);
                        known = (Map<String, Command>) f.get(map);
                    } catch (NoSuchFieldException ex) {
                        c = c.getSuperclass();
                    }
                }
            }
            if (known == null) return;
            Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator();
            while (it.hasNext()) if (it.next().getValue() == command) it.remove();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ players

    /** Player who already joined, by name (never asks Mojang, never blocks). */
    public static OfflinePlayer knownPlayer(String name) {
        if (name == null) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) return op;
        }
        return null;
    }

    // ------------------------------------------------------------------ title / action bar

    public static void title(Player p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        if (!titleChecked) {
            titleChecked = true;
            try {
                title5 = Player.class.getMethod("sendTitle", String.class, String.class, int.class, int.class, int.class);
            } catch (Throwable ignored) {
            }
            try {
                title2 = Player.class.getMethod("sendTitle", String.class, String.class);
            } catch (Throwable ignored) {
            }
        }
        String t = title == null || title.isEmpty() ? " " : title;
        String s = subtitle == null ? "" : subtitle;
        try {
            if (title5 != null) {
                title5.invoke(p, t, s, fadeIn, stay, fadeOut);
                return;
            }
        } catch (Throwable ignored) {
        }
        if (legacyTitle(p, t, s, fadeIn, stay, fadeOut)) return;
        try {
            if (title2 != null) title2.invoke(p, t, s);
        } catch (Throwable ignored) {
        }
    }

    /** 1.8: title packets. */
    private static boolean legacyTitle(Player p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        try {
            String nms = nmsPackage();
            Class<?> packet = Class.forName(nms + "PacketPlayOutTitle");
            Class<?> action = Class.forName(nms + "PacketPlayOutTitle$EnumTitleAction");
            Class<?> component = Class.forName(nms + "IChatBaseComponent");
            Constructor<?> times = packet.getConstructor(int.class, int.class, int.class);
            Constructor<?> text = packet.getConstructor(action, component);
            sendPacket(p, nms, times.newInstance(fadeIn, stay, fadeOut));
            sendPacket(p, nms, text.newInstance(enumConstant(action, "TITLE"), chatComponent(nms, title)));
            if (!subtitle.isEmpty()) {
                sendPacket(p, nms, text.newInstance(enumConstant(action, "SUBTITLE"), chatComponent(nms, subtitle)));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void actionBar(Player p, String legacyText) {
        if (!actionChecked) {
            actionChecked = true;
            try {
                Class<?> type = Class.forName("net.md_5.bungee.api.ChatMessageType");
                actionBarType = type.getField("ACTION_BAR").get(null);
                actionBarSend = Player.Spigot.class.getMethod("sendMessage", type, BaseComponent[].class);
            } catch (Throwable ignored) {
            }
        }
        try {
            if (actionBarSend != null) {
                actionBarSend.invoke(p.spigot(), actionBarType, TextComponent.fromLegacyText(legacyText));
                return;
            }
        } catch (Throwable ignored) {
        }
        // 1.8 - 1.9: chat packet in position 2
        try {
            String nms = nmsPackage();
            Class<?> component = Class.forName(nms + "IChatBaseComponent");
            Class<?> packet = Class.forName(nms + "PacketPlayOutChat");
            Object obj = packet.getConstructor(component, byte.class).newInstance(chatComponent(nms, legacyText), (byte) 2);
            sendPacket(p, nms, obj);
        } catch (Throwable ignored) {
        }
    }

    private static String nmsPackage() {
        String pkg = Bukkit.getServer().getClass().getPackage().getName();
        return "net.minecraft.server." + pkg.substring(pkg.lastIndexOf('.') + 1) + ".";
    }

    private static Object chatComponent(String nms, String legacyText) throws Exception {
        Class<?> serializer = Class.forName(nms + "IChatBaseComponent$ChatSerializer");
        BaseComponent[] comps = TextComponent.fromLegacyText(legacyText);
        String json = (String) Class.forName("net.md_5.bungee.chat.ComponentSerializer")
                .getMethod("toString", BaseComponent[].class).invoke(null, (Object) comps);
        return serializer.getMethod("a", String.class).invoke(null, json);
    }

    private static void sendPacket(Player p, String nms, Object packet) throws Exception {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Object conn = handle.getClass().getField("playerConnection").get(handle);
        conn.getClass().getMethod("sendPacket", Class.forName(nms + "Packet")).invoke(conn, packet);
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object o : type.getEnumConstants()) if (((Enum<?>) o).name().equals(name)) return o;
        throw new IllegalArgumentException(name);
    }

    // ------------------------------------------------------------------ sounds / effects

    /** New names (ENTITY_PLAYER_LEVELUP), old names (LEVEL_UP) or keys (entity.player.levelup). */
    public static void sound(Player p, String name, float volume, float pitch) {
        if (name == null || name.trim().isEmpty() || name.equalsIgnoreCase("none")) return;
        String s = name.trim();
        Location at = p.getLocation();
        if (s.indexOf('.') < 0 && s.indexOf(':') < 0) {
            String upper = s.toUpperCase(Locale.ROOT);
            Object sound = soundField(upper);
            if (sound == null && !Ver.atLeast(9)) sound = soundField(OLD_SOUNDS.getOrDefault(upper, upper));
            if (sound != null) {
                try {
                    Class<?> soundClass = Class.forName("org.bukkit.Sound");
                    Player.class.getMethod("playSound", Location.class, soundClass, float.class, float.class)
                            .invoke(p, at, sound, volume, pitch);
                    return;
                } catch (Throwable ignored) {
                }
            }
            s = upper.toLowerCase(Locale.ROOT).replace('_', '.');
        }
        try {
            p.playSound(at, s, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    private static Object soundField(String name) {
        try {
            return Class.forName("org.bukkit.Sound").getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static PotionEffectType effect(String name) {
        if (name == null) return null;
        String n = name.trim();
        PotionEffectType type = PotionEffectType.getByName(n.toUpperCase(Locale.ROOT));
        if (type != null) return type;
        try {
            Class<?> key = Class.forName("org.bukkit.NamespacedKey");
            Object k = key.getMethod("minecraft", String.class).invoke(null, n.toLowerCase(Locale.ROOT));
            return (PotionEffectType) PotionEffectType.class.getMethod("getByKey", key).invoke(null, k);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ items

    public static void glow(ItemStack item, ItemMeta meta) {
        try {
            meta.getClass().getMethod("setEnchantmentGlintOverride", Boolean.class).invoke(meta, Boolean.TRUE);
        } catch (Throwable t) {
            Enchantment e = enchant("unbreaking", "DURABILITY");
            if (e == null) e = enchant("luck_of_the_sea", "LUCK");
            if (e != null) {
                meta.addEnchant(e, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
    }

    private static Enchantment enchant(String key, String oldName) {
        try {
            Class<?> nk = Class.forName("org.bukkit.NamespacedKey");
            Object k = nk.getMethod("minecraft", String.class).invoke(null, key);
            Object e = Enchantment.class.getMethod("getByKey", nk).invoke(null, k);
            if (e != null) return (Enchantment) e;
        } catch (Throwable ignored) {
        }
        try {
            return (Enchantment) Enchantment.class.getMethod("getByName", String.class).invoke(null, oldName);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void modelData(ItemMeta meta, int data) {
        if (data <= 0) return;
        try {
            meta.getClass().getMethod("setCustomModelData", Integer.class).invoke(meta, data);
        } catch (Throwable ignored) {
        }
    }

    public static void hideFlags(ItemMeta meta) {
        for (ItemFlag flag : ItemFlag.values()) {
            String n = flag.name();
            if (n.equals("HIDE_ATTRIBUTES") || n.equals("HIDE_POTION_EFFECTS") || n.equals("HIDE_ADDITIONAL_TOOLTIP")
                    || n.equals("HIDE_UNBREAKABLE")) {
                try {
                    meta.addItemFlags(flag);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Puts a texture (base64 value from minecraft-heads) on a head. */
    public static void setTexture(SkullMeta meta, String base64) {
        UUID id = UUID.nameUUIDFromBytes(base64.getBytes(StandardCharsets.UTF_8));
        // 1.18.1+: Bukkit player profiles
        try {
            String url = textureUrl(base64);
            if (url != null) {
                Object profile = Bukkit.class.getMethod("createPlayerProfile", UUID.class, String.class).invoke(null, id, "FlareHead");
                Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
                textures.getClass().getMethod("setSkin", URL.class).invoke(textures, new URL(url));
                profile.getClass().getMethod("setTextures", Class.forName("org.bukkit.profile.PlayerTextures")).invoke(profile, textures);
                meta.getClass().getMethod("setOwnerProfile", Class.forName("org.bukkit.profile.PlayerProfile")).invoke(meta, profile);
                return;
            }
        } catch (Throwable ignored) {
        }
        // older: Mojang GameProfile inside the skull meta
        try {
            Class<?> gp = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> prop = Class.forName("com.mojang.authlib.properties.Property");
            Object profile = gp.getConstructor(UUID.class, String.class).newInstance(id, "FlareHead");
            Object props = gp.getMethod("getProperties").invoke(profile);
            Object property = prop.getConstructor(String.class, String.class).newInstance("textures", base64);
            props.getClass().getMethod("put", Object.class, Object.class).invoke(props, "textures", property);
            try {
                Method set = meta.getClass().getDeclaredMethod("setProfile", gp);
                set.setAccessible(true);
                set.invoke(meta, profile);
            } catch (NoSuchMethodException e) {
                Field f = meta.getClass().getDeclaredField("profile");
                f.setAccessible(true);
                f.set(meta, profile);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String textureUrl(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
            Matcher m = URL_IN_JSON.matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean skinLookups;

    /**
     * Can the server look up the skin of a player? Not on offline-mode servers without BungeeCord / Velocity:
     * the ids are not Mojang ids, every lookup fails and Paper writes a warning for each head.
     */
    public static boolean skinLookups() {
        if (skinLookups != null) return skinLookups;
        boolean ok = Bukkit.getOnlineMode();
        if (!ok) {
            try {
                ok = Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false);
            } catch (Throwable ignored) {
            }
        }
        if (!ok) {
            java.io.File paper = new java.io.File("config", "paper-global.yml");
            java.io.File old = new java.io.File("paper.yml");
            if (paper.isFile()) ok = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(paper).getBoolean("proxies.velocity.enabled", false);
            if (!ok && old.isFile()) ok = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(old).getBoolean("settings.velocity-support.enabled", false);
        }
        skinLookups = ok;
        return ok;
    }

    /** Copies the skin an online player already has (also from SkinsRestorer) on a head, without lookups. 1.18.1+. */
    public static boolean copySkin(SkullMeta meta, Player p) {
        try {
            Class<?> type = Class.forName("org.bukkit.profile.PlayerProfile");
            Object profile = Player.class.getMethod("getPlayerProfile").invoke(p);
            if (!type.isInstance(profile)) return false;
            Object textures = type.getMethod("getTextures").invoke(profile);
            if (Boolean.TRUE.equals(Class.forName("org.bukkit.profile.PlayerTextures").getMethod("isEmpty").invoke(textures))) return false;
            SkullMeta.class.getMethod("setOwnerProfile", type).invoke(meta, profile);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static void setOwner(SkullMeta meta, String name) {
        if (name == null || name.isEmpty()) return;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && copySkin(meta, online)) return;
        if (!skinLookups()) return; // offline-mode server: a plain head instead of a failed lookup
        if (online != null) {
            try {
                SkullMeta.class.getMethod("setOwningPlayer", OfflinePlayer.class).invoke(meta, online);
                return;
            } catch (Throwable ignored) {
            }
        }
        meta.setOwner(name);
    }

    // ------------------------------------------------------------------ hands, heads, menus

    private static void handMethods() {
        if (handChecked) return;
        handChecked = true;
        try {
            mainHandGet = PlayerInventory.class.getMethod("getItemInMainHand");
            mainHandSet = PlayerInventory.class.getMethod("setItemInMainHand", ItemStack.class);
        } catch (Throwable ignored) {
        }
    }

    /** Item in the main hand (1.9+) or in the hand (1.8). */
    @SuppressWarnings("deprecation")
    public static ItemStack hand(Player p) {
        handMethods();
        try {
            if (mainHandGet != null) return (ItemStack) mainHandGet.invoke(p.getInventory());
        } catch (Throwable ignored) {
        }
        return p.getItemInHand();
    }

    @SuppressWarnings("deprecation")
    public static void setHand(Player p, ItemStack item) {
        handMethods();
        try {
            if (mainHandSet != null) {
                mainHandSet.invoke(p.getInventory(), item);
                return;
            }
        } catch (Throwable ignored) {
        }
        p.setItemInHand(item);
    }

    /** How a head is written in the files: texture:&lt;base64&gt; or head:&lt;name&gt;, null if it is a plain head. */
    @SuppressWarnings("deprecation")
    public static String describeHead(SkullMeta meta) {
        try {
            Field f = meta.getClass().getDeclaredField("profile");
            f.setAccessible(true);
            Object profile = f.get(meta);
            if (profile != null && profile.getClass().getName().equals("com.mojang.authlib.GameProfile")) {
                Object props = profile.getClass().getMethod("getProperties").invoke(profile);
                Object textures = props.getClass().getMethod("get", Object.class).invoke(props, "textures");
                for (Object p : (Iterable<?>) textures) {
                    String value;
                    try {
                        value = (String) p.getClass().getMethod("value").invoke(p);
                    } catch (NoSuchMethodException e) {
                        value = (String) p.getClass().getMethod("getValue").invoke(p);
                    }
                    if (value != null && !value.isEmpty()) return "texture:" + value;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object profile = meta.getClass().getMethod("getOwnerProfile").invoke(meta);
            if (profile != null) {
                Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
                Object skin = textures.getClass().getMethod("getSkin").invoke(textures);
                if (skin != null) {
                    String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + skin + "\"}}}";
                    return "texture:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
                }
                Object name = profile.getClass().getMethod("getName").invoke(profile);
                if (name != null && !"FlareHead".equals(name)) return "head:" + name;
            }
        } catch (Throwable ignored) {
        }
        try {
            String owner = meta.getOwner();
            if (owner != null && !owner.isEmpty() && !"FlareHead".equals(owner)) return "head:" + owner;
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean hasGlow(ItemMeta meta) {
        try {
            if (Boolean.TRUE.equals(meta.getClass().getMethod("getEnchantmentGlintOverride").invoke(meta))) return true;
        } catch (Throwable ignored) {
        }
        return meta.hasEnchants();
    }

    public static int modelData(ItemMeta meta) {
        try {
            if ((Boolean) meta.getClass().getMethod("hasCustomModelData").invoke(meta)) {
                return (Integer) meta.getClass().getMethod("getCustomModelData").invoke(meta);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** Holder of the menu the player has open (InventoryView became an interface in 1.21). */
    public static InventoryHolder topHolder(Player p) {
        try {
            Object view = Player.class.getMethod("getOpenInventory").invoke(p);
            Object top = view.getClass().getMethod("getTopInventory").invoke(view);
            return top instanceof Inventory ? ((Inventory) top).getHolder() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Inventory titles longer than 32 characters crash 1.8. */
    public static String menuTitle(String legacy) {
        if (Ver.atLeast(9) || legacy.length() <= 32) return legacy;
        // 1.8: 32 characters, color codes included. Gradients repeat the codes on every letter: write each change once
        String compact = compactLegacy(legacy);
        if (compact.length() <= 32) return compact;
        // still too long: one style for the whole title
        String first = leadingCodes(compact);
        String single = first + compact.replaceAll("\u00a7.", "");
        return cut(single.length() <= 32 ? single : compact, 32);
    }

    /** Legacy text with a color/format code only where the style really changes. */
    static String compactLegacy(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char color = 0, shownColor = 0;
        StringBuilder fmt = new StringBuilder();
        String shownFmt = "";
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                char k = Character.toLowerCase(s.charAt(++i));
                if ((k >= '0' && k <= '9') || (k >= 'a' && k <= 'f')) {
                    color = k;
                    fmt.setLength(0);
                } else if (k == 'r') {
                    color = 0;
                    fmt.setLength(0);
                } else if ("klmno".indexOf(k) >= 0 && fmt.indexOf(String.valueOf(k)) < 0) {
                    fmt.append(k);
                }
                continue;
            }
            String f = fmt.toString();
            boolean same = color == shownColor && f.equals(shownFmt);
            // the color of a space can't be seen (unless underlined or struck through)
            if (c == ' ' && started && f.equals(shownFmt) && f.indexOf('m') < 0 && f.indexOf('n') < 0) same = true;
            if (!same || !started && (color != 0 || !f.isEmpty())) {
                if (started && color == shownColor && f.startsWith(shownFmt)) {
                    for (char x : f.substring(shownFmt.length()).toCharArray()) out.append('\u00a7').append(x);
                } else {
                    if (color != 0 || started) out.append('\u00a7').append(color == 0 ? 'r' : color);
                    for (char x : f.toCharArray()) out.append('\u00a7').append(x);
                }
                shownColor = color;
                shownFmt = f;
            }
            started = true;
            out.append(c);
        }
        return out.toString();
    }

    private static String leadingCodes(String s) {
        int i = 0;
        while (i + 1 < s.length() && s.charAt(i) == '\u00a7') i += 2;
        return s.substring(0, i);
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        String t = s.substring(0, max);
        if (t.endsWith("\u00a7")) t = t.substring(0, max - 1);
        return t;
    }
}
