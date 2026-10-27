package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * config.yml > coins-on-ground: killed mobs drop a coin item, the player gets the mobcoins when he picks it up.
 * Coins of the same player close to each other become one. Coins never stay after a restart.
 */
public final class GroundCoins implements Listener {

    private final FlareMobcoins plugin;
    private final Map<UUID, Coin> coins = new ConcurrentHashMap<>();
    private final Listener pickupListener = new Listener() {
    };
    private boolean enabled;
    private boolean onlyKiller;
    private ItemSpec item;
    private String name;
    private long despawnMillis;
    private List<String> pickupActions = Collections.emptyList();
    private BukkitTask cleaner;
    private long counter;

    public GroundCoins(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("coins-on-ground.enabled", false);
        onlyKiller = plugin.getConfig().getBoolean("coins-on-ground.only-killer", true);
        item = ItemSpec.simple(plugin.getConfig().getString("coins-on-ground.material", "SUNFLOWER"), null, null);
        name = plugin.getConfig().getString("coins-on-ground.name", "");
        despawnMillis = Math.max(5, plugin.getConfig().getLong("coins-on-ground.despawn-seconds", 300)) * 1000L;
        pickupActions = plugin.getConfig().getStringList("coins-on-ground.pickup-actions");
        if (cleaner != null) cleaner.cancel();
        cleaner = Bukkit.getScheduler().runTaskTimer(plugin, this::clean, 100L, 100L);
    }

    /** Pickup events: EntityPickupItemEvent on 1.12+ (also stops mobs), PlayerPickupItemEvent before. */
    public void registerPickup() {
        String[] events = {"org.bukkit.event.entity.EntityPickupItemEvent", "org.bukkit.event.player.PlayerPickupItemEvent"};
        for (String name : events) {
            try {
                @SuppressWarnings("unchecked")
                Class<? extends Event> type = (Class<? extends Event>) Class.forName(name);
                Bukkit.getPluginManager().registerEvent(type, pickupListener, EventPriority.HIGHEST,
                        (listener, event) -> onPickup(event), plugin, false);
                return;
            } catch (Throwable ignored) {
                // not on this version: try the next one
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public int size() {
        return coins.size();
    }

    // ------------------------------------------------------------------ drop

    public void drop(Player owner, Location at, double amount) {
        double total = amount;
        // coins of the same player close by become one
        for (Entity e : at.getWorld().getNearbyEntities(at, 3, 1.5, 3)) {
            Coin c = coins.get(e.getUniqueId());
            if (c == null || !c.owner.equals(owner.getUniqueId())) continue;
            total += c.amount;
            coins.remove(e.getUniqueId());
            e.remove();
        }
        total = Num.round(total);
        ItemStack stack = item.build(s -> s);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            // a different hidden name for every coin: the game never merges two coins by itself
            meta.setDisplayName(hidden(++counter));
            stack.setItemMeta(meta);
        }
        Item dropped = at.getWorld().dropItem(at.clone().add(0, 0.2, 0), stack);
        dropped.setPickupDelay(10);
        String shown = Placeholders.apply(name, owner, pairs(total));
        if (shown != null && !shown.isEmpty()) {
            dropped.setCustomName(Text.legacy(shown));
            dropped.setCustomNameVisible(true);
        }
        coins.put(dropped.getUniqueId(), new Coin(owner.getUniqueId(), total, System.currentTimeMillis() + despawnMillis, dropped));
    }

    private static String hidden(long id) {
        StringBuilder sb = new StringBuilder("§r");
        for (char c : Long.toHexString(id).toCharArray()) sb.append('§').append(c);
        return sb.toString();
    }

    private static String[] pairs(double amount) {
        String v = Num.plain(amount);
        return new String[]{"{amount}", v, "{amount_formatted}", Num.shortFormat(amount), "{amount_commas}", Num.commas(amount), "{mobcoins}", v};
    }

    // ------------------------------------------------------------------ pickup

    private void onPickup(Event event) {
        Item entity;
        Entity picker;
        try {
            entity = (Item) event.getClass().getMethod("getItem").invoke(event);
            try {
                picker = (Entity) event.getClass().getMethod("getEntity").invoke(event);
            } catch (NoSuchMethodException e) {
                picker = (Entity) event.getClass().getMethod("getPlayer").invoke(event);
            }
        } catch (Throwable t) {
            return;
        }
        Coin coin = coins.get(entity.getUniqueId());
        if (coin == null) return;
        ((Cancellable) event).setCancelled(true);
        if (!(picker instanceof Player)) return;
        Player p = (Player) picker;
        if (onlyKiller && !p.getUniqueId().equals(coin.owner)) return;
        if (coins.remove(entity.getUniqueId()) == null) return;
        entity.remove();
        Actions.run(p, pickupActions, new Actions.Context("drop coin", null, pairs(coin.amount)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopper(InventoryPickupItemEvent e) {
        if (coins.containsKey(e.getItem().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent e) {
        coins.remove(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (coins.isEmpty()) return;
        Chunk chunk = e.getChunk();
        for (Entity entity : chunk.getEntities()) {
            if (coins.remove(entity.getUniqueId()) != null) entity.remove();
        }
    }

    private void clean() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Coin>> it = coins.entrySet().iterator();
        while (it.hasNext()) {
            Coin c = it.next().getValue();
            if (now >= c.expires || !c.item.isValid()) {
                it.remove();
                if (c.item.isValid()) c.item.remove();
            }
        }
    }

    /** On reload / shutdown: coins on the ground disappear. */
    public void removeAll() {
        for (Coin c : new ArrayList<>(coins.values())) {
            try {
                c.item.remove();
            } catch (Throwable ignored) {
            }
        }
        coins.clear();
    }

    public void shutdown() {
        if (cleaner != null) cleaner.cancel();
        removeAll();
        HandlerList.unregisterAll(pickupListener);
    }

    private static final class Coin {
        final UUID owner;
        final double amount;
        final long expires;
        final Item item;

        Coin(UUID owner, double amount, long expires, Item item) {
            this.owner = owner;
            this.amount = amount;
            this.expires = expires;
            this.item = item;
        }
    }
}
