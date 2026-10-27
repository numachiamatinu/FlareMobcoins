package net.flares.flaremobcoins.API;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * The 3.x API, kept so plugins made for FlareMobcoins 3.x keep working.
 * New plugins should use {@link dev.tmdevelopment.flaremobcoins.api.FlareMobcoinsAPI}.
 */
@Deprecated
public class MobcoinsPlayer {

    UUID uuid;
    double mobcoins;
    double multiplier = 1.0;

    @SuppressWarnings("deprecation")
    public static MobcoinsPlayer warpPlayer(UUID uuid) {
        FlareMobcoins plugin = FlareMobcoins.get();
        double coins = plugin.coins().balanceNow(uuid);
        return new MobcoinsPlayer(uuid, coins, plugin.coins().multiplier(uuid));
    }

    @SuppressWarnings("deprecation")
    public static MobcoinsPlayer warpPlayer(String name) {
        OfflinePlayer known = Compat.knownPlayer(name);
        return warpPlayer(known != null ? known.getUniqueId() : Bukkit.getOfflinePlayer(name).getUniqueId());
    }

    public MobcoinsPlayer() {
        this.mobcoins = 0.0;
    }

    public MobcoinsPlayer(UUID uuid, double mobcoins, double multiplier) {
        this.uuid = uuid;
        this.mobcoins = mobcoins;
        this.multiplier = multiplier;
    }

    public UUID getPlayer() {
        return uuid;
    }

    public void setPlayer(UUID uuid) {
        this.uuid = uuid;
    }

    public double getMobcoins() {
        return mobcoins;
    }

    public void setMobcoins(double mobcoins) {
        this.mobcoins = mobcoins;
        if (uuid != null) FlareMobcoins.get().coins().set(uuid, name(), mobcoins, "api (3.x)");
    }

    public void objectSetMobcoins(double mobcoins) {
        this.mobcoins = mobcoins;
    }

    public void objectSetMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public void giveMobcoins(double mobcoins) {
        this.mobcoins += mobcoins;
        if (uuid != null) FlareMobcoins.get().coins().give(uuid, name(), mobcoins, "api (3.x)");
    }

    public void removeMobcoins(double mobcoins) {
        this.mobcoins = Math.max(0, this.mobcoins - mobcoins);
        if (uuid != null) FlareMobcoins.get().coins().take(uuid, name(), mobcoins, false, "api (3.x)");
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
        if (uuid != null) FlareMobcoins.get().coins().setMultiplier(uuid, name(), multiplier);
    }

    private String name() {
        return Bukkit.getOfflinePlayer(uuid).getName();
    }
}
