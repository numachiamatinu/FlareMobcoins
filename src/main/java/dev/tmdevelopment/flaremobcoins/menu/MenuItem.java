package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One item of a menu: a button, a product (with a price) or an element of a menu of the plugin. */
public final class MenuItem {

    static final String[] CLICKS = {"LEFT", "SHIFT_LEFT", "RIGHT", "SHIFT_RIGHT", "MIDDLE", "NUMBER_KEY",
            "DOUBLE_CLICK", "DROP", "CONTROL_DROP", "SWAP_OFFHAND"};

    String key;
    /** From "elements:" of a menu of the plugin (menus/): the plugin knows what it does. */
    boolean element;
    ItemSpec spec;
    /** The exact item saved by the in-game editor (used when there is no material). */
    ItemStack exact;
    List<Integer> slots = new ArrayList<>();
    int priority;
    int order;
    boolean update;
    boolean adminOnly;
    List<String> showIf = Collections.emptyList();
    List<String> hideIf = Collections.emptyList();
    final List<String> clickAny = new ArrayList<>();
    final Map<String, List<String>> clickBy = new HashMap<>();
    List<String> onUpdate = Collections.emptyList();
    Product product;
    /** Rotating shops: "normal" / "premium", null for the items that are always there. */
    String rotating;
    /** Rotating shops: true = every player can buy it stock times, false = stock for the whole server. */
    boolean perPlayer;
    /** Rotating shops: pieces of one rotation, 0 = no limit. */
    int rotationStock;

    public String key() {
        return key;
    }

    public Product product() {
        return product;
    }

    public List<Integer> slots() {
        return slots;
    }

    public boolean adminOnly() {
        return adminOnly;
    }

    public String rotating() {
        return rotating;
    }

    public String name() {
        return spec == null ? null : spec.name();
    }

    static MenuItem read(String key, ConfigurationSection sec, boolean element, String where) {
        MenuItem item = new MenuItem();
        item.key = key;
        item.element = element;
        item.spec = ItemSpec.of(sec, where);
        Object exact = sec.get("item");
        if (exact instanceof ItemStack) item.exact = (ItemStack) exact;
        item.priority = sec.getInt("priority", 0);
        item.update = sec.getBoolean("update", false);
        item.adminOnly = sec.getBoolean("admin-only", false);
        List<String> show = new ArrayList<>(MenuDef.list(sec, "show-if"));
        show.addAll(MenuDef.list(sec, "view_requirements", "view_requirement_list", "view_requirement"));
        item.showIf = show;
        item.hideIf = MenuDef.list(sec, "hide-if");
        item.onUpdate = MenuDef.list(sec, "on-update", "on_update");
        for (String raw : MenuDef.list(sec, "actions", "click_commands", "click_actions")) {
            String line = raw.trim();
            String click = clickPrefix(line);
            if (click != null) {
                item.clickBy.computeIfAbsent(click, k -> new ArrayList<>()).add(line.substring(line.indexOf(']') + 1).trim());
            } else {
                item.clickAny.add(line);
            }
        }
        for (String k : sec.getKeys(false)) {
            String lower = k.toLowerCase(Locale.ROOT);
            if (lower.endsWith("_click_commands") || lower.endsWith("_click_actions")) {
                String click = lower.substring(0, lower.indexOf("_click")).toUpperCase(Locale.ROOT);
                item.clickBy.computeIfAbsent(click, x -> new ArrayList<>()).addAll(sec.getStringList(k));
            }
        }
        if (sec.isSet("price")) item.product = Product.read(sec, where);
        ConfigurationSection rot = sec.getConfigurationSection("rotating_shop");
        if (rot != null) {
            item.rotating = "premium".equalsIgnoreCase(rot.getString("type", "normal")) ? "premium" : "normal";
            item.perPlayer = "PLAYER".equalsIgnoreCase(rot.getString("stock_type", "SERVER"));
            item.rotationStock = Math.max(0, rot.getInt("stock_amount", 0));
        }
        return item;
    }

    static String clickPrefix(String line) {
        if (!line.startsWith("[")) return null;
        int end = line.indexOf(']');
        if (end < 0) return null;
        String tag = line.substring(1, end).trim().toUpperCase(Locale.ROOT);
        for (String c : CLICKS) if (c.equals(tag)) return c;
        return null;
    }

    /** An item with a price: bought with a click, the plugin takes the mobcoins and gives what is written. */
    public static final class Product {
        double price;
        /** ItemStack (exact item saved by the editor) or ItemSpec (written in the file). */
        final List<Object> give = new ArrayList<>();
        List<String> commands = Collections.emptyList();
        Boolean confirm;
        int limit;
        String limitReset = "never";
        int stock;
        String stockReset = "never";
        String permission;

        public double price() {
            return price;
        }

        public int limit() {
            return limit;
        }

        public int stock() {
            return stock;
        }

        static Product read(ConfigurationSection sec, String where) {
            Product p = new Product();
            p.price = Math.max(0, sec.getDouble("price", 0));
            List<?> give = sec.getList("give");
            if (give != null) {
                int n = 0;
                for (Object o : give) {
                    if (o instanceof ItemStack) {
                        p.give.add(o);
                    } else if (o instanceof Map) {
                        p.give.add(ItemSpec.of(MenuDef.section((Map<?, ?>) o), where + " > give " + (++n)));
                    } else if (o instanceof String) {
                        String[] parts = ((String) o).trim().split("\\s+");
                        ItemSpec spec = ItemSpec.simple(parts[0], null, null);
                        if (parts.length > 1) spec.amount(parseInt(parts[1], 1));
                        p.give.add(spec);
                    }
                }
            }
            p.commands = MenuDef.list(sec, "commands");
            if (sec.isSet("confirm")) p.confirm = sec.getBoolean("confirm");
            p.limit = Math.max(0, sec.getInt("limit", 0));
            p.limitReset = sec.getString("limit-reset", "never").toLowerCase(Locale.ROOT);
            p.stock = Math.max(0, sec.getInt("stock", 0));
            p.stockReset = sec.getString("stock-reset", "never").toLowerCase(Locale.ROOT);
            p.permission = MenuDef.blank(sec.getString("permission"));
            return p;
        }

        private static int parseInt(String s, int fallback) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return fallback;
            }
        }
    }
}
