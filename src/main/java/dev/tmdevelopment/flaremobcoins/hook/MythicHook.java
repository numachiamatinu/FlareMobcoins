package dev.tmdevelopment.flaremobcoins.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * MythicMobs 4.x (io.lumine.xikage) and 5.x (io.lumine.mythic), through reflection: no MythicMobs library is needed
 * and nothing breaks when it is missing. The methods are looked up once; a check is one map lookup of MythicMobs.
 */
public final class MythicHook {

    private static Object helper;
    private static Method instance, mobType, typeOf, internalName, level;
    private static boolean failed;
    /** the last mob looked up: type() and level() of the same kill ask MythicMobs once */
    private static java.lang.ref.WeakReference<Entity> lastEntity = new java.lang.ref.WeakReference<>(null);
    private static Object lastMob;

    private MythicHook() {
    }

    /** Looks for MythicMobs (on start, reload and when MythicMobs is enabled after us). */
    public static void setup() {
        helper = null;
        lastEntity = new java.lang.ref.WeakReference<>(null);
        lastMob = null;
        instance = mobType = typeOf = internalName = level = null;
        failed = false;
        Plugin p = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (p == null || !p.isEnabled()) return;
        try {
            Object api;
            try {
                // 5.x
                Class<?> main = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
                api = main.getMethod("getAPIHelper").invoke(main.getMethod("inst").invoke(null));
            } catch (ClassNotFoundException e) {
                // 4.x
                Class<?> main = Class.forName("io.lumine.xikage.mythicmobs.MythicMobs");
                api = main.getMethod("getAPIHelper").invoke(main.getMethod("inst").invoke(null));
            }
            instance = api.getClass().getMethod("getMythicMobInstance", Entity.class);
            helper = api;
        } catch (Throwable t) {
            helper = null;
        }
    }

    public static boolean available() {
        return helper != null && !failed;
    }

    /** The active MythicMobs mob of an entity, null if it is a normal mob. */
    private static Object active(Entity e) {
        if (helper == null || failed) return null;
        if (e == lastEntity.get()) return lastMob;
        try {
            lastMob = instance.invoke(helper, e);
            lastEntity = new java.lang.ref.WeakReference<>(e);
            return lastMob;
        } catch (Throwable t) {
            // a MythicMobs update changed the API: stop asking instead of failing on every kill
            failed = true;
            Bukkit.getLogger().warning("[FlareMobcoins] Could not read MythicMobs mobs: " + t);
            return null;
        }
    }

    /** Internal name of the MythicMobs mob (as in its file), null if it is a normal mob. */
    public static String type(Entity e) {
        Object mob = active(e);
        if (mob == null) return null;
        try {
            if (mobType == null && typeOf == null) {
                try {
                    mobType = mob.getClass().getMethod("getMobType");
                } catch (NoSuchMethodException ex) {
                    typeOf = mob.getClass().getMethod("getType");
                }
            }
            if (mobType != null) {
                Object v = mobType.invoke(mob);
                return v == null ? null : String.valueOf(v);
            }
            Object type = typeOf.invoke(mob);
            if (type == null) return null;
            if (internalName == null) internalName = type.getClass().getMethod("getInternalName");
            return String.valueOf(internalName.invoke(type));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Level of the MythicMobs mob, 1 if it has none. */
    public static double level(Entity e) {
        Object mob = active(e);
        if (mob == null) return 1;
        try {
            if (level == null) level = mob.getClass().getMethod("getLevel");
            Object v = level.invoke(mob);
            return v instanceof Number ? ((Number) v).doubleValue() : 1;
        } catch (Throwable t) {
            return 1;
        }
    }
}
