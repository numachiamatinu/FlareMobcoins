package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Shift + left click on a rotating slot of the editor: the items that take turns there, how often they change. */
final class RotationMenu extends Gui {

    private static final int PER_PAGE = 36;
    private final EditorSession editor;
    private final boolean premium;
    private int page;

    RotationMenu(FlareMobcoins plugin, Player viewer, EditorSession editor, boolean premium) {
        super(plugin, viewer);
        this.editor = editor;
        this.premium = premium;
    }

    private String type() {
        return premium ? "premium" : "normal";
    }

    @Override
    protected String title() {
        return lang().raw("gui.rotation.title").replace("{type}", lang().raw("gui.product.rotations." + type()));
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void build() {
        editor.reloadDef();
        MenuDef def = editor.def;
        if (def.rotation == null) {
            editor.openLater();
            return;
        }
        List<MenuItem> pool = new ArrayList<>(def.rotation.pool(premium).values());
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < pool.size(); i++) {
            MenuItem item = pool.get(from + i);
            set(i, entry(item), e -> {
                if (e.isShiftClick() && e.isRightClick()) {
                    editor.reloadDef();
                    editor.def.yaml.set("items." + item.key + ".rotating_shop", null);
                    editor.def = plugin.menus().save(editor.def);
                    lang().send(viewer, "editor.rotation-removed");
                    refresh();
                } else {
                    new ProductMenu(plugin, viewer, editor, item.key, this::openLater).openLater();
                }
            });
        }
        if (page > 0) set(45, button("ARROW", "gui.common.previous"), e -> {
            page--;
            refresh();
        });
        if (from + PER_PAGE < pool.size()) set(53, button("ARROW", "gui.common.next"), e -> {
            page++;
            refresh();
        });
        long every = def.rotation.refresh(premium);
        set(47, value("CLOCK", "gui.rotation.every", plugin.menus().rotations().format(every * 1000L)), e ->
                plugin.chatInput().ask(viewer, "editor.ask-rotation-time", String.valueOf(every), text -> {
                    long seconds = Rotations.seconds(text);
                    if (seconds <= 0) {
                        lang().send(viewer, "general.invalid-amount", "{input}", text);
                    } else {
                        editor.reloadDef();
                        editor.def.yaml.set("rotating_shop." + type() + "_refresh", seconds);
                        editor.def = plugin.menus().save(editor.def);
                    }
                    openLater();
                }, this::openLater));
        set(48, button("EMERALD", "gui.rotation.add-hand"), e -> {
            ItemStack hand = Compat.hand(viewer);
            if (hand == null || hand.getType().name().endsWith("AIR")) {
                lang().send(viewer, "editor.empty-hand");
                return;
            }
            ItemStack item = EditorSession.clean(hand);
            plugin.chatInput().ask(viewer, "editor.ask-price", "", text -> {
                double price = Num.parse(text);
                if (price < 0) {
                    lang().send(viewer, "general.invalid-amount", "{input}", text);
                } else {
                    editor.reloadDef();
                    String key = editor.createProduct(item, price);
                    editor.def.yaml.set("items." + key + ".rotating_shop.type", type());
                    editor.def.yaml.set("items." + key + ".rotating_shop.stock_type", "SERVER");
                    editor.def.yaml.set("items." + key + ".rotating_shop.stock_amount", 0);
                    editor.def = plugin.menus().save(editor.def);
                    lang().send(viewer, "editor.product-created", "{price}", Num.commas(price));
                }
                openLater();
            }, this::openLater);
        });
        set(50, button("NETHER_STAR", "gui.rotation.now", "{slots}", String.valueOf(def.rotation.slots(premium).size())), e -> {
            editor.reloadDef();
            plugin.menus().rotations().rotateNow(editor.def, premium);
            plugin.menus().renderAll();
            lang().send(viewer, "editor.rotated");
        });
        set(46, button("BOOK", "gui.rotation.help", "{items}", String.valueOf(pool.size()),
                "{slots}", String.valueOf(def.rotation.slots(premium).size())));
        set(49, button("ARROW", "gui.common.back"), e -> editor.openLater());
        fill();
    }

    private ItemStack entry(MenuItem item) {
        ItemStack stack = item.exact != null && item.spec.materialUnset() ? item.exact.clone()
                : item.spec.build(s -> Placeholders.apply(s, viewer, "{stock}", "?", "{max_stock}", "?"));
        if (stack == null) stack = new ItemStack(org.bukkit.Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String price = item.product == null ? "-" : Num.commas(item.product.price);
        String stock = item.rotationStock <= 0 ? "-" : String.valueOf(item.rotationStock);
        for (String l : lang().lines("gui.rotation.entry-lore")) {
            lore.add(Text.item(Placeholders.apply(l, viewer, "{price}", price, "{stock}", stock,
                    "{type}", lang().raw(item.perPlayer ? "gui.product.stock-types.player" : "gui.product.stock-types.server"))));
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}
