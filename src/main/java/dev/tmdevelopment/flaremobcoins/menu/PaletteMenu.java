package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Num;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shift + right click on an empty slot of the editor: what goes on that slot. */
final class PaletteMenu extends Gui {

    private static final String[] GLASS = {"BLACK", "GRAY", "LIGHT_GRAY", "WHITE", "RED", "ORANGE", "YELLOW", "LIME",
            "LIGHT_BLUE", "PURPLE", "PINK"};
    private final EditorSession editor;
    private final int target;

    PaletteMenu(FlareMobcoins plugin, Player viewer, EditorSession editor, int target) {
        super(plugin, viewer);
        this.editor = editor;
        this.target = target;
    }

    @Override
    protected String title() {
        return lang().raw("gui.palette.title").replace("{slot}", String.valueOf(target));
    }

    @Override
    protected int rows() {
        return 6;
    }

    private void back() {
        editor.openLater();
    }

    @Override
    protected void build() {
        editor.reloadDef();
        MenuDef def = editor.def;
        if (def.builtin) {
            int slot = 10;
            for (MenuItem item : def.items.values()) {
                if (!item.element) continue;
                if (slot == 17) slot = 19;
                if (slot >= 26) break;
                String material = item.spec.materialUnset() ? "PAPER" : item.spec.material();
                if (material.contains(":") || material.startsWith("[") || material.contains("{")) material = "PLAYER_HEAD";
                set(slot++, item(material, "&f" + item.key, lang().lines("gui.palette.element.lore"), item.slots.isEmpty(),
                        "{amount}", String.valueOf(item.slots.size())), e -> {
                    editor.clearSlot(target);
                    ConfigurationSection s = editor.section(item.key);
                    if (s != null) {
                        List<Integer> slots = new ArrayList<>(item.slots);
                        slots.add(target);
                        s.set("slot", null);
                        s.set("slots", MenuDef.formatSlots(slots));
                    }
                    editor.def = plugin.menus().save(editor.def);
                    back();
                });
            }
        }
        int slot = 28;
        for (String color : GLASS) {
            if (slot == 35) slot = 37;
            String material = color + "_STAINED_GLASS_PANE";
            set(slot++, item(material, lang().raw("gui.palette.glass.name"), lang().lines("gui.palette.glass.lore"), false), e -> {
                editor.addDecoration(target, deco(material, " ", new ArrayList<>(), new ArrayList<>()));
                back();
            });
        }
        set(46, button("BOOK", "gui.palette.link"), e -> new MenuPicker(plugin, viewer, editor, target).openLater());
        set(47, button("ARROW", "gui.palette.back-button"), e -> {
            editor.addDecoration(target, deco("ARROW", lang().raw("gui.palette.back-button.item"), new ArrayList<>(),
                    new ArrayList<>(Arrays.asList("[back]"))));
            back();
        });
        set(48, button("BARRIER", "gui.palette.close-button"), e -> {
            editor.addDecoration(target, deco("BARRIER", lang().raw("gui.palette.close-button.item"), new ArrayList<>(),
                    new ArrayList<>(Arrays.asList("[close]"))));
            back();
        });
        set(50, button("PLAYER_HEAD", "gui.palette.balance"), e -> {
            editor.addDecoration(target, deco("head:{player}", lang().raw("gui.palette.balance.item"),
                    lang().lines("gui.palette.balance.item-lore"), new ArrayList<>()));
            back();
        });
        if (!def.builtin) {
            set(51, button("EMERALD", "gui.palette.sell-hand"), e -> {
                ItemStack hand = Compat.hand(viewer);
                if (hand == null || hand.getType().name().endsWith("AIR")) {
                    lang().send(viewer, "editor.empty-hand");
                    return;
                }
                ItemStack item = EditorSession.clean(hand);
                plugin.chatInput().ask(viewer, "editor.ask-price", "", text -> {
                    double price = Num.parse(text);
                    if (price < 0) lang().send(viewer, "general.invalid-amount", "{input}", text);
                    else editor.sell(target, item, price);
                    back();
                }, this::back);
            });
            set(52, button("LIME_STAINED_GLASS_PANE", "gui.palette.rotating-normal"), e -> {
                editor.addRotatingSlot(target, false);
                back();
            });
            set(43, button("MAGENTA_STAINED_GLASS_PANE", "gui.palette.rotating-premium"), e -> {
                editor.addRotatingSlot(target, true);
                back();
            });
        }
        set(4, button("BOOK", "gui.palette.help"));
        set(53, button("ARROW", "gui.common.back"), e -> back());
        fill();
    }

    static Map<String, Object> deco(String material, String name, List<String> lore, List<String> actions) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("material", material);
        d.put("name", name);
        d.put("lore", lore);
        d.put("glow", false);
        d.put("admin-only", false);
        d.put("actions", actions);
        return d;
    }

    /** "Button to another menu": every shop and menu of the plugin. */
    static final class MenuPicker extends Gui {

        private final EditorSession editor;
        private final int target;

        MenuPicker(FlareMobcoins plugin, Player viewer, EditorSession editor, int target) {
            super(plugin, viewer);
            this.editor = editor;
            this.target = target;
        }

        @Override
        protected String title() {
            return lang().raw("gui.picker.title");
        }

        @Override
        protected int rows() {
            return 6;
        }

        @Override
        protected void build() {
            List<MenuDef> all = new ArrayList<>(plugin.menus().shops());
            for (String id : MenuManager.BUILTIN) {
                if (id.equals(Builtins.CONFIRM)) continue;
                MenuDef d = plugin.menus().builtin(id);
                if (d != null) all.add(d);
            }
            int slot = 0;
            for (MenuDef d : all) {
                if (slot >= 45) break;
                if (d.id.equalsIgnoreCase(editor.def.id)) continue;
                String title = d.title;
                set(slot++, item(d.builtin ? "KNOWLEDGE_BOOK" : "CHEST", "&f" + d.id, lang().lines("gui.picker.entry.lore"), false,
                        "{title}", title), e -> {
                    List<String> lore = new ArrayList<>(lang().lines("gui.picker.button-lore"));
                    editor.addDecoration(target, deco(d.builtin ? "KNOWLEDGE_BOOK" : "CHEST", title, lore,
                            new ArrayList<>(Arrays.asList("[open] " + d.id))));
                    editor.openLater();
                });
            }
            set(49, button("ARROW", "gui.common.back"), e -> new PaletteMenu(plugin, viewer, editor, target).openLater());
            fill();
        }
    }
}
