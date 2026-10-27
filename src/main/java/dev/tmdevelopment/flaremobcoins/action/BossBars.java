package dev.tmdevelopment.flaremobcoins.action;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import dev.tmdevelopment.flaremobcoins.util.Text;
import dev.tmdevelopment.flaremobcoins.util.Ver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [BOSSBAR] text;seconds;COLOR;STYLE or the 3.x form message="..." screen_time="5" bar_color="RED" bar_style="SOLID".
 * Boss bars exist from 1.9: on 1.8 the text is shown in the action bar.
 */
final class BossBars {

    private static final Pattern KV = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private BossBars() {
    }

    static void show(Player player, String value) {
        String text = value;
        double seconds = 5;
        String color = "PURPLE", style = "SOLID";
        if (value.contains("=\"")) {
            Matcher m = KV.matcher(value);
            while (m.find()) {
                String k = m.group(1).toLowerCase(Locale.ROOT);
                String v = m.group(2);
                if (k.equals("message")) text = v;
                else if (k.equals("screen_time")) seconds = parse(v, 5);
                else if (k.equals("bar_color")) color = v;
                else if (k.equals("bar_style")) style = v;
            }
        } else {
            String[] parts = value.split(";");
            text = parts[0];
            if (parts.length > 1) seconds = parse(parts[1], 5);
            if (parts.length > 2) color = parts[2].trim();
            if (parts.length > 3) style = parts[3].trim();
        }
        if (seconds <= 0) seconds = 5;
        if (!Ver.atLeast(9)) {
            Compat.actionBar(player, Text.legacy(text));
            return;
        }
        try {
            Class<?> barColor = Class.forName("org.bukkit.boss.BarColor");
            Class<?> barStyle = Class.forName("org.bukkit.boss.BarStyle");
            Class<?> barFlag = Class.forName("org.bukkit.boss.BarFlag");
            Class<?> bossBar = Class.forName("org.bukkit.boss.BossBar");
            Object c = constant(barColor, color, "PURPLE");
            Object s = constant(barStyle, style, "SOLID");
            Object flags = Array.newInstance(barFlag, 0);
            Object bar = Bukkit.class.getMethod("createBossBar", String.class, barColor, barStyle, flags.getClass())
                    .invoke(null, Text.legacy(text), c, s, flags);
            bossBar.getMethod("addPlayer", Player.class).invoke(bar, player);
            bossBar.getMethod("setVisible", boolean.class).invoke(bar, true);
            long ticks = Math.max(1, Math.round(seconds * 20));
            Bukkit.getScheduler().runTaskLater(FlareMobcoins.get(), () -> {
                try {
                    bossBar.getMethod("removeAll").invoke(bar);
                } catch (Throwable ignored) {
                }
            }, ticks);
        } catch (Throwable t) {
            Compat.actionBar(player, Text.legacy(text));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Class<?> type, String name, String fallback) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type, name.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Enum.valueOf((Class<? extends Enum>) type, fallback);
        }
    }

    private static double parse(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
