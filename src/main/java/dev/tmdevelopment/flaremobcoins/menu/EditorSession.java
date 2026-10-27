package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import dev.tmdevelopment.flaremobcoins.util.Ver;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-game editor of a menu: the menu opens with its items, they can be moved, taken out or added from the
 * inventory (drag and drop). Closing the menu saves the file.
 * Shift + right click on an empty slot = palette, on an item = admins only on / off.
 * Shift + left click on an item = product settings, on an item of the inventory placed in the menu = sell it.
 */
public final class EditorSession implements InventoryHolder {

    /** Last lore line of the items of the editor: what they are (i: item, d: decoration, l: list slot, r: rotating slot). */
    static final String MARK = "⚙ fm:";
    private final FlareMobcoins plugin;
    final Player viewer;
    MenuDef def;
    private Inventory inventory;
    private final Set<String> rendered = new HashSet<>();
    /** decorations under an item: kept when the item stays there */
    private final Map<Integer, Set<Integer>> hiddenDeco = new HashMap<>();
    /** items under another item in the same slot (priority): kept when the first one stays there */
    private final Map<String, Set<Integer>> shadowed = new HashMap<>();

    public EditorSession(FlareMobcoins plugin, Player viewer, MenuDef def) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.def = def;
    }

    public void open() {
        if (def.broken) {
            plugin.lang().send(viewer, "editor.broken", "{menu}", def.id);
            return;
        }
        String title = Compat.menuTitle(Text.legacy(Placeholders.apply(plugin.lang().raw("gui.editor.title"), viewer, "{menu}", def.id)));
        inventory = Bukkit.createInventory(this, def.size, title);
        build();
        viewer.openInventory(inventory);
        plugin.lang().send(viewer, def.rotation != null ? "editor.editing-rotating" : "editor.editing", "{menu}", def.id);
    }

    public void openLater() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            reloadDef();
            open();
        });
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void build() {
        inventory.clear();
        rendered.clear();
        hiddenDeco.clear();
        shadowed.clear();
        Map<Integer, Integer> decoAt = new HashMap<>();
        for (MenuDef.Deco d : def.decorations) {
            ItemStack item = d.item != null ? d.item.clone() : d.spec.build(s -> Placeholders.apply(s, viewer, "{menu}", def.id));
            if (item == null) continue;
            if (d.adminOnly) item = addLore(item, plugin.lang().lines("gui.editor.admin-only-tag"));
            if (!d.actions.isEmpty()) {
                item = addLore(item, Collections.singletonList(plugin.lang().raw("gui.editor.actions-tag").replace("{actions}", String.join(", ", d.actions))));
            }
            ItemStack tagged = tag(item, "d:" + d.index);
            for (int slot : d.slots) {
                if (slot >= inventory.getSize()) continue;
                inventory.setItem(slot, tagged.clone());
                decoAt.put(slot, d.index);
            }
        }
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= inventory.getSize() || e.getValue().isEmpty()) continue;
            MenuItem first = e.getValue().get(0);
            rendered.add(first.key);
            for (int i = 1; i < e.getValue().size(); i++) {
                shadowed.computeIfAbsent(e.getValue().get(i).key, k -> new HashSet<>()).add(slot);
            }
            Integer deco = decoAt.get(slot);
            if (deco != null) hiddenDeco.computeIfAbsent(deco, k -> new HashSet<>()).add(slot);
            if (first.element && def.builtin && first.key.equals("entries")) {
                int number = first.slots.indexOf(slot) + 1;
                ItemStack marker = ItemSpec.simple("LIGHT_BLUE_STAINED_GLASS_PANE", plugin.lang().raw("gui.editor.list-slot.name"),
                        plugin.lang().lines("gui.editor.list-slot.lore")).amount(Math.max(1, Math.min(64, number)))
                        .build(s -> Placeholders.apply(s, viewer, "{number}", String.valueOf(number)));
                inventory.setItem(slot, tag(marker, "l:" + first.key));
                continue;
            }
            ItemStack item = first.exact != null && first.spec.materialUnset() ? first.exact.clone()
                    : first.spec.build(s -> Placeholders.apply(s, viewer, "{menu}", def.id, "{slot}", String.valueOf(slot)));
            if (item == null) continue;
            List<String> extra = new ArrayList<>();
            if (e.getValue().size() > 1) {
                extra.add(plugin.lang().raw("gui.editor.more-tag").replace("{count}", String.valueOf(e.getValue().size() - 1)));
            }
            if (first.product != null) extra.add(plugin.lang().raw("gui.editor.product-tag").replace("{price}", Num.commas(first.product.price)));
            if (first.adminOnly) extra.addAll(plugin.lang().lines("gui.editor.admin-only-tag"));
            inventory.setItem(slot, tag(addLore(item, extra), "i:" + first.key));
        }
        if (def.rotation != null) {
            for (boolean premium : new boolean[]{false, true}) {
                List<Integer> rs = def.rotation.slots(premium);
                for (int i = 0; i < rs.size(); i++) {
                    int slot = rs.get(i);
                    if (slot >= inventory.getSize()) continue;
                    Integer deco = decoAt.get(slot);
                    if (deco != null) hiddenDeco.computeIfAbsent(deco, k -> new HashSet<>()).add(slot);
                    inventory.setItem(slot, rotatingMarker(premium, i + 1));
                }
            }
        }
    }

    private ItemStack rotatingMarker(boolean premium, int number) {
        String path = "gui.editor.rotating-slot." + (premium ? "premium" : "normal");
        int items = def.rotation.pool(premium).size();
        ItemStack marker = ItemSpec.simple(premium ? "MAGENTA_STAINED_GLASS_PANE" : "LIME_STAINED_GLASS_PANE",
                plugin.lang().raw(path + ".name"), plugin.lang().lines(path + ".lore")).amount(Math.max(1, Math.min(64, number)))
                .build(s -> Placeholders.apply(s, viewer, "{number}", String.valueOf(number), "{items}", String.valueOf(items)));
        return tag(marker, "r:" + (premium ? "premium" : "normal"));
    }

    private ItemStack addLore(ItemStack item, List<String> lines) {
        if (lines.isEmpty()) return item;
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        for (String l : lines) lore.add(Text.item(Placeholders.apply(l, viewer)));
        meta.setLore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    static ItemStack tag(ItemStack item, String tag) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + MARK + tag);
        meta.setLore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    static String tagOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return null;
        for (String line : meta.getLore()) {
            String plain = ChatColor.stripColor(line);
            if (plain == null || !plain.startsWith(MARK)) continue;
            String t = plain.substring(MARK.length()).trim();
            if (t.startsWith("i:") || t.startsWith("d:") || t.startsWith("l:") || t.startsWith("r:")) return t;
        }
        return null;
    }

    static boolean isEditorItem(ItemStack item) {
        return tagOf(item) != null;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().name().endsWith("AIR");
    }

    public void handleClick(InventoryClickEvent e) {
        if (e.getAction().name().startsWith("DROP")) {
            e.setCancelled(true);
            return;
        }
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize() || !e.isShiftClick()) return;
        ItemStack current = e.getCurrentItem();
        boolean none = empty(current);
        if (e.isRightClick()) {
            e.setCancelled(true);
            if (none) new PaletteMenu(plugin, viewer, this, slot).openLater();
            else toggleAdminOnly(slot, current);
            return;
        }
        if (!e.isLeftClick() || none) return;
        String tag = tagOf(current);
        if (tag != null && tag.startsWith("r:")) {
            e.setCancelled(true);
            new RotationMenu(plugin, viewer, this, tag.equals("r:premium")).openLater();
            return;
        }
        if (def.builtin) return;
        e.setCancelled(true);
        if (tag != null && tag.startsWith("i:")) {
            new ProductMenu(plugin, viewer, this, tag.substring(2)).openLater();
            return;
        }
        if (tag != null) return;
        ItemStack sold = clean(current);
        plugin.chatInput().ask(viewer, "editor.ask-price", "", text -> {
            double price = Num.parse(text);
            if (price < 0) plugin.lang().send(viewer, "general.invalid-amount", "{input}", text);
            else sell(slot, sold, price);
            openLater();
        }, this::openLater);
    }

    private void toggleAdminOnly(int slot, ItemStack item) {
        save();
        String tag = tagOf(item);
        Boolean now = null;
        if (tag != null && tag.startsWith("i:")) {
            ConfigurationSection s = section(tag.substring(2));
            if (s != null) {
                now = !s.getBoolean("admin-only", false);
                s.set("admin-only", now);
            }
        } else if (tag == null || tag.startsWith("d:")) {
            List<Map<String, Object>> decos = decorations();
            for (Map<String, Object> d : decos) {
                if (decoSlots(d).contains(slot)) {
                    now = !Boolean.TRUE.equals(d.get("admin-only"));
                    d.put("admin-only", now);
                    break;
                }
            }
            def.yaml.set("decorations", decos);
        }
        def = plugin.menus().save(def);
        if (now != null) plugin.lang().send(viewer, now ? "editor.admin-only-on" : "editor.admin-only-off");
        build();
    }

    public void handleClose(InventoryCloseEvent e) {
        save();
        PlayerInventory inv = viewer.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isEditorItem(inv.getItem(i))) inv.setItem(i, null);
        }
        if (isEditorItem(viewer.getItemOnCursor())) viewer.setItemOnCursor(null);
        plugin.lang().send(viewer, "editor.saved", "{menu}", def.id);
    }

    ConfigurationSection section(String key) {
        ConfigurationSection s = def.yaml.getConfigurationSection("items." + key);
        return s != null ? s : def.yaml.getConfigurationSection("elements." + key);
    }

    List<Map<String, Object>> decorations() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<?> raw = def.yaml.getList("decorations");
        if (raw == null) return out;
        for (Object o : raw) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
            out.add(copy);
        }
        return out;
    }

    static List<Integer> decoSlots(Map<String, Object> d) {
        List<Integer> out = new ArrayList<>(MenuDef.parseSlots(d.get("slots"), 54));
        for (int s : MenuDef.parseSlots(d.get("slot"), 54)) if (!out.contains(s)) out.add(s);
        return out;
    }

    /** Writes where every item is now. Items of the inventory put in the menu become decorations. */
    void save() {
        if (inventory == null) return;
        int size = inventory.getSize();
        Map<String, List<Integer>> itemSlots = new LinkedHashMap<>();
        Set<Integer> taken = new HashSet<>();
        List<Map<String, Object>> oldDecos = decorations();
        Map<Integer, Map<String, Object>> keptDecos = new LinkedHashMap<>();
        Map<String, Map<String, Object>> newDecos = new LinkedHashMap<>();
        List<ItemStack> giveBack = new ArrayList<>();
        Map<String, List<Integer>> rotating = new HashMap<>();
        rotating.put("normal", new ArrayList<>());
        rotating.put("premium", new ArrayList<>());
        for (int slot = 0; slot < size; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (empty(item)) continue;
            String tag = tagOf(item);
            if (tag != null && (tag.startsWith("i:") || tag.startsWith("l:"))) {
                itemSlots.computeIfAbsent(tag.substring(2), k -> new ArrayList<>()).add(slot);
                taken.add(slot);
            } else if (tag != null && tag.startsWith("r:")) {
                rotating.get(tag.substring(2)).add(slot);
                taken.add(slot);
            } else if (tag != null && tag.startsWith("d:")) {
                int index;
                try {
                    index = Integer.parseInt(tag.substring(2));
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (index >= 0 && index < oldDecos.size()) {
                    slotsOf(keptDecos.computeIfAbsent(index, i -> fresh(oldDecos.get(i)))).add(slot);
                }
            } else {
                giveBack.add(clean(item));
                Map<String, Object> d = describe(item);
                String same = d.toString();
                Map<String, Object> existing = newDecos.get(same);
                if (existing == null) {
                    existing = d;
                    existing.put("slots", new ArrayList<Integer>());
                    newDecos.put(same, existing);
                }
                slotsOf(existing).add(slot);
            }
        }
        for (String key : rendered) {
            ConfigurationSection s = section(key);
            if (s == null) continue;
            List<Integer> now = itemSlots.containsKey(key) ? new ArrayList<>(itemSlots.get(key)) : new ArrayList<>();
            for (int hidden : shadowed.getOrDefault(key, Collections.<Integer>emptySet())) {
                if (taken.contains(hidden) && !now.contains(hidden)) now.add(hidden);
            }
            // slots outside a smaller menu are kept (they come back if the menu gets bigger)
            for (String k : new String[]{"slots", "slot"}) {
                for (int old : MenuDef.parseSlots(s.get(k), 54)) if (old >= size && !now.contains(old)) now.add(old);
            }
            s.set("slot", null);
            s.set("slots", MenuDef.formatSlots(now));
        }
        List<Map<String, Object>> decos = new ArrayList<>();
        for (int i = 0; i < oldDecos.size(); i++) {
            Map<String, Object> old = oldDecos.get(i);
            List<Integer> extra = new ArrayList<>();
            for (int s : decoSlots(old)) if (s >= size) extra.add(s);
            for (int s : hiddenDeco.getOrDefault(i, Collections.<Integer>emptySet())) if (taken.contains(s)) extra.add(s);
            Map<String, Object> d = keptDecos.get(i);
            if (d == null && extra.isEmpty()) continue;
            if (d == null) d = fresh(old);
            for (int s : extra) if (!slotsOf(d).contains(s)) slotsOf(d).add(s);
            decos.add(d);
        }
        decos.addAll(newDecos.values());
        for (Map<String, Object> d : decos) d.put("slots", MenuDef.formatSlots(slotsOf(d)));
        def.yaml.set("decorations", decos);
        if (def.rotation != null) {
            def.yaml.set("rotating_shop.normal_slots", rotating.get("normal"));
            def.yaml.set("rotating_shop.premium_slots", rotating.get("premium"));
        }
        def = plugin.menus().save(def);
        for (ItemStack back : giveBack) {
            for (ItemStack left : viewer.getInventory().addItem(back).values()) viewer.getWorld().dropItemNaturally(viewer.getLocation(), left);
        }
    }

    private static Map<String, Object> fresh(Map<String, Object> d) {
        Map<String, Object> copy = new LinkedHashMap<>(d);
        copy.remove("slot");
        copy.put("slots", new ArrayList<Integer>());
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> slotsOf(Map<String, Object> d) {
        Object v = d.get("slots");
        if (v instanceof List && (((List<?>) v).isEmpty() || ((List<?>) v).get(0) instanceof Integer)) return (List<Integer>) v;
        List<Integer> parsed = MenuDef.parseSlots(v, 54);
        d.put("slots", parsed);
        return parsed;
    }

    /** The item without the lore line of the editor. */
    static ItemStack clean(ItemStack item) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return copy;
        List<String> lore = new ArrayList<>();
        for (String line : meta.getLore()) {
            String plain = ChatColor.stripColor(line);
            if (plain == null || !plain.startsWith(MARK)) lore.add(line);
        }
        // lore written again only when a line was removed: the item stays exactly as it was
        if (lore.size() == meta.getLore().size()) return copy;
        meta.setLore(lore.isEmpty() ? null : lore);
        copy.setItemMeta(meta);
        return copy;
    }

    /** An item as it is written in the files (material, name, lore ...). */
    @SuppressWarnings("deprecation")
    static Map<String, Object> describe(ItemStack raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        ItemStack item = clean(raw);
        ItemMeta meta = item.getItemMeta();
        String material = item.getType().name();
        if (meta instanceof SkullMeta) {
            String head = Compat.describeHead((SkullMeta) meta);
            if (head != null) material = head;
        }
        short data = item.getDurability();
        if (!Ver.flat() && data > 0 && !material.contains(":")) material = material + ":" + data;
        out.put("material", material);
        out.put("name", meta != null && meta.hasDisplayName() ? Text.toEditable(meta.getDisplayName()) : " ");
        List<String> lore = new ArrayList<>();
        if (meta != null && meta.hasLore() && meta.getLore() != null) {
            for (String l : meta.getLore()) lore.add(Text.toEditable(l));
        }
        out.put("lore", lore);
        out.put("glow", meta != null && Compat.hasGlow(meta));
        if (item.getAmount() > 1) out.put("amount", item.getAmount());
        int model = meta == null ? 0 : Compat.modelData(meta);
        if (model > 0) out.put("custom-model-data", model);
        out.put("admin-only", false);
        out.put("actions", new ArrayList<String>());
        return out;
    }

    /** Removes everything from a slot (items and decorations). */
    void clearSlot(int slot) {
        for (String key : def.items.keySet()) {
            ConfigurationSection s = section(key);
            if (s == null) continue;
            List<Integer> slots = new ArrayList<>(MenuDef.parseSlots(s.get("slots"), 54));
            for (int x : MenuDef.parseSlots(s.get("slot"), 54)) if (!slots.contains(x)) slots.add(x);
            if (slots.remove(Integer.valueOf(slot))) {
                s.set("slot", null);
                s.set("slots", MenuDef.formatSlots(slots));
            }
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> d : decorations()) {
            List<Integer> slots = decoSlots(d);
            slots.remove(Integer.valueOf(slot));
            if (slots.isEmpty()) continue;
            d.remove("slot");
            d.put("slots", MenuDef.formatSlots(slots));
            kept.add(d);
        }
        def.yaml.set("decorations", kept);
        if (def.rotation != null) {
            for (String type : new String[]{"normal", "premium"}) {
                List<Integer> slots = new ArrayList<>(MenuDef.parseSlots(def.yaml.get("rotating_shop." + type + "_slots"), 54));
                if (slots.remove(Integer.valueOf(slot))) def.yaml.set("rotating_shop." + type + "_slots", slots);
            }
        }
    }

    void addDecoration(int slot, Map<String, Object> deco) {
        clearSlot(slot);
        deco.put("slots", String.valueOf(slot));
        List<Map<String, Object>> decos = decorations();
        decos.add(deco);
        def.yaml.set("decorations", decos);
        def = plugin.menus().save(def);
    }

    /** A rotating slot (normal or premium) on this slot; the shop becomes a rotating shop if it wasn't one. */
    void addRotatingSlot(int slot, boolean premium) {
        clearSlot(slot);
        String path = "rotating_shop." + (premium ? "premium" : "normal") + "_slots";
        List<Integer> slots = new ArrayList<>(MenuDef.parseSlots(def.yaml.get(path), 54));
        if (!slots.contains(slot)) slots.add(slot);
        def.yaml.set(path, slots);
        if (!def.yaml.isSet("rotating_shop.normal_refresh")) def.yaml.set("rotating_shop.normal_refresh", 21600);
        if (!def.yaml.isSet("rotating_shop.premium_refresh")) def.yaml.set("rotating_shop.premium_refresh", 86400);
        def = plugin.menus().save(def);
    }

    /** The item becomes a product of the menu: buyers get exactly that item. */
    void sell(int slot, ItemStack item, double price) {
        clearSlot(slot);
        String key = createProduct(item, price);
        def.yaml.set("items." + key + ".slots", String.valueOf(slot));
        def = plugin.menus().save(def);
        plugin.lang().send(viewer, "editor.product-created", "{price}", Num.commas(price));
    }

    /** Writes a new product (no slots) and returns its key. */
    String createProduct(ItemStack item, double price) {
        Map<String, Object> d = describe(item);
        String name = Text.plain(String.valueOf(d.get("name"))).trim();
        String key = freeKey(name.isEmpty() ? item.getType().name().toLowerCase(Locale.ROOT) : name);
        ConfigurationSection s = def.yaml.createSection("items." + key);
        for (String k : new String[]{"material", "name", "lore", "glow", "amount", "custom-model-data"}) {
            if (d.containsKey(k)) s.set(k, d.get(k));
        }
        s.set("price", number(price));
        List<Object> give = new ArrayList<>();
        give.add(item);
        s.set("give", give);
        s.set("confirm", false);
        s.set("limit", 0);
        s.set("stock", 0);
        return key;
    }

    /** 20 instead of 20.0 in the files. */
    static Object number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Object) (long) v : (Object) v;
    }

    String freeKey(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "item";
        if (base.length() > 30) base = base.substring(0, 30);
        String key = base;
        int n = 2;
        while (def.yaml.isSet("items." + key) || def.yaml.isSet("elements." + key)) key = base + "-" + n++;
        return key;
    }

    void reloadDef() {
        MenuDef now = plugin.menus().def(def.id);
        if (now != null) def = now;
    }
}
