package dev.tmdevelopment.flaremobcoins.data;

import dev.tmdevelopment.flaremobcoins.util.Num;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** data/data.yml, same layout as 3.x: account.&lt;uuid&gt;.mobcoins / .multiplier (+ .name). */
public final class YamlStorage implements Storage {

    private final File file;
    /** {mobcoins, multiplier} */
    private final Map<UUID, double[]> accounts = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private YamlConfiguration yaml;
    /** "who|item|period" -> times bought (shop limits and stock) */
    private final Map<String, Integer> purchases = new ConcurrentHashMap<>();
    /** who -> key -> value (stats, boosters, events) */
    private final Map<String, Map<String, Double>> stats = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public YamlStorage(File file) {
        this.file = file;
    }

    @Override
    public String type() {
        return "yaml";
    }

    @Override
    public void open() throws Exception {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new IllegalStateException("can't create " + file.getParent());
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        for (Object o : yaml.getStringList("purchases")) {
            String line = String.valueOf(o);
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                purchases.put(line.substring(0, eq), Integer.parseInt(line.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        // "who|key=value" lines
        for (Object o : yaml.getStringList("stats")) {
            String line = String.valueOf(o);
            int bar = line.indexOf('|');
            int eq = line.lastIndexOf('=');
            if (bar <= 0 || eq <= bar + 1) continue;
            try {
                stats.computeIfAbsent(line.substring(0, bar), k -> new ConcurrentHashMap<>())
                        .put(line.substring(bar + 1, eq), Double.parseDouble(line.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        ConfigurationSection sec = yaml.getConfigurationSection("account");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            double mult = sec.isSet(key + ".multiplier") ? sec.getDouble(key + ".multiplier") : 1;
            accounts.put(id, new double[]{Num.round(sec.getDouble(key + ".mobcoins")), mult <= 0 ? 1 : mult});
            String name = sec.getString(key + ".name");
            if (name != null) names.put(id, name);
        }
    }

    // ------------------------------------------------------------------ stats, boosters, events

    @Override
    public Map<String, Double> stats(String who) {
        Map<String, Double> m = stats.get(who);
        return m == null ? new java.util.HashMap<>() : new java.util.HashMap<>(m);
    }

    @Override
    public synchronized void addStats(Map<String, Map<String, Double>> deltas) {
        for (Map.Entry<String, Map<String, Double>> who : deltas.entrySet()) {
            Map<String, Double> m = stats.computeIfAbsent(who.getKey(), k -> new ConcurrentHashMap<>());
            for (Map.Entry<String, Double> e : who.getValue().entrySet()) m.merge(e.getKey(), e.getValue(), Double::sum);
        }
        if (!deltas.isEmpty()) dirty = true;
    }

    @Override
    public synchronized void setStat(String who, String key, double value) {
        if (value == 0) {
            Map<String, Double> m = stats.get(who);
            if (m != null && m.remove(key) != null) dirty = true;
            return;
        }
        stats.computeIfAbsent(who, k -> new ConcurrentHashMap<>()).put(key, value);
        dirty = true;
    }

    @Override
    public List<Account> topStat(String key, int size) {
        List<Account> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> e : stats.entrySet()) {
            Double v = e.getValue().get(key);
            if (v == null || v <= 0) continue;
            UUID id;
            try {
                id = UUID.fromString(e.getKey());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            out.add(new Account(id, names.get(id), Num.round(v), 1));
        }
        out.sort(Comparator.comparingDouble((Account a) -> a.mobcoins).reversed());
        return out.size() > size ? new ArrayList<>(out.subList(0, size)) : out;
    }

    @Override
    public synchronized void deleteStatIfAtMost(String who, String key, double max) {
        Map<String, Double> m = stats.get(who);
        if (m == null) return;
        Double v = m.get(key);
        if (v != null && v <= max) {
            m.remove(key);
            dirty = true;
        }
    }

    @Override
    public synchronized void maxStat(String who, String key, double value) {
        Map<String, Double> m = stats.computeIfAbsent(who, k -> new ConcurrentHashMap<>());
        Double v = m.get(key);
        if (v == null || v < value) {
            m.put(key, value);
            dirty = true;
        }
    }

    @Override
    public synchronized double takeStat(String who, String key) {
        Map<String, Double> m = stats.get(who);
        Double v = m == null ? null : m.remove(key);
        if (v == null) return -1;
        dirty = true;
        return v;
    }

    @Override
    public synchronized void setStats(Map<String, Map<String, Double>> values) {
        for (Map.Entry<String, Map<String, Double>> who : values.entrySet()) {
            stats.computeIfAbsent(who.getKey(), k -> new ConcurrentHashMap<>()).putAll(who.getValue());
        }
        dirty = true;
    }

    @Override
    public Map<String, Map<String, Double>> allStats() {
        Map<String, Map<String, Double>> out = new java.util.HashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : stats.entrySet()) out.put(e.getKey(), new java.util.HashMap<>(e.getValue()));
        return out;
    }

    @Override
    public synchronized void deleteStats(String prefix, java.util.Collection<String> keep) {
        for (Map<String, Double> m : stats.values()) {
            if (m.keySet().removeIf(k -> k.startsWith(prefix) && !keep.contains(k))) dirty = true;
        }
    }

    private static String pkey(String who, String item, String period) {
        return who + "|" + item + "|" + period;
    }

    @Override
    public int bought(String who, String item, String period) {
        return purchases.getOrDefault(pkey(who, item, period), 0);
    }

    @Override
    public synchronized boolean reserve(String who, String item, String period, int max) {
        if (max <= 0) return false;
        String k = pkey(who, item, period);
        int now = purchases.getOrDefault(k, 0);
        if (now >= max) return false;
        purchases.put(k, now + 1);
        dirty = true;
        return true;
    }

    @Override
    public synchronized void release(String who, String item, String period) {
        String k = pkey(who, item, period);
        int now = purchases.getOrDefault(k, 0);
        if (now <= 0) return;
        purchases.put(k, now - 1);
        dirty = true;
    }

    @Override
    public void close() {
        try {
            flush();
        } catch (Exception ignored) {
        }
    }

    @Override
    public synchronized void flush() throws Exception {
        if (!dirty || yaml == null) return;
        dirty = false;
        YamlConfiguration out = new YamlConfiguration();
        // other keys of the file are kept (3.x wrote shop data here); "players" and "mobcoins" were empty 3.x examples
        for (String key : yaml.getKeys(false)) {
            if (!key.equals("account") && !key.equals("players") && !key.equals("mobcoins") && !key.equals("purchases")
                    && !key.equals("stats")) {
                out.set(key, yaml.get(key));
            }
        }
        if (!purchases.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Integer> e : purchases.entrySet()) if (e.getValue() > 0) lines.add(e.getKey() + "=" + e.getValue());
            lines.sort(null);
            out.set("purchases", lines);
        }
        List<String> statLines = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> who : stats.entrySet()) {
            for (Map.Entry<String, Double> e : who.getValue().entrySet()) {
                if (e.getValue() != 0) statLines.add(who.getKey() + "|" + e.getKey() + "=" + stat(e.getValue()));
            }
        }
        if (!statLines.isEmpty()) {
            statLines.sort(null);
            out.set("stats", statLines);
        }
        for (Map.Entry<UUID, double[]> e : accounts.entrySet()) {
            String path = "account." + e.getKey();
            out.set(path + ".mobcoins", e.getValue()[0]);
            out.set(path + ".multiplier", e.getValue()[1]);
            String name = names.get(e.getKey());
            if (name != null) out.set(path + ".name", name);
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        out.save(tmp);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** 12 not 12.0, times (boosters) without the E notation. */
    private static String stat(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    @Override
    public Account get(UUID uuid) {
        double[] a = accounts.get(uuid);
        return a == null ? null : new Account(uuid, names.get(uuid), a[0], a[1]);
    }

    /** Balance without creating anything (used by the main thread with yaml). */
    public Double balance(UUID uuid) {
        double[] a = accounts.get(uuid);
        return a == null ? null : a[0];
    }

    public Double multiplier(UUID uuid) {
        double[] a = accounts.get(uuid);
        return a == null ? null : a[1];
    }

    @Override
    public synchronized boolean create(UUID uuid, String name, double start) {
        if (name != null) names.put(uuid, name);
        if (accounts.containsKey(uuid)) return false;
        accounts.put(uuid, new double[]{Num.round(Math.max(0, start)), 1});
        dirty = true;
        return true;
    }

    @Override
    public synchronized double add(UUID uuid, String name, double delta, double start) {
        create(uuid, name, start);
        double[] a = accounts.get(uuid);
        a[0] = Math.max(0, Num.round(a[0] + delta));
        dirty = true;
        return a[0];
    }

    @Override
    public synchronized double take(UUID uuid, double amount) {
        double[] a = accounts.get(uuid);
        if (a == null || a[0] + 0.0001 < amount) return -1;
        a[0] = Math.max(0, Num.round(a[0] - amount));
        dirty = true;
        return a[0];
    }

    @Override
    public synchronized void set(UUID uuid, String name, double value) {
        if (name != null) names.put(uuid, name);
        double[] a = accounts.get(uuid);
        if (a == null) accounts.put(uuid, new double[]{Num.round(Math.max(0, value)), 1});
        else a[0] = Num.round(Math.max(0, value));
        dirty = true;
    }

    @Override
    public synchronized void setMultiplier(UUID uuid, String name, double multiplier, double start) {
        create(uuid, name, start);
        accounts.get(uuid)[1] = multiplier <= 0 ? 1 : multiplier;
        dirty = true;
    }

    @Override
    public void updateName(UUID uuid, String name) {
        if (name == null) return;
        String old = names.put(uuid, name);
        if (!name.equals(old)) dirty = true;
    }

    @Override
    public Account byName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return get(e.getKey());
        }
        return null;
    }

    @Override
    public List<Account> top(int size) {
        List<Account> all = all();
        all.sort(Comparator.comparingDouble((Account a) -> a.mobcoins).reversed());
        return all.size() > size ? new ArrayList<>(all.subList(0, size)) : all;
    }

    @Override
    public List<Account> all() {
        List<Account> out = new ArrayList<>(accounts.size());
        for (Map.Entry<UUID, double[]> e : accounts.entrySet()) {
            out.add(new Account(e.getKey(), names.get(e.getKey()), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    @Override
    public int count() {
        return accounts.size();
    }
}
