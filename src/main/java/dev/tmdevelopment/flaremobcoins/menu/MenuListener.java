package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Clicks, drags and closes of every menu of the plugin (players can never take items out). */
public final class MenuListener implements Listener {

    private final FlareMobcoins plugin;

    public MenuListener(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof EditorSession) {
            ((EditorSession) holder).handleClick(e);
        } else if (holder instanceof Gui) {
            ((Gui) holder).handleClick(e);
        } else if (holder instanceof MenuSession) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player)) return;
            MenuSession s = (MenuSession) holder;
            Inventory clicked = e.getClickedInventory();
            if (clicked == null || clicked.getHolder() != s) return;
            if (e.getRawSlot() < 0 || e.getRawSlot() >= top.getSize()) return;
            String click = e.getClick() == null ? "LEFT" : e.getClick().name();
            try {
                s.click(e.getRawSlot(), click);
            } catch (Throwable t) {
                plugin.getLogger().warning("Error in menu " + s.def().id() + " (slot " + e.getRawSlot() + "): " + t);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof EditorSession) return;
        if (holder instanceof Gui) ((Gui) holder).handleDrag(e);
        else if (holder instanceof MenuSession) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof EditorSession) {
            ((EditorSession) holder).handleClose(e);
        } else if (holder instanceof Gui) {
            ((Gui) holder).handleClose(e);
        } else if (holder instanceof MenuSession && e.getPlayer() instanceof Player) {
            plugin.menus().closed((Player) e.getPlayer(), (MenuSession) holder);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        MenuSession s = plugin.menus().session(e.getPlayer());
        if (s != null) plugin.menus().closed(e.getPlayer(), s);
    }
}
