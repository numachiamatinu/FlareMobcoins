package dev.tmdevelopment.flaremobcoins.feature;

import dev.tmdevelopment.flaremobcoins.util.Ver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Locale;

/** Boss bars that stay on screen (1.9+, through reflection: the plugin is built against 1.8). Null bars do nothing. */
final class Bars {

    private static boolean checked;
    private static Class<?> colorClass, styleClass;
    private static Method create, setTitle, setProgress, addPlayer, removePlayer, removeAll, setColor;
    private static Object noFlags;

    private Bars() {
    }

    private static boolean ready() {
        if (!checked) {
            checked = true;
            if (!Ver.atLeast(9)) return false;
            try {
                colorClass = Class.forName("org.bukkit.boss.BarColor");
                styleClass = Class.forName("org.bukkit.boss.BarStyle");
                Class<?> flag = Class.forName("org.bukkit.boss.BarFlag");
                Class<?> bar = Class.forName("org.bukkit.boss.BossBar");
                noFlags = Array.newInstance(flag, 0);
                create = Bukkit.class.getMethod("createBossBar", String.class, colorClass, styleClass, noFlags.getClass());
                setTitle = bar.getMethod("setTitle", String.class);
                setProgress = bar.getMethod("setProgress", double.class);
                addPlayer = bar.getMethod("addPlayer", Player.class);
                removePlayer = bar.getMethod("removePlayer", Player.class);
                removeAll = bar.getMethod("removeAll");
                setColor = bar.getMethod("setColor", colorClass);
            } catch (Throwable t) {
                create = null;
            }
        }
        return create != null;
    }

    static boolean supported() {
        return ready();
    }

    /** A new bar, null on 1.8. */
    static Object create(String title, String color, String style) {
        if (!ready()) return null;
        try {
            return create.invoke(null, title, constant(colorClass, color, "YELLOW"), constant(styleClass, style, "SOLID"), noFlags);
        } catch (Throwable t) {
            return null;
        }
    }

    static void title(Object bar, String title) {
        call(setTitle, bar, title);
    }

    static void progress(Object bar, double value) {
        call(setProgress, bar, Math.max(0, Math.min(1, value)));
    }

    static void color(Object bar, String color) {
        if (ready()) call(setColor, bar, constant(colorClass, color, "YELLOW"));
    }

    static void add(Object bar, Player p) {
        call(addPlayer, bar, p);
    }

    static void remove(Object bar, Player p) {
        call(removePlayer, bar, p);
    }

    static void removeAll(Object bar) {
        call(removeAll, bar);
    }

    private static void call(Method m, Object bar, Object... args) {
        if (bar == null || m == null) return;
        try {
            m.invoke(bar, args);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Class<?> type, String name, String fallback) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type, name.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Enum.valueOf((Class<? extends Enum>) type, fallback);
        }
    }
}
