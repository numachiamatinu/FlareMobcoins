package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /mobcoins editor: the menus of the plugin and every shop. Left click = edit, right click = open, shift + right = on / off. */
public final class LayoutListMenu extends Gui {

    private static final int[] SHOP_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40};
    private int page;

    public LayoutListMenu(FlareMobcoins plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return lang().raw("gui.menus.title");
    }

    @Override
    protected int rows() {
        return 6;
    }

    private String state(boolean on) {
        return lang().raw(on ? "gui.common.enabled" : "gui.common.disabled");
    }

    @Override
    protected void build() {
        int slot = 10;
        for (String id : MenuManager.BUILTIN) {
            MenuDef d = plugin.menus().builtin(id);
            if (d == null) continue;
            set(slot, button("KNOWLEDGE_BOOK", "gui.menus.builtin", "{menu}", id, "{title}", d.title,
                    "{description}", lang().raw("gui.menus.descriptions." + id), "{status}", state(d.enabled)), e -> click(d, e.isRightClick(), e.isShiftClick()));
            slot += 2;
        }
        List<MenuDef> shops = new ArrayList<>(plugin.menus().shops());
        int from = page * SHOP_SLOTS.length;
        for (int i = 0; i < SHOP_SLOTS.length && from + i < shops.size(); i++) {
            MenuDef d = shops.get(from + i);
            int products = 0;
            for (MenuItem item : d.items.values()) if (item.product != null) products++;
            boolean main = d.id.equalsIgnoreCase(plugin.menus().mainShop());
            int rotating = d.rotation == null ? 0 : d.rotation.normalPool.size() + d.rotation.premiumPool.size();
            set(SHOP_SLOTS[i], button(main ? "ENDER_CHEST" : "CHEST", d.rotation != null ? "gui.menus.rotating-shop" : "gui.menus.shop",
                    "{menu}", d.id, "{title}", d.title, "{items}", String.valueOf(d.items.size()), "{products}", String.valueOf(products),
                    "{rotating}", String.valueOf(rotating),
                    "{commands}", d.openCommands.isEmpty() ? "-" : "/" + String.join(", /", d.openCommands),
                    "{status}", state(d.enabled)), e -> click(d, e.isRightClick(), e.isShiftClick()));
        }
        if (page > 0) set(45, button("ARROW", "gui.common.previous"), e -> {
            page--;
            refresh();
        });
        if (from + SHOP_SLOTS.length < shops.size()) set(53, button("ARROW", "gui.common.next"), e -> {
            page++;
            refresh();
        });
        set(4, button("BOOK", "gui.menus.help"));
        set(42, button("EMERALD", "gui.menus.new"), e -> plugin.chatInput().ask(viewer, "editor.ask-new-menu", "", text -> {
            MenuDef d = plugin.menus().create(text);
            if (d == null) {
                lang().send(viewer, "editor.new-menu-taken", "{menu}", text);
                openLater();
            } else {
                lang().send(viewer, "editor.new-menu", "{menu}", d.id);
                new EditorSession(plugin, viewer, d).openLater();
            }
        }, this::openLater));
        set(49, button("ARROW", "gui.common.back"), e -> new SettingsMenu(plugin, viewer).openLater());
        fill();
    }

    private void click(MenuDef d, boolean right, boolean shift) {
        if (right && shift) {
            // on / off: the "enabled" line of the file
            if (d.broken) {
                lang().send(viewer, "editor.broken", "{menu}", d.id);
                return;
            }
            d.yaml.set("enabled", !d.enabled);
            plugin.menus().save(d);
            refresh();
            return;
        }
        if (right) {
            if (!(d.builtin && d.id.equals(Builtins.CONFIRM))) plugin.menus().openLater(viewer, d.id, null);
            return;
        }
        new EditorSession(plugin, viewer, d).openLater();
    }
}
