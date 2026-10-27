package dev.tmdevelopment.flaremobcoins.config;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.J8;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/**
 * FlareMobcoins 3.x -> 4.x, once, on the first start: old files are saved in backup-3.x/, the settings go into the
 * new config.yml, shop/ becomes shops/, the global multiplier goes into data/state.yml.
 * Balances are not touched: same data/data.yml, data/data.db and MySQL table. drops.yml is kept as it is.
 */
public final class Migration {

    private Migration() {
    }

    public static boolean isOldConfig(File file) {
        if (!file.exists()) return false;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (y.isSet("config-version")) return false;
        return y.isSet("select_locale") || y.isSet("storage.cache_time") || y.isSet("display_coin")
                || y.isSet("first_join_give_mobcoins") || y.isSet("rotating_shop_time_format");
    }

    public static void run(FlareMobcoins plugin) {
        File dir = plugin.getDataFolder();
        File configFile = new File(dir, "config.yml");
        if (!isOldConfig(configFile)) return;
        plugin.getLogger().info("FlareMobcoins 3.x files found: updating them to the new version...");
        try {
            File backup = new File(dir, "backup-3.x");
            if (backup.exists()) backup = new File(dir, "backup-3.x-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
            if (!backup.mkdirs()) throw new IOException("can't create " + backup.getName());
            for (String name : new String[]{"config.yml", "drops.yml", "locale", "shop", "data/data.yml", "data/logs.yml", "data/data.db"}) {
                File f = new File(dir, name);
                if (f.exists()) copy(f.toPath(), new File(backup, name).toPath());
            }

            YamlConfiguration old = YamlConfiguration.loadConfiguration(configFile);
            String text;
            try (InputStream in = plugin.getResource("config.yml")) {
                text = new String(J8.readAll(in), StandardCharsets.UTF_8);
            }
            String storage = old.getString("storage.type", "file").toLowerCase();
            if (storage.equals("file")) storage = "yaml";
            if (!storage.equals("yaml") && !storage.equals("sqlite") && !storage.equals("mysql")) storage = "yaml";
            text = YamlPatch.set(text, "storage.type", storage);
            // 3.x used storage.mysql.table also for SQLite (default "flaretokens"): the same name is kept
            String table = old.getString("storage.mysql.table", "flaretokens");
            text = YamlPatch.set(text, "storage.table", YamlPatch.quote(table));
            text = YamlPatch.set(text, "storage.mysql.host", YamlPatch.quote(old.getString("storage.mysql.host", "localhost")));
            text = YamlPatch.set(text, "storage.mysql.port", String.valueOf(old.getInt("storage.mysql.port", 3306)));
            text = YamlPatch.set(text, "storage.mysql.database", YamlPatch.quote(old.getString("storage.mysql.database", "minecraft")));
            text = YamlPatch.set(text, "storage.mysql.username", YamlPatch.quote(old.getString("storage.mysql.username", "root")));
            text = YamlPatch.set(text, "storage.mysql.password", YamlPatch.quote(old.getString("storage.mysql.password", "")));
            text = YamlPatch.set(text, "first-join-mobcoins", String.valueOf(Math.max(0, old.getDouble("first_join_give_mobcoins", 0))));
            if (old.isSet("display_coin")) {
                text = YamlPatch.set(text, "coins-on-ground.enabled", String.valueOf(old.getBoolean("display_coin.enable", false)));
                text = YamlPatch.set(text, "coins-on-ground.only-killer", String.valueOf(old.getBoolean("display_coin.only_the_killer_can_collect", true)));
                if (old.isString("display_coin.display_material")) {
                    text = YamlPatch.set(text, "coins-on-ground.material", YamlPatch.quote(old.getString("display_coin.display_material")));
                }
                if (old.isList("display_coin.pickup_commands")) {
                    List<String> actions = new ArrayList<>();
                    // 3.x example had "[mobcoins_give ] ..." (with a space): it gave nothing
                    for (String a : old.getStringList("display_coin.pickup_commands")) actions.add(a.replace("[mobcoins_give ]", "[mobcoins_give]"));
                    text = YamlPatch.setList(text, "coins-on-ground.pickup-actions", actions);
                }
            }
            text = YamlPatch.set(text, "shop.enabled", String.valueOf(old.getBoolean("shop.enable", true)));
            text = YamlPatch.set(text, "shop.main-shop", YamlPatch.quote(old.getString("shop.main_shop", "main")));
            String open = old.getString("shop.open_command", "mobcoinshop");
            text = YamlPatch.setList(text, "shop.open-command", open == null || open.trim().isEmpty()
                    ? Collections.<String>emptyList() : Collections.singletonList(open.trim()));
            if (old.isString("rotating_shop_time_format")) {
                text = YamlPatch.set(text, "shop.rotating-time-format", YamlPatch.quote(old.getString("rotating_shop_time_format")));
            }
            J8.write(configFile.toPath(), text, StandardCharsets.UTF_8);

            // global multiplier: data/data.yml -> data/state.yml
            File data = new File(dir, "data/data.yml");
            if (data.exists()) {
                YamlConfiguration d = YamlConfiguration.loadConfiguration(data);
                double global = d.getDouble("global_multiplier", 1);
                if (global > 0 && global != 1) {
                    File stateFile = new File(dir, "data/state.yml");
                    YamlConfiguration state = stateFile.exists() ? YamlConfiguration.loadConfiguration(stateFile) : new YamlConfiguration();
                    state.set("global-multiplier", global);
                    state.save(stateFile);
                }
            }

            // shop/ -> shops/
            File oldShops = new File(dir, "shop");
            File shops = new File(dir, "shops");
            int moved = 0;
            if (oldShops.isDirectory()) {
                if (!shops.exists() && !shops.mkdirs()) throw new IOException("can't create shops/");
                File[] files = oldShops.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.getName().endsWith(".yml")) continue;
                        Files.copy(f.toPath(), new File(shops, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                        moved++;
                    }
                }
                delete(oldShops.toPath());
            }
            delete(new File(dir, "locale").toPath());
            Files.deleteIfExists(new File(dir, "data/logs.yml").toPath());
            plugin.getLogger().info("Update done: settings moved to the new config.yml, " + moved + " shop(s) moved to shops/, "
                    + "balances kept (" + storage + ", table " + table + "). Old files saved in " + backup.getName() + "/");
            plugin.getLogger().info("Messages are now in lang/en.yml: if you changed the old locale/en.yml, copy your texts from the backup.");
            plugin.getLogger().info("Rotating shops start a new rotation after the update.");
        } catch (Exception e) {
            plugin.getLogger().severe("Could not update the FlareMobcoins 3.x files: " + e.getMessage()
                    + ". Nothing was deleted; check the folder plugins/FlareMobcoins.");
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(from)) {
            s.forEach(all::add);
        }
        for (Path p : all) {
            Path target = to.resolve(from.relativize(p).toString());
            if (Files.isDirectory(p)) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(path)) {
            s.forEach(all::add);
        }
        all.sort(Comparator.reverseOrder());
        for (Path p : all) Files.deleteIfExists(p);
    }
}
