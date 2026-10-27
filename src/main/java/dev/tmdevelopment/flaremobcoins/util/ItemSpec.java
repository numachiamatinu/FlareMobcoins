package dev.tmdevelopment.flaremobcoins.util;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An item written in a file: material (also "[head] base64", "[player_head] {player}", "[ia] id", "[ie] id",
 * "[potion] SPEED"), amount, display_name, lore, glow, custom_model_data, damage.
 */
public final class ItemSpec {

    private static final Pattern PREFIX = Pattern.compile("^\\[([^]]*)]\\s*(.*)$");
    private String material = "STONE";
    private boolean materialSet;
    private int amount = 1;
    private String name;
    private List<String> lore = Collections.emptyList();
    private int data = -1;
    private int modelData;
    private int damage;
    private boolean glow;
    private String where = "config";

    public static ItemSpec of(ConfigurationSection sec, String where) {
        ItemSpec spec = new ItemSpec();
        spec.where = where;
        if (sec == null) return spec;
        spec.materialSet = sec.isSet("material");
        spec.material = sec.getString("material", "STONE");
        spec.amount = Math.max(1, Math.min(64, sec.getInt("amount", 1)));
        if (sec.isSet("display_name")) spec.name = sec.getString("display_name");
        else if (sec.isSet("display-name")) spec.name = sec.getString("display-name");
        else if (sec.isSet("name")) spec.name = sec.getString("name");
        if (sec.isList("lore")) spec.lore = sec.getStringList("lore");
        else if (sec.isString("lore")) spec.lore = Collections.singletonList(sec.getString("lore"));
        if (sec.isSet("data")) spec.data = sec.getInt("data");
        spec.modelData = sec.getInt("custom_model_data", sec.getInt("custom-model-data", sec.getInt("custom_model", sec.getInt("model", 0))));
        // 3.x: "data" was the custom model data
        if (spec.modelData == 0 && spec.data > 0 && Ver.atLeast(14)) spec.modelData = spec.data;
        spec.damage = sec.getInt("damage", 0);
        spec.glow = sec.getBoolean("glow", false);
        return spec;
    }

    public static ItemSpec simple(String material, String name, List<String> lore) {
        ItemSpec spec = new ItemSpec();
        spec.material = material;
        spec.materialSet = material != null;
        spec.name = name;
        spec.lore = lore == null ? Collections.emptyList() : lore;
        return spec;
    }

    public String name() {
        return name;
    }

    public String material() {
        return material;
    }

    public List<String> lore() {
        return lore;
    }

    public int amount() {
        return amount;
    }

    public ItemSpec amount(int value) {
        amount = Math.max(1, Math.min(64, value));
        return this;
    }

    public ItemSpec glow(boolean value) {
        glow = value;
        return this;
    }

    /** No "material" in the file (the exact item saved by the editor is used). */
    public boolean materialUnset() {
        return !materialSet;
    }

    /** "head:Notch" is a player name, "head:eyJ0..." a texture. */
    static boolean looksLikeTexture(String v) {
        if (v == null) return false;
        String s = v.trim();
        return s.length() > 16 || s.matches("[0-9a-f]{40,}");
    }

    /** Builds the item for a menu; every text goes through the placeholders and then the text engine. */
    public ItemStack build(UnaryOperator<String> placeholders) {
        return build(placeholders, true);
    }

    /** Builds an item given to a player: no hidden flags and no extra data, so it stacks with normal items. */
    public ItemStack buildReal(UnaryOperator<String> placeholders) {
        return build(placeholders, false);
    }

    @SuppressWarnings("deprecation")
    private ItemStack build(UnaryOperator<String> placeholders, boolean menu) {
        UnaryOperator<String> ph = placeholders == null ? UnaryOperator.identity() : placeholders;
        ItemStack item = base(ph);
        item.setAmount(amount);
        if (damage > 0) item.setDurability((short) damage);
        if (!menu && name == null && lore.isEmpty() && modelData == 0 && !glow) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (name != null) meta.setDisplayName(Text.item(ph.apply(name)));
        if (!lore.isEmpty()) {
            List<String> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                for (String part : ph.apply(line).split("\n", -1)) lines.add(Text.item(part));
            }
            meta.setLore(lines);
        }
        Compat.modelData(meta, modelData);
        if (glow) Compat.glow(item, meta);
        if (menu) Compat.hideFlags(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack base(UnaryOperator<String> ph) {
        String m = material == null ? "STONE" : material.trim();
        Matcher matcher = PREFIX.matcher(m);
        String kind = null;
        String value = m;
        if (matcher.find()) {
            kind = matcher.group(1).trim().toLowerCase(Locale.ROOT);
            value = matcher.group(2).trim();
        } else {
            int colon = m.indexOf(':');
            if (colon > 0) {
                String k = m.substring(0, colon).toLowerCase(Locale.ROOT);
                if (k.equals("head") || k.equals("texture") || k.equals("base64") || k.equals("player") || k.equals("ia")) {
                    kind = k.equals("player") ? "player_head" : k;
                    value = m.substring(colon + 1).trim();
                }
            }
        }
        if (kind == null) return Materials.itemOrFallback(m, data, where);
        if (kind.equals("head") && !looksLikeTexture(value)) kind = "player_head";
        switch (kind) {
            case "head":
            case "base64":
            case "texture": {
                ItemStack head = Materials.playerHead();
                if (head == null) return Materials.itemOrFallback("STONE", -1, where);
                ItemMeta meta = head.getItemMeta();
                if (meta instanceof SkullMeta) {
                    Compat.setTexture((SkullMeta) meta, value);
                    head.setItemMeta(meta);
                }
                return head;
            }
            case "player_head":
            case "player": {
                ItemStack head = Materials.playerHead();
                if (head == null) return Materials.itemOrFallback("STONE", -1, where);
                ItemMeta meta = head.getItemMeta();
                if (meta instanceof SkullMeta) {
                    Compat.setOwner((SkullMeta) meta, ph.apply(value));
                    head.setItemMeta(meta);
                }
                return head;
            }
            case "ia":
            case "itemsadder": {
                ItemStack ia = itemsAdder(value);
                return ia != null ? ia : Materials.itemOrFallback("STONE", -1, where);
            }
            case "ie":
            case "executableitems": {
                ItemStack ei = executableItem(value);
                return ei != null ? ei : Materials.itemOrFallback("STONE", -1, where);
            }
            case "potion": {
                ItemStack potion = Materials.itemOrFallback("POTION", -1, where);
                ItemMeta meta = potion.getItemMeta();
                PotionEffectType type = Compat.effect(value);
                if (meta instanceof PotionMeta && type != null) {
                    ((PotionMeta) meta).addCustomEffect(new PotionEffect(type, 20, 0), true);
                    potion.setItemMeta(meta);
                }
                return potion;
            }
            case "empty":
            case "air":
                return Materials.itemOrFallback("AIR", -1, where);
            default:
                return Materials.itemOrFallback(value, data, where);
        }
    }

    private static ItemStack itemsAdder(String id) {
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return null;
        try {
            Class<?> c = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = c.getMethod("getInstance", String.class).invoke(null, id);
            return stack == null ? null : (ItemStack) c.getMethod("getItemStack").invoke(stack);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ItemStack executableItem(String id) {
        if (Bukkit.getPluginManager().getPlugin("ExecutableItems") == null) return null;
        try {
            Class<?> api = Class.forName("com.ssomar.score.api.executableitems.ExecutableItemsAPI");
            Object manager = api.getMethod("getExecutableItemsManager").invoke(null);
            Object found = manager.getClass().getMethod("getExecutableItem", String.class).invoke(manager, id);
            if (found instanceof Optional && ((Optional<?>) found).isPresent()) {
                Object ei = ((Optional<?>) found).get();
                return (ItemStack) ei.getClass().getMethod("buildItem", int.class, Optional.class).invoke(ei, 1, Optional.empty());
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
