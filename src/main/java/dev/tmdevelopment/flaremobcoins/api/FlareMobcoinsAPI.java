package dev.tmdevelopment.flaremobcoins.api;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.data.CoinManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * API for other plugins. Online balances are read from memory (instant);
 * for offline players use the fetch / CompletableFuture methods.
 */
public final class FlareMobcoinsAPI {

    private FlareMobcoinsAPI() {
    }

    private static CoinManager coins() {
        FlareMobcoins plugin = FlareMobcoins.get();
        if (plugin == null || plugin.coins() == null) throw new IllegalStateException("FlareMobcoins is not enabled");
        return plugin.coins();
    }

    public static double getMobcoins(Player player) {
        return coins().balance(player.getUniqueId());
    }

    public static double getMobcoins(UUID uuid) {
        return coins().balance(uuid);
    }

    public static CompletableFuture<Double> fetchMobcoins(UUID uuid) {
        return coins().fetch(uuid);
    }

    public static boolean hasMobcoins(Player player, double amount) {
        return getMobcoins(player) + 0.0001 >= amount;
    }

    public static CompletableFuture<Double> giveMobcoins(OfflinePlayer player, double amount) {
        return coins().give(player.getUniqueId(), player.getName(), amount, "api");
    }

    /** Online player: removes them only if he has enough. */
    public static boolean takeMobcoins(Player player, double amount) {
        return coins().spend(player, amount, "api");
    }

    /** Result -1 if he has less. */
    public static CompletableFuture<Double> takeMobcoins(OfflinePlayer player, double amount) {
        return coins().take(player.getUniqueId(), player.getName(), amount, true, "api");
    }

    public static CompletableFuture<Double> setMobcoins(OfflinePlayer player, double amount) {
        return coins().set(player.getUniqueId(), player.getName(), amount, "api");
    }

    public static CompletableFuture<Double> setMobcoins(UUID uuid, double amount) {
        return coins().set(uuid, Bukkit.getOfflinePlayer(uuid).getName(), amount, "api");
    }

    /** Multiplier of the mob drops of a player (1 = none). */
    public static double getMultiplier(Player player) {
        return coins().multiplier(player.getUniqueId());
    }

    public static CompletableFuture<Double> setMultiplier(OfflinePlayer player, double multiplier) {
        return coins().setMultiplier(player.getUniqueId(), player.getName(), multiplier);
    }

    public static double getGlobalMultiplier() {
        return coins().globalMultiplier();
    }
}
