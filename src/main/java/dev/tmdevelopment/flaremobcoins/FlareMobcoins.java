package dev.tmdevelopment.flaremobcoins;

import dev.tmdevelopment.flaremobcoins.command.MobcoinsCommand;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.config.Migration;
import dev.tmdevelopment.flaremobcoins.data.CoinManager;
import dev.tmdevelopment.flaremobcoins.data.SqlStorage;
import dev.tmdevelopment.flaremobcoins.data.StateFile;
import dev.tmdevelopment.flaremobcoins.data.Storage;
import dev.tmdevelopment.flaremobcoins.data.YamlStorage;
import dev.tmdevelopment.flaremobcoins.feature.Drops;
import dev.tmdevelopment.flaremobcoins.feature.GroundCoins;
import dev.tmdevelopment.flaremobcoins.feature.PlayerListener;
import dev.tmdevelopment.flaremobcoins.hook.PapiExpansion;
import dev.tmdevelopment.flaremobcoins.hook.VaultHook;
import dev.tmdevelopment.flaremobcoins.install.DependencyInstaller;
import dev.tmdevelopment.flaremobcoins.menu.ChatInput;
import dev.tmdevelopment.flaremobcoins.menu.MenuListener;
import dev.tmdevelopment.flaremobcoins.menu.MenuManager;
import dev.tmdevelopment.flaremobcoins.stats.TMDStats;
import dev.tmdevelopment.flaremobcoins.update.UpdateChecker;
import dev.tmdevelopment.flaremobcoins.util.Headers;
import dev.tmdevelopment.flaremobcoins.util.Materials;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import dev.tmdevelopment.flaremobcoins.util.Ver;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FlareMobcoins extends JavaPlugin {

    private static FlareMobcoins instance;
    private Lang lang;
    private StateFile state;
    private CoinManager coins;
    private MenuManager menus;
    private dev.tmdevelopment.flaremobcoins.menu.ChestBuilder chestBuilder;
    private Drops drops;
    private GroundCoins groundCoins;
    private TMDStats stats;
    private UpdateChecker updates;
    private ChatInput chatInput;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public static FlareMobcoins get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();
        Materials.init(getLogger());
        Migration.run(this);
        saveDefaultConfig();
        Headers.sync(this, new File(getDataFolder(), "config.yml"), "config.yml");
        reloadConfig();
        updateConfig();
        Num.setSuffixes(getConfig().getStringList("format.suffixes"));
        boolean papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        Placeholders.setPapi(papi);

        lang = new Lang(this);
        lang.load();
        state = new StateFile(this);
        state.load();
        Storage storage = createStorage(getConfig().getString("storage.type", "sqlite"));
        coins = new CoinManager(this, storage, state);
        try {
            coins.open();
        } catch (Throwable t) {
            getLogger().severe("Could not open the storage (" + storage.type() + "): " + t.getMessage());
            getLogger().severe("Check storage: in config.yml. FlareMobcoins is disabled to keep your balances safe.");
            coins = null;
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        VaultHook.setup();
        menus = new MenuManager(this, state);
        chestBuilder = new dev.tmdevelopment.flaremobcoins.menu.ChestBuilder(this);
        menus.load();
        drops = new Drops(this);
        drops.load();
        groundCoins = new GroundCoins(this);
        groundCoins.load();
        updates = new UpdateChecker(this);
        chatInput = new ChatInput(this);

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(drops, this);
        Bukkit.getPluginManager().registerEvents(groundCoins, this);
        Bukkit.getPluginManager().registerEvents(updates, this);
        Bukkit.getPluginManager().registerEvents(chatInput, this);
        groundCoins.registerPickup();
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        PluginCommand cmd = getCommand("mobcoins");
        if (cmd != null) {
            MobcoinsCommand executor = new MobcoinsCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
            Bukkit.getPluginManager().registerEvents(executor, this);
        }
        if (papi) registerPapi();
        new DependencyInstaller(this).run(name -> {
            if (name.equalsIgnoreCase("PlaceholderAPI") && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Placeholders.setPapi(true);
                registerPapi();
            }
            if (name.equalsIgnoreCase("Vault")) VaultHook.setup();
        });
        // economy plugins register in Vault after us
        Bukkit.getScheduler().runTaskLater(this, VaultHook::setup, 40L);
        startTasks();
        updates.start();
        startMetrics();
        banner(papi, System.currentTimeMillis() - start);
    }

    @Override
    public void onDisable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (updates != null) updates.stop();
        if (stats != null) stats.shutdown();
        if (groundCoins != null) groundCoins.shutdown();
        if (menus != null) menus.shutdown();
        if (state != null) state.saveSync();
        if (coins != null) coins.shutdown();
        instance = null;
    }

    private void startTasks() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        String type = coins.storage().type();
        long refresh = getConfig().getLong("storage.refresh-seconds", 10);
        if ((type.equals("mysql") || type.equals("mariadb")) && refresh > 0) {
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, coins::refreshOnline, refresh * 20, refresh * 20));
        }
        long top = Math.max(10, getConfig().getLong("top.refresh-seconds", 60));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> coins.refreshTop(), top * 20, top * 20));
        if (type.equals("yaml")) tasks.add(Bukkit.getScheduler().runTaskTimer(this, coins::flushAsync, 200L, 200L));
    }

    private void registerPapi() {
        try {
            new PapiExpansion(this).register();
        } catch (Throwable t) {
            getLogger().warning("Could not register the PlaceholderAPI placeholders: " + t.getMessage());
        }
    }

    public Storage createStorage(String type) {
        FileConfiguration c = getConfig();
        String t = type == null ? "sqlite" : type.toLowerCase(Locale.ROOT).trim();
        String table = c.getString("storage.table", "flaremobcoins");
        switch (t) {
            case "yaml":
            case "yml":
            case "file":
                return new YamlStorage(new File(getDataFolder(), "data/data.yml"));
            case "mysql":
            case "mariadb":
                return SqlStorage.mysql(t.equals("mariadb"), c.getString("storage.mysql.host", "localhost"),
                        c.getInt("storage.mysql.port", 3306), c.getString("storage.mysql.database", "minecraft"),
                        c.getString("storage.mysql.username", "root"), c.getString("storage.mysql.password", ""),
                        c.getString("storage.mysql.flags", ""), table, getLogger());
            default:
                if (!t.equals("sqlite")) getLogger().warning("Unknown storage.type '" + type + "', using sqlite.");
                return SqlStorage.sqlite(new File(getDataFolder(), "data/data.db"), table, getLogger());
        }
    }

    /** New options of this version are added to config.yml; the values of the server are kept. */
    private void updateConfig() {
        FileConfiguration c = getConfig();
        Configuration defaults = c.getDefaults();
        if (defaults == null) return;
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (key.startsWith("auto-install.plugins.") && c.isSet("auto-install.plugins")) continue;
            if (!c.isSet(key)) missing.add(key);
        }
        if (missing.isEmpty()) return;
        // written as text with their comments: Bukkit's save would drop every comment on 1.8 - 1.17
        File file = new File(getDataFolder(), "config.yml");
        String text = dev.tmdevelopment.flaremobcoins.config.ConfigUpdater.resource(this, "config.yml");
        if (text != null && dev.tmdevelopment.flaremobcoins.config.ConfigUpdater.addMissing(file, text, missing, getLogger())) {
            reloadConfig();
        } else {
            for (String key : missing) c.set(key, defaults.get(key));
            saveConfig();
        }
        getLogger().info("config.yml updated with the new options of this version.");
    }

    public void reloadAll() {
        Headers.sync(this, new File(getDataFolder(), "config.yml"), "config.yml");
        reloadConfig();
        Num.setSuffixes(getConfig().getStringList("format.suffixes"));
        lang.load();
        coins.reloadSettings();
        menus.closeAll();
        menus.load();
        drops.load();
        groundCoins.load();
        VaultHook.setup();
        startTasks();
        updates.start();
        coins.refreshTop();
    }

    /** An option changed in /mobcoins settings: reads config.yml again without closing the menus. */
    public void applyConfig() {
        reloadConfig();
        Num.setSuffixes(getConfig().getStringList("format.suffixes"));
        coins.reloadSettings();
        groundCoins.load();
        startTasks();
        updates.start();
    }

    public void onFirstJoin(Player player, double amount) {
        lang.send(player, "first-join", "{amount}", Num.plain(amount), "{amount_commas}", Num.commas(amount));
    }

    private void startMetrics() {
        try {
            stats = new TMDStats(this, "flaremobcoins", getConfig().getBoolean("stats", true));
            stats.addValue("storage", () -> coins.storage().type());
            stats.addValue("shops", () -> menus.size());
            stats.addValue("drops", () -> drops.enabled() ? drops.size() : 0);
            stats.addValue("coins_on_ground", () -> groundCoins.enabled() ? "On" : "Off");
        } catch (Throwable t) {
            getLogger().fine("TMDStats not started: " + t.getMessage());
        }
    }

    private void banner(boolean papi, long ms) {
        boolean vault = Bukkit.getPluginManager().isPluginEnabled("Vault");
        String g = "<gradient:#f7b733:#fce38a>";
        String[] lines = {
                "",
                g + "  ███████╗██╗      █████╗ ██████╗ ███████╗</gradient>",
                g + "  ██╔════╝██║     ██╔══██╗██╔══██╗██╔════╝</gradient>",
                g + "  █████╗  ██║     ███████║██████╔╝█████╗  </gradient>",
                g + "  ██╔══╝  ██║     ██╔══██║██╔══██╗██╔══╝  </gradient>",
                g + "  ██║     ███████╗██║  ██║██║  ██║███████╗</gradient>",
                g + "  ╚═╝     ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝</gradient>",
                "<gray>          M O B C O I N S  <dark_gray>|  <white>v" + getDescription().getVersion(),
                "",
                "<gray>  Developed by " + g + "<bold>TMDevelopment</bold></gradient>",
                "<gray>  Minecraft: <white>" + Ver.text() + " <dark_gray>| <gray>Storage: <white>" + coins.storage().type()
                        + " <dark_gray>| <gray>Accounts: <white>" + coins.accounts(),
                "<gray>  Shops: <white>" + menus.size() + " <dark_gray>| <gray>Drops: "
                        + (drops.enabled() ? "<green>" + drops.size() : "<white>off")
                        + " <dark_gray>| <gray>Coins on ground: " + (groundCoins.enabled() ? "<green>on" : "<white>off"),
                "<gray>  PlaceholderAPI: " + (papi ? "<green>hooked" : papiDownload() ? "<yellow>downloading..." : "<red>not found") + " <dark_gray>| <gray>Vault: "
                        + (vault ? "<green>found" : "<red>not found"),
                "<gray>  Enabled in <white>" + ms + "ms",
                ""};
        for (String l : lines) Bukkit.getConsoleSender().sendMessage(Text.console(l));
    }

    /** PlaceholderAPI is missing but auto-install is getting it (the banner says so instead of "not found"). */
    private boolean papiDownload() {
        return getConfig().getBoolean("auto-install.enabled", true)
                && getConfig().getBoolean("auto-install.plugins.PlaceholderAPI.enabled", true)
                && getConfig().isConfigurationSection("auto-install.plugins.PlaceholderAPI");
    }

    public Lang lang() {
        return lang;
    }

    public CoinManager coins() {
        return coins;
    }

    public MenuManager menus() {
        return menus;
    }

    public dev.tmdevelopment.flaremobcoins.menu.ChestBuilder chestBuilder() {
        return chestBuilder;
    }

    public Drops drops() {
        return drops;
    }

    public ChatInput chatInput() {
        return chatInput;
    }

    public UpdateChecker updates() {
        return updates;
    }

    public GroundCoins coinsOnGround() {
        return groundCoins;
    }
}
