package dev.tmdevelopment.flaremobcoins.util;

import org.bukkit.Bukkit;

/**
 * Minecraft version of the server. 1.8.8 = (1, 8, 8). The new numbering (26.1, 26.2 ...) counts as newer than every 1.x.
 */
public final class Ver {

    private static int major = 1;
    private static int minor = 21;
    private static int patch = 0;

    static {
        try {
            String v = Bukkit.getBukkitVersion();
            int dash = v.indexOf('-');
            if (dash > 0) v = v.substring(0, dash);
            String[] parts = v.split("\\.");
            major = Integer.parseInt(parts[0].replaceAll("\\D", ""));
            if (parts.length > 1) minor = Integer.parseInt(parts[1].replaceAll("\\D", ""));
            if (parts.length > 2) patch = Integer.parseInt(parts[2].replaceAll("\\D", ""));
        } catch (Throwable ignored) {
        }
    }

    private Ver() {
    }

    /** True on 1.minor or newer. */
    public static boolean atLeast(int minorVersion) {
        return major > 1 || minor >= minorVersion;
    }

    /** True on 1.minor.patch or newer. */
    public static boolean atLeast(int minorVersion, int patchVersion) {
        if (major > 1) return true;
        return minor > minorVersion || minor == minorVersion && patch >= patchVersion;
    }

    /** Hex colors (1.16+). */
    public static boolean hex() {
        return atLeast(16);
    }

    /** New material names (1.13+). */
    public static boolean flat() {
        return atLeast(13);
    }

    public static String text() {
        return major + "." + minor + (patch > 0 ? "." + patch : "");
    }
}
