package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.data.StateFile;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Headers;
import dev.tmdevelopment.flaremobcoins.util.J8;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the shops (shops/) and the menus of the plugin (menus/), opens them and keeps the open ones up to date. */
public final class MenuManager {

    public static final List<String> BUILTIN = J8.list(Builtins.MAIN, Builtins.TOP, Builtins.DROPS, Builtins.CONFIRM);
    private static final Pattern LINE = Pattern.compile("line (\\d+)");
    private final FlareMobcoins plugin;
    private final Rotations rotations;
    private final Map<String, MenuDef> shops = new LinkedHashMap<>();
    private final Map<String, MenuDef> builtins = new LinkedHashMap<>();
    private final Map<UUID, MenuSession> open = new ConcurrentHashMap<>();
    private final List<Command> registered = new ArrayList<>();
    /** Open commands in use now: command -> menu. Paper keeps old commands until a restart, they check this. */
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private BukkitTask ticker;
    private BukkitTask rotationTicker;
    private YamlConfiguration shopTexts;

    public MenuManager(FlareMobcoins plugin, StateFile state) {
        this.plugin = plugin;
        this.rotations = new Rotations(plugin, state);
    }

    Rotations rotations() {
        return rotations;
    }

    public File shopDir() {
        return new File(plugin.getDataFolder(), "shops");
    }

    public File menuDir() {
        return new File(plugin.getDataFolder(), "menus");
    }

    public void load() {
        shops.clear();
        builtins.clear();
        shopTexts = resource("shop-texts.yml");
        File dir = shopDir();
        if (!dir.exists()) plugin.saveResource("shops/main.yml", false);
        loadShops(dir);
        loadBuiltins();
        registerCommands();
        for (MenuDef d : shops.values()) rotations.update(d, false);
        if (ticker != null) ticker.cancel();
        if (rotationTicker != null) rotationTicker.cancel();
        long every = Math.max(5, plugin.getConfig().getLong("shop.update-ticks", 20));
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (MenuSession s : new ArrayList<>(open.values())) {
                if (!s.player().isOnline()) {
                    open.remove(s.player().getUniqueId());
                    continue;
                }
                try {
                    s.tick();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Error updating menu " + s.def.id + ": " + t.getMessage());
                }
            }
        }, every, every);
        rotationTicker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (MenuDef d : shops.values()) {
                if (d.rotation != null && rotations.update(d, true)) {
                    for (MenuSession s : new ArrayList<>(open.values())) if (s.def == d) s.render();
                }
            }
        }, 20L, 20L);
    }

    private void loadShops(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) loadShops(f);
                continue;
            }
            String name = f.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".yml")) continue;
            String id = name.substring(0, name.length() - 4);
            YamlConfiguration y = read(f, "shops/" + name);
            if (y == null) continue;
            try {
                shops.put(id.toLowerCase(Locale.ROOT), MenuDef.load(id, f, false, y, shopTexts(), plugin.getLogger()));
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not load shops/" + name + ": " + t.getMessage());
            }
        }
    }

    private void loadBuiltins() {
        File dir = menuDir();
        if (!dir.exists() && !dir.mkdirs()) plugin.getLogger().warning("Could not create the menus folder");
        for (String id : BUILTIN) {
            String res = "menus/" + id + ".yml";
            File f = new File(dir, id + ".yml");
            if (!f.exists()) plugin.saveResource(res, false);
            Headers.sync(plugin, f, res);
            YamlConfiguration y = read(f, res);
            YamlConfiguration defaults = resource(res);
            boolean broken = y == null;
            if (broken) y = defaults == null ? new YamlConfiguration() : defaults;
            else if (defaults != null && complete(y, defaults)) save(f, res, y);
            ConfigurationSection texts = defaults == null ? null : defaults.getConfigurationSection("texts");
            MenuDef d = MenuDef.load(id, f, true, y, texts, plugin.getLogger());
            d.broken = broken;
            builtins.put(id, d);
        }
    }

    /** Elements and texts added by a new version go in the file of the server (edited ones are kept). */
    private boolean complete(YamlConfiguration y, YamlConfiguration defaults) {
        boolean changed = false;
        ConfigurationSection elements = defaults.getConfigurationSection("elements");
        if (elements != null) {
            for (String k : elements.getKeys(false)) {
                if (!y.isConfigurationSection("elements." + k)) {
                    y.set("elements." + k, elements.getConfigurationSection(k));
                    changed = true;
                }
            }
        }
        ConfigurationSection texts = defaults.getConfigurationSection("texts");
        if (texts != null) {
            for (String k : texts.getKeys(true)) {
                if (!texts.isConfigurationSection(k) && !y.isSet("texts." + k)) {
                    y.set("texts." + k, texts.get(k));
                    changed = true;
                }
            }
        }
        for (String k : new String[]{"enabled", "open-commands", "open-permission"}) {
            if (!y.isSet(k) && defaults.isSet(k)) {
                y.set(k, defaults.get(k));
                changed = true;
            }
        }
        return changed;
    }

    YamlConfiguration read(File f, String name) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(f);
            return y;
        } catch (InvalidConfigurationException e) {
            plugin.getLogger().warning(name + " has a mistake and was not loaded: " + firstLine(e.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read " + name + ": " + e.getMessage());
        }
        return null;
    }

    private static String firstLine(String message) {
        if (message == null) return "?";
        String m = message.replace("\r", "");
        int nl = m.indexOf('\n');
        String first = nl < 0 ? m : m.substring(0, nl);
        Matcher line = LINE.matcher(m);
        return line.find() ? "line " + line.group(1) + ": " + first : first;
    }

    private ConfigurationSection shopTexts() {
        return shopTexts == null ? null : shopTexts.getConfigurationSection("texts");
    }

    YamlConfiguration resource(String name) {
        try (InputStream in = plugin.getResource(name)) {
            return in == null ? null : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Saves with the explanations of the file in the jar on top. */
    void save(File f, String resource, YamlConfiguration y) {
        // only what changed is written: comments, quotes and layout of the file stay as they are
        if (f.isFile()) {
            try {
                String old = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String patched = dev.tmdevelopment.flaremobcoins.config.YamlKeeper.patch(old, y);
                if (patched != null) {
                    if (!patched.equals(old.replace("\r\n", "\n"))) J8.write(f.toPath(), patched, StandardCharsets.UTF_8);
                    return;
                }
            } catch (Exception ignored) {
                // written again below
            }
        }
        String header = Headers.of(plugin, resource);
        String body = Headers.split(y.saveToString())[1];
        try {
            J8.write(f.toPath(), header + body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not save " + f.getName() + ": " + e.getMessage());
        }
    }

    /** Saves a menu changed by the editor and loads it again. */
    public MenuDef save(MenuDef def) {
        if (def.broken) return def;
        save(def.file, def.builtin ? "menus/" + def.id + ".yml" : "shops/main.yml", def.yaml);
        YamlConfiguration y = read(def.file, def.file.getName());
        if (y == null) return def;
        YamlConfiguration defaults = def.builtin ? resource("menus/" + def.id + ".yml") : null;
        ConfigurationSection texts = def.builtin ? (defaults == null ? null : defaults.getConfigurationSection("texts")) : shopTexts();
        MenuDef loaded = MenuDef.load(def.id, def.file, def.builtin, y, texts, plugin.getLogger());
        if (def.builtin) builtins.put(def.id, loaded);
        else {
            shops.put(def.id.toLowerCase(Locale.ROOT), loaded);
            rotations.update(loaded, false);
        }
        registerCommands();
        return loaded;
    }

    /** A new empty shop: shops/&lt;name&gt;.yml. Null if the name is not valid or used. */
    public MenuDef create(String rawName) {
        String id = rawName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty() || shops.containsKey(id) || BUILTIN.contains(id)) return null;
        File f = new File(shopDir(), id + ".yml");
        if (f.exists()) return null;
        YamlConfiguration y = new YamlConfiguration();
        y.set("enabled", true);
        y.set("title", "<gradient:#f7b733:#fce38a><bold>" + id.toUpperCase(Locale.ROOT) + "</bold></gradient>");
        y.set("rows", 3);
        y.set("open-commands", new ArrayList<String>());
        y.set("open-permission", "");
        y.createSection("items");
        y.set("decorations", new ArrayList<Object>());
        save(f, "shops/main.yml", y);
        MenuDef d = MenuDef.load(id, f, false, y, shopTexts(), plugin.getLogger());
        shops.put(id, d);
        return d;
    }

    /** Is /name already a command of the server (another plugin, or a menu of this one)? */
    public boolean commandTaken(String name) {
        try {
            org.bukkit.command.Command c = Compat.commandMap().getCommand(name.toLowerCase(Locale.ROOT));
            return c != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Copies a file to backups/ (folder-name-date.yml). Null if it could not be copied. */
    public File backup(File f, String folder) {
        if (f == null || !f.isFile()) return null;
        File dir = new File(plugin.getDataFolder(), "backups");
        if (!dir.exists() && !dir.mkdirs()) return null;
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        String base = f.getName().endsWith(".yml") ? f.getName().substring(0, f.getName().length() - 4) : f.getName();
        File out = new File(dir, folder + "-" + base + "-" + stamp + ".yml");
        int n = 2;
        while (out.exists()) out = new File(dir, folder + "-" + base + "-" + stamp + "-" + n++ + ".yml");
        try {
            java.nio.file.Files.copy(f.toPath(), out.toPath());
            return out;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not copy " + f.getName() + " to backups/: " + e.getMessage());
            return null;
        }
    }

    /** Deletes a shop: the file goes to backups/ first. Returns the backup, null if nothing was removed. */
    public File remove(MenuDef def) {
        if (def == null || def.builtin) return null;
        File backup = backup(def.file, "shops");
        if (backup == null) return null;
        for (MenuSession s : new ArrayList<>(open.values())) {
            if (s.def == def || s.def.id.equals(def.id)) dev.tmdevelopment.flaremobcoins.action.Actions.closeLater(s.player());
        }
        if (!def.file.delete()) {
            plugin.getLogger().warning("Could not delete " + def.file.getPath());
            return null;
        }
        shops.remove(def.id.toLowerCase(Locale.ROOT));
        registerCommands();
        return backup;
    }

    public Collection<String> names() {
        List<String> out = new ArrayList<>();
        for (MenuDef d : shops.values()) out.add(d.id());
        return out;
    }

    public int size() {
        return shops.size();
    }

    /** A shop. */
    public MenuDef get(String id) {
        return id == null ? null : shops.get(id.toLowerCase(Locale.ROOT));
    }

    /** A shop or a menu of the plugin. */
    public MenuDef def(String id) {
        if (id == null) return null;
        MenuDef d = shops.get(id.toLowerCase(Locale.ROOT));
        return d != null ? d : builtins.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<MenuDef> shops() {
        return Collections.unmodifiableCollection(shops.values());
    }

    public MenuDef builtin(String id) {
        return builtins.get(id);
    }

    public String mainShop() {
        return plugin.getConfig().getString("shop.main-shop", "main");
    }

    /** Time left of a rotating shop, "-" if it doesn't rotate. */
    public String timeLeft(String shop, boolean premium) {
        MenuDef d = get(shop);
        return d == null ? "-" : timeLeft(d, premium);
    }

    String timeLeft(MenuDef def, boolean premium) {
        return rotations.format(rotations.millisLeft(def, premium));
    }

    /** What normal players can use: config.yml > player-access and the permission (admins always). */
    public boolean canOpen(CommandSender sender, String feature) {
        if (sender.hasPermission("flaremobcoins.admin")) return true;
        if (!plugin.getConfig().getBoolean("player-access." + feature, true)) return false;
        return sender.hasPermission("flaremobcoins.command." + feature);
    }

    private static String accessOf(String id) {
        return id.equals(Builtins.MAIN) ? "menu" : id.equals(Builtins.TOP) ? "top" : id.equals(Builtins.DROPS) ? "drops" : "shop";
    }

    public boolean open(Player player, String id, boolean check) {
        return open(player, id, check, null);
    }

    /** Opens a menu. check = access, permission and open requirements (false when an admin opens it for someone). */
    public boolean open(Player player, String id, boolean check, MenuSession parent) {
        MenuDef def = def(id);
        if (def == null) {
            plugin.lang().send(player, "shop.not-found", "{shop}", id == null ? "" : id);
            return false;
        }
        if (def.builtin && def.id.equals(Builtins.CONFIRM)) return false;
        boolean admin = player.hasPermission("flaremobcoins.admin");
        if (!def.builtin && !plugin.getConfig().getBoolean("shop.enabled", true) && !admin) {
            plugin.lang().send(player, "shop.disabled");
            return false;
        }
        if (def.builtin && def.id.equals(Builtins.DROPS) && !admin && (!plugin.drops().enabled() || plugin.drops().size() == 0)) {
            plugin.lang().send(player, "drops.none");
            return false;
        }
        if (!def.enabled && !admin) {
            plugin.lang().send(player, !def.builtin ? "shop.disabled" : def.id.equals(Builtins.DROPS) ? "drops.menu-disabled" : "shop.menu-disabled");
            return false;
        }
        if (check && !gate(player, def)) return false;
        if (!def.builtin) rotations.update(def, true);
        MenuSession s = new MenuSession(def, player, parent);
        show(player, s);
        s.loadCounters();
        if (!def.openActions.isEmpty()) Actions.run(player, def.openActions, s.context());
        return true;
    }

    private boolean gate(Player player, MenuDef def) {
        Actions.Context ctx = new Actions.Context("shop " + def.id, null, "{menu}", def.id, "{shop}", def.id);
        if (!canOpen(player, def.builtin ? accessOf(def.id) : "shop")) {
            plugin.lang().send(player, "general.no-permission");
            return false;
        }
        if (def.permission != null && !player.hasPermission(def.permission) && !player.hasPermission("flaremobcoins.admin")) {
            if (def.denyActions.isEmpty()) plugin.lang().send(player, "general.no-permission");
            else Actions.run(player, def.denyActions, ctx);
            return false;
        }
        if (!Actions.check(player, def.openRequirements, ctx)) {
            if (def.denyActions.isEmpty()) plugin.lang().send(player, "shop.requirements");
            else Actions.run(player, def.denyActions, ctx);
            return false;
        }
        return true;
    }

    private void show(Player player, MenuSession s) {
        s.render();
        open.put(player.getUniqueId(), s);
        player.openInventory(s.getInventory());
        // the close event of the previous menu removed it
        open.put(player.getUniqueId(), s);
        Actions.sound(player, "sounds.open-shop");
    }

    public void openLater(Player player, String id, MenuSession parent) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) open(player, id, true, parent);
        });
    }

    /** Back to a menu: a new session if the file was reloaded in the meantime. */
    public MenuSession reopen(Player player, MenuSession s) {
        MenuDef now = def(s.def.id);
        if (now != null && now != s.def) {
            MenuSession fresh = new MenuSession(now, player, s.parent);
            show(player, fresh);
            fresh.loadCounters();
            return fresh;
        }
        show(player, s);
        s.loadCounters();
        return s;
    }

    boolean openConfirm(MenuSession from, MenuItem item, double price) {
        MenuDef confirm = builtins.get(Builtins.CONFIRM);
        if (confirm == null || !confirm.enabled) return false;
        MenuSession s = new MenuSession(confirm, from.player, from);
        s.pendingItem = item;
        s.pendingFrom = from;
        s.pendingPrice = price;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (from.player.isOnline()) show(from.player, s);
        });
        return true;
    }

    public boolean isOpen(Player player, MenuSession s) {
        return open.get(player.getUniqueId()) == s;
    }

    MenuSession session(Player player) {
        return open.get(player.getUniqueId());
    }

    void closed(Player player, MenuSession s) {
        open.remove(player.getUniqueId(), s);
    }

    public void renderAll() {
        for (MenuSession s : new ArrayList<>(open.values())) if (s.player().isOnline()) s.render();
    }

    public void closeAll() {
        for (MenuSession s : new ArrayList<>(open.values())) {
            try {
                s.player().closeInventory();
            } catch (Throwable ignored) {
            }
        }
        open.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = Compat.topHolder(p);
            if (h instanceof Gui || h instanceof EditorSession) p.closeInventory();
        }
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        if (rotationTicker != null) rotationTicker.cancel();
        closeAll();
        unregisterCommands();
    }

    // ------------------------------------------------------------------ open commands

    private void registerCommands() {
        unregisterCommands();
        Map<String, String> commands = new HashMap<>();
        Object main = plugin.getConfig().get("shop.open-command");
        List<String> mainCommands = main instanceof List ? plugin.getConfig().getStringList("shop.open-command")
                : main == null ? Collections.<String>emptyList() : Collections.singletonList(String.valueOf(main));
        for (String c : mainCommands) put(commands, c, mainShop());
        for (MenuDef d : shops.values()) for (String c : d.openCommands()) put(commands, c, d.id());
        for (MenuDef d : builtins.values()) for (String c : d.openCommands()) put(commands, c, d.id());
        active.clear();
        active.putAll(commands);
        if (commands.isEmpty()) {
            Compat.syncCommands();
            return;
        }
        for (Map.Entry<String, String> e : commands.entrySet()) {
            String name = e.getKey();
            String target = e.getValue();
            Command cmd = new Command(name, "Opens the " + target + " menu", "/" + name, new ArrayList<>()) {
                @Override
                public boolean execute(CommandSender sender, String label, String[] args) {
                    if (!(sender instanceof Player)) {
                        plugin.lang().send(sender, "general.players-only");
                        return true;
                    }
                    String now = active.get(name);
                    if (now == null) {
                        // the menu was removed: answer like an unknown command
                        sender.sendMessage(unknownCommand());
                        return true;
                    }
                    open((Player) sender, now, true);
                    return true;
                }
            };
            try {
                Compat.commandMap().register("flaremobcoins", cmd);
                registered.add(cmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not register /" + name + ": " + t.getMessage());
            }
        }
        Compat.syncCommands();
    }

    private static String unknownCommand() {
        try {
            String m = Bukkit.spigot().getConfig().getString("messages.unknown-command");
            if (m != null) return org.bukkit.ChatColor.translateAlternateColorCodes('&', m);
        } catch (Throwable ignored) {
        }
        return "Unknown command. Type \"/help\" for help.";
    }

    private static void put(Map<String, String> map, String command, String target) {
        if (command == null) return;
        String c = command.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) c = c.substring(1);
        if (!c.isEmpty() && !c.contains(" ")) map.putIfAbsent(c, target);
    }

    private void unregisterCommands() {
        for (Command c : registered) Compat.removeKnown(c);
        registered.clear();
    }
}
