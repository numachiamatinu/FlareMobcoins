package dev.tmdevelopment.flaremobcoins.config;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Headers;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Chat messages from lang/&lt;language&gt;.yml. A message is one line or a list; "" or [] = off. */
public final class Lang {

    private static final String[] BUNDLED = {"en"};
    private final FlareMobcoins plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    private YamlConfiguration admin = new YamlConfiguration();
    private String prefix = "";

    public Lang(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) plugin.getLogger().warning("Could not create the lang folder");
        for (String name : BUNDLED) {
            if (!new File(dir, name + ".yml").exists()) plugin.saveResource("lang/" + name + ".yml", false);
        }
        String language = plugin.getConfig().getString("language", "en");
        File file = new File(dir, language + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("lang/" + language + ".yml not found, using en.yml");
            file = new File(dir, "en.yml");
        }
        Headers.sync(plugin, file, "lang/en.yml");
        cfg = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("lang/en.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                cfg.setDefaults(defaults);
                cfg = addMissing(file, "lang/en.yml", cfg, defaults);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the default messages: " + e.getMessage());
        }
        prefix = cfg.getString("prefix", "");
        loadAdmin();
    }

    /** Texts of the admin menus (paths "gui.*"): menus/admin.yml, like the other Flare plugins. */
    private void loadAdmin() {
        File f = new File(plugin.getDataFolder(), "menus/admin.yml");
        if (!f.exists()) plugin.saveResource("menus/admin.yml", false);
        Headers.sync(plugin, f, "menus/admin.yml");
        admin = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource("menus/admin.yml")) {
            if (in == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            admin.setDefaults(defaults);
            admin = addMissing(f, "menus/admin.yml", admin, defaults);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the default texts of menus/admin.yml: " + e.getMessage());
        }
    }

    /**
     * Texts added by a newer version are written into the file with their comments; edited ones are never touched.
     * Bukkit's own save (used only if that fails) would drop every comment on 1.8 - 1.17.
     */
    private YamlConfiguration addMissing(File file, String resource, YamlConfiguration current, YamlConfiguration defaults)
            throws java.io.IOException {
        List<String> missing = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || current.isSet(key)) continue;
            missing.add(key);
        }
        if (missing.isEmpty()) return current;
        String text = ConfigUpdater.resource(plugin, resource);
        if (text != null && ConfigUpdater.addMissing(file, text, missing, plugin.getLogger())) {
            current = YamlConfiguration.loadConfiguration(file);
            current.setDefaults(defaults);
        } else {
            for (String key : missing) current.set(key, defaults.get(key));
            current.save(file);
        }
        plugin.getLogger().info("New texts added to " + resource.replace("lang/en.yml", "lang/" + file.getName()));
        return current;
    }

    private Object value(String path) {
        if (path.startsWith("gui.")) return admin.get(path.substring(4));
        return cfg.get(path);
    }

    public String prefix() {
        return prefix;
    }

    /** First line of a message ("" if it is off). */
    public String raw(String path) {
        Object v = value(path);
        if (v instanceof List<?>) {
            List<?> l = (List<?>) v;
            return l.isEmpty() ? "" : String.valueOf(l.get(0));
        }
        return v == null ? "&cMissing message: " + path : String.valueOf(v);
    }

    public List<String> lines(String path) {
        List<String> out = new ArrayList<>();
        Object v = value(path);
        if (v instanceof List<?>) {
            for (Object o : (List<?>) v) out.add(String.valueOf(o));
        } else if (v == null) {
            out.add("&cMissing message: " + path);
        } else if (!String.valueOf(v).isEmpty()) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    public boolean isSet(String path) {
        return path.startsWith("gui.") ? admin.isSet(path.substring(4)) : cfg.isSet(path);
    }

    /** Sends a message; the placeholders of the receiver are used. */
    public void send(CommandSender to, String path, String... pairs) {
        for (String line : lines(path)) chat(to, line, pairs);
    }

    /** Sends a message whose player placeholders are about another player. */
    public void sendAbout(CommandSender to, OfflinePlayer about, String path, String... pairs) {
        for (String line : lines(path)) deliver(to, Placeholders.apply(line, about, pairs));
    }

    public static void chat(CommandSender to, String line, String... pairs) {
        if (to == null || line == null) return;
        Player p = to instanceof Player ? (Player) to : null;
        deliver(to, Placeholders.apply(line, p, pairs));
    }

    /** Sends an already filled line. */
    public static void deliver(CommandSender to, String text) {
        if (text == null) return;
        if (to instanceof Player) {
            ((Player) to).spigot().sendMessage(Text.component(text));
        } else if (to != null) {
            to.sendMessage(Text.console(text));
        }
    }

    public static void broadcast(String text) {
        for (Player p : Bukkit.getOnlinePlayers()) deliver(p, text);
        deliver(Bukkit.getConsoleSender(), text);
    }
}
