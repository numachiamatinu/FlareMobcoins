package dev.tmdevelopment.flaremobcoins.update;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a new FlareMobcoins version in the public GitHub releases. It only reads: nothing is sent.
 * Servers on a stable version are never offered a beta; servers on a beta also see newer betas.
 * The answer is kept for 6 hours.
 */
public final class UpdateChecker implements Listener {

    public static final String REPO = "numachiamatinu/FlareMobcoins";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    private static final String PAGE = "https://github.com/" + REPO + "/releases";
    private static final long KEEP_MS = 6 * 60 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 60 * 1000L;
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DRAFT = Pattern.compile("\"draft\"\\s*:\\s*(true|false)");
    private static final Pattern PRE = Pattern.compile("\"prerelease\"\\s*:\\s*(true|false)");

    public enum State { OFF, UNKNOWN, LATEST, OUTDATED }

    public static final class Result {
        public final State state;
        public final String latest;
        public final String url;

        Result(State state, String latest, String url) {
            this.state = state;
            this.latest = latest;
            this.url = url;
        }
    }

    private final FlareMobcoins plugin;
    private final String current;
    private volatile Result result = new Result(State.UNKNOWN, null, PAGE);
    private volatile long checkedAt;
    private volatile long failedAt;
    private volatile CompletableFuture<Result> running;
    private volatile String announced;
    private BukkitTask task;

    public UpdateChecker(FlareMobcoins plugin) {
        this.plugin = plugin;
        this.current = plugin.getDescription().getVersion();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("update-check", true);
    }

    /** Starts the check on startup and every 6 hours (or stops it when update-check is false). */
    public void start() {
        stop();
        if (!enabled()) return;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> refresh(true), 100L, KEEP_MS / 50);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /** The last answer, or a new one if it is older than 6 hours. Completed on the main thread. */
    public CompletableFuture<Result> get() {
        CompletableFuture<Result> out = new CompletableFuture<>();
        if (!enabled()) {
            out.complete(new Result(State.OFF, null, PAGE));
            return out;
        }
        long now = System.currentTimeMillis();
        boolean fresh = checkedAt > 0 && now - checkedAt < KEEP_MS;
        boolean waitRetry = failedAt > 0 && now - failedAt < RETRY_MS;
        if (fresh || waitRetry) {
            out.complete(result);
            return out;
        }
        CompletableFuture<Result> f = running;
        if (f == null) {
            f = new CompletableFuture<>();
            running = f;
            CompletableFuture<Result> mine = f;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> mine.complete(refresh(false)));
        }
        f.thenAccept(r -> sync(() -> out.complete(r)));
        return out;
    }

    private Result refresh(boolean scheduled) {
        try {
            Result r = check();
            result = r;
            checkedAt = System.currentTimeMillis();
            failedAt = 0;
            if (scheduled && r.state == State.OUTDATED && !r.latest.equals(announced)) {
                announced = r.latest;
                sync(() -> plugin.lang().send(Bukkit.getConsoleSender(), "update.console", pairs(r)));
            }
            return r;
        } catch (Throwable t) {
            failedAt = System.currentTimeMillis();
            plugin.getLogger().fine("Update check failed: " + t);
            return checkedAt > 0 ? result : new Result(State.UNKNOWN, null, PAGE);
        } finally {
            running = null;
        }
    }

    private Result check() throws Exception {
        String best = newest(read(API), current);
        if (best == null || compare(best, current) <= 0) return new Result(State.LATEST, best == null ? current : clean(best), PAGE);
        return new Result(State.OUTDATED, clean(best), PAGE + "/tag/" + best);
    }

    /** Newest release tag that this server may be offered (no drafts, betas only for beta servers). */
    static String newest(String body, String current) {
        boolean beta = isBeta(current);
        Matcher tags = TAG.matcher(body);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (tags.find()) {
            spans.add(new int[]{tags.start(), tags.end()});
            names.add(tags.group(1));
        }
        String best = null;
        for (int i = 0; i < names.size(); i++) {
            int from = spans.get(i)[1];
            int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : body.length();
            String part = body.substring(from, to);
            if (flag(DRAFT, part)) continue;
            String tag = names.get(i);
            boolean pre = flag(PRE, part) || isBeta(tag);
            if (pre && !beta) continue;
            if (parse(tag) == null) continue;
            if (best == null || compare(tag, best) > 0) best = tag;
        }
        return best;
    }

    private static boolean flag(Pattern p, String part) {
        Matcher m = p.matcher(part);
        return m.find() && m.group(1).equals("true");
    }

    private String read(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "TMDevelopment/FlareMobcoins/" + current);
        try {
            if (con.getResponseCode() != 200) throw new IllegalStateException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }

    // ------------------------------------------------------------------ versions

    private static String clean(String v) {
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    static boolean isBeta(String v) {
        return clean(v).indexOf('-') >= 0;
    }

    /** "4.1.0-beta2" -> [4, 1, 0, 2] with the last number = beta number (or MAX for a stable version). */
    static long[] parse(String v) {
        String s = clean(v);
        String pre = null;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            pre = s.substring(dash + 1);
            s = s.substring(0, dash);
        }
        String[] parts = s.split("\\.");
        if (parts.length == 0 || parts.length > 4) return null;
        long[] out = new long[5];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Long.parseLong(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (pre == null) {
            out[4] = Long.MAX_VALUE;
        } else {
            String digits = pre.replaceAll("[^0-9]", "");
            out[4] = digits.isEmpty() ? 0 : Long.parseLong(digits.length() > 9 ? digits.substring(0, 9) : digits);
        }
        return out;
    }

    static int compare(String a, String b) {
        long[] x = parse(a), y = parse(b);
        if (x == null || y == null) return x == null ? (y == null ? 0 : -1) : 1;
        for (int i = 0; i < x.length; i++) {
            if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ join

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!enabled() || result.state != State.OUTDATED) return;
        if (!p.hasPermission("flaremobcoins.command.version") && !p.hasPermission("flaremobcoins.admin")) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && result.state == State.OUTDATED) plugin.lang().send(p, "update.join", pairs(result));
        }, 40L);
    }

    public String[] pairs(Result r) {
        return new String[]{"{latest}", r.latest == null ? "?" : r.latest, "{url}", r.url, "{current}", current};
    }

    private void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    public static String javaVersion() {
        return System.getProperty("java.version", "?");
    }

    public static String serverSoftware() {
        String name = Bukkit.getName();
        return name == null ? "?" : name.toLowerCase(Locale.ROOT).equals("craftbukkit") ? "CraftBukkit" : name;
    }
}
