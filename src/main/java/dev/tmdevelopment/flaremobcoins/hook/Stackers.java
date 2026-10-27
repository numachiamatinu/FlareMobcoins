package dev.tmdevelopment.flaremobcoins.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;

/** Size of a stacked mob (RoseStacker, WildStacker). 1 when there is no stacker. */
public final class Stackers {

    private Stackers() {
    }

    public static int size(LivingEntity entity) {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("RoseStacker")) {
                Class<?> api = Class.forName("dev.rosewood.rosestacker.api.RoseStackerAPI");
                Object inst = api.getMethod("getInstance").invoke(null);
                Object stacked = api.getMethod("getStackedEntity", LivingEntity.class).invoke(inst, entity);
                if (stacked != null) return Math.max(1, (Integer) stacked.getClass().getMethod("getStackSize").invoke(stacked));
            }
            if (Bukkit.getPluginManager().isPluginEnabled("WildStacker")) {
                Class<?> api = Class.forName("com.bgsoftware.wildstacker.api.WildStackerAPI");
                Object stacked = api.getMethod("getStackedEntity", LivingEntity.class).invoke(null, entity);
                if (stacked != null) return Math.max(1, (Integer) stacked.getClass().getMethod("getStackAmount").invoke(stacked));
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }
}
