package dev.tmdevelopment.flaremobcoins.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Money through Vault, without needing Vault to start. */
public final class VaultHook {

    private static Object economy;

    private VaultHook() {
    }

    public static void setup() {
        economy = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            Class<?> type = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(type);
            economy = rsp == null ? null : rsp.getProvider();
        } catch (Throwable ignored) {
        }
    }

    public static boolean available() {
        if (economy == null) setup();
        return economy != null;
    }

    private static Object call(String method, Player p, double amount) throws Exception {
        return economy.getClass().getMethod(method, OfflinePlayer.class, double.class).invoke(economy, p, amount);
    }

    public static boolean has(Player p, double amount) {
        if (!available()) return false;
        try {
            return (Boolean) call("has", p, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    public static double balance(Player p) {
        if (!available()) return 0;
        try {
            return (Double) economy.getClass().getMethod("getBalance", OfflinePlayer.class).invoke(economy, p);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void withdraw(Player p, double amount) {
        if (!available()) return;
        try {
            call("withdrawPlayer", p, amount);
        } catch (Throwable ignored) {
        }
    }

    public static void deposit(Player p, double amount) {
        if (!available()) return;
        try {
            call("depositPlayer", p, amount);
        } catch (Throwable ignored) {
        }
    }

    public static void set(Player p, double amount) {
        if (!available()) return;
        double now = balance(p);
        if (now > amount) withdraw(p, now - amount);
        else if (now < amount) deposit(p, amount - now);
    }
}
