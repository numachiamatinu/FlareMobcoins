package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Num;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Shift + left click on an item of the editor: price, limit, stock, rotation, name, commands, items given. */
final class ProductMenu extends Gui {

    private static final String[] RESETS = {"never", "daily", "weekly", "monthly"};
    private static final String[] ROTATIONS = {"none", "normal", "premium"};
    private final EditorSession editor;
    private final String key;
    /** where "Back" goes: the editor, or the rotating items list */
    private final Runnable back;

    ProductMenu(FlareMobcoins plugin, Player viewer, EditorSession editor, String key) {
        this(plugin, viewer, editor, key, null);
    }

    ProductMenu(FlareMobcoins plugin, Player viewer, EditorSession editor, String key, Runnable back) {
        super(plugin, viewer);
        this.editor = editor;
        this.key = key;
        this.back = back != null ? back : editor::openLater;
    }

    @Override
    protected String title() {
        return lang().raw("gui.product.title").replace("{item}", key);
    }

    @Override
    protected int rows() {
        return 5;
    }

    private ConfigurationSection section() {
        return editor.section(key);
    }

    /** The section of the file as it is now (the editor saves when it closes, after this menu was built). */
    private ConfigurationSection live() {
        editor.reloadDef();
        ConfigurationSection s = editor.section(key);
        return s != null ? s : editor.def.yaml.createSection("items." + key);
    }

    private void saveAndRefresh() {
        editor.def = plugin.menus().save(editor.def);
        openLater();
    }

    private void ask(String message, String current, Consumer<String> answer) {
        plugin.chatInput().ask(viewer, message, current, text -> {
            answer.accept(text);
            saveAndRefresh();
        }, this::openLater);
    }

    private static String next(String[] values, String now) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(now)) return values[(i + 1) % values.length];
        return values[0];
    }

    private String resetName(String reset) {
        return lang().raw("gui.product.resets." + reset);
    }

    @Override
    protected void build() {
        editor.reloadDef();
        ConfigurationSection s = section();
        if (s == null) {
            back.run();
            return;
        }
        boolean forSale = s.isSet("price");
        double price = s.getDouble("price", 0);
        set(4, button("NAME_TAG", "gui.product.info", "{item}", key, "{name}", String.valueOf(s.getString("name", key)),
                "{state}", lang().raw(forSale ? "gui.product.for-sale" : "gui.product.not-for-sale")));

        set(10, value("GOLD_INGOT", "gui.product.price", forSale ? Num.commas(price) : "-"), e -> {
            if (e.isRightClick() && forSale) {
                live().set("price", null);
                saveAndRefresh();
                return;
            }
            ask("editor.ask-price", forSale ? Num.plain(price) : "", text -> {
                double v = Num.parse(text);
                if (v < 0) {
                    lang().send(viewer, "general.invalid-amount", "{input}", text);
                    return;
                }
                live().set("price", EditorSession.number(v));
                // a 3.x item paid with [buy(mobcoins)]: now the price does it, so it is not paid twice
                for (String list : new String[]{"actions", "click_commands", "click_actions"}) {
                    if (!live().isList(list)) continue;
                    List<String> lines = new ArrayList<>(live().getStringList(list));
                    if (lines.removeIf(l -> l.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\[(left|right)]\\s*", "").startsWith("[buy("))) {
                        live().set(list, lines);
                    }
                }
            });
        });

        boolean confirm = s.getBoolean("confirm", false);
        set(11, toggle("LIME_DYE", "gui.product.confirm", confirm), e -> {
            live().set("confirm", !confirm);
            saveAndRefresh();
        });

        int limit = s.getInt("limit", 0);
        String limitReset = s.getString("limit-reset", "never").toLowerCase(Locale.ROOT);
        set(12, value("HOPPER", "gui.product.limit", limit <= 0 ? "-" : String.valueOf(limit), "{reset}", resetName(limitReset)), e -> {
            if (e.isRightClick()) {
                live().set("limit-reset", next(RESETS, limitReset));
                saveAndRefresh();
            } else {
                ask("editor.ask-limit", String.valueOf(limit), t -> live().set("limit", (int) Math.max(0, Num.parse(t))));
            }
        });

        int stock = s.getInt("stock", 0);
        String stockReset = s.getString("stock-reset", "never").toLowerCase(Locale.ROOT);
        set(13, value("CHEST", "gui.product.stock", stock <= 0 ? "-" : String.valueOf(stock), "{reset}", resetName(stockReset)), e -> {
            if (e.isRightClick()) {
                live().set("stock-reset", next(RESETS, stockReset));
                saveAndRefresh();
            } else {
                ask("editor.ask-stock", String.valueOf(stock), t -> live().set("stock", (int) Math.max(0, Num.parse(t))));
            }
        });

        String permission = s.getString("permission", "");
        set(14, value("TRIPWIRE_HOOK", "gui.product.permission", permission == null || permission.isEmpty() ? "-" : permission), e -> {
            if (e.isRightClick()) {
                live().set("permission", "");
                saveAndRefresh();
            } else {
                ask("editor.ask-permission", permission, t -> live().set("permission", t.equalsIgnoreCase("none") ? "" : t.trim()));
            }
        });

        boolean adminOnly = s.getBoolean("admin-only", false);
        set(15, toggle("REDSTONE_TORCH", "gui.product.admin-only", adminOnly), e -> {
            live().set("admin-only", !adminOnly);
            saveAndRefresh();
        });

        String rotation = s.isConfigurationSection("rotating_shop")
                ? s.getString("rotating_shop.type", "normal").toLowerCase(Locale.ROOT) : "none";
        boolean shopRotates = editor.def.rotation != null;
        set(16, value("CLOCK", shopRotates ? "gui.product.rotation" : "gui.product.rotation-off",
                lang().raw("gui.product.rotations." + rotation)), e -> {
            if (!shopRotates) {
                lang().send(viewer, "editor.no-rotation");
                return;
            }
            String now = next(ROTATIONS, rotation);
            if (now.equals("none")) {
                live().set("rotating_shop", null);
                lang().send(viewer, "editor.rotation-removed");
            } else {
                live().set("rotating_shop.type", now);
                if (!live().isSet("rotating_shop.stock_type")) live().set("rotating_shop.stock_type", "SERVER");
                if (!live().isSet("rotating_shop.stock_amount")) live().set("rotating_shop.stock_amount", 0);
            }
            saveAndRefresh();
        });

        set(20, value("OAK_SIGN", "gui.product.name", String.valueOf(s.getString("name", s.getString("display_name", "")))),
                e -> ask("editor.ask-name", s.getString("name", s.getString("display_name", "")), t -> {
                    if (live().isSet("display_name")) live().set("display_name", t);
                    else live().set("name", t);
                }));

        List<String> commands = new ArrayList<>(s.getStringList("commands"));
        set(21, value("COMMAND_BLOCK", "gui.product.commands", String.valueOf(commands.size()),
                "{last}", commands.isEmpty() ? "-" : commands.get(commands.size() - 1)), e -> {
            if (e.isRightClick()) {
                if (!commands.isEmpty()) commands.remove(commands.size() - 1);
                live().set("commands", commands);
                saveAndRefresh();
                return;
            }
            ask("editor.ask-command", "", t -> {
                String cmd = t.trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                commands.add(cmd);
                live().set("commands", commands);
            });
        });

        List<?> raw = s.getList("give");
        List<Object> give = raw == null ? new ArrayList<>() : new ArrayList<>(raw);
        set(22, value("CHEST_MINECART", "gui.product.give", String.valueOf(give.size())), e -> {
            if (e.isRightClick()) {
                if (!give.isEmpty()) give.remove(give.size() - 1);
                live().set("give", give);
                saveAndRefresh();
                return;
            }
            ItemStack hand = Compat.hand(viewer);
            if (hand == null || hand.getType().name().endsWith("AIR")) {
                lang().send(viewer, "editor.empty-hand");
                return;
            }
            give.add(EditorSession.clean(hand));
            live().set("give", give);
            saveAndRefresh();
        });

        if (!rotation.equals("none")) {
            int amount = s.getInt("rotating_shop.stock_amount", 0);
            boolean perPlayer = "PLAYER".equalsIgnoreCase(s.getString("rotating_shop.stock_type", "SERVER"));
            set(23, value("BARREL", "gui.product.rotation-stock", amount <= 0 ? "-" : String.valueOf(amount),
                    "{type}", lang().raw(perPlayer ? "gui.product.stock-types.player" : "gui.product.stock-types.server")), e -> {
                if (e.isRightClick()) {
                    live().set("rotating_shop.stock_type", perPlayer ? "SERVER" : "PLAYER");
                    saveAndRefresh();
                } else {
                    ask("editor.ask-rotation-stock", String.valueOf(amount),
                            t -> live().set("rotating_shop.stock_amount", (int) Math.max(0, Num.parse(t))));
                }
            });
        }

        set(24, button("BARRIER", "gui.product.delete"), e -> {
            if (!e.isShiftClick()) {
                lang().send(viewer, "editor.delete-shift");
                return;
            }
            editor.def.yaml.set("items." + key, null);
            editor.def = plugin.menus().save(editor.def);
            lang().send(viewer, "editor.deleted", "{item}", key);
            back.run();
        });
        set(40, button("ARROW", "gui.common.back"), e -> back.run());
        fill();
    }
}
