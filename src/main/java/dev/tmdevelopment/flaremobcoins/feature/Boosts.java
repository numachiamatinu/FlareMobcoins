package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.data.StatsManager;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Tags;
import dev.tmdevelopment.flaremobcoins.util.Text;
import dev.tmdevelopment.flaremobcoins.util.Time;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Timed boosters of the players (/mobcoins booster, also as items) and the event of the whole server
 * (/mobcoins event). Both multiply the mob drops. The end times are saved in the storage, so they survive
 * restarts and, with MySQL, work on every server of the network. Boosters count real time (also offline).
 */
public final class Boosts implements Listener {

    static final String KEY = "booster:";
    private static final String EVENT_MULT = "event:multiplier";
    private static final String EVENT_UNTIL = "event:until";
    private static final String EVENT_LENGTH = "event:length";
    private static final String ITEM_TAG = "booster";

    private final FlareMobcoins plugin;
    private final Map<UUID, Object> bars = new HashMap<>();
    private final Map<UUID, long[]> barState = new HashMap<>();
    private BukkitTask ticker;
    private double eventMult = 1;
    private long eventUntil;
    private long eventLength;
    private boolean eventShown;
    private Object eventBar;

    public Boosts(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public void load() {
        if (ticker != null) ticker.cancel();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        // bars are made again with the new colors / texts
        removeBars();
        if (eventBar != null) {
            Bars.removeAll(eventBar);
            eventBar = null;
        }
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        removeBars();
        if (eventBar != null) Bars.removeAll(eventBar);
        eventBar = null;
    }

    private void removeBars() {
        for (Object bar : bars.values()) Bars.removeAll(bar);
        bars.clear();
        barState.clear();
    }

    // ------------------------------------------------------------------ multipliers

    /** Booster x event of a player (1 = none). */
    public double multiplier(Player p) {
        return personal(p.getUniqueId()) * event();
    }

    /** Highest active booster of an online player, 1 if none. */
    public double personal(UUID id) {
        double[] best = best(id);
        return best == null ? 1 : best[0];
    }

    /** {multiplier, end time} of the highest active booster, null if none. */
    public double[] best(UUID id) {
        long now = System.currentTimeMillis();
        double[] best = null;
        for (Map.Entry<String, Double> e : plugin.stats().all(id.toString(), KEY).entrySet()) {
            if (e.getValue() <= now) continue;
            double m = multiplierOf(e.getKey());
            if (best == null || m > best[0]) best = new double[]{m, e.getValue()};
        }
        return best;
    }

    /** Every active booster {multiplier, end time}, highest first. */
    public List<double[]> active(Map<String, Double> values) {
        long now = System.currentTimeMillis();
        List<double[]> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : values.entrySet()) {
            if (e.getKey().startsWith(KEY) && e.getValue() > now) out.add(new double[]{multiplierOf(e.getKey()), e.getValue()});
        }
        out.sort((a, b) -> Double.compare(b[0], a[0]));
        return out;
    }

    public double event() {
        return eventActive() ? eventMult : 1;
    }

    public boolean eventActive() {
        return System.currentTimeMillis() < eventUntil;
    }

    public long eventLeft() {
        return Math.max(0, eventUntil - System.currentTimeMillis());
    }

    public double eventMultiplier() {
        return eventMult;
    }

    static String key(double multiplier) {
        return KEY + Math.round(multiplier * 100);
    }

    static double multiplierOf(String key) {
        return Num.parseInt(key.substring(KEY.length()), 100) / 100.0;
    }

    // ------------------------------------------------------------------ changes

    /** Starts (or makes longer) a booster. The future gets the end time on the main thread. */
    public java.util.concurrent.CompletableFuture<Long> give(UUID id, double multiplier, long millis) {
        return plugin.stats().extend(id.toString(), key(multiplier), millis);
    }

    public void remove(UUID id, java.util.function.Consumer<Integer> done) {
        plugin.stats().clear(id.toString(), KEY, done);
    }

    public void startEvent(double multiplier, long millis) {
        long now = System.currentTimeMillis();
        eventMult = multiplier;
        eventUntil = now + millis;
        eventLength = millis;
        plugin.stats().set(StatsManager.SERVER, EVENT_MULT, multiplier);
        plugin.stats().set(StatsManager.SERVER, EVENT_UNTIL, eventUntil);
        plugin.stats().set(StatsManager.SERVER, EVENT_LENGTH, millis);
        eventShown = false;
        showEvent();
    }

    public void stopEvent() {
        eventUntil = System.currentTimeMillis();
        clearEvent();
        endEvent();
    }

    private void clearEvent() {
        plugin.stats().set(StatsManager.SERVER, EVENT_MULT, 0);
        plugin.stats().set(StatsManager.SERVER, EVENT_UNTIL, 0);
        plugin.stats().set(StatsManager.SERVER, EVENT_LENGTH, 0);
    }

    /** The event saved in the storage (on start, and every few seconds with MySQL: started on another server). */
    public void syncEvent() {
        plugin.stats().reloadServer(() -> {
            double m = plugin.stats().get(StatsManager.SERVER, EVENT_MULT);
            long until = (long) plugin.stats().get(StatsManager.SERVER, EVENT_UNTIL);
            long length = (long) plugin.stats().get(StatsManager.SERVER, EVENT_LENGTH);
            if (m > 0 && until > System.currentTimeMillis()) {
                boolean changed = until != eventUntil || m != eventMult;
                eventMult = m;
                eventUntil = until;
                eventLength = length > 0 ? length : until - System.currentTimeMillis();
                if (changed && eventShown) {
                    // a new event over the old one: same bar, new text
                    updateEventBar();
                } else if (!eventShown) {
                    showEvent();
                }
            } else if (eventShown) {
                eventUntil = Math.min(eventUntil, System.currentTimeMillis());
                endEvent();
            }
        });
    }

    private String[] eventPairs() {
        return new String[]{"{multiplier}", Num.plain(eventMult), "{time}", Time.format(eventLeft()), "{length}", Time.format(eventLength)};
    }

    private void showEvent() {
        if (eventShown) return;
        eventShown = true;
        for (String line : plugin.lang().lines("event.started")) Lang.broadcast(Placeholders.apply(line, null, eventPairs()));
        for (Player p : Bukkit.getOnlinePlayers()) Actions.sound(p, "sounds.event");
        updateEventBar();
    }

    private void endEvent() {
        if (!eventShown) return;
        eventShown = false;
        for (String line : plugin.lang().lines("event.ended")) Lang.broadcast(Placeholders.apply(line, null, "{multiplier}", Num.plain(eventMult)));
        if (eventBar != null) Bars.removeAll(eventBar);
        eventBar = null;
    }

    private void updateEventBar() {
        if (!cfg().getBoolean("events.bossbar.enabled", true)) return;
        String title = Text.legacy(Placeholders.apply(plugin.lang().raw("event.bossbar"), null, eventPairs()));
        if (eventBar == null) {
            eventBar = Bars.create(title, cfg().getString("events.bossbar.color", "YELLOW"), cfg().getString("events.bossbar.style", "SOLID"));
            for (Player p : Bukkit.getOnlinePlayers()) Bars.add(eventBar, p);
        } else {
            Bars.title(eventBar, title);
        }
        Bars.progress(eventBar, eventLength <= 0 ? 1 : (double) eventLeft() / eventLength);
    }

    // ------------------------------------------------------------------ every second

    private void tick() {
        if (eventShown) {
            if (!eventActive()) {
                // the saved end time already says it is over: nothing is deleted (another server may have a newer one)
                endEvent();
            } else {
                updateEventBar();
            }
        }
        boolean bossbar = cfg().getBoolean("boosters.bossbar.enabled", true);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!plugin.stats().isLoaded(id)) continue;
            Map<String, Double> mine = plugin.stats().all(id.toString(), KEY);
            if (mine.isEmpty()) {
                hideBar(id);
                continue;
            }
            for (Map.Entry<String, Double> e : mine.entrySet()) {
                if (e.getValue() > now) continue;
                // ended: removed from the storage (only if nobody made it longer meanwhile)
                plugin.stats().expire(id.toString(), e.getKey(), e.getValue());
                // the player is told when it just ended, not when he joins after it ended offline
                if (now - e.getValue() < 5000) plugin.lang().send(p, "booster.ended", "{multiplier}", Num.plain(multiplierOf(e.getKey())));
            }
            double[] best = best(id);
            if (best == null || !bossbar) {
                hideBar(id);
                continue;
            }
            showBar(p, best);
        }
    }

    private void showBar(Player p, double[] best) {
        UUID id = p.getUniqueId();
        long left = (long) best[1] - System.currentTimeMillis();
        long[] state = barState.get(id);
        // the bar starts full: its length is the time left when it appeared (or when a new booster started)
        if (state == null || state[0] != (long) best[1]) {
            state = new long[]{(long) best[1], Math.max(1, left)};
            barState.put(id, state);
        }
        String title = Text.legacy(Placeholders.apply(plugin.lang().raw("booster.bossbar"), p,
                "{multiplier}", Num.plain(best[0]), "{time}", Time.format(left)));
        Object bar = bars.get(id);
        if (bar == null) {
            bar = Bars.create(title, cfg().getString("boosters.bossbar.color", "GREEN"), cfg().getString("boosters.bossbar.style", "SOLID"));
            if (bar == null) return;
            Bars.add(bar, p);
            bars.put(id, bar);
        } else {
            Bars.title(bar, title);
        }
        Bars.progress(bar, (double) left / state[1]);
    }

    private void hideBar(UUID id) {
        Object bar = bars.remove(id);
        barState.remove(id);
        if (bar != null) Bars.removeAll(bar);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (eventBar != null) Bars.add(eventBar, e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hideBar(e.getPlayer().getUniqueId());
        if (eventBar != null) Bars.remove(eventBar, e.getPlayer());
    }

    // ------------------------------------------------------------------ booster items

    public ItemStack item(double multiplier, long millis, int amount) {
        ItemSpec spec = ItemSpec.of(cfg().getConfigurationSection("boosters.item"), "config.yml > boosters.item");
        ItemStack item = spec.buildReal(s -> Placeholders.apply(s, null, "{multiplier}", Num.plain(multiplier), "{time}", Time.format(millis)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Tags.set(plugin, meta, ITEM_TAG, Num.plain(multiplier) + ";" + millis);
            item.setItemMeta(meta);
        }
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return item;
    }

    /** {multiplier, millis} of a booster item, null if the item is not one. */
    double[] readItem(ItemStack item) {
        String v = Tags.get(plugin, item, ITEM_TAG);
        if (v == null) return null;
        String[] parts = v.split(";");
        if (parts.length != 2) return null;
        double m = Num.parseDouble(parts[0], -1);
        long ms;
        try {
            ms = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return m > 0 && ms > 0 ? new double[]{m, ms} : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!Tags.mainHand(e)) return;
        Player p = e.getPlayer();
        ItemStack hand = Compat.hand(p);
        double[] booster = readItem(hand);
        if (booster == null) return;
        e.setCancelled(true);
        if (!p.hasPermission("flaremobcoins.booster.use")) {
            plugin.lang().send(p, "general.no-permission");
            return;
        }
        // creative players can copy any item
        if ("CREATIVE".equals(String.valueOf(p.getGameMode())) && !p.hasPermission("flaremobcoins.redeem.creative")) {
            plugin.lang().send(p, "booster.creative");
            return;
        }
        if (!plugin.stats().isLoaded(p.getUniqueId())) return;
        double[] best = best(p.getUniqueId());
        if (best != null && best[0] > booster[0]) {
            plugin.lang().send(p, "booster.stronger", "{multiplier}", Num.plain(best[0]),
                    "{time}", Time.format((long) best[1] - System.currentTimeMillis()));
            Actions.sound(p, "sounds.error");
            return;
        }
        // the item goes first: a second click can't use it twice
        ItemStack one = hand.clone();
        one.setAmount(1);
        if (hand.getAmount() <= 1) Compat.setHand(p, null);
        else {
            hand.setAmount(hand.getAmount() - 1);
            Compat.setHand(p, hand);
        }
        give(p.getUniqueId(), booster[0], (long) booster[1]).whenComplete((until, error) -> {
            if (!p.isOnline()) return;
            if (error != null) {
                // not saved: the item comes back
                for (ItemStack rest : p.getInventory().addItem(one).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
                plugin.lang().send(p, "shop.error");
                return;
            }
            plugin.lang().send(p, "booster.activated", "{multiplier}", Num.plain(booster[0]),
                    "{time}", Time.format(until - System.currentTimeMillis()));
            Actions.sound(p, "sounds.receive");
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (readItem(e.getItemInHand()) != null) e.setCancelled(true);
    }
}
