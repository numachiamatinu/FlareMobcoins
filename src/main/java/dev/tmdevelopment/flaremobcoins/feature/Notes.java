package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Tags;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/**
 * Mobcoin notes (/mobcoins withdraw): mobcoins become an item players can trade, right click to get them back.
 * Every note has its own id, written in the item (item data on 1.14+, an invisible lore line before) and in the
 * storage: a note can be redeemed once, so copied or made-up notes give nothing (also on every server of a MySQL
 * network). Notes can always be redeemed, also when withdraw is turned off later.
 */
public final class Notes implements Listener {

    private static final String TAG = "note";
    private final FlareMobcoins plugin;

    public Notes(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("withdraw.enabled", false);
    }

    public double min() {
        return Math.max(0.01, plugin.getConfig().getDouble("withdraw.min-amount", 10));
    }

    /** Makes the note and writes it in the storage. */
    public ItemStack create(Player owner, double amount) {
        ItemSpec spec = ItemSpec.of(plugin.getConfig().getConfigurationSection("withdraw.item"), "config.yml > withdraw.item");
        double a = Num.round(amount);
        ItemStack item = spec.buildReal(s -> Placeholders.apply(s, owner, "{amount}", Num.plain(a),
                "{amount_commas}", Num.commas(a), "{amount_formatted}", Num.shortFormat(a)));
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        if (meta != null) {
            // id and cents (no decimals to lose)
            Tags.set(plugin, meta, TAG, id + ":" + Math.round(a * 100));
            item.setItemMeta(meta);
        }
        plugin.stats().addNote(id, a);
        return item;
    }

    /** {id, value} of a note, null if the item is not a note. */
    String[] read(ItemStack item) {
        String v = Tags.get(plugin, item, TAG);
        if (v == null) return null;
        int c = v.indexOf(':');
        if (c <= 0) return null;
        try {
            double value = Num.round(Long.parseLong(v.substring(c + 1).trim()) / 100.0);
            return value > 0 ? new String[]{v.substring(0, c), Num.plain(value)} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean isNote(ItemStack item) {
        return read(item) != null;
    }

    /** Gives the note, or drops it at the feet when the inventory is full. */
    public void hand(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!Tags.mainHand(e)) return;
        Player p = e.getPlayer();
        ItemStack hand = Compat.hand(p);
        String[] note = read(hand);
        if (note == null) return;
        e.setCancelled(true);
        if (!p.hasPermission("flaremobcoins.redeem")) {
            plugin.lang().send(p, "general.no-permission");
            return;
        }
        // creative players can copy any item: no free mobcoins
        if ("CREATIVE".equals(String.valueOf(p.getGameMode())) && !p.hasPermission("flaremobcoins.redeem.creative")) {
            plugin.lang().send(p, "withdraw.creative");
            return;
        }
        if (!plugin.coins().isLoaded(p.getUniqueId())) return;
        double value = Num.parseDouble(note[1], 0);
        // the item goes first; the mobcoins come when the storage says the note is real and not used yet
        ItemStack one = hand.clone();
        one.setAmount(1);
        if (hand.getAmount() <= 1) Compat.setHand(p, null);
        else {
            hand.setAmount(hand.getAmount() - 1);
            Compat.setHand(p, hand);
        }
        plugin.stats().takeNote(note[0]).whenComplete((stored, error) -> {
            if (error != null) {
                // storage error: the note is still valid, it comes back
                if (p.isOnline()) {
                    for (ItemStack rest : p.getInventory().addItem(one).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
                    plugin.lang().send(p, "shop.error");
                }
                return;
            }
            if (stored == null || stored < 0 || Math.abs(stored - value) > 0.001) {
                plugin.getLogger().warning(p.getName() + " used a note that was already redeemed or not made by the plugin (" + note[0] + ", "
                        + note[1] + " mobcoins): it was removed.");
                if (p.isOnline()) plugin.lang().send(p, "withdraw.invalid");
                return;
            }
            // the player may have left meanwhile: the mobcoins go to his account anyway
            plugin.coins().give(p.getUniqueId(), p.getName(), value, "note");
            if (!p.isOnline()) return;
            plugin.lang().send(p, "withdraw.redeemed", "{amount}", Num.plain(value), "{amount_commas}", Num.commas(value),
                    "{amount_formatted}", Num.shortFormat(value), "{count}", "1");
            Actions.sound(p, "sounds.receive");
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isNote(e.getItemInHand())) e.setCancelled(true);
    }
}
