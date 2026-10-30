package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.action.Actions;
import dev.tmdevelopment.flaremobcoins.data.Storage;
import dev.tmdevelopment.flaremobcoins.feature.Drops;
import dev.tmdevelopment.flaremobcoins.util.ItemSpec;
import dev.tmdevelopment.flaremobcoins.util.Materials;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import dev.tmdevelopment.flaremobcoins.util.Ver;
import net.flares.flaremobcoins.drops.EntityConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the elements of the menus of the plugin do (menus/mobcoins.yml, top.yml, drops.yml, confirm.yml). */
final class Builtins {

    static final String MAIN = "mobcoins";
    static final String TOP = "top";
    static final String DROPS = "drops";
    static final String CONFIRM = "confirm";

    private static final String HEAD_ALL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjRiNTRmM2U2YTcwMTZhMTRlNzZlZWRjNTMwNDBmNDk2MDU4MWJhY2FjODE0ZmZiNTgxNmZhZTFhMTRiMDE4ZCJ9fX0";
    private static final String HEAD_HOSTILE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzQwZWJhZjUxMTE3NzAwZWU5NjM3NGUxYjUzZjRkNDJmMjRiYmQyNzkwNTllNjUxMzk5YTBkNGVhYzdlOTczMiJ9fX0";
    private static final String HEAD_PASSIVE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmU2NGYzNDEyZmYwMzkxMWY4N2RiMGJkZjBkN2Q5YjZkYmUwMjgzNGFhMjM2MjQ5NGMyOTVlZGJkODUwIn19fQ";

    private Builtins() {
    }

    /** The "entries" element of top / drops: its slots are filled with the list. */
    static boolean isList(MenuSession s, MenuItem item) {
        return s.def.builtin && item.element && item.key.equals("entries")
                && (s.def.id.equals(TOP) || s.def.id.equals(DROPS));
    }

    static int pageSize(MenuSession s) {
        MenuItem entries = s.def.items.get("entries");
        return entries == null ? 0 : entries.slots.size();
    }

    static void drawList(MenuSession s, MenuItem item) {
        List<Integer> slots = item.slots;
        if (slots.isEmpty()) return;
        if (s.def.id.equals(TOP)) {
            FlareMobcoins plugin = FlareMobcoins.get();
            List<Storage.Account> top = s.topMode.equals("weekly") ? plugin.stats().weekly()
                    : s.topMode.equals("monthly") ? plugin.stats().monthly() : plugin.coins().top();
            String mode = s.def.text("modes." + s.topMode, s.topMode.equals("balance") ? "MobCoins" : "Earned");
            int n = Math.min(slots.size(), top.size());
            for (int i = 0; i < n; i++) {
                Storage.Account a = top.get(i);
                String name = a.name == null || a.name.isEmpty() ? "?" : a.name;
                String[] pairs = {"{position}", String.valueOf(i + 1), "{name}", name, "{amount}", Num.plain(a.mobcoins),
                        "{amount_commas}", Num.commas(a.mobcoins), "{amount_formatted}", Num.shortFormat(a.mobcoins), "{mode}", mode};
                s.set(slots.get(i), s.build(withMaterial(item, "head:" + name), slots.get(i), pairs), null);
            }
            if (top.isEmpty()) empty(s, s.topMode.equals("balance") ? "empty" : "period-empty", item);
            return;
        }
        List<Drops.Drop> drops = FlareMobcoins.get().drops().enabled()
                ? FlareMobcoins.get().drops().visible() : new ArrayList<>();
        int from = s.page * slots.size();
        for (int i = 0; i < slots.size() && from + i < drops.size(); i++) {
            Drops.Drop d = drops.get(from + i);
            s.set(slots.get(i), s.build(withMaterial(item, icon(d)), slots.get(i), dropPairs(s, d)), null);
        }
        if (drops.isEmpty()) empty(s, "empty", item);
    }

    private static String[] dropPairs(MenuSession s, Drops.Drop d) {
        String entities;
        switch (d.group()) {
            case ALL:
                entities = s.def.text("groups.all", "every mob");
                break;
            case HOSTILE:
                entities = s.def.text("groups.hostile", "hostile mobs");
                break;
            case PASSIVE:
                entities = s.def.text("groups.passive", "passive mobs");
                break;
            default: {
                List<String> names = new ArrayList<>();
                for (String e : d.entities()) names.add(pretty(e));
                for (String m : d.mythic()) names.add(Drops.pretty(m));
                if (d.anyMythic()) names.add(s.def.text("groups.mythic", "MythicMobs mobs"));
                entities = String.join(", ", names);
            }
        }
        double[] range = Num.range(d.amount());
        String value = range == null ? Num.plain(Num.roll(d.amount()))
                : s.def.text("range-format", "{min} - {max}").replace("{min}", Num.plain(range[0])).replace("{max}", Num.plain(range[1]));
        return new String[]{"{name}", pretty(d.key()), "{entities}", entities, "{chance}", Num.plain(d.chance()),
                "{value}", value, "{entity}", d.key()};
    }

    private static String icon(Drops.Drop d) {
        if (d.icon() != null && !d.icon().isEmpty()) return d.icon();
        if (d.group() == EntityConfiguration.EntityGroup.ALL) return "texture:" + HEAD_ALL;
        if (d.group() == EntityConfiguration.EntityGroup.HOSTILE) return "texture:" + HEAD_HOSTILE;
        if (d.group() == EntityConfiguration.EntityGroup.PASSIVE) return "texture:" + HEAD_PASSIVE;
        String first = d.entities().isEmpty() ? "" : d.entities().iterator().next();
        if (Ver.flat() && Materials.item(first + "_SPAWN_EGG", -1) != null) return first + "_SPAWN_EGG";
        return "texture:" + HEAD_ALL;
    }

    /** ZOMBIE_VILLAGER -> Zombie Villager */
    static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.replace('-', '_').split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** The material written in the file wins; "{material}" or none = the one of the entry. */
    private static MenuItem withMaterial(MenuItem item, String material) {
        if (material == null || !(item.spec.materialUnset() || "{material}".equals(item.spec.material()))) return item;
        MenuItem copy = new MenuItem();
        copy.key = item.key;
        copy.element = true;
        copy.spec = ItemSpec.simple(material, item.spec.name(), item.spec.lore());
        return copy;
    }

    private static void empty(MenuSession s, String path, MenuItem item) {
        String name = s.def.text(path + ".name", null);
        if (name == null) name = s.def.text("empty.name", null);
        if (name == null) return;
        ItemSpec spec = ItemSpec.simple(s.def.text(path + ".material", "BARRIER"), name, s.def.lines(path + ".lore"));
        s.set(center(item.slots), spec.build(t -> Placeholders.apply(t, s.player)), null);
    }

    /** The slot in the middle of the list area. */
    static int center(List<Integer> slots) {
        double row = 0, col = 0;
        for (int slot : slots) {
            row += slot / 9;
            col += slot % 9;
        }
        row /= slots.size();
        col /= slots.size();
        int best = slots.get(0);
        double distance = Double.MAX_VALUE;
        for (int slot : slots) {
            double d = Math.pow(slot / 9 - row, 2) + Math.pow(slot % 9 - col, 2);
            if (d < distance - 1e-9) {
                distance = d;
                best = slot;
            }
        }
        return best;
    }

    /** Elements without actions: the plugin knows what they do. */
    static boolean click(MenuSession s, MenuItem item, String click) {
        if (!item.element || !s.def.builtin) return false;
        if (!item.clickAny.isEmpty() || !item.clickBy.isEmpty()) return false;
        FlareMobcoins plugin = FlareMobcoins.get();
        MenuManager menus = plugin.menus();
        switch (item.key) {
            case "close":
                Actions.closeLater(s.player);
                return true;
            case "back":
                s.back();
                return true;
            case "shop":
                menus.openLater(s.player, menus.mainShop(), s);
                return true;
            case "drops":
                menus.openLater(s.player, DROPS, s);
                return true;
            case "top":
                menus.openLater(s.player, TOP, s);
                return true;
            case "all-time":
            case "weekly":
            case "monthly": {
                if (!s.def.id.equals(TOP)) return false;
                String mode = item.key.equals("all-time") ? "balance" : item.key;
                if (!mode.equals("balance") && !plugin.periodTops().enabled(mode)) return true;
                s.topMode = mode;
                // the list is read again (at most every 5 seconds)
                plugin.coins().refreshTopThen(() -> plugin.stats().refreshTops(Math.max(10, plugin.getConfig().getInt("top.size", 10)), () -> {
                    if (s.player.isOnline() && menus.isOpen(s.player, s)) s.render();
                }));
                s.render();
                return true;
            }
            case "stats":
                Actions.closeLater(s.player);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (s.player.isOnline()) s.player.performCommand("mobcoins stats");
                });
                return true;
            case "previous":
                if (s.page > 0) {
                    s.page--;
                    s.render();
                }
                return true;
            case "next":
                if ((s.page + 1) * pageSize(s) < plugin.drops().visible().size()) {
                    s.page++;
                    s.render();
                }
                return true;
            case "confirm":
                if (s.pendingItem != null && s.pendingFrom != null) {
                    MenuSession from = s.pendingFrom;
                    MenuItem product = s.pendingItem;
                    double price = s.pendingPrice;
                    s.pendingItem = null;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!s.player.isOnline()) return;
                        MenuSession back = menus.reopen(s.player, from);
                        MenuItem now = back.def.items.get(product.key);
                        if (now != null && now.product != null) Purchase.execute(back, now, price);
                    });
                }
                return true;
            case "cancel":
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!s.player.isOnline()) return;
                    if (s.pendingFrom != null) menus.reopen(s.player, s.pendingFrom);
                    else s.player.closeInventory();
                });
                return true;
            default:
                return false;
        }
    }

    /** confirm.yml: the "product" element shows the product being bought. top.yml: the shown list glows. */
    static boolean drawSpecial(MenuSession s, MenuItem item, int slot) {
        if (s.def.builtin && s.def.id.equals(TOP) && item.element && item.key.equals("stats")
                && item.clickAny.isEmpty() && item.clickBy.isEmpty() && !FlareMobcoins.get().menus().canOpen(s.player, "stats")) {
            // stats turned off for this player: no button
            s.set(slot, null, null);
            return true;
        }
        if (s.def.builtin && s.def.id.equals(TOP) && item.element
                && (item.key.equals("all-time") || item.key.equals("weekly") || item.key.equals("monthly"))) {
            String mode = item.key.equals("all-time") ? "balance" : item.key;
            if (!mode.equals("balance") && !FlareMobcoins.get().periodTops().enabled(mode)) {
                s.set(slot, null, null);
                return true;
            }
            ItemStack stack = s.build(item, slot, null);
            if (stack != null && mode.equals(s.topMode)) {
                org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    dev.tmdevelopment.flaremobcoins.util.Compat.glow(stack, meta);
                    stack.setItemMeta(meta);
                }
            }
            s.set(slot, stack, (session, click) -> session.clickItem(item, click));
            return true;
        }
        if (!s.def.builtin || !s.def.id.equals(CONFIRM) || !item.key.equals("product") || s.pendingItem == null) return false;
        ItemStack stack = s.pendingFrom.build(s.pendingItem, slot, null);
        s.set(slot, stack, null);
        return true;
    }
}
