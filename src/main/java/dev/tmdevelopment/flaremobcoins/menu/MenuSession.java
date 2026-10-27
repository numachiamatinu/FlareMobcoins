package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A menu open for one player. */
public final class MenuSession implements InventoryHolder {

    final MenuDef def;
    final Player player;
    private final Inventory inventory;
    MenuSession parent;
    private final Map<Integer, Click> clicks = new HashMap<>();
    private final Map<Integer, MenuItem> shown = new HashMap<>();
    private long lastClick;
    private double renderedBalance = Double.NaN;
    private int ticks;
    /** times this player bought each product (limit), and pieces sold to everybody (stock) */
    final Map<String, Integer> bought = new HashMap<>();
    final Map<String, Integer> sold = new HashMap<>();
    /** confirm menu: the product waiting for the confirmation */
    MenuItem pendingItem;
    MenuSession pendingFrom;
    double pendingPrice;
    /** pages of the lists of the plugin (drops) */
    int page;

    MenuSession(MenuDef def, Player player, MenuSession parent) {
        this.def = def;
        this.player = player;
        this.parent = parent;
        String title = Compat.menuTitle(Text.legacy(Placeholders.apply(def.title, player, "{menu}", def.id, "{shop}", def.id)));
        this.inventory = Bukkit.createInventory(this, def.size, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public MenuDef def() {
        return def;
    }

    public Player player() {
        return player;
    }

    FlareMobcoins plugin() {
        return FlareMobcoins.get();
    }

    MenuManager menus() {
        return plugin().menus();
    }

    boolean admin() {
        return player.hasPermission("flaremobcoins.admin");
    }

    /** Placeholders of the whole menu ({menu}, time left of the rotating shop). */
    public String[] pairs() {
        return new String[]{"{menu}", def.id, "{shop}", def.id,
                "{normal_time_left}", menus().timeLeft(def, false), "{premium_time_left}", menus().timeLeft(def, true)};
    }

    Actions.Context context(String... extra) {
        return new Actions.Context("shop " + def.id, this, extra);
    }

    // ------------------------------------------------------------------ drawing

    public void render() {
        renderedBalance = plugin().coins().balance(player.getUniqueId());
        clicks.clear();
        shown.clear();
        inventory.clear();
        for (MenuDef.Deco d : def.decorations) {
            if (d.adminOnly && !admin()) continue;
            ItemStack item = d.item != null ? d.item.clone() : d.spec.build(s -> Placeholders.apply(s, player, pairs()));
            if (item == null) continue;
            List<String> actions = d.actions;
            for (int slot : d.slots) {
                if (slot >= inventory.getSize()) continue;
                inventory.setItem(slot, item.clone());
                if (!actions.isEmpty()) clicks.put(slot, (s, click) -> Actions.run(player, actions, context()));
            }
        }
        Set<MenuItem> lists = new HashSet<>();
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= inventory.getSize()) continue;
            MenuItem item = pick(e.getValue());
            if (item == null) continue;
            if (Builtins.isList(this, item)) {
                if (lists.add(item)) Builtins.drawList(this, item);
            } else {
                draw(slot, item);
            }
        }
        for (int slot : rotatingSlots()) draw(slot, menus().rotations().at(def, slot));
    }

    private List<Integer> rotatingSlots() {
        List<Integer> out = new ArrayList<>();
        if (def.rotation == null) return out;
        for (int s : def.rotation.normalSlots) if (s < inventory.getSize()) out.add(s);
        for (int s : def.rotation.premiumSlots) if (s < inventory.getSize() && !out.contains(s)) out.add(s);
        return out;
    }

    /**
     * Every second: everything again when the balance changed (prices, "you need X more") or, every 5 seconds,
     * when show-if / hide-if / permissions now pick another item; otherwise the items with update: true,
     * the items that appear / disappear and the rotating slots.
     */
    void tick() {
        ticks++;
        if (plugin().coins().balance(player.getUniqueId()) != renderedBalance || ticks % 5 == 0 && pickChanged()) {
            render();
            return;
        }
        if (!def.hasUpdating) return;
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= inventory.getSize()) continue;
            List<MenuItem> options = e.getValue();
            boolean dynamic = false;
            for (MenuItem i : options) if (i.update || options.size() > 1) dynamic = true;
            if (!dynamic) continue;
            MenuItem now = pick(options);
            MenuItem before = shown.get(slot);
            if (now == before && (now == null || !now.update)) continue;
            if (now == null) {
                render();
                return;
            }
            if (Builtins.isList(this, now)) continue;
            draw(slot, now);
            if (!now.onUpdate.isEmpty()) Actions.run(player, now.onUpdate, context());
        }
        for (int slot : rotatingSlots()) {
            MenuItem now = menus().rotations().at(def, slot);
            if (now != shown.get(slot) || now != null && now.update) draw(slot, now);
        }
    }

    private boolean pickChanged() {
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= inventory.getSize()) continue;
            MenuItem item = pick(e.getValue());
            if (item != null && Builtins.isList(this, item)) continue;
            if (item != shown.get(slot)) return true;
        }
        return false;
    }

    /** Redraws the menu on the next tick (action [REFRESH]). */
    public void refresh() {
        Bukkit.getScheduler().runTask(plugin(), () -> {
            if (player.isOnline() && menus().isOpen(player, this)) render();
        });
    }

    private MenuItem pick(List<MenuItem> options) {
        Actions.Context ctx = context();
        for (MenuItem i : options) {
            if (i.adminOnly && !admin()) continue;
            if (!Actions.check(player, i.showIf, ctx)) continue;
            if (!i.hideIf.isEmpty() && Actions.check(player, i.hideIf, ctx)) continue;
            return i;
        }
        return null;
    }

    void set(int slot, ItemStack item, Click click) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, item);
        if (click != null) clicks.put(slot, click);
        else clicks.remove(slot);
    }

    private void draw(int slot, MenuItem item) {
        if (item == null) {
            shown.remove(slot);
            set(slot, null, null);
            return;
        }
        shown.put(slot, item);
        if (Builtins.drawSpecial(this, item, slot)) return;
        set(slot, build(item, slot, null), (s, click) -> s.clickItem(item, click));
    }

    ItemStack build(MenuItem item, int slot, String[] extra) {
        String[] pairs = pairs(item, slot, extra);
        ItemStack stack = item.exact != null && item.spec.materialUnset() ? item.exact.clone()
                : item.spec.build(s -> Placeholders.apply(s, player, pairs));
        if (item.product == null || stack == null) return stack;
        List<String> lines = productLines(item);
        if (lines.isEmpty()) return stack;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        for (String line : lines) {
            for (String part : Placeholders.apply(line, player, pairs).split("\n", -1)) lore.add(Text.item(part));
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    String[] pairs(MenuItem item, int slot, String[] extra) {
        List<String> out = new ArrayList<>();
        // rotating items first: their {stock} is the stock of the rotation
        if (item.rotating != null) {
            int left = menus().rotations().left(def, item, player);
            add(out, "{stock}", left < 0 ? "∞" : String.valueOf(left),
                    "{max_stock}", item.rotationStock <= 0 ? "∞" : String.valueOf(item.rotationStock));
        }
        add(out, pairs());
        add(out, "{slot}", String.valueOf(slot));
        if (item.product != null) {
            MenuItem.Product p = item.product;
            double balance = plugin().coins().balance(player.getUniqueId());
            int b = bought.getOrDefault(item.key, 0);
            int s = sold.getOrDefault(item.key, 0);
            add(out, "{price}", Num.commas(p.price), "{price_raw}", Num.plain(p.price),
                    "{limit}", String.valueOf(p.limit), "{bought}", String.valueOf(b),
                    "{left}", String.valueOf(Math.max(0, p.limit - b)),
                    "{stock}", String.valueOf(p.stock), "{stock_left}", String.valueOf(Math.max(0, p.stock - s)),
                    "{missing}", Num.commas(Math.max(0, p.price - balance)));
        }
        if (pendingItem != null && pendingFrom != null && item != pendingItem) {
            add(out, pendingFrom.pairs(pendingItem, slot, null));
            add(out, "{product}", Purchase.name(pendingFrom, pendingItem));
        }
        if (extra != null) add(out, extra);
        return out.toArray(new String[0]);
    }

    private static void add(List<String> list, String... values) {
        for (String v : values) list.add(v);
    }

    /** Lines added under a product: price, limit, stock, and what happens when it is clicked. */
    private List<String> productLines(MenuItem item) {
        MenuItem.Product p = item.product;
        List<String> lines = new ArrayList<>(def.lines("product.info"));
        if (p.limit > 0) lines.addAll(def.lines("product.limit"));
        if (p.stock > 0) lines.addAll(def.lines("product.stock"));
        if (item.rotating != null && item.rotationStock > 0) {
            lines.addAll(def.lines(item.perPlayer ? "product.rotating-player-stock" : "product.rotating-stock"));
        }
        String state;
        if (p.permission != null && !player.hasPermission(p.permission)) state = "product.no-permission";
        else if (item.rotating != null && menus().rotations().left(def, item, player) == 0) state = "product.sold-out";
        else if (p.stock > 0 && sold.getOrDefault(item.key, 0) >= p.stock) state = "product.sold-out";
        else if (p.limit > 0 && bought.getOrDefault(item.key, 0) >= p.limit) state = "product.limit-reached";
        else if (plugin().coins().balance(player.getUniqueId()) + 0.0001 < p.price) state = "product.not-enough";
        else state = "product.can-buy";
        lines.addAll(def.lines(state));
        return lines;
    }

    // ------------------------------------------------------------------ clicks

    void click(int slot, String click) {
        Click c = clicks.get(slot);
        if (c == null) return;
        if (!isBuyClick(click)) {
            // Q, Ctrl+Q, number keys, F, middle and double click: only for items that ask for them ([DROP] ...)
            MenuItem it = shown.get(slot);
            if (it == null || it.product != null || !it.clickBy.containsKey(click)) return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClick < plugin().getConfig().getLong("shop.click-cooldown-ms", 200)) return;
        lastClick = now;
        c.run(this, click);
    }

    void clickItem(MenuItem item, String click) {
        if (item.product != null) {
            if (isBuyClick(click)) Purchase.start(this, item);
            return;
        }
        if (Builtins.click(this, item, click)) return;
        List<String> actions = actionsFor(item, click);
        if (actions.isEmpty()) return;
        if (item.rotating != null && menus().rotations().left(def, item, player) == 0) {
            plugin().lang().send(player, "shop.no-stock");
            Actions.sound(player, "sounds.error");
            return;
        }
        boolean done = Actions.run(player, actions, context(pairs(item, -1, null)));
        if (done && item.rotating != null) menus().rotations().consume(def, item, player);
        if (player.isOnline() && menus().isOpen(player, this)) render();
    }

    static List<String> actionsFor(MenuItem item, String click) {
        List<String> out = new ArrayList<>();
        List<String> byClick = item.clickBy.get(click);
        if (byClick != null) out.addAll(byClick);
        out.addAll(item.clickAny);
        return out;
    }

    static boolean isBuyClick(String click) {
        return click.equals("LEFT") || click.equals("RIGHT") || click.equals("SHIFT_LEFT") || click.equals("SHIFT_RIGHT");
    }

    /** [back]: the menu this one was opened from, or close. */
    public void back() {
        MenuSession to = parent;
        Bukkit.getScheduler().runTask(plugin(), () -> {
            if (!player.isOnline()) return;
            if (to == null) player.closeInventory();
            else menus().reopen(player, to);
        });
    }

    /** Reads how many times the products with a limit / stock were bought. */
    void loadCounters() {
        if (!def.hasProducts) return;
        for (MenuItem item : def.items.values()) {
            MenuItem.Product p = item.product;
            if (p == null) continue;
            String key = Purchase.key(def, item);
            if (p.limit > 0) {
                plugin().coins().bought(player.getUniqueId().toString(), key, Purchase.period(p.limitReset)).thenAccept(n -> {
                    bought.put(item.key, n);
                    refresh();
                });
            }
            if (p.stock > 0) {
                plugin().coins().bought("*", key, Purchase.period(p.stockReset)).thenAccept(n -> {
                    sold.put(item.key, n);
                    refresh();
                });
            }
        }
    }

    interface Click {
        void run(MenuSession session, String click);
    }
}
