package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.config.ConfigWriter;
import dev.tmdevelopment.flaremobcoins.util.Num;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** /mobcoins settings: every option of config.yml and drops.yml in a menu, saved in the files (comments are kept). */
public final class SettingsMenu extends Gui {

    public SettingsMenu(FlareMobcoins plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return lang().raw("gui.settings.title");
    }

    @Override
    protected int rows() {
        return 5;
    }

    private void flip(String file, String path, boolean now) {
        if (ConfigWriter.set(plugin, file, path, !now)) {
            if (file.equals("drops.yml")) plugin.drops().load();
            else plugin.applyConfig();
            lang().send(viewer, "settings.changed", "{option}", path, "{value}", lang().raw(!now ? "gui.common.enabled" : "gui.common.disabled"));
        }
        refresh();
    }

    private void number(String path, String message, boolean decimals) {
        double now = plugin.getConfig().getDouble(path, 0);
        plugin.chatInput().ask(viewer, message, Num.plain(now), text -> {
            double v = Num.parse(text);
            if (v < 0) {
                lang().send(viewer, "general.invalid-amount", "{input}", text);
            } else {
                Object value = decimals ? (Object) Num.round(v) : (Object) (long) v;
                if (ConfigWriter.set(plugin, "config.yml", path, value)) {
                    plugin.applyConfig();
                    lang().send(viewer, "settings.changed", "{option}", path, "{value}", String.valueOf(value));
                }
            }
            openLater();
        }, this::openLater);
    }

    private boolean on(String path) {
        return plugin.getConfig().getBoolean(path, true);
    }

    @Override
    protected void build() {
        set(4, button("NETHER_STAR", "gui.settings.info", "{version}", plugin.getDescription().getVersion(),
                "{storage}", plugin.coins().storage().type(), "{accounts}", String.valueOf(plugin.coins().accounts()),
                "{shops}", String.valueOf(plugin.menus().size()), "{drops}", String.valueOf(plugin.drops().size())));

        boolean drops = plugin.drops().enabled();
        set(10, toggle("ROTTEN_FLESH", "gui.settings.drops", drops), e -> flip("drops.yml", "enabled", drops));
        boolean spawners = plugin.drops().ignoreSpawners();
        set(11, toggle("SPAWNER", "gui.settings.spawner-mobs", spawners), e -> flip("drops.yml", "ignore-spawner-mobs", spawners));
        boolean ground = plugin.getConfig().getBoolean("coins-on-ground.enabled", false);
        set(12, toggle("GOLD_NUGGET", "gui.settings.coins-on-ground", ground), e -> flip("config.yml", "coins-on-ground.enabled", ground));
        boolean killer = on("coins-on-ground.only-killer");
        set(13, toggle("IRON_SWORD", "gui.settings.only-killer", killer), e -> flip("config.yml", "coins-on-ground.only-killer", killer));
        boolean pay = on("pay.enabled");
        set(14, toggle("EMERALD", "gui.settings.pay", pay), e -> flip("config.yml", "pay.enabled", pay));
        boolean offline = on("pay.allow-offline");
        set(15, toggle("ENDER_PEARL", "gui.settings.pay-offline", offline), e -> flip("config.yml", "pay.allow-offline", offline));
        boolean shop = on("shop.enabled");
        set(16, toggle("CHEST", "gui.settings.shop", shop), e -> flip("config.yml", "shop.enabled", shop));

        boolean sounds = on("sounds.enabled");
        set(19, toggle("NOTE_BLOCK", "gui.settings.sounds", sounds), e -> flip("config.yml", "sounds.enabled", sounds));
        boolean updates = on("update-check");
        set(20, toggle("COMPASS", "gui.settings.update-check", updates), e -> flip("config.yml", "update-check", updates));
        set(21, value("SUNFLOWER", "gui.settings.first-join", Num.commas(plugin.getConfig().getDouble("first-join-mobcoins", 0))),
                e -> number("first-join-mobcoins", "settings.ask-first-join", true));
        set(22, value("GOLD_INGOT", "gui.settings.pay-min", Num.commas(plugin.getConfig().getDouble("pay.min-amount", 1))),
                e -> number("pay.min-amount", "settings.ask-pay-min", true));
        set(23, value("CLOCK", "gui.settings.pay-cooldown", String.valueOf(plugin.getConfig().getLong("pay.cooldown-seconds", 0))),
                e -> number("pay.cooldown-seconds", "settings.ask-pay-cooldown", false));
        set(24, value("LIME_STAINED_GLASS_PANE", "gui.settings.confirm-above", Num.commas(plugin.getConfig().getDouble("shop.confirm-above", 0))),
                e -> number("shop.confirm-above", "settings.ask-confirm-above", true));
        double global = plugin.coins().globalMultiplier();
        set(25, value("EXPERIENCE_BOTTLE", "gui.settings.global-multiplier", "x" + Num.plain(global)),
                e -> plugin.chatInput().ask(viewer, "settings.ask-global-multiplier", Num.plain(global), text -> {
                    double v = Num.parseDouble(text.replace("x", ""), -1);
                    if (v <= 0) lang().send(viewer, "multiplier.invalid", "{input}", text);
                    else {
                        plugin.coins().setGlobalMultiplier(v);
                        lang().send(viewer, "multiplier.global", "{value}", Num.plain(v));
                    }
                    openLater();
                }, this::openLater));

        set(28, value("ENDER_CHEST", "gui.settings.main-shop", plugin.menus().mainShop()),
                e -> plugin.chatInput().ask(viewer, "settings.ask-main-shop", plugin.menus().mainShop(), text -> {
                    String name = text.trim();
                    if (plugin.menus().get(name) == null) lang().send(viewer, "shop.not-found", "{shop}", name);
                    else if (ConfigWriter.set(plugin, "config.yml", "shop.main-shop", name)) plugin.applyConfig();
                    openLater();
                }, this::openLater));
        set(30, button("PAINTING", "gui.settings.menus", "{shops}", String.valueOf(plugin.menus().size())),
                e -> new LayoutListMenu(plugin, viewer).openLater());
        set(31, button("IRON_DOOR", "gui.settings.access"), e -> new AccessMenu(plugin, viewer).openLater());
        set(32, button("KNOWLEDGE_BOOK", "gui.settings.top"), e -> plugin.menus().openLater(viewer, Builtins.TOP, null));
        set(34, button("ZOMBIE_HEAD", "gui.settings.drops-menu"), e -> plugin.menus().openLater(viewer, Builtins.DROPS, null));
        set(40, button("LIME_DYE", "gui.settings.reload"), e -> Bukkit.getScheduler().runTask(plugin, () -> {
            viewer.closeInventory();
            plugin.reloadAll();
            lang().send(viewer, "general.reloaded", "{shops}", String.valueOf(plugin.menus().size()),
                    "{drops}", String.valueOf(plugin.drops().size()));
        }));
        set(44, button("BARRIER", "gui.common.close"), e -> Bukkit.getScheduler().runTask(plugin, viewer::closeInventory));
        fill();
    }

    /** What normal players can use (config.yml > player-access). Admins can always use everything. */
    static final class AccessMenu extends Gui {

        private static final String[][] KEYS = {{"menu", "SUNFLOWER"}, {"shop", "CHEST"}, {"drops", "ROTTEN_FLESH"},
                {"top", "KNOWLEDGE_BOOK"}, {"pay", "GOLD_INGOT"}};

        AccessMenu(FlareMobcoins plugin, Player viewer) {
            super(plugin, viewer);
        }

        @Override
        protected String title() {
            return lang().raw("gui.access.title");
        }

        @Override
        protected int rows() {
            return 3;
        }

        @Override
        protected void build() {
            int[] slots = {11, 12, 13, 14, 15};
            for (int i = 0; i < KEYS.length; i++) {
                String path = "player-access." + KEYS[i][0];
                boolean on = plugin.getConfig().getBoolean(path, true);
                set(slots[i], toggle(KEYS[i][1], "gui.access." + KEYS[i][0], on), e -> {
                    if (ConfigWriter.set(plugin, "config.yml", path, !on)) plugin.applyConfig();
                    refresh();
                });
            }
            set(22, button("ARROW", "gui.common.back"), e -> new SettingsMenu(plugin, viewer).openLater());
            fill();
        }
    }
}
