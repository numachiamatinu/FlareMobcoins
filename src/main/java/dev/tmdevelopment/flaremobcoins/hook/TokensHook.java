package dev.tmdevelopment.flaremobcoins.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/** FlareTokens 4.x (same family): [tokens_give], [buy(tokens)], [has_tokens]. */
public final class TokensHook {

    private static final String API = "dev.tmdevelopment.flaretokens.api.FlareTokensAPI";

    private TokensHook() {
    }

    public static boolean available() {
        if (!Bukkit.getPluginManager().isPluginEnabled("FlareTokens")) return false;
        try {
            Class.forName(API);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        return Class.forName(API).getMethod(name, types);
    }

    public static long balance(Player p) {
        try {
            return (Long) method("getTokens", Player.class).invoke(null, p);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean take(Player p, long amount) {
        try {
            return (Boolean) method("takeTokens", Player.class, long.class).invoke(null, p, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void give(Player p, long amount) {
        try {
            method("giveTokens", OfflinePlayer.class, long.class).invoke(null, p, amount);
        } catch (Throwable ignored) {
        }
    }

    public static void remove(Player p, long amount) {
        try {
            method("takeTokens", OfflinePlayer.class, long.class).invoke(null, p, amount);
        } catch (Throwable ignored) {
        }
    }

    public static void set(Player p, long amount) {
        try {
            method("setTokens", OfflinePlayer.class, long.class).invoke(null, p, amount);
        } catch (Throwable ignored) {
        }
    }
}
