package dev.tmdevelopment.flaremobcoins.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;

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

    /** Why the last patch returned null (written in the console by the caller). */
    private static String lastReason = "";

    private YamlKeeper() {
    }

    public static String lastReason() {
        return lastReason;
    }

    /**
     * The old text changed so that it holds the values of {@code now}.
     * @return the new text, or null when it could not be done safely (the caller then saves the whole file)
     */
    public static String patch(String oldText, YamlConfiguration given) {
        try {
            // sections copied from another file (new elements of an update) are made part of this one first
            YamlConfiguration now = new YamlConfiguration();
            for (String key : given.getKeys(false)) {
                Object v = plain(given.get(key));
                if (v instanceof Map) now.createSection(key, (Map<?, ?>) v);
                else now.set(key, v);
            }
            String text = oldText.replace("\r\n", "\n");
            YamlConfiguration old = new YamlConfiguration();
            old.loadFromString(text);
            String[] out = {text};
            lastReason = "";
            if (!section(old, now, "", out)) return null;
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(out[0]);
            String diff = difference(leaves(check), leaves(now));
            if (diff != null) {
                lastReason = "check failed at " + diff;
                return null;
            }
            return out[0];
        } catch (Throwable t) {
            lastReason = String.valueOf(t);
            return null;
        }
    }

    /** First path whose value is not the same, null if the two files hold the same values. */
    private static String difference(Map<String, Object> a, Map<String, Object> b) {
        if (!a.keySet().equals(b.keySet())) {
            for (String k : a.keySet()) if (!b.containsKey(k)) return k;
            for (String k : b.keySet()) if (!a.containsKey(k)) return k;
        }
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (!same(e.getValue(), b.get(e.getKey()))) return e.getKey();
        }
        return null;
    }

    /**
     * Same value? Items and other saved objects are compared as objects: on new versions the same item can be
     * written with its parts in another order, so their text is not a safe check.
     */
    static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof ConfigurationSection) a = plain(a);
        if (b instanceof ConfigurationSection) b = plain(b);
        if (a instanceof ConfigurationSerializable && b instanceof ConfigurationSerializable) {
            try {
                if (a.equals(b) || b.equals(a)) return true;
            } catch (Throwable ignored) {
                // compared by their saved values below
            }
            try {
                return same(((ConfigurationSerializable) a).serialize(), ((ConfigurationSerializable) b).serialize());
            } catch (Throwable t) {
                return false;
            }
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a, mb = (Map<?, ?>) b;
            if (ma.size() != mb.size()) return false;
            Map<String, Object> keyed = new HashMap<>();
            for (Map.Entry<?, ?> e : mb.entrySet()) keyed.put(String.valueOf(e.getKey()), e.getValue());
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!keyed.containsKey(k) || !same(e.getValue(), keyed.get(k))) return false;
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a, lb = (List<?>) b;
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) if (!same(la.get(i), lb.get(i))) return false;
            return true;
        }
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        if (a.equals(b)) return true;
        return dump(a).equals(dump(b));
    }

    private static boolean section(ConfigurationSection old, ConfigurationSection now, String base, String[] out) {
        Set<String> keys = new LinkedHashSet<>();
        ConfigurationSection o = base.isEmpty() ? old : old.getConfigurationSection(base);
        ConfigurationSection n = base.isEmpty() ? now : now.getConfigurationSection(base);
        if (o != null) keys.addAll(o.getKeys(false));
        if (n != null) keys.addAll(n.getKeys(false));
        for (String key : keys) {
            if (key.contains(".")) {
                lastReason = "key with a dot: " + key;
                return false;
            }
            String path = base.isEmpty() ? key : base + "." + key;
            Object a = old.get(path);
            Object b = now.get(path);
            if (a instanceof ConfigurationSection && b instanceof ConfigurationSection) {
                if (!section(old, now, path, out)) return false;
                continue;
            }
            if (a != null && b != null && same(a, b)) continue;
            String changed = null;
            if (a != null && b != null && scalar(a) && scalar(b)) {
                String v = scalarText(b);
                if (v != null) changed = YamlPatch.setKeepComment(out[0], path, v);
            }
            if (changed == null) changed = YamlPatch.replaceBlock(out[0], path, b == null ? null : fragment(key, b));
            if (changed == null) {
                lastReason = "could not write " + path;
                return false;
            }
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

    /**
     * "key: value" (and the lines under it), starting at column 0, written like our files: texts in "double quotes",
     * lists as "  - item". Values that are not plain (saved items ...) are written by Bukkit.
     */
    private static List<String> fragment(String key, Object value) {
        List<String> own = new ArrayList<>();
        if (write(own, "", key, plain(value))) return own;
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set(key, plain(value));
        String s = tmp.saveToString();
        List<String> lines = new ArrayList<>();
        for (String l : s.split("\n", -1)) lines.add(l);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    private static final java.util.regex.Pattern SIMPLE_KEY = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    /** Writes key: value in our style. @return false if the value is not plain (Bukkit writes it then). */
    private static boolean write(List<String> out, String pad, String key, Object v) {
        String k = SIMPLE_KEY.matcher(key).matches() ? key : text(key);
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                out.add(pad + k + ": {}");
                return true;
            }
            out.add(pad + k + ":");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!write(out, pad + "  ", String.valueOf(e.getKey()), e.getValue())) return false;
            }
            return true;
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                out.add(pad + k + ": []");
                return true;
            }
            out.add(pad + k + ":");
            for (Object item : l) {
                if (item instanceof Map && !((Map<?, ?>) item).isEmpty()) {
                    // "  - first: value" then the other keys under it
                    List<String> inner = new ArrayList<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) item).entrySet()) {
                        if (!write(inner, "", String.valueOf(e.getKey()), e.getValue())) return false;
                    }
                    for (int i = 0; i < inner.size(); i++) out.add(pad + (i == 0 ? "  - " : "    ") + inner.get(i));
                    continue;
                }
                String s = plainText(item);
                if (s == null) {
                    // a saved object in a list: written by Bukkit under "- "
                    List<String> lines = bukkit("x", item);
                    if (lines.size() < 2) return false;
                    for (int i = 1; i < lines.size(); i++) {
                        String line = lines.get(i).length() >= 2 ? lines.get(i).substring(2) : lines.get(i).trim();
                        out.add(pad + (i == 1 ? "  - " : "    ") + line);
                    }
                    continue;
                }
                out.add(pad + "  - " + s);
            }
            return true;
        }
        String s = plainText(v);
        if (s == null) {
            if (v == null) return false;
            // a saved object (an item ...): written by Bukkit, the rest stays in our style
            for (String l : bukkit(key, v)) out.add(pad + l);
            return true;
        }
        out.add(pad + k + ": " + s);
        return true;
    }

    private static List<String> bukkit(String key, Object value) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set(key, value);
        List<String> lines = new ArrayList<>();
        for (String l : tmp.saveToString().split("\n", -1)) lines.add(l);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    /**
     * A whole file in our style (texts in double quotes, lists as "  - item"), checked to hold exactly the same
     * values. Used for new files and when the old text could not be changed line by line. Null if not possible.
     */
    public static String render(YamlConfiguration config) {
        try {
            List<String> out = new ArrayList<>();
            for (String key : config.getKeys(false)) {
                if (!write(out, "", key, plain(config.get(key)))) return null;
            }
            String text = String.join("\n", out) + "\n";
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(text);
            return difference(leaves(check), leaves(config)) == null ? text : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A plain value on one line ("text", 10, true), null if it is not plain. */
    private static String plainText(Object v) {
        if (v instanceof String) return text((String) v);
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) return String.valueOf(v);
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return null;
    }

    private static String text(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
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

    /** Every value of the file (no sections), to compare two files whatever the order. */
    private static Map<String, Object> leaves(ConfigurationSection c) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : c.getValues(true).entrySet()) {
            if (e.getValue() instanceof ConfigurationSection) {
                if (((ConfigurationSection) e.getValue()).getKeys(false).isEmpty()) out.put(e.getKey(), new HashMap<String, Object>());
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
