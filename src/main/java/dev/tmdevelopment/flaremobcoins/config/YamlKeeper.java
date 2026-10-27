package dev.tmdevelopment.flaremobcoins.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Saves a changed YAML file by touching only what changed: single values are changed on their line, changed
 * blocks (an item, the decorations ...) are written again, everything else (comments, quotes, layout) stays as it
 * was. Bukkit's own save writes the whole file again and drops every comment on 1.8 - 1.17.
 */
public final class YamlKeeper {

    private YamlKeeper() {
    }

    /**
     * The old text changed so that it holds the values of {@code now}.
     * @return the new text, or null when it could not be done safely (the caller then saves the whole file)
     */
    public static String patch(String oldText, YamlConfiguration now) {
        try {
            String text = oldText.replace("\r\n", "\n");
            YamlConfiguration old = new YamlConfiguration();
            old.loadFromString(text);
            String[] out = {text};
            if (!section(old, now, "", out)) return null;
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(out[0]);
            if (!leaves(check).equals(leaves(now))) return null;
            return out[0];
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean section(ConfigurationSection old, ConfigurationSection now, String base, String[] out) {
        Set<String> keys = new LinkedHashSet<>();
        ConfigurationSection o = base.isEmpty() ? old : old.getConfigurationSection(base);
        ConfigurationSection n = base.isEmpty() ? now : now.getConfigurationSection(base);
        if (o != null) keys.addAll(o.getKeys(false));
        if (n != null) keys.addAll(n.getKeys(false));
        for (String key : keys) {
            if (key.contains(".")) return false;
            String path = base.isEmpty() ? key : base + "." + key;
            Object a = old.get(path);
            Object b = now.get(path);
            if (a instanceof ConfigurationSection && b instanceof ConfigurationSection) {
                if (!section(old, now, path, out)) return false;
                continue;
            }
            if (a != null && b != null && dump(a).equals(dump(b))) continue;
            String changed = null;
            if (a != null && b != null && scalar(a) && scalar(b)) {
                String v = scalarText(b);
                if (v != null) changed = YamlPatch.setKeepComment(out[0], path, v);
            }
            if (changed == null) changed = YamlPatch.replaceBlock(out[0], path, b == null ? null : fragment(key, b));
            if (changed == null) return false;
            out[0] = changed;
        }
        return true;
    }

    private static boolean scalar(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    /** A value as YAML on one line, null if it needs more lines. */
    private static String scalarText(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            if (s.contains("\n")) return null;
            return YamlPatch.quote(s);
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        }
        return String.valueOf(v);
    }

    /** "key: value" (and the lines under it) as Bukkit writes it, starting at column 0. */
    private static List<String> fragment(String key, Object value) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set(key, plain(value));
        String s = tmp.saveToString();
        List<String> lines = new ArrayList<>();
        for (String l : s.split("\n", -1)) lines.add(l);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    /** Sections as plain maps, so they can be written on their own. */
    private static Object plain(Object v) {
        if (v instanceof ConfigurationSection) {
            Map<String, Object> m = new LinkedHashMap<>();
            ConfigurationSection s = (ConfigurationSection) v;
            for (String k : s.getKeys(false)) m.put(k, plain(s.get(k)));
            return m;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object o : (List<?>) v) l.add(plain(o));
            return l;
        }
        if (v instanceof Map) {
            Map<Object, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) m.put(e.getKey(), plain(e.getValue()));
            return m;
        }
        return v;
    }

    private static String dump(Object v) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("v", plain(v));
        return tmp.saveToString();
    }

    /** Every value of the file (no sections) as written YAML, to compare two files whatever the order. */
    private static Map<String, String> leaves(ConfigurationSection c) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Object> e : c.getValues(true).entrySet()) {
            if (e.getValue() instanceof ConfigurationSection) {
                if (((ConfigurationSection) e.getValue()).getKeys(false).isEmpty()) out.put(e.getKey(), "{}");
                continue;
            }
            out.put(e.getKey(), dump(e.getValue()));
        }
        return out;
    }
}
