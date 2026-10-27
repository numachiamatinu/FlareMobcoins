package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Base of the admin menus (settings, editor tools): texts from lang/en.yml > gui, one action per slot. */
public abstract class Gui implements InventoryHolder {

    protected final FlareMobcoins plugin;
    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Gui(FlareMobcoins plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    protected abstract String title();

    protected abstract int rows();

    protected abstract void build();

    protected Lang lang() {
        return plugin.lang();
    }

    public void open() {
        String title = Compat.menuTitle(Text.legacy(Placeholders.apply(title(), viewer)));
        inventory = Bukkit.createInventory(this, rows() * 9, title);
        actions.clear();
        build();
        viewer.openInventory(inventory);
    }

    /** Opens on the next tick (from a click or a chat answer). */
    public void openLater() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) open();
        });
    }

    public void refresh() {
        if (inventory == null) return;
        inventory.clear();
        actions.clear();
        build();
    }

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** Item with name and lore from lang/en.yml (path.name, path.lore). */
    protected ItemStack button(String material, String path, String... pairs) {
        return item(material, lang().raw(path + ".name"), lang().lines(path + ".lore"), false, pairs);
    }

    /** An on / off option: name and description from the path, then the Status / Click to toggle lines. */
    protected ItemStack toggle(String material, String path, boolean on, String... pairs) {
        List<String> lore = new ArrayList<>(lang().lines(path + ".lore"));
        lore.addAll(lang().lines("gui.common.toggle-lore"));
        String[] all = join(pairs, "{status}", lang().raw(on ? "gui.common.enabled" : "gui.common.disabled"));
        return item(on ? material : "GRAY_DYE", lang().raw(path + ".name"), lore, on, all);
    }

    /** A value: name and description from the path, then the Now / Click to change lines. */
    protected ItemStack value(String material, String path, String value, String... pairs) {
        List<String> lore = new ArrayList<>(lang().lines(path + ".lore"));
        lore.addAll(lang().lines(lang().isSet(path + ".click") ? path + ".click" : "gui.common.value-lore"));
        return item(material, lang().raw(path + ".name"), lore, false, join(pairs, "{value}", value));
    }

    protected ItemStack item(String material, String name, List<String> lore, boolean glow, String... pairs) {
        ItemSpec spec = ItemSpec.simple(material, name, lore);
        if (glow) spec.glow(true);
        return spec.build(s -> Placeholders.apply(s, viewer, pairs));
    }

    protected static String[] join(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Empty slots get the border glass of FlareMobcoins. */
    protected void fill() {
        ItemStack glass = item(plugin.getConfig().getString("menus.border", "YELLOW_STAINED_GLASS_PANE"), " ", null, false);
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    protected Inventory inventory() {
        return inventory;
    }

    public void handleClick(InventoryClickEvent e) {
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) return;
        Consumer<InventoryClickEvent> action = actions.get(slot);
        // only left, right and shift clicks (not Q, number keys, F, double click)
        if (action == null || !MenuSession.isBuyClick(e.getClick() == null ? "LEFT" : e.getClick().name())) return;
        Actions.sound(viewer, "sounds.click");
        action.accept(e);
    }

    public void handleDrag(InventoryDragEvent e) {
        e.setCancelled(true);
    }

    public void handleClose(InventoryCloseEvent e) {
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
