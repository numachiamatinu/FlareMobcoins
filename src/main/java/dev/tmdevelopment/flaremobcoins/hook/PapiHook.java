package dev.tmdevelopment.flaremobcoins.hook;

import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/** PlaceholderAPI placeholders inside our texts (only called when PlaceholderAPI is enabled). */
public final class PapiHook {

    private static Method set;
    private static boolean checked;

    private PapiHook() {
    }

    public static String apply(OfflinePlayer player, String text) {
        if (!checked) {
            checked = true;
            try {
                set = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (Throwable ignored) {
            }
        }
        if (set == null) return text;
        try {
            return (String) set.invoke(null, player, text);
        } catch (Throwable t) {
            return text;
        }
    }
}
