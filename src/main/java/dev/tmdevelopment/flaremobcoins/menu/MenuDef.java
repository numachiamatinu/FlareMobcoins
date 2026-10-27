package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * A menu file: a shop of shops/ or a menu of the plugin in menus/ (top, drops, confirm).
 * Same format as FlareTokens; the FlareMobcoins 3.x shops (menu_title, size, click_commands, rotating_shop ...)
 * work as they are.
 */
public final class MenuDef {

    final String id;
    final File file;
    final boolean builtin;
    final YamlConfiguration yaml;
    boolean enabled = true;
    String title = "&8Menu";
    int size = 27;
    String permission;
    List<String> openRequirements = Collections.emptyList();
    List<String> openActions = Collections.emptyList();
    List<String> denyActions = Collections.emptyList();
    List<String> openCommands = Collections.emptyList();
    final Map<String, MenuItem> items = new LinkedHashMap<>();
    final List<Deco> decorations = new ArrayList<>();
    /** slot -> items that can be there, the first one that can be seen is shown */
    final Map<Integer, List<MenuItem>> slots = new HashMap<>();
    final Map<String, Object> texts = new HashMap<>();
    /** null if the shop doesn't rotate */
    Rotation rotation;
    boolean hasUpdating;
    boolean hasProducts;
    /** The file has a mistake: the defaults are used and the editor doesn't save over it. */
    boolean broken;

    private MenuDef(String id, File file, boolean builtin, YamlConfiguration yaml) {
        this.id = id;
        this.file = file;
        this.builtin = builtin;
        this.yaml = yaml;
    }

    public String id() {
        return id;
    }

    public boolean builtin() {
        return builtin;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean broken() {
        return broken;
    }

    public File file() {
        return file;
    }

    public String title() {
        return title;
    }

    public int size() {
        return size;
    }

    public List<String> openCommands() {
        return openCommands;
    }

    public YamlConfiguration yaml() {
        return yaml;
    }

    public Map<String, MenuItem> items() {
        return items;
    }

    public MenuItem item(String key) {
        return items.get(key);
    }

    public Rotation rotation() {
        return rotation;
    }

    /** One text of the "texts" section (a list becomes lines). */
    public String text(String path, String fallback) {
        Object v = texts.get(path);
        if (v == null) return fallback;
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) v) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(o);
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    public List<String> lines(String path) {
        Object v = texts.get(path);
        if (v == null) return Collections.emptyList();
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) v) out.add(String.valueOf(o));
            return out;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? Collections.<String>emptyList() : Collections.singletonList(s);
    }

    static MenuDef load(String id, File file, boolean builtin, YamlConfiguration y, ConfigurationSection defaultTexts, Logger log) {
        MenuDef def = new MenuDef(id, file, builtin, y);
        String where = (builtin ? "menus/" : "shops/") + file.getName();
        def.enabled = y.getBoolean("enabled", true);
        def.title = y.getString("title", y.getString("menu_title", def.title));
        if (y.isSet("type") && !"CHEST".equalsIgnoreCase(y.getString("type"))) {
            log.warning(where + ": type " + y.getString("type") + " is not supported, using a chest (rows / size).");
        }
        int size = y.isSet("rows") ? y.getInt("rows") * 9 : y.getInt("size", 27);
        if (size < 9) size = 9;
        if (size > 54) size = 54;
        if (size % 9 != 0) size = (size / 9 + 1) * 9;
        def.size = size;
        def.permission = blank(first(y, "open-permission", "menu_permission", "permission"));
        def.openRequirements = list(y, "open-requirements", "open_requirements", "open_requirement");
        def.openActions = list(y, "open-actions", "open_commands", "open_actions");
        def.denyActions = list(y, "deny-actions", "deny_commands", "deny_actions");
        def.openCommands = list(y, "open-commands", "open_command", "commands");

        if (defaultTexts != null) {
            for (String k : defaultTexts.getKeys(true)) {
                if (!defaultTexts.isConfigurationSection(k)) def.texts.put(k, defaultTexts.get(k));
            }
        }
        ConfigurationSection texts = y.getConfigurationSection("texts");
        if (texts != null) {
            for (String k : texts.getKeys(true)) {
                if (!texts.isConfigurationSection(k)) def.texts.put(k, texts.get(k));
            }
        }

        ConfigurationSection rot = y.getConfigurationSection("rotating_shop");
        if (rot != null && !builtin) {
            Rotation r = new Rotation();
            r.normalSlots = parseSlots(rot.get("normal_slots"), size);
            r.premiumSlots = parseSlots(rot.get("premium_slots"), size);
            r.normalRefresh = Math.max(1, rot.getLong("normal_refresh", 3600));
            r.premiumRefresh = Math.max(1, rot.getLong("premium_refresh", 86400));
            def.rotation = r;
            def.hasUpdating = true;
        }

        int order = 0;
        for (String section : new String[]{"elements", "items"}) {
            ConfigurationSection sec = y.getConfigurationSection(section);
            if (sec == null) continue;
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null || def.items.containsKey(key)) continue;
                MenuItem item = MenuItem.read(key, s, section.equals("elements"), where + " > " + key);
                item.order = order++;
                item.slots = slots(s, size);
                def.items.put(key, item);
                if (item.update) def.hasUpdating = true;
                if (item.product != null) def.hasProducts = true;
                if (item.rotating != null && def.rotation != null) {
                    // rotating items go in the rotating slots, their own slots are not used
                    def.rotation.pool(item.rotating.equals("premium")).put(key, item);
                    continue;
                }
                item.rotating = null;
                for (int slot : item.slots) def.slots.computeIfAbsent(slot, k -> new ArrayList<>()).add(item);
            }
        }
        // lowest priority first; same priority: the item with fewer slots (a button over a border), then the file order
        Comparator<MenuItem> byPriority = Comparator.<MenuItem>comparingInt(i -> i.priority)
                .thenComparingInt(i -> i.slots.size()).thenComparingInt(i -> i.order);
        for (List<MenuItem> l : def.slots.values()) l.sort(byPriority);

        List<?> decos = y.getList("decorations");
        if (decos != null) {
            int index = 0;
            for (Object o : decos) {
                if (!(o instanceof Map)) continue;
                Deco d = readDeco((Map<?, ?>) o, size, where + " > decorations");
                d.index = index++;
                def.decorations.add(d);
            }
        }
        return def;
    }

    static Deco readDeco(Map<?, ?> map, int size, String where) {
        Deco d = new Deco();
        d.slots = parseSlots(map.get("slots"), size);
        if (map.containsKey("slot")) {
            for (int s : parseSlots(map.get("slot"), size)) if (!d.slots.contains(s)) d.slots.add(s);
        }
        Object item = map.get("item");
        if (item instanceof ItemStack) d.item = (ItemStack) item;
        d.spec = ItemSpec.of(section(map), where);
        Object admin = map.get("admin-only");
        d.adminOnly = admin instanceof Boolean && (Boolean) admin;
        Object actions = map.get("actions");
        if (actions instanceof List) {
            for (Object a : (List<?>) actions) d.actions.add(String.valueOf(a));
        }
        return d;
    }

    static List<Integer> slots(ConfigurationSection sec, int size) {
        TreeSet<Integer> set = new TreeSet<>();
        for (String k : new String[]{"slots", "slot"}) {
            if (sec.isSet(k)) set.addAll(parseSlots(sec.get(k), 54));
        }
        List<Integer> out = new ArrayList<>(set);
        out.removeIf(s -> s < 0 || s >= size);
        return out;
    }

    /** 5 · [0, 1] · "0-8" · "0-8,45-53" */
    public static List<Integer> parseSlots(Object raw, int size) {
        TreeSet<Integer> set = new TreeSet<>();
        if (raw == null) return new ArrayList<>();
        if (raw instanceof Number) {
            set.add(((Number) raw).intValue());
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) set.addAll(parseSlots(o, size));
        } else {
            for (String part : String.valueOf(raw).split(",")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                try {
                    int dash = p.indexOf('-', 1);
                    if (dash > 0) {
                        int a = Integer.parseInt(p.substring(0, dash).trim());
                        int b = Integer.parseInt(p.substring(dash + 1).trim());
                        for (int s = Math.min(a, b); s <= Math.max(a, b); s++) set.add(s);
                    } else {
                        set.add(Integer.parseInt(p));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        set.removeIf(s -> s < 0 || s >= size);
        return new ArrayList<>(set);
    }

    /** [0, 1, 2, 7] -> "0-2,7" */
    public static String formatSlots(List<Integer> slots) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(slots));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            int from = sorted.get(i);
            int to = from;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == to + 1) to = sorted.get(++i);
            if (sb.length() > 0) sb.append(',');
            sb.append(from == to ? String.valueOf(from) : from + "-" + to);
        }
        return sb.toString();
    }

    static String first(ConfigurationSection sec, String... keys) {
        for (String k : keys) if (sec.isSet(k)) return sec.getString(k);
        return null;
    }

    static List<String> list(ConfigurationSection sec, String... keys) {
        for (String k : keys) {
            if (sec.isList(k)) return sec.getStringList(k);
            if (sec.isString(k)) {
                String s = sec.getString(k);
                return s == null || s.isEmpty() ? Collections.<String>emptyList() : Collections.singletonList(s);
            }
        }
        return Collections.emptyList();
    }

    static String blank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /** A map of a list (decorations, give) as a section, to read it like the rest of the file. */
    static ConfigurationSection section(Map<?, ?> map) {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && !"item".equals(String.valueOf(e.getKey()))) y.set(String.valueOf(e.getKey()), e.getValue());
        }
        return y;
    }

    /** Glass, borders and simple buttons of the "decorations" list. */
    public static final class Deco {
        List<Integer> slots = new ArrayList<>();
        ItemSpec spec;
        ItemStack item;
        boolean adminOnly;
        final List<String> actions = new ArrayList<>();
        int index;
    }

    /** rotating_shop: the items of the pools take turns in the normal / premium slots. */
    public static final class Rotation {
        List<Integer> normalSlots = Collections.emptyList();
        List<Integer> premiumSlots = Collections.emptyList();
        long normalRefresh;
        long premiumRefresh;
        final Map<String, MenuItem> normalPool = new LinkedHashMap<>();
        final Map<String, MenuItem> premiumPool = new LinkedHashMap<>();

        public List<Integer> slots(boolean premium) {
            return premium ? premiumSlots : normalSlots;
        }

        public Map<String, MenuItem> pool(boolean premium) {
            return premium ? premiumPool : normalPool;
        }

        public long refresh(boolean premium) {
            return premium ? premiumRefresh : normalRefresh;
        }
    }
}
