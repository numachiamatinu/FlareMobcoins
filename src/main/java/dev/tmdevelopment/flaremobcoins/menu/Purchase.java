package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buying a product: checks, confirm menu, limit and stock reserved in the storage (safe with several servers),
 * mobcoins taken, then the items, commands and actions of the product.
 */
final class Purchase {

    /** players with a purchase in progress (no double purchases) */
    private static final Set<UUID> busy = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Purchase() {
    }

    /** "shop.item" (limit and stock are counted per product). */
    static String key(MenuDef def, MenuItem item) {
        String k = def.id.toLowerCase(Locale.ROOT) + "." + item.key;
        return k.length() <= 100 ? k : k.substring(0, 80) + "#" + Integer.toHexString(k.hashCode());
    }

    /** never = "all", daily = "2026-10-25", weekly = "2026-W43", monthly = "2026-10" */
    static String period(String reset) {
        LocalDate d = LocalDate.now();
        switch (reset == null ? "never" : reset) {
            case "daily":
            case "day":
                return d.toString();
            case "weekly":
            case "week":
                return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "monthly":
            case "month":
                return d.getYear() + "-" + d.getMonthValue();
            default:
                return "all";
        }
    }

    static void start(MenuSession s, MenuItem item) {
        FlareMobcoins plugin = FlareMobcoins.get();
        Player p = s.player;
        MenuItem.Product product = item.product;
        if (product.permission != null && !p.hasPermission(product.permission)) {
            fail(s, "shop.no-permission-item", item);
            return;
        }
        if (item.rotating != null && s.menus().rotations().left(s.def, item, p) == 0) {
            fail(s, "shop.no-stock", item);
            return;
        }
        if (product.limit > 0 && s.bought.getOrDefault(item.key, 0) >= product.limit) {
            fail(s, "shop.limit-reached", item);
            return;
        }
        if (product.stock > 0 && s.sold.getOrDefault(item.key, 0) >= product.stock) {
            fail(s, "shop.sold-out", item);
            return;
        }
        double price = product.price;
        double balance = plugin.coins().balance(p.getUniqueId());
        if (balance + 0.0001 < price) {
            notEnough(s, item, price);
            return;
        }
        double confirmAbove = plugin.getConfig().getDouble("shop.confirm-above", 0);
        boolean confirm = product.confirm != null ? product.confirm : confirmAbove > 0 && price >= confirmAbove;
        if (confirm && s.menus().openConfirm(s, item, price)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) execute(s, item, -1);
        });
    }

    /** confirmedPrice: the price seen in the confirm menu (-1 = no confirm menu). */
    static void execute(MenuSession s, MenuItem item, double confirmedPrice) {
        FlareMobcoins plugin = FlareMobcoins.get();
        Player p = s.player;
        MenuItem.Product product = item.product;
        if (product.permission != null && !p.hasPermission(product.permission)) {
            fail(s, "shop.no-permission-item", item);
            return;
        }
        if (item.rotating != null && s.menus().rotations().left(s.def, item, p) == 0) {
            fail(s, "shop.no-stock", item);
            return;
        }
        if (!busy.add(p.getUniqueId())) return;
        double price = product.price;
        if (confirmedPrice >= 0 && price > confirmedPrice + 0.0001) {
            busy.remove(p.getUniqueId());
            fail(s, "shop.price-changed", item);
            return;
        }
        String key = key(s.def, item);
        String who = p.getUniqueId().toString();
        String limitPeriod = period(product.limitReset);
        String stockPeriod = period(product.stockReset);
        CompletableFuture<Boolean> limit = product.limit > 0
                ? plugin.coins().reserve(who, key, limitPeriod, product.limit) : CompletableFuture.completedFuture(true);
        limit.whenComplete((limitOk, limitError) -> {
            if (limitError != null || !Boolean.TRUE.equals(limitOk)) {
                busy.remove(p.getUniqueId());
                if (limitError == null) {
                    s.bought.put(item.key, product.limit);
                    fail(s, "shop.limit-reached", item);
                } else {
                    fail(s, "shop.error", item);
                }
                return;
            }
            CompletableFuture<Boolean> stock = product.stock > 0
                    ? plugin.coins().reserve("*", key, stockPeriod, product.stock) : CompletableFuture.completedFuture(true);
            stock.whenComplete((stockOk, stockError) -> {
                if (stockError != null || !Boolean.TRUE.equals(stockOk)) {
                    busy.remove(p.getUniqueId());
                    if (product.limit > 0) plugin.coins().release(who, key, limitPeriod);
                    if (stockError == null) {
                        s.sold.put(item.key, product.stock);
                        fail(s, "shop.sold-out", item);
                    } else {
                        fail(s, "shop.error", item);
                    }
                    return;
                }
                try {
                    if (!p.isOnline() || !plugin.coins().spend(p, price, "shop " + s.def.id + ": " + item.key)) {
                        if (product.limit > 0) plugin.coins().release(who, key, limitPeriod);
                        if (product.stock > 0) plugin.coins().release("*", key, stockPeriod);
                        if (p.isOnline()) notEnough(s, item, price);
                        return;
                    }
                    if (product.limit > 0) s.bought.merge(item.key, 1, Integer::sum);
                    if (product.stock > 0) s.sold.merge(item.key, 1, Integer::sum);
                    if (item.rotating != null) s.menus().rotations().consume(s.def, item, p);
                    deliver(s, item, price);
                } finally {
                    busy.remove(p.getUniqueId());
                    if (p.isOnline() && s.menus().isOpen(p, s)) s.render();
                }
            });
        });
    }

    private static void deliver(MenuSession s, MenuItem item, double price) {
        FlareMobcoins plugin = FlareMobcoins.get();
        Player p = s.player;
        MenuItem.Product product = item.product;
        String[] pairs = s.pairs(item, -1, new String[]{"{uuid}", p.getUniqueId().toString()});
        for (Object o : product.give) {
            ItemStack stack = o instanceof ItemStack ? ((ItemStack) o).clone() : ((ItemSpec) o).buildReal(t -> Placeholders.apply(t, p, pairs));
            if (stack == null) continue;
            for (ItemStack left : p.getInventory().addItem(stack).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        for (String raw : product.commands) {
            String cmd = Placeholders.apply(raw, p, pairs).trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isEmpty()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
        List<String> actions = MenuSession.actionsFor(item, "LEFT");
        if (!actions.isEmpty()) Actions.run(p, actions, new Actions.Context("shop " + s.def.id, s, pairs));
        plugin.lang().send(p, "shop.bought", "{product}", name(s, item), "{price}", Num.commas(price));
        Actions.sound(p, "sounds.buy");
    }

    private static void notEnough(MenuSession s, MenuItem item, double price) {
        FlareMobcoins plugin = FlareMobcoins.get();
        double balance = plugin.coins().balance(s.player.getUniqueId());
        plugin.lang().send(s.player, "shop.not-enough", "{price}", Num.commas(price), "{amount}", Num.commas(price),
                "{missing}", Num.commas(Math.max(0, price - balance)), "{product}", name(s, item));
        Actions.sound(s.player, "sounds.error");
    }

    private static void fail(MenuSession s, String message, MenuItem item) {
        FlareMobcoins plugin = FlareMobcoins.get();
        plugin.lang().send(s.player, message, "{product}", name(s, item));
        Actions.sound(s.player, "sounds.error");
        if (s.player.isOnline() && plugin.menus().isOpen(s.player, s)) s.render();
    }

    /** Name of the product for the messages (the key if it has no name). */
    static String name(MenuSession s, MenuItem item) {
        String n = item.spec.name();
        return n == null || Text.plain(n).trim().isEmpty() ? item.key : Placeholders.apply(n, s.player, "{menu}", s.def.id);
    }
}
