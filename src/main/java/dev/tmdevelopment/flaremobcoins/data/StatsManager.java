package dev.tmdevelopment.flaremobcoins.data;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Num;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Stats of the players (kills, mobcoins from mobs, this week / month), their boosters and the server event.
 * Kept in memory for online players; additions are written together every 30 seconds by the storage thread,
 * so a kill never waits for the database. With MySQL every server of the network adds to the same values.
 */
public final class StatsManager {

    public static final String KILLS = "kills";
    public static final String EARNED = "earned";
    public static final String GOLDEN = "golden";
    public static final String BEST_COMBO = "best-combo";
    public static final String SERVER = "*";

    private final FlareMobcoins plugin;
    private final CoinManager coins;
    private final Object lock = new Object();
    /** values read from the storage (online players and "*") */
    private final Map<String, Map<String, Double>> loaded = new ConcurrentHashMap<>();
    /** additions written while the values of a player were being read */
    private final Map<String, Map<String, Double>> loading = new ConcurrentHashMap<>();
    /** additions not written yet */
    private Map<String, Map<String, Double>> pending = new HashMap<>();
    /** the newest read of every player: an older one that comes back later is ignored (quick rejoin) */
    private final Map<String, Long> loadTokens = new ConcurrentHashMap<>();
    private long loadCounter;
    private volatile List<Storage.Account> weekly = Collections.emptyList();
    private volatile List<Storage.Account> monthly = Collections.emptyList();
    private volatile long topsTime;

    public StatsManager(FlareMobcoins plugin, CoinManager coins) {
        this.plugin = plugin;
        this.coins = coins;
    }

    // ------------------------------------------------------------------ periods

    /** "week:2026-W44" (weeks start on Monday). */
    public static String week() {
        return week(LocalDate.now());
    }

    public static String week(LocalDate d) {
        return "week:" + d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** "month:2026-10". */
    public static String month() {
        return month(LocalDate.now());
    }

    public static String month(LocalDate d) {
        return "month:" + d.getYear() + "-" + String.format("%02d", d.getMonthValue());
    }

    // ------------------------------------------------------------------ online players

    /** Reads the values of a player who joined; then runs done (main thread). */
    public void load(Player player, Runnable done) {
        String who = player.getUniqueId().toString();
        // what is already waiting is written before the read; what is written after it is kept in "loading"
        flush();
        Map<String, Double> during = new ConcurrentHashMap<>();
        loading.put(who, during);
        long token = ++loadCounter;
        loadTokens.put(who, token);
        coins.async(() -> {
            Map<String, Double> values = coins.storage().stats(who);
            coins.sync(() -> {
                if (!Long.valueOf(token).equals(loadTokens.get(who))) return;
                loadTokens.remove(who);
                loading.remove(who, during);
                if (Bukkit.getPlayer(player.getUniqueId()) == null) return;
                // the same map object: a failed write can still take its numbers back out of it
                for (Map.Entry<String, Double> e : values.entrySet()) during.merge(e.getKey(), e.getValue(), Double::sum);
                loaded.put(who, during);
                if (done != null) done.run();
            });
        });
    }

    /** A player left: his additions are written now (another server of the network reads them when he joins it). */
    public void unload(UUID id) {
        flush();
        loaded.remove(id.toString());
        loading.remove(id.toString());
        loadTokens.remove(id.toString());
    }

    public boolean isLoaded(UUID id) {
        return loaded.containsKey(id.toString());
    }

    /** A value of an online player (or "*"), with what is not written yet. */
    public double get(String who, String key) {
        Map<String, Double> m = loaded.get(who);
        double v = m == null ? 0 : m.getOrDefault(key, 0d);
        synchronized (lock) {
            Map<String, Double> p = pending.get(who);
            if (p != null) v += p.getOrDefault(key, 0d);
        }
        return v;
    }

    public double get(UUID id, String key) {
        return get(id.toString(), key);
    }

    /** Every value of an online player whose key starts with prefix. */
    public Map<String, Double> all(String who, String prefix) {
        Map<String, Double> out = new HashMap<>();
        Map<String, Double> m = loaded.get(who);
        if (m != null) for (Map.Entry<String, Double> e : m.entrySet()) if (e.getKey().startsWith(prefix)) out.put(e.getKey(), e.getValue());
        synchronized (lock) {
            Map<String, Double> p = pending.get(who);
            if (p != null) for (Map.Entry<String, Double> e : p.entrySet()) if (e.getKey().startsWith(prefix)) out.merge(e.getKey(), e.getValue(), Double::sum);
        }
        return out;
    }

    // ------------------------------------------------------------------ changes

    public void add(UUID id, String key, double amount) {
        if (amount == 0) return;
        String k = key.length() > 64 ? key.substring(0, 64) : key;
        synchronized (lock) {
            pending.computeIfAbsent(id.toString(), x -> new HashMap<>()).merge(k, amount, Double::sum);
        }
    }

    /** A mob killed by a player (mob = ZOMBIE, mythic:Name ...). */
    public void kill(UUID id, String mob, boolean golden) {
        add(id, KILLS, 1);
        add(id, KILLS + ":" + mob, 1);
        if (golden) add(id, GOLDEN, 1);
    }

    /** Mobcoins from a mob (or a coin on the ground): total, this week and this month. */
    public void earned(UUID id, double amount) {
        double a = Num.round(amount);
        if (a <= 0) return;
        add(id, EARNED, a);
        add(id, week(), a);
        add(id, month(), a);
    }

    /** Keeps the highest value (best combo): written as "the higher one", never added. */
    public void max(UUID id, String key, double value) {
        Map<String, Double> m = loaded.get(id.toString());
        if (m == null || m.getOrDefault(key, 0d) >= value) return;
        m.put(key, value);
        String who = id.toString();
        coins.async(() -> coins.storage().maxStat(who, key, value));
    }

    /** Removes an ended value (booster) only if nobody made it longer meanwhile. */
    public void expire(String who, String key, double until) {
        Map<String, Double> m = loaded.get(who);
        if (m != null) m.remove(key, until);
        coins.async(() -> coins.storage().deleteStatIfAtMost(who, key, until));
    }

    /** Sets a value right away (boosters, events); 0 removes it. */
    public void set(String who, String key, double value) {
        Map<String, Double> m = loaded.get(who);
        if (m != null) {
            if (value == 0) m.remove(key);
            else m.put(key, value);
        }
        coins.async(() -> coins.storage().setStat(who, key, value));
    }

    /**
     * Adds time to a value that is an end time (boosters): read from the storage first (another server may have
     * changed it), then written. done gets the new end time on the main thread.
     */
    public CompletableFuture<Long> extend(String who, String key, long millis) {
        CompletableFuture<Long> f = new CompletableFuture<>();
        coins.async(() -> {
            Double now = coins.storage().stats(who).get(key);
            long base = Math.max(now == null ? 0 : now.longValue(), System.currentTimeMillis());
            long until = base + millis;
            coins.storage().setStat(who, key, until);
            coins.sync(() -> {
                Map<String, Double> m = loaded.get(who);
                if (m != null) m.put(key, (double) until);
                f.complete(until);
            });
        }, f);
        return f;
    }

    /** Removes every key of a player that starts with prefix (all his boosters). done gets how many. */
    public void clear(String who, String prefix, Consumer<Integer> done) {
        coins.async(() -> {
            int n = 0;
            for (String key : coins.storage().stats(who).keySet()) {
                if (!key.startsWith(prefix)) continue;
                coins.storage().setStat(who, key, 0);
                n++;
            }
            int count = n;
            coins.sync(() -> {
                Map<String, Double> m = loaded.get(who);
                if (m != null) m.keySet().removeIf(k -> k.startsWith(prefix));
                if (done != null) done.accept(count);
            });
        });
    }

    // ------------------------------------------------------------------ storage

    /** Writes the additions now (on the storage thread, in order with everything else). */
    public void flush() {
        Map<String, Map<String, Double>> snapshot;
        synchronized (lock) {
            if (pending.isEmpty()) return;
            snapshot = pending;
            pending = new HashMap<>();
        }
        // shown right away; the map each player's numbers went into is kept to take them back if the write fails
        Map<String, Map<String, Double>> into = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> who : snapshot.entrySet()) {
            Map<String, Double> m = loaded.get(who.getKey());
            if (m == null) m = loading.get(who.getKey());
            if (m == null) continue;
            for (Map.Entry<String, Double> e : who.getValue().entrySet()) m.merge(e.getKey(), e.getValue(), Double::sum);
            into.put(who.getKey(), m);
        }
        CompletableFuture<Void> f = new CompletableFuture<>();
        coins.async(() -> {
            coins.storage().addStats(snapshot);
            f.complete(null);
        }, f);
        f.whenComplete((ok, error) -> {
            if (error == null) return;
            // not written: out of the shown numbers and back in the queue, written with the next ones
            for (Map.Entry<String, Map<String, Double>> who : snapshot.entrySet()) {
                Map<String, Double> m = into.get(who.getKey());
                if (m != null && (loaded.get(who.getKey()) == m || loading.get(who.getKey()) == m)) {
                    for (Map.Entry<String, Double> e : who.getValue().entrySet()) m.merge(e.getKey(), -e.getValue(), Double::sum);
                }
            }
            synchronized (lock) {
                for (Map.Entry<String, Map<String, Double>> who : snapshot.entrySet()) {
                    Map<String, Double> p = pending.computeIfAbsent(who.getKey(), x -> new HashMap<>());
                    for (Map.Entry<String, Double> e : who.getValue().entrySet()) p.merge(e.getKey(), e.getValue(), Double::sum);
                }
            }
        });
    }

    /** Every value of a player or "*" from the storage (after the additions are written). Main thread. */
    public void read(String who, Consumer<Map<String, Double>> done) {
        flush();
        coins.async(() -> {
            Map<String, Double> values = coins.storage().stats(who);
            coins.sync(() -> done.accept(values));
        });
    }

    public CompletableFuture<Map<String, Double>> fetch(UUID id) {
        CompletableFuture<Map<String, Double>> f = new CompletableFuture<>();
        if (isLoaded(id)) {
            Map<String, Double> out = all(id.toString(), "");
            f.complete(out);
            return f;
        }
        read(id.toString(), f::complete);
        return f;
    }

    /** Reloads "*" (the event, changed by another server of the network). */
    public void reloadServer(Runnable done) {
        coins.async(() -> {
            Map<String, Double> values = coins.storage().stats(SERVER);
            coins.sync(() -> {
                loaded.put(SERVER, new ConcurrentHashMap<>(values));
                if (done != null) done.run();
            });
        });
    }

    // ------------------------------------------------------------------ notes

    public static final String NOTES = "notes";

    /** A new note is written in the storage: only notes written here can be redeemed, and only once. */
    public void addNote(String id, double amount) {
        coins.async(() -> coins.storage().setStat(NOTES, id, amount));
    }

    /** Takes a note out of the storage. The future gets its value, -1 if it was already redeemed or never existed. */
    public CompletableFuture<Double> takeNote(String id) {
        CompletableFuture<Double> f = new CompletableFuture<>();
        coins.async(() -> {
            double v = coins.storage().takeStat(NOTES, id);
            coins.sync(() -> f.complete(v));
        }, f);
        return f;
    }

    // ------------------------------------------------------------------ weekly / monthly top

    public List<Storage.Account> weekly() {
        return weekly;
    }

    public List<Storage.Account> monthly() {
        return monthly;
    }

    /** Reads the weekly and monthly top again; then runs done on the main thread (can be null). */
    public void refreshTops(int size, Runnable done) {
        // at most every 5 seconds (the menu buttons and /mobcoins top can be clicked many times)
        if (System.currentTimeMillis() - topsTime < 5000) {
            if (done != null) done.run();
            return;
        }
        topsTime = System.currentTimeMillis();
        flush();
        String w = week(), m = month();
        coins.async(() -> {
            try {
                weekly = Collections.unmodifiableList(new ArrayList<>(coins.storage().topStat(w, size)));
                monthly = Collections.unmodifiableList(new ArrayList<>(coins.storage().topStat(m, size)));
            } finally {
                if (done != null) coins.sync(done);
            }
        });
    }

    /** Top of a past (or current) period: on the storage thread, done on the main thread. */
    public void top(String key, int size, Consumer<List<Storage.Account>> done) {
        flush();
        coins.async(() -> {
            List<Storage.Account> list = coins.storage().topStat(key, size);
            coins.sync(() -> done.accept(list));
        });
    }

    /** Keeps only the current and the last period of weeks and months. */
    public void cleanOld() {
        LocalDate now = LocalDate.now();
        List<String> weeks = new ArrayList<>();
        weeks.add(week(now));
        weeks.add(week(now.minusWeeks(1)));
        List<String> months = new ArrayList<>();
        months.add(month(now));
        months.add(month(now.minusMonths(1)));
        coins.async(() -> {
            coins.storage().deleteStats("week:", weeks);
            coins.storage().deleteStats("month:", months);
        });
    }
}
