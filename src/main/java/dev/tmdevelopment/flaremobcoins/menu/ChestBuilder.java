package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chest builder: a menu is built in a real chest (or barrel, shulker box) and taken over with one command.
 * <pre>
 * /mobcoins layout add &lt;name&gt;      new shop from the chest you look at (shops/&lt;name&gt;.yml, opened with /&lt;name&gt;)
 * /mobcoins layout import &lt;menu&gt;   the chest replaces the decorations of a menu (its buttons stay)
 * /mobcoins layout chest &lt;menu&gt;    the other way round: the menu goes into the empty chest you look at
 * /mobcoins layout remove &lt;name&gt;   deletes a shop (the file goes in backups/)
 * </pre>
 * Lore lines that start with "[" are actions and options, not text ("\[" for a real "[").
 */
public final class ChestBuilder {

    private static final int RANGE = 5;
    private static final List<String> SUBS = Arrays.asList("add", "import", "chest", "remove");
    /** [left] [right] [shift-left] [shift-right] in front of an action: only that click. */
    private static final Map<String, String> CLICKS = new HashMap<>();
    /** Pending removes: player -> shop, confirmed within 30 seconds. */
    private static final Map<UUID, String> REMOVING = new HashMap<>();
    private static final Map<UUID, Long> REMOVING_AT = new HashMap<>();

    static {
        CLICKS.put("left", "LEFT");
        CLICKS.put("right", "RIGHT");
        CLICKS.put("shift-left", "SHIFT_LEFT");
        CLICKS.put("shift_left", "SHIFT_LEFT");
        CLICKS.put("shift-right", "SHIFT_RIGHT");
        CLICKS.put("shift_right", "SHIFT_RIGHT");
    }

    private final FlareMobcoins plugin;

    public ChestBuilder(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    public static boolean isBuilderCommand(String sub) {
        return sub != null && SUBS.contains(sub.toLowerCase(Locale.ROOT));
    }

    public static List<String> subs() {
        return SUBS;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private MenuManager menus() {
        return plugin.menus();
    }

    /** args: "add", "name" ... (without "layout"). */
    public void run(Player p, String label, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2) {
            lang().send(p, "layout.usage." + sub, "{label}", label);
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add":
                add(p, label, name);
                break;
            case "import":
                importInto(p, label, name);
                break;
            case "chest":
                toChest(p, name);
                break;
            default:
                remove(p, label, name, args.length > 2 && args[2].equalsIgnoreCase("confirm"));
        }
    }

    // ------------------------------------------------------------------ the container

    /** The container the player looks at, or null (the message is already sent). */
    private Inventory container(Player p) {
        Block b;
        try {
            b = p.getTargetBlock((Set<Material>) null, RANGE);
        } catch (Throwable t) {
            b = null;
        }
        if (b == null || b.getType().name().endsWith("AIR")) {
            lang().send(p, "layout.no-container");
            return null;
        }
        BlockState state = b.getState();
        if (!(state instanceof InventoryHolder)) {
            lang().send(p, "layout.no-container");
            return null;
        }
        Inventory inv = ((InventoryHolder) state).getInventory();
        String type = inv.getType().name();
        if (!(inv.getType() == InventoryType.CHEST || type.equals("BARREL") || type.equals("SHULKER_BOX"))
                || inv.getSize() % 9 != 0 || inv.getSize() == 0) {
            lang().send(p, "layout.not-supported", "{block}", b.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            return null;
        }
        return inv;
    }

    private static boolean empty(ItemStack i) {
        return i == null || i.getType().name().endsWith("AIR");
    }

    // ------------------------------------------------------------------ add

    private void add(Player p, String label, String name) {
        if (!name.matches("[a-z0-9-]{1,32}")) {
            lang().send(p, "layout.bad-name", "{name}", name);
            return;
        }
        if (menus().def(name) != null || MenuManager.BUILTIN.contains(name) || new File(menus().shopDir(), name + ".yml").exists()) {
            lang().send(p, "layout.name-taken", "{name}", name);
            return;
        }
        if (menus().commandTaken(name)) {
            lang().send(p, "layout.command-taken", "{name}", name);
            return;
        }
        Inventory inv = container(p);
        if (inv == null) return;
        if (isEmpty(inv)) {
            lang().send(p, "layout.chest-empty");
            return;
        }
        MenuDef def = menus().create(name);
        if (def == null) {
            lang().send(p, "layout.name-taken", "{name}", name);
            return;
        }
        def.yaml.set("open-commands", Collections.singletonList(name));
        Summary sum = new Summary();
        apply(def, inv, sum, p);
        def = menus().save(def);
        sum.send(p, "layout.added", "{menu}", def.id, "{label}", label, "{file}", "shops/" + def.id + ".yml");
        new EditorSession(plugin, p, def).openLater();
    }

    // ------------------------------------------------------------------ import

    private void importInto(Player p, String label, String name) {
        MenuDef def = menus().def(name);
        if (def == null || def.broken) {
            lang().send(p, def == null ? "shop.not-found" : "editor.broken", "{shop}", name, "{menu}", name);
            return;
        }
        Inventory inv = container(p);
        if (inv == null) return;
        if (isEmpty(inv)) {
            lang().send(p, "layout.chest-empty");
            return;
        }
        File backup = menus().backup(def.file, def.builtin ? "menus" : "shops");
        if (backup != null) plugin.getLogger().info("Backup of " + def.file.getName() + " before the import: " + backup.getPath());
        Summary sum = new Summary();
        apply(def, inv, sum, p);
        def = menus().save(def);
        sum.send(p, "layout.imported", "{menu}", def.id, "{label}", label,
                "{file}", (def.builtin ? "menus/" : "shops/") + def.id + ".yml", "{backup}", backup == null ? "-" : "backups/" + backup.getName());
        new EditorSession(plugin, p, def).openLater();
    }

    /**
     * The chest becomes the layout of the menu: rows = size of the container, the decorations are replaced,
     * items marked [button] &lt;id&gt; move the item / element of that id (or a rotating slot) there,
     * the other items of the menu stay where they are.
     */
    private void apply(MenuDef def, Inventory inv, Summary sum, Player p) {
        int size = inv.getSize();
        sum.rows = size / 9;
        def.yaml.set("rows", sum.rows);
        def.yaml.set("size", null);
        Map<String, List<Integer>> buttons = new LinkedHashMap<>();
        Map<String, Map<String, Object>> decos = new LinkedHashMap<>();
        int newItems = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack raw = inv.getItem(slot);
            if (empty(raw)) continue;
            sum.items++;
            Parsed parsed = parse(raw);
            if (parsed.button != null) {
                buttons.computeIfAbsent(parsed.button, k -> new ArrayList<>()).add(slot);
                continue;
            }
            sum.actions += parsed.actions.size();
            Map<String, Object> look = look(parsed.item);
            boolean needsItem = parsed.update || parsed.permission != null || parsed.byClick;
            if (needsItem) {
                // an item of the menu: click-specific actions, permission, live placeholders
                ItemMeta im = parsed.item.getItemMeta();
                String shown = im != null && im.hasDisplayName() ? ChatColor.stripColor(im.getDisplayName()) : "";
                if (shown.contains("{player}")) shown = "player";
                String key = freeKey(def, shown.trim().isEmpty() ? parsed.item.getType().name().toLowerCase(Locale.ROOT) : shown);
                ConfigurationSection s = def.yaml.createSection("items." + key);
                for (Map.Entry<String, Object> e : look.entrySet()) s.set(e.getKey(), e.getValue());
                s.set("slots", String.valueOf(slot));
                if (parsed.adminOnly) s.set("admin-only", true);
                if (parsed.update) s.set("update", true);
                if (parsed.permission != null) s.set("show-if", Collections.singletonList("[has_permission] " + parsed.permission));
                s.set("actions", parsed.actions);
                newItems++;
                continue;
            }
            Map<String, Object> d = new LinkedHashMap<>(look);
            d.put("admin-only", parsed.adminOnly);
            d.put("actions", parsed.actions);
            String same = d.toString();
            Map<String, Object> existing = decos.get(same);
            if (existing == null) {
                existing = d;
                existing.put("slots", new ArrayList<Integer>());
                decos.put(same, existing);
            }
            slotsOf(existing).add(slot);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> d : decos.values()) {
            Map<String, Object> ordered = new LinkedHashMap<>();
            ordered.put("slots", MenuDef.formatSlots(slotsOf(d)));
            for (Map.Entry<String, Object> e : d.entrySet()) if (!e.getKey().equals("slots")) ordered.put(e.getKey(), e.getValue());
            list.add(ordered);
        }
        def.yaml.set("decorations", list);
        // [button] <id>: items / elements of the menu and rotating slots
        for (Map.Entry<String, List<Integer>> e : buttons.entrySet()) {
            String id = e.getKey();
            if (id.equals("rotating-normal") || id.equals("rotating-premium")) {
                boolean premium = id.endsWith("premium");
                def.yaml.set("rotating_shop." + (premium ? "premium" : "normal") + "_slots", e.getValue());
                if (!def.yaml.isSet("rotating_shop.normal_refresh")) def.yaml.set("rotating_shop.normal_refresh", 21600);
                if (!def.yaml.isSet("rotating_shop.premium_refresh")) def.yaml.set("rotating_shop.premium_refresh", 86400);
                sum.buttons += e.getValue().size();
                continue;
            }
            ConfigurationSection s = def.yaml.getConfigurationSection("items." + id);
            if (s == null) s = def.yaml.getConfigurationSection("elements." + id);
            if (s == null) {
                sum.unknown.add(id);
                continue;
            }
            s.set("slot", null);
            s.set("slots", MenuDef.formatSlots(e.getValue()));
            sum.buttons += e.getValue().size();
        }
        sum.newItems = newItems;
        // items of the menu outside the new size
        for (String k : new String[]{"items", "elements"}) {
            ConfigurationSection sec = def.yaml.getConfigurationSection(k);
            if (sec == null) continue;
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null || buttons.containsKey(key)) continue;
                for (String sk : new String[]{"slots", "slot"}) {
                    for (int slot : MenuDef.parseSlots(s.get(sk), 54)) if (slot >= size) sum.outside.add(key);
                }
            }
        }
    }

    private static boolean isEmpty(Inventory inv) {
        for (ItemStack i : inv.getContents()) if (!empty(i)) return false;
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> slotsOf(Map<String, Object> d) {
        return (List<Integer>) d.get("slots");
    }

    private static String freeKey(MenuDef def, String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "item";
        if (base.length() > 30) base = base.substring(0, 30);
        String key = base;
        int n = 2;
        while (def.yaml.isSet("items." + key) || def.yaml.isSet("elements." + key)) key = base + "-" + n++;
        return key;
    }

    /** What the lore of an item says: actions and options taken out, the rest is the item. */
    static final class Parsed {
        ItemStack item;
        final List<String> actions = new ArrayList<>();
        boolean byClick;
        boolean adminOnly;
        boolean update;
        String permission;
        String button;
    }

    static Parsed parse(ItemStack raw) {
        Parsed out = new Parsed();
        ItemStack item = EditorSession.clean(raw);
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasLore() && meta.getLore() != null) {
            List<String> lore = new ArrayList<>();
            boolean changed = false;
            for (String line : meta.getLore()) {
                String plain = ChatColor.stripColor(line);
                String t = plain == null ? "" : plain.trim();
                if (t.startsWith("\\[")) {
                    // a real "[" at the start of a line
                    int at = line.indexOf("\\[");
                    lore.add(line.substring(0, at) + line.substring(at + 1));
                    changed = true;
                    continue;
                }
                if (!t.startsWith("[") || t.indexOf(']') < 0) {
                    lore.add(line);
                    continue;
                }
                changed = true;
                option(out, t);
            }
            if (changed) {
                meta.setLore(lore.isEmpty() ? null : lore);
                item.setItemMeta(meta);
            }
        }
        out.item = item;
        return out;
    }

    private static void option(Parsed out, String line) {
        String tag = line.substring(1, line.indexOf(']')).trim().toLowerCase(Locale.ROOT);
        String rest = line.substring(line.indexOf(']') + 1).trim();
        switch (tag) {
            case "admin-only":
            case "admin_only":
                out.adminOnly = true;
                return;
            case "update":
                out.update = true;
                return;
            case "permission":
                if (!rest.isEmpty()) out.permission = rest;
                return;
            case "button":
                if (!rest.isEmpty()) out.button = rest.toLowerCase(Locale.ROOT);
                return;
            default:
        }
        String click = CLICKS.get(tag);
        if (click != null && rest.startsWith("[")) {
            out.byClick = true;
            out.actions.add("[" + click + "] " + rest);
            return;
        }
        out.actions.add(line);
    }

    /**
     * The item as it is written in the files: material / name / lore when that gives back the same item,
     * otherwise the complete item (enchantments, NBT ...) under "item".
     */
    static Map<String, Object> look(ItemStack item) {
        Map<String, Object> out = new LinkedHashMap<>();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta && meta.hasDisplayName() && "{player}".equals(ChatColor.stripColor(meta.getDisplayName()).trim())) {
            out.put("material", "head:{player}");
            out.put("name", Text.toEditable(meta.getDisplayName()));
            List<String> lore = new ArrayList<>();
            if (meta.hasLore() && meta.getLore() != null) for (String l : meta.getLore()) lore.add(Text.toEditable(l));
            out.put("lore", lore);
            if (item.getAmount() > 1) out.put("amount", item.getAmount());
            return out;
        }
        Map<String, Object> d = EditorSession.describe(item);
        d.remove("admin-only");
        d.remove("actions");
        if (sameItem(d, item)) {
            if (Boolean.FALSE.equals(d.get("glow"))) d.remove("glow");
            // "&r" in front of the texts comes from the menus themselves: not written in the file
            if (d.get("name") instanceof String) d.put("name", noReset((String) d.get("name")));
            if (d.get("lore") instanceof List) {
                List<String> lore = new ArrayList<>();
                for (Object l : (List<?>) d.get("lore")) lore.add(noReset(String.valueOf(l)));
                d.put("lore", lore);
            }
            return d;
        }
        out.put("item", item.clone());
        return out;
    }

    private static String noReset(String s) {
        while (s.startsWith("&r")) s = s.substring(2);
        return s.isEmpty() ? " " : s;
    }

    private static boolean sameItem(Map<String, Object> d, ItemStack item) {
        try {
            ItemSpec spec = ItemSpec.of(MenuDef.section(d), "chest builder");
            ItemStack back = spec.buildReal(s -> s);
            if (back == null || back.getAmount() != item.getAmount()) return false;
            return normal(back, item).isSimilar(normal(item, back));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The item with the differences that change nothing on screen taken out: "§r" codes in name and lore,
     * item flags the other item doesn't have (the menus hide them by themselves).
     */
    private static ItemStack normal(ItemStack item, ItemStack other) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        if (meta.hasDisplayName()) meta.setDisplayName(meta.getDisplayName().replace("\u00a7r", ""));
        if (meta.hasLore() && meta.getLore() != null) {
            List<String> lore = new ArrayList<>();
            for (String l : meta.getLore()) lore.add(l.replace("\u00a7r", ""));
            meta.setLore(lore);
        }
        ItemMeta o = other.getItemMeta();
        for (org.bukkit.inventory.ItemFlag f : new ArrayList<>(meta.getItemFlags())) {
            if (o == null || !o.getItemFlags().contains(f)) meta.removeItemFlags(f);
        }
        copy.setItemMeta(meta);
        return copy;
    }

    // ------------------------------------------------------------------ chest (menu -> chest)

    private void toChest(Player p, String name) {
        MenuDef def = menus().def(name);
        if (def == null || def.broken) {
            lang().send(p, def == null ? "shop.not-found" : "editor.broken", "{shop}", name, "{menu}", name);
            return;
        }
        Inventory inv = container(p);
        if (inv == null) return;
        if (!isEmpty(inv)) {
            lang().send(p, "layout.chest-not-empty");
            return;
        }
        if (inv.getSize() < def.size) {
            lang().send(p, "layout.chest-small", "{rows}", String.valueOf(def.size / 9), "{chest}", String.valueOf(inv.getSize() / 9));
            return;
        }
        int placed = 0;
        for (MenuDef.Deco d : def.decorations) {
            ItemStack item = d.item != null ? d.item.clone() : d.spec.buildReal(s -> s);
            if (item == null) continue;
            List<String> extra = new ArrayList<>();
            if (d.adminOnly) extra.add("[admin-only]");
            extra.addAll(d.actions);
            item = withLore(item, extra);
            for (int slot : d.slots) {
                if (slot >= def.size || slot >= inv.getSize()) continue;
                inv.setItem(slot, item.clone());
                placed++;
            }
        }
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= def.size || e.getValue().isEmpty()) continue;
            MenuItem first = e.getValue().get(0);
            ItemStack item = first.exact != null && first.spec.materialUnset() ? first.exact.clone()
                    : first.spec.buildReal(s -> Placeholders.apply(s, p, "{slot}", String.valueOf(slot)));
            if (item == null) item = new ItemStack(Material.STONE);
            inv.setItem(slot, withLore(item, Collections.singletonList("[button] " + first.key)));
            placed++;
        }
        if (def.rotation != null) {
            for (boolean premium : new boolean[]{false, true}) {
                ItemStack marker = ItemSpec.simple(premium ? "MAGENTA_STAINED_GLASS_PANE" : "LIME_STAINED_GLASS_PANE",
                        premium ? "&dPremium rotating slot" : "&aNormal rotating slot", Collections.<String>emptyList()).buildReal(s -> s);
                for (int slot : def.rotation.slots(premium)) {
                    if (slot >= def.size) continue;
                    inv.setItem(slot, withLore(marker, Collections.singletonList("[button] rotating-" + (premium ? "premium" : "normal"))));
                    placed++;
                }
            }
        }
        lang().send(p, "layout.exported", "{menu}", def.id, "{items}", String.valueOf(placed), "{rows}", String.valueOf(def.size / 9));
    }

    /** Lore lines added at the end; existing lines that start with "[" are written "\[" so they stay text. */
    private static ItemStack withLore(ItemStack item, List<String> extra) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        List<String> lore = new ArrayList<>();
        if (meta.hasLore() && meta.getLore() != null) {
            for (String l : meta.getLore()) {
                String plain = ChatColor.stripColor(l);
                if (plain != null && plain.trim().startsWith("[")) {
                    int at = l.indexOf('[');
                    l = l.substring(0, at) + "\\" + l.substring(at);
                }
                lore.add(l);
            }
        }
        for (String x : extra) lore.add(ChatColor.GRAY + x);
        if (lore.isEmpty() && extra.isEmpty()) return copy;
        meta.setLore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    // ------------------------------------------------------------------ remove

    private void remove(Player p, String label, String name, boolean confirm) {
        MenuDef def = menus().def(name);
        if (def == null || def.builtin) {
            lang().send(p, def == null ? "shop.not-found" : "layout.remove-builtin", "{shop}", name, "{menu}", name);
            return;
        }
        if (def.id.equalsIgnoreCase(menus().mainShop())) {
            lang().send(p, "layout.remove-main", "{menu}", def.id);
            return;
        }
        UUID id = p.getUniqueId();
        Long at = REMOVING_AT.get(id);
        boolean confirmed = confirm && def.id.equals(REMOVING.get(id)) && at != null && System.currentTimeMillis() - at < 30_000L;
        if (!confirmed) {
            REMOVING.put(id, def.id);
            REMOVING_AT.put(id, System.currentTimeMillis());
            lang().send(p, "layout.remove-confirm", "{menu}", def.id, "{label}", label);
            return;
        }
        REMOVING.remove(id);
        REMOVING_AT.remove(id);
        File backup = menus().remove(def);
        if (backup == null) {
            lang().send(p, "layout.remove-failed", "{menu}", def.id);
            return;
        }
        plugin.getLogger().info("shops/" + def.id + ".yml removed, backup: " + backup.getPath());
        lang().send(p, "layout.removed", "{menu}", def.id, "{backup}", "backups/" + backup.getName());
    }

    // ------------------------------------------------------------------ summary

    private final class Summary {
        int rows;
        int items;
        int actions;
        int buttons;
        int newItems;
        final List<String> unknown = new ArrayList<>();
        final List<String> outside = new ArrayList<>();

        void send(Player p, String path, String... pairs) {
            List<String> all = new ArrayList<>(Arrays.asList(pairs));
            all.addAll(Arrays.asList("{rows}", String.valueOf(rows), "{items}", String.valueOf(items),
                    "{actions}", String.valueOf(actions), "{buttons}", String.valueOf(buttons)));
            lang().send(p, path, all.toArray(new String[0]));
            if (!unknown.isEmpty()) lang().send(p, "layout.unknown-buttons", "{buttons}", String.join(", ", unknown));
            if (!outside.isEmpty()) {
                lang().send(p, "layout.outside", "{items}", String.join(", ", new java.util.LinkedHashSet<>(outside)));
            }
        }
    }

    // ------------------------------------------------------------------ tab

    public List<String> tab(String[] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("add")) return Collections.singletonList("<name>");
            List<String> names = new ArrayList<>(menus().names());
            if (!sub.equals("remove")) names.addAll(MenuManager.BUILTIN);
            return filter(names, args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> list, String start) {
        List<String> out = new ArrayList<>();
        for (String s : list) if (s.toLowerCase(Locale.ROOT).startsWith(start.toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }
}
