package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Materials;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import dev.tmdevelopment.flaremobcoins.util.Ver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * config.yml > sell: players sell mob loot for mobcoins. /mobcoins sell opens a menu (put the items in,
 * close = sold), /mobcoins sell hand and /mobcoins sell all sell from the inventory.
 */
public final class Sell implements Listener {

    private static final int ROWS = 5;
    private static final int FREE = 36;
    private static final int SELL_SLOT = 40;
    private static final int CANCEL_SLOT = 36;

    private final FlareMobcoins plugin;
    /** "MATERIAL" or "MATERIAL:data" (1.8 - 1.12) -> price of one */
    private final Map<String, Double> prices = new HashMap<>();
    private boolean enabled;
    private boolean plainOnly;

    public Sell(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("sell.enabled", false);
        plainOnly = plugin.getConfig().getBoolean("sell.only-plain-items", true);
        prices.clear();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("sell.prices");
        if (s == null) return;
        for (String name : s.getKeys(false)) {
            double price = s.getDouble(name, 0);
            if (price <= 0) continue;
            ItemStack item = Materials.item(name, -1);
            if (item == null) continue;
            prices.put(key(item.getType(), Ver.flat() ? -1 : item.getDurability()), price);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    private static String key(Material m, int data) {
        return data < 0 ? m.name() : m.name() + ":" + data;
    }

    /** Price of one item, 0 if it can't be sold. */
    @SuppressWarnings("deprecation")
    public double price(ItemStack item) {
        if (item == null || Materials.isAir(item.getType())) return 0;
        if (plainOnly && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants())) return 0;
        }
        Double p = Ver.flat() ? prices.get(key(item.getType(), -1)) : prices.get(key(item.getType(), item.getDurability()));
        return p == null ? 0 : p;
    }

    /** Sells every item of the array that has a price (they become null). @return {mobcoins, items sold} */
    private double[] take(ItemStack[] items, int from, int to) {
        double total = 0;
        int count = 0;
        for (int i = from; i < to && i < items.length; i++) {
            double price = price(items[i]);
            if (price <= 0) continue;
            total += price * items[i].getAmount();
            count += items[i].getAmount();
            items[i] = null;
        }
        return new double[]{Num.round(total), count};
    }

    private void pay(Player p, double[] sold) {
        if (sold[1] <= 0) {
            plugin.lang().send(p, "sell.nothing");
            return;
        }
        plugin.coins().give(p.getUniqueId(), p.getName(), sold[0], "sell");
        plugin.lang().send(p, "sell.sold", "{count}", String.valueOf((int) sold[1]), "{amount}", Num.plain(sold[0]),
                "{amount_commas}", Num.commas(sold[0]), "{amount_formatted}", Num.shortFormat(sold[0]));
        Actions.sound(p, "sounds.receive");
    }

    /** /mobcoins sell all: every sellable item of the inventory (not the armor). */
    public void sellAll(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        int size = Math.min(36, contents.length);
        double[] sold = take(contents, 0, size);
        if (sold[1] > 0) {
            for (int i = 0; i < size; i++) p.getInventory().setItem(i, contents[i]);
        }
        pay(p, sold);
    }

    /** /mobcoins sell hand */
    public void sellHand(Player p) {
        ItemStack[] hand = {Compat.hand(p)};
        double[] sold = take(hand, 0, 1);
        if (sold[1] > 0) Compat.setHand(p, null);
        pay(p, sold);
    }

    public void open(Player p) {
        new Menu(p).open();
    }

    // ------------------------------------------------------------------ menu

    /** The sell menu: 4 free rows, the last row has the buttons. */
    final class Menu implements InventoryHolder {
        private final Player player;
        private Inventory inventory;
        private boolean done;

        Menu(Player player) {
            this.player = player;
        }

        void open() {
            inventory = Bukkit.createInventory(this, ROWS * 9, Compat.menuTitle(Text.legacy(Placeholders.apply(plugin.lang().raw("gui.sell.title"), player))));
            drawButtons();
            player.openInventory(inventory);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void drawButtons() {
            ItemStack glass = ItemSpec.simple(plugin.getConfig().getString("menus.border", "YELLOW_STAINED_GLASS_PANE"), " ", null)
                    .build(s -> s);
            for (int i = FREE; i < ROWS * 9; i++) inventory.setItem(i, glass.clone());
            double value = 0;
            int count = 0;
            for (int i = 0; i < FREE; i++) {
                ItemStack it = inventory.getItem(i);
                double price = price(it);
                if (price <= 0) continue;
                value += price * it.getAmount();
                count += it.getAmount();
            }
            String[] pairs = {"{amount}", Num.plain(value), "{amount_commas}", Num.commas(value), "{count}", String.valueOf(count)};
            inventory.setItem(SELL_SLOT, button("EMERALD", "gui.sell.sell", pairs));
            inventory.setItem(CANCEL_SLOT, button("BARRIER", "gui.sell.cancel", pairs));
            inventory.setItem(44, button("BOOK", "gui.sell.help", pairs));
        }

        private ItemStack button(String material, String path, String... pairs) {
            List<String> lore = plugin.lang().lines(path + ".lore");
            return ItemSpec.simple(material, plugin.lang().raw(path + ".name"), lore).build(s -> Placeholders.apply(s, player, pairs));
        }

        void redrawLater() {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!done && player.isOnline() && Compat.topHolder(player) == this) drawButtons();
            });
        }

        /** Sells what has a price; the rest goes back to the player. */
        void finish(boolean sell) {
            if (done) return;
            done = true;
            ItemStack[] items = new ItemStack[FREE];
            for (int i = 0; i < FREE; i++) {
                items[i] = inventory.getItem(i);
                inventory.setItem(i, null);
            }
            if (sell) pay(player, take(items, 0, FREE));
            for (ItemStack rest : items) {
                if (rest == null) continue;
                Map<Integer, ItemStack> left = player.getInventory().addItem(rest);
                for (ItemStack drop : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        /** Sell button: sells and stays open with the rest. */
        void sellNow() {
            ItemStack[] items = new ItemStack[FREE];
            for (int i = 0; i < FREE; i++) items[i] = inventory.getItem(i);
            double[] sold = take(items, 0, FREE);
            for (int i = 0; i < FREE; i++) inventory.setItem(i, items[i]);
            pay(player, sold);
            drawButtons();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        Menu menu = (Menu) e.getInventory().getHolder();
        if (menu.done) {
            // sold or cancelled, closing on the next tick: nothing can be put in anymore
            e.setCancelled(true);
            return;
        }
        int raw = e.getRawSlot();
        int top = e.getInventory().getSize();
        // double click would pull the glass of the last row
        if (e.getClick() == ClickType.DOUBLE_CLICK || e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        if (raw >= FREE && raw < top) {
            e.setCancelled(true);
            if (raw == SELL_SLOT && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)) menu.sellNow();
            else if (raw == CANCEL_SLOT && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)) {
                menu.finish(false);
                Bukkit.getScheduler().runTask(plugin, () -> menu.player.closeInventory());
            }
            return;
        }
        if (raw >= top && e.isShiftClick()) {
            // shift click from the inventory: only into the free rows
            e.setCancelled(true);
            ItemStack moving = e.getCurrentItem();
            if (moving == null || Materials.isAir(moving.getType())) return;
            ItemStack rest = moveIn(menu.inventory, moving.clone());
            e.setCurrentItem(rest);
            menu.drawButtons();
        }
        menu.redrawLater();
    }

    /** Puts an item in the free rows (stacking first). @return what did not fit, null if all of it. */
    private static ItemStack moveIn(Inventory inv, ItemStack item) {
        int left = item.getAmount();
        for (int i = 0; i < FREE && left > 0; i++) {
            ItemStack in = inv.getItem(i);
            if (in == null || !in.isSimilar(item)) continue;
            int room = in.getMaxStackSize() - in.getAmount();
            if (room <= 0) continue;
            int put = Math.min(room, left);
            in.setAmount(in.getAmount() + put);
            left -= put;
        }
        for (int i = 0; i < FREE && left > 0; i++) {
            if (inv.getItem(i) != null) continue;
            ItemStack copy = item.clone();
            int put = Math.min(item.getMaxStackSize(), left);
            copy.setAmount(put);
            inv.setItem(i, copy);
            left -= put;
        }
        if (left <= 0) return null;
        ItemStack rest = item.clone();
        rest.setAmount(left);
        return rest;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        if (((Menu) e.getInventory().getHolder()).done) {
            e.setCancelled(true);
            return;
        }
        for (int slot : e.getRawSlots()) {
            if (slot >= FREE && slot < e.getInventory().getSize()) {
                e.setCancelled(true);
                return;
            }
        }
        ((Menu) e.getInventory().getHolder()).redrawLater();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) ((Menu) e.getInventory().getHolder()).finish(true);
    }

    /** Names of the items that can be sold, for the help. */
    public String list() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : prices.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            String name = e.getKey().contains(":") ? e.getKey().substring(0, e.getKey().indexOf(':')) : e.getKey();
            sb.append(Drops.pretty(name.toLowerCase(Locale.ROOT))).append(' ').append(Num.plain(e.getValue()));
        }
        return sb.toString();
    }

    /** On reload / shutdown: open sell menus give the items back. */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Compat.topHolder(p) instanceof Menu) p.closeInventory();
        }
    }
}
