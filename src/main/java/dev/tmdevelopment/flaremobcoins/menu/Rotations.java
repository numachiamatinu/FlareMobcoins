package dev.tmdevelopment.flaremobcoins.menu;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.config.Lang;
import dev.tmdevelopment.flaremobcoins.data.StateFile;
import dev.tmdevelopment.flaremobcoins.util.Placeholders;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Rotating shops: which items are in the rotating slots, their stock and when they change.
 * Saved in data/state.yml (rotating.&lt;shop&gt;.normal / .premium), so a restart keeps the same items.
 */
final class Rotations {

    private final FlareMobcoins plugin;
    private final StateFile state;

    Rotations(FlareMobcoins plugin, StateFile state) {
        this.plugin = plugin;
        this.state = state;
    }

    private String path(MenuDef menu, boolean premium) {
        return "rotating." + menu.id.toLowerCase() + "." + (premium ? "premium" : "normal");
    }

    /**
     * Rotates the items whose time is over (or that don't match the file anymore).
     * @return true if something changed (open menus must be redrawn)
     */
    boolean update(MenuDef menu, boolean announce) {
        if (menu.rotation == null) return false;
        boolean changed = false;
        for (boolean premium : new boolean[]{false, true}) {
            List<Integer> slots = menu.rotation.slots(premium);
            Map<String, MenuItem> pool = menu.rotation.pool(premium);
            String base = path(menu, premium);
            long last = state.yaml().getLong(base + ".last", 0);
            long now = System.currentTimeMillis();
            boolean expired = last <= 0 || now >= last + menu.rotation.refresh(premium) * 1000L;
            if (!expired && valid(base, slots, pool)) continue;
            if (slots.isEmpty() || pool.isEmpty()) {
                if (state.yaml().isSet(base)) {
                    state.yaml().set(base, null);
                    state.save();
                    changed = true;
                }
                continue;
            }
            roll(base, slots, pool, now);
            changed = true;
            if (announce && last > 0 && expired) {
                String msg = plugin.lang().raw("shop.rotating-reset." + (premium ? "premium" : "normal"));
                if (!msg.isEmpty() && !msg.equalsIgnoreCase("disabled")) {
                    Lang.broadcast(Placeholders.apply(msg, null, "{shop}", menu.id, "{shop_name}", menu.id));
                }
            }
        }
        return changed;
    }

    /** The saved items still exist in the file and fill the slots of the file. */
    private boolean valid(String base, List<Integer> slots, Map<String, MenuItem> pool) {
        ConfigurationSection sec = state.yaml().getConfigurationSection(base + ".slots");
        if (sec == null) return slots.isEmpty() || pool.isEmpty();
        int expected = Math.min(slots.size(), pool.size());
        int found = 0;
        for (String k : sec.getKeys(false)) {
            int slot;
            try {
                slot = Integer.parseInt(k);
            } catch (NumberFormatException e) {
                return false;
            }
            if (!slots.contains(slot) || !pool.containsKey(sec.getString(k))) return false;
            found++;
        }
        return found == expected;
    }

    private void roll(String base, List<Integer> slots, Map<String, MenuItem> pool, long now) {
        List<String> keys = new ArrayList<>(pool.keySet());
        Collections.shuffle(keys);
        state.yaml().set(base, null);
        state.yaml().set(base + ".last", now);
        for (int i = 0; i < slots.size() && i < keys.size(); i++) {
            String key = keys.get(i);
            state.yaml().set(base + ".slots." + slots.get(i), key);
            MenuItem item = pool.get(key);
            if (!item.perPlayer && item.rotationStock > 0) state.yaml().set(base + ".stock." + key, item.rotationStock);
        }
        state.save();
    }

    /** New items right now (from the editor). */
    void rotateNow(MenuDef menu, boolean premium) {
        state.yaml().set(path(menu, premium) + ".last", 0);
        state.save();
        update(menu, false);
    }

    /** "30m", "6h", "1d", "1d 12h" or seconds. -1 if it is not a time. */
    static long seconds(String text) {
        String t = text.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (t.matches("\\d+")) return Long.parseLong(t);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([smhdw])").matcher(t);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            end = m.end();
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "w": total += n * 604800; break;
                case "d": total += n * 86400; break;
                case "h": total += n * 3600; break;
                case "m": total += n * 60; break;
                default: total += n;
            }
        }
        return end == t.length() && total > 0 ? total : -1;
    }

    /** Rotating item in this slot, or null. */
    MenuItem at(MenuDef menu, int slot) {
        if (menu.rotation == null) return null;
        for (boolean premium : new boolean[]{false, true}) {
            String key = state.yaml().getString(path(menu, premium) + ".slots." + slot);
            if (key != null) {
                MenuItem item = menu.rotation.pool(premium).get(key);
                if (item != null) return item;
            }
        }
        return null;
    }

    /** What is left for this player: -1 = no limit. */
    int left(MenuDef menu, MenuItem item, Player player) {
        if (item.rotationStock <= 0) return -1;
        String base = path(menu, "premium".equals(item.rotating));
        if (item.perPlayer) {
            int bought = state.yaml().getInt(base + ".bought." + item.key + "." + player.getUniqueId(), 0);
            return Math.max(0, item.rotationStock - bought);
        }
        return Math.max(0, state.yaml().getInt(base + ".stock." + item.key, item.rotationStock));
    }

    /** One bought. */
    void consume(MenuDef menu, MenuItem item, Player player) {
        if (item.rotationStock <= 0) return;
        String base = path(menu, "premium".equals(item.rotating));
        if (item.perPlayer) {
            String p = base + ".bought." + item.key + "." + player.getUniqueId();
            state.yaml().set(p, state.yaml().getInt(p, 0) + 1);
        } else {
            String p = base + ".stock." + item.key;
            state.yaml().set(p, Math.max(0, state.yaml().getInt(p, item.rotationStock) - 1));
        }
        state.save();
    }

    /** Milliseconds before the next rotation, -1 if the shop has no such rotation. */
    long millisLeft(MenuDef menu, boolean premium) {
        if (menu.rotation == null || menu.rotation.slots(premium).isEmpty() || menu.rotation.pool(premium).isEmpty()) return -1;
        long last = state.yaml().getLong(path(menu, premium) + ".last", System.currentTimeMillis());
        return Math.max(0, last + menu.rotation.refresh(premium) * 1000L - System.currentTimeMillis());
    }

    /** "1d 2h 3m 4s" with config.yml > shop.rotating-time-format (3.x %days% %hours% %min% %sec% work too). */
    String format(long millis) {
        if (millis < 0) return "-";
        String f = plugin.getConfig().getString("shop.rotating-time-format", "{days}d {hours}h {minutes}m {seconds}s");
        long s = millis / 1000;
        return f.replace("{days}", String.valueOf(s / 86400)).replace("%days%", String.valueOf(s / 86400 % 365))
                .replace("%years%", String.valueOf(s / 31536000))
                .replace("{hours}", String.valueOf(s / 3600 % 24)).replace("%hours%", String.valueOf(s / 3600 % 24))
                .replace("{minutes}", String.valueOf(s / 60 % 60)).replace("%min%", String.valueOf(s / 60 % 60))
                .replace("{seconds}", String.valueOf(s % 60)).replace("%sec%", String.valueOf(s % 60));
    }
}
