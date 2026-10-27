package dev.tmdevelopment.flaremobcoins.data;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.api.MobcoinsChangeEvent;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Num;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Balances of online players are kept in memory: reading never waits for the database.
 * Every change is applied in memory right away and written by one storage thread, in order.
 */
public final class CoinManager {

    /** Returned when another plugin cancelled the change. */
    public static final double CANCELLED = -2;

    private final FlareMobcoins plugin;
    private final Storage storage;
    private final StateFile state;
    private final ExecutorService db;
    private final Object lock = new Object();
    private final Map<UUID, Double> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Double> multipliers = new ConcurrentHashMap<>();
    /** Bumped on every local change: a value read from the database before the change is ignored. */
    private final Map<UUID, Integer> versions = new ConcurrentHashMap<>();
    private volatile List<Storage.Account> top = Collections.emptyList();
    private final AtomicBoolean topPending = new AtomicBoolean();
    private volatile long topTime;
    private volatile int accounts;
    private volatile double startBalance;
    private volatile boolean logTransactions;
    private final File logFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public CoinManager(FlareMobcoins plugin, Storage storage, StateFile state) {
        this.plugin = plugin;
        this.storage = storage;
        this.state = state;
        this.db = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FlareMobcoins-Storage");
            t.setDaemon(true);
            return t;
        });
        this.logFile = new File(plugin.getDataFolder(), "logs/transactions.log");
        reloadSettings();
    }

    public void reloadSettings() {
        startBalance = Num.round(Math.max(0, plugin.getConfig().getDouble("first-join-mobcoins", 0)));
        logTransactions = plugin.getConfig().getBoolean("logs.transactions", true);
    }

    public Storage storage() {
        return storage;
    }

    public void open() throws Exception {
        Future<Object> f = db.submit(() -> {
            storage.open();
            accounts = storage.count();
            return null;
        });
        try {
            f.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        refreshTop(true);
    }

    public void shutdown() {
        db.execute(() -> {
            try {
                storage.flush();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not save the balances", t);
            }
            storage.close();
        });
        db.shutdown();
        try {
            if (!db.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("The storage took too long to close.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ online players

    public void load(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        int version;
        synchronized (lock) {
            bump(id);
            version = versions.get(id);
        }
        async(() -> {
            boolean created = storage.create(id, name, startBalance);
            Storage.Account acc = storage.get(id);
            if (created) accounts++;
            double coins = acc == null ? 0 : acc.mobcoins;
            double mult = acc == null ? 1 : acc.multiplier;
            if (created) log("CREATE", name, startBalance, coins, "first join");
            sync(() -> {
                Player online = Bukkit.getPlayer(id);
                if (online == null) return;
                multipliers.put(id, mult);
                if (!apply(id, version, coins)) reload(id, 3);
                if (created && startBalance > 0) plugin.onFirstJoin(online, startBalance);
            });
        });
    }

    private boolean apply(UUID id, int version, double coins) {
        synchronized (lock) {
            Integer now = versions.get(id);
            if (now == null) return true;
            if (now != version) return false;
            cache.put(id, coins);
            return true;
        }
    }

    private void reload(UUID id, int tries) {
        Integer v = versions.get(id);
        if (v == null) return;
        int version = v;
        async(() -> {
            Storage.Account acc = storage.get(id);
            sync(() -> {
                if (!apply(id, version, acc == null ? 0 : acc.mobcoins) && tries > 0) reload(id, tries - 1);
            });
        });
    }

    public void unload(UUID id) {
        synchronized (lock) {
            cache.remove(id);
            versions.remove(id);
            multipliers.remove(id);
        }
    }

    public boolean isLoaded(UUID id) {
        return cache.containsKey(id);
    }

    // ------------------------------------------------------------------ reading

    /** Balance in memory (online players; every player with yaml). 0 if unknown. */
    public double balance(UUID id) {
        Double v = cache.get(id);
        if (v != null) return v;
        if (storage instanceof YamlStorage) {
            Double y = ((YamlStorage) storage).balance(id);
            return y == null ? 0 : y;
        }
        return 0;
    }

    /** Waits (max 3 seconds) for an offline balance: only for old API calls. */
    public double balanceNow(UUID id) {
        Double v = cache.get(id);
        if (v != null) return v;
        if (storage instanceof YamlStorage) return balance(id);
        try {
            Storage.Account a = db.submit(() -> storage.get(id)).get(3, TimeUnit.SECONDS);
            return a == null ? 0 : a.mobcoins;
        } catch (Exception e) {
            return 0;
        }
    }

    public CompletableFuture<Double> fetch(UUID id) {
        Double v = cache.get(id);
        if (v != null) return CompletableFuture.completedFuture(v);
        CompletableFuture<Double> f = new CompletableFuture<>();
        async(() -> {
            Storage.Account a = storage.get(id);
            sync(() -> f.complete(a == null ? 0 : a.mobcoins));
        }, f);
        return f;
    }

    /** Player multiplier (1 = none). Online players and yaml only, 1 otherwise. */
    public double multiplier(UUID id) {
        Double m = multipliers.get(id);
        if (m != null) return m;
        if (storage instanceof YamlStorage) {
            Double y = ((YamlStorage) storage).multiplier(id);
            return y == null ? 1 : y;
        }
        return 1;
    }

    public CompletableFuture<Double> fetchMultiplier(UUID id) {
        Double m = multipliers.get(id);
        if (m != null) return CompletableFuture.completedFuture(m);
        CompletableFuture<Double> f = new CompletableFuture<>();
        async(() -> {
            Storage.Account a = storage.get(id);
            sync(() -> f.complete(a == null ? 1 : a.multiplier));
        }, f);
        return f;
    }

    public CompletableFuture<Double> setMultiplier(UUID id, String name, double value) {
        double v = value <= 0 ? 1 : value;
        if (multipliers.containsKey(id) || Bukkit.getPlayer(id) != null) multipliers.put(id, v);
        CompletableFuture<Double> f = new CompletableFuture<>();
        async(() -> {
            storage.setMultiplier(id, name, v, startBalance);
            sync(() -> f.complete(v));
        }, f);
        return f;
    }

    public double globalMultiplier() {
        double g = state.yaml().getDouble("global-multiplier", 1);
        return g <= 0 ? 1 : g;
    }

    public void setGlobalMultiplier(double value) {
        state.yaml().set("global-multiplier", value <= 0 ? 1 : value);
        state.save();
    }

    // ------------------------------------------------------------------ changes

    public CompletableFuture<Double> give(UUID id, String name, double amount, String cause) {
        CompletableFuture<Double> f = new CompletableFuture<>();
        double add = Num.round(amount);
        if (add <= 0) {
            f.complete(balance(id));
            return f;
        }
        synchronized (lock) {
            Double now = cache.get(id);
            if (now != null) {
                double after = Num.round(Math.min(Num.MAX, now + add));
                if (!event(id, now, after, MobcoinsChangeEvent.Type.GIVE, cause)) {
                    f.complete(CANCELLED);
                    return f;
                }
                cache.put(id, after);
                bump(id);
                f.complete(after);
                async(() -> storage.add(id, name, add, startBalance));
                log("GIVE", name, add, after, cause);
                return f;
            }
            bump(id);
        }
        async(() -> {
            if (storage.create(id, name, startBalance)) accounts++;
            double after = storage.add(id, name, add, startBalance);
            log("GIVE", name, add, after, cause);
            sync(() -> f.complete(after));
        }, f);
        return f;
    }

    /**
     * Removes mobcoins. strict = only if he has enough (result -1 otherwise), not strict = down to 0.
     */
    public CompletableFuture<Double> take(UUID id, String name, double amount, boolean strict, String cause) {
        CompletableFuture<Double> f = new CompletableFuture<>();
        double sub = Num.round(amount);
        if (sub <= 0) {
            f.complete(balance(id));
            return f;
        }
        synchronized (lock) {
            Double now = cache.get(id);
            if (now != null) {
                if (strict && now + 0.0001 < sub) {
                    f.complete(-1d);
                    return f;
                }
                double after = Num.round(Math.max(0, now - sub));
                if (!event(id, now, after, MobcoinsChangeEvent.Type.TAKE, cause)) {
                    f.complete(strict ? -1d : CANCELLED);
                    return f;
                }
                cache.put(id, after);
                bump(id);
                f.complete(after);
                double removed = Num.round(now - after);
                async(() -> {
                    double result = strict ? storage.take(id, removed) : storage.add(id, name, -removed, startBalance);
                    if (result < 0) {
                        plugin.getLogger().warning("The balance of " + name + " was lower in the database (changed by another server?), reloaded.");
                        sync(() -> reload(id, 3));
                    }
                });
                log("TAKE", name, sub, after, cause);
                return f;
            }
            bump(id);
        }
        async(() -> {
            double after;
            if (strict) {
                after = storage.take(id, sub);
            } else {
                storage.create(id, name, startBalance);
                after = storage.add(id, name, -sub, startBalance);
            }
            if (after >= 0) log("TAKE", name, sub, after, cause);
            double result = after;
            sync(() -> f.complete(result));
        }, f);
        return f;
    }

    public CompletableFuture<Double> set(UUID id, String name, double value, String cause) {
        CompletableFuture<Double> f = new CompletableFuture<>();
        double v = Num.round(Math.max(0, Math.min(Num.MAX, value)));
        synchronized (lock) {
            Double now = cache.get(id);
            if (now != null) {
                if (!event(id, now, v, MobcoinsChangeEvent.Type.SET, cause)) {
                    f.complete(CANCELLED);
                    return f;
                }
                cache.put(id, v);
                bump(id);
                f.complete(v);
                async(() -> storage.set(id, name, v));
                log("SET", name, v, v, cause);
                return f;
            }
            bump(id);
        }
        async(() -> {
            if (storage.get(id) == null) accounts++;
            storage.set(id, name, v);
            log("SET", name, v, v, cause);
            sync(() -> f.complete(v));
        }, f);
        return f;
    }

    /** Removes mobcoins from an online player right now. @return false if he has less (nothing removed). */
    public boolean spend(Player player, double amount, String cause) {
        double a = Num.round(amount);
        if (a <= 0) return true;
        synchronized (lock) {
            Double now = cache.get(player.getUniqueId());
            if (now == null || now + 0.0001 < a) return false;
            Double result = take(player.getUniqueId(), player.getName(), a, true, cause).getNow(-1d);
            return result != null && result >= 0;
        }
    }

    private boolean event(UUID id, double before, double after, MobcoinsChangeEvent.Type type, String cause) {
        if (!Bukkit.isPrimaryThread()) return true;
        MobcoinsChangeEvent e = new MobcoinsChangeEvent(id, before, after, type, cause);
        try {
            Bukkit.getPluginManager().callEvent(e);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "A MobcoinsChangeEvent listener failed", t);
        }
        return !e.isCancelled();
    }

    private void bump(UUID id) {
        if (versions.containsKey(id) || Bukkit.getPlayer(id) != null) versions.merge(id, 1, Integer::sum);
    }

    // ------------------------------------------------------------------ lookups

    /** Account by name: online, known by the server, or saved in the storage. null = never joined. */
    public CompletableFuture<Storage.Account> find(String name) {
        CompletableFuture<Storage.Account> f = new CompletableFuture<>();
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            f.complete(new Storage.Account(online.getUniqueId(), online.getName(), balance(online.getUniqueId()),
                    multiplier(online.getUniqueId())));
            return f;
        }
        OfflinePlayer known = Compat.knownPlayer(name);
        if (known != null) {
            f.complete(new Storage.Account(known.getUniqueId(), known.getName() == null ? name : known.getName(),
                    balance(known.getUniqueId()), multiplier(known.getUniqueId())));
            return f;
        }
        async(() -> {
            Storage.Account a = storage.byName(name);
            sync(() -> f.complete(a));
        }, f);
        return f;
    }

    public List<Storage.Account> top() {
        return top;
    }

    public int accounts() {
        return Math.max(accounts, cache.size());
    }

    public void refreshTop() {
        refreshTop(false);
    }

    public void refreshTop(boolean force) {
        if (!force && System.currentTimeMillis() - topTime < 5000) return;
        if (!topPending.compareAndSet(false, true)) return;
        topTime = System.currentTimeMillis();
        int size = Math.max(10, plugin.getConfig().getInt("top.size", 10));
        async(() -> {
            try {
                try {
                    storage.flush();
                } catch (Throwable ignored) {
                }
                List<Storage.Account> list = storage.top(size);
                accounts = storage.count();
                top = Collections.unmodifiableList(new ArrayList<>(list));
            } finally {
                topPending.set(false);
            }
        });
    }

    /** MySQL: reads again the balances of online players (changes made by other servers). */
    public void refreshOnline() {
        if (cache.isEmpty()) return;
        Map<UUID, Integer> snapshot = new HashMap<>(versions);
        List<UUID> ids = new ArrayList<>(cache.keySet());
        async(() -> {
            Map<UUID, Storage.Account> found = new HashMap<>();
            for (UUID id : ids) {
                Storage.Account a = storage.get(id);
                if (a != null) found.put(id, a);
            }
            sync(() -> {
                for (Map.Entry<UUID, Storage.Account> e : found.entrySet()) {
                    Integer v = snapshot.get(e.getKey());
                    if (v != null && cache.containsKey(e.getKey())) {
                        apply(e.getKey(), v, e.getValue().mobcoins);
                        multipliers.put(e.getKey(), e.getValue().multiplier);
                    }
                }
            });
        });
    }

    // ------------------------------------------------------------------ shop limits and stock

    public CompletableFuture<Integer> bought(String who, String item, String period) {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        async(() -> {
            int n = storage.bought(who, item, period);
            sync(() -> f.complete(n));
        }, f);
        return f;
    }

    public CompletableFuture<Boolean> reserve(String who, String item, String period, int max) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        async(() -> {
            boolean ok = storage.reserve(who, item, period, max);
            sync(() -> f.complete(ok));
        }, f);
        return f;
    }

    public void release(String who, String item, String period) {
        async(() -> storage.release(who, item, period));
    }

    public void flushAsync() {
        async(storage::flush);
    }

    /** Copies every account to another storage. done gets the number of accounts or "error: ...". */
    public void migrate(Storage target, Consumer<String> done) {
        async(() -> {
            storage.flush();
            List<Storage.Account> all = storage.all();
            target.open();
            int n = 0;
            for (Storage.Account a : all) {
                target.set(a.uuid, a.name, a.mobcoins);
                if (a.multiplier != 1) target.setMultiplier(a.uuid, a.name, a.multiplier, 0);
                n++;
            }
            target.flush();
            target.close();
            int count = n;
            sync(() -> done.accept(String.valueOf(count)));
        }, t -> sync(() -> done.accept("error: " + t.getMessage())));
    }

    // ------------------------------------------------------------------ threads

    private interface Work {
        void run() throws Exception;
    }

    private void async(Work work) {
        async(work, (Consumer<Throwable>) null);
    }

    private void async(Work work, CompletableFuture<?> f) {
        async(work, t -> sync(() -> f.completeExceptionally(t)));
    }

    private void async(Work work, Consumer<Throwable> onError) {
        try {
            db.execute(() -> {
                try {
                    work.run();
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Storage error (" + storage.type() + "): " + t.getMessage(), t);
                    if (onError != null) onError.accept(t);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void sync(Runnable r) {
        if (plugin.isEnabled() && !Bukkit.isPrimaryThread()) {
            try {
                Bukkit.getScheduler().runTask(plugin, r);
            } catch (Throwable t) {
                r.run();
            }
        } else {
            r.run();
        }
    }

    private void log(String what, String name, double amount, double after, String cause) {
        if (!logTransactions || cause != null && cause.startsWith("drop")) return;
        long time = System.currentTimeMillis();
        try {
            db.execute(() -> {
                String line = "[" + dateFormat.format(new Date(time)) + "] " + what + " " + (name == null ? "?" : name)
                        + " " + Num.plain(amount) + " -> " + Num.plain(after) + (cause == null ? "" : " (" + cause + ")") + "\n";
                try {
                    if (!logFile.getParentFile().exists()) logFile.getParentFile().mkdirs();
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                        w.write(line);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }
}
