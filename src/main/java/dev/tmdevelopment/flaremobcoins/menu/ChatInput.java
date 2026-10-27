package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** "Write it in chat" of the admin menus: the next chat line of the player is the answer (2 minutes, "cancel" = back). */
public final class ChatInput implements Listener {

    private static final long TIMEOUT_MS = 120_000L;
    private final FlareMobcoins plugin;
    private final Map<UUID, Ask> waiting = new ConcurrentHashMap<>();

    public ChatInput(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    /** Closes the menu, sends the question (message) and the clickable current value. */
    public void ask(Player player, String message, String current, Consumer<String> answer, Runnable cancel) {
        waiting.put(player.getUniqueId(), new Ask(answer, cancel, System.currentTimeMillis() + TIMEOUT_MS));
        String now = current == null ? "" : current;
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            plugin.lang().send(player, message, "{current}", now);
            plugin.lang().send(player, "input.hint", "{current}", now);
        });
    }

    public boolean waiting(Player player) {
        return waiting.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Ask ask = waiting.remove(e.getPlayer().getUniqueId());
        if (ask == null) return;
        e.setCancelled(true);
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!e.getPlayer().isOnline()) return;
            if (System.currentTimeMillis() > ask.until || text.equalsIgnoreCase("cancel")) {
                plugin.lang().send(e.getPlayer(), "input.cancelled");
                if (ask.cancel != null) ask.cancel.run();
            } else {
                ask.answer.accept(text);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        waiting.remove(e.getPlayer().getUniqueId());
    }

    private static final class Ask {
        final Consumer<String> answer;
        final Runnable cancel;
        final long until;

        Ask(Consumer<String> answer, Runnable cancel, long until) {
            this.answer = answer;
            this.cancel = cancel;
            this.until = until;
        }
    }
}
