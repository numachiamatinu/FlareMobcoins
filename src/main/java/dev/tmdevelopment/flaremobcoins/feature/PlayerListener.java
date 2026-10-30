package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Loads the balance of a player when he joins, forgets it when he leaves. */
public final class PlayerListener implements Listener {

    private final FlareMobcoins plugin;

    public PlayerListener(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        plugin.coins().load(e.getPlayer());
        plugin.stats().load(e.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.coins().unload(e.getPlayer().getUniqueId());
        plugin.stats().unload(e.getPlayer().getUniqueId());
    }
}
