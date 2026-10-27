package dev.tmdevelopment.flaremobcoins.util;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Text engine without libraries, the same from 1.8 to the latest version:
 * &amp; codes, &amp;#hex, {#hex}, MiniMessage tags (colors, decorations, gradient, rainbow, click, hover, reset, newline)
 * and the 3.x form &lt;GRADIENT:#a-#b&gt;. Hex colors become the nearest color below 1.16.
 */
public final class Text {

    private static final Map<String, int[]> NAMED = new HashMap<>();
    /** {rgb, code} of the 16 chat colors. */
    private static final int[][] PALETTE = {
            {0x000000, '0'}, {0x0000AA, '1'}, {0x00AA00, '2'}, {0x00AAAA, '3'},
            {0xAA0000, '4'}, {0xAA00AA, '5'}, {0xFFAA00, '6'}, {0xAAAAAA, '7'},
            {0x555555, '8'}, {0x5555FF, '9'}, {0x55FF55, 'a'}, {0x55FFFF, 'b'},
            {0xFF5555, 'c'}, {0xFF55FF, 'd'}, {0xFFFF55, 'e'}, {0xFFFFFF, 'f'}};
    private static Method hexColor;
    private static boolean hexChecked;

    static {
        String[] names = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        for (int i = 0; i < names.length; i++) NAMED.put(names[i], PALETTE[i]);
        NAMED.put("grey", PALETTE[7]);
        NAMED.put("dark_grey", PALETTE[8]);
    }

    private Text() {
    }

    // ------------------------------------------------------------------ public

    /** § text (hex on 1.16+): item names, titles, menu titles, boss bars. */
    public static String legacy(String raw) {
        return render(spans(raw), Ver.hex());
    }

    /** Item name / lore line: §r first so the game doesn't make it italic. */
    public static String item(String raw) {
        return raw == null || raw.isEmpty() ? "" : "§r" + legacy(raw);
    }

    public static List<String> items(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) out.add(item(s));
        return out;
    }

    /** Console: no hex codes. */
    public static String console(String raw) {
        return render(spans(raw), false);
    }

    /** Text of an item (§ codes) back to what is written in the files: &amp;a, &amp;#rrggbb. */
    public static String toEditable(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 13 < s.length() && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X') && hexRun(s, i + 2)) {
                out.append("&#");
                for (int k = 0; k < 6; k++) out.append(s.charAt(i + 3 + k * 2));
                i += 13;
            } else {
                out.append(c == '\u00a7' ? '&' : c);
            }
        }
        return out.toString();
    }

    private static boolean hexRun(String s, int from) {
        for (int k = 0; k < 6; k++) {
            int i = from + k * 2;
            if (i + 1 >= s.length() || s.charAt(i) != '\u00a7' || Character.digit(s.charAt(i + 1), 16) < 0) return false;
        }
        return true;
    }

    public static String plain(String raw) {
        StringBuilder sb = new StringBuilder();
        for (Span s : spans(raw)) sb.append(s.text);
        return sb.toString();
    }

    /** Chat component with colors, click and hover. */
    public static BaseComponent component(String raw) {
        TextComponent root = new TextComponent("");
        for (Span s : spans(raw)) root.addExtra(bungee(s));
        return root;
    }

    // ------------------------------------------------------------------ colors

    private static int[] legacyColor(char code) {
        char c = Character.toLowerCase(code);
        for (int[] p : PALETTE) if (p[1] == c) return p;
        return null;
    }

    static char nearest(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        long best = Long.MAX_VALUE;
        char code = 'f';
        for (int[] p : PALETTE) {
            int pr = p[0] >> 16 & 0xFF, pg = p[0] >> 8 & 0xFF, pb = p[0] & 0xFF;
            long d = (long) (r - pr) * (r - pr) + (long) (g - pg) * (g - pg) + (long) (b - pb) * (b - pb);
            if (d < best) {
                best = d;
                code = (char) p[1];
            }
        }
        return code;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int g = (int) Math.round((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return r << 16 | g << 8 | bl;
    }

    private static Integer parseColor(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        int[] named = NAMED.get(v);
        if (named != null) return named[0];
        if (v.startsWith("&") || v.startsWith("§")) v = v.substring(1);
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() == 6) {
            try {
                return Integer.parseInt(v, 16);
            } catch (NumberFormatException ignored) {
            }
        }
        if (v.length() == 3) {
            try {
                int r = Integer.parseInt(v.substring(0, 1), 16);
                int g = Integer.parseInt(v.substring(1, 2), 16);
                int b = Integer.parseInt(v.substring(2, 3), 16);
                return r * 17 << 16 | g * 17 << 8 | b * 17;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static boolean hexAt(String s, int from) {
        if (from + 6 > s.length()) return false;
        for (int i = from; i < from + 6; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    // ------------------------------------------------------------------ parsing

    static List<Span> spans(String raw) {
        List<Span> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        walk(parse(raw), new Style(), out);
        List<Span> merged = new ArrayList<>(out.size());
        for (Span s : out) {
            if (s.text.isEmpty()) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1).style.same(s.style)) {
                Span last = merged.remove(merged.size() - 1);
                merged.add(new Span(last.text + s.text, last.style));
            } else {
                merged.add(s);
            }
        }
        return merged;
    }

    private static Node parse(String s) {
        Node root = new Node();
        List<Node> stack = new ArrayList<>();
        stack.add(root);
        StringBuilder buf = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && s.charAt(i + 1) == '<') {
                buf.append('<');
                i++;
                continue;
            }
            // {#rrggbb}
            if (c == '{' && i + 8 < n && s.charAt(i + 1) == '#' && s.charAt(i + 8) == '}' && hexAt(s, i + 2)) {
                flush(buf, stack);
                legacyHex(Integer.parseInt(s.substring(i + 2, i + 8), 16), stack);
                i += 8;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < n) {
                char next = s.charAt(i + 1);
                // &#rrggbb
                if (next == '#' && hexAt(s, i + 2)) {
                    flush(buf, stack);
                    legacyHex(Integer.parseInt(s.substring(i + 2, i + 8), 16), stack);
                    i += 7;
                    continue;
                }
                // §x§r§r§g§g§b§b
                if ((next == 'x' || next == 'X') && i + 13 < n) {
                    StringBuilder hex = new StringBuilder();
                    boolean ok = true;
                    for (int k = 0; k < 6; k++) {
                        int pos = i + 2 + k * 2;
                        char sign = s.charAt(pos), digit = s.charAt(pos + 1);
                        if (sign != '&' && sign != '§' || Character.digit(digit, 16) < 0) {
                            ok = false;
                            break;
                        }
                        hex.append(digit);
                    }
                    if (ok) {
                        flush(buf, stack);
                        legacyHex(Integer.parseInt(hex.toString(), 16), stack);
                        i += 13;
                        continue;
                    }
                }
                char code = Character.toLowerCase(next);
                if ("0123456789abcdefklmnor".indexOf(code) >= 0) {
                    flush(buf, stack);
                    legacyCode(code, stack);
                    i++;
                    continue;
                }
            }
            if (c == '<') {
                int end = tagEnd(s, i);
                if (end > i && tag(s.substring(i + 1, end), buf, stack)) {
                    i = end;
                    continue;
                }
            }
            buf.append(c);
        }
        flush(buf, stack);
        return root;
    }

    private static void flush(StringBuilder buf, List<Node> stack) {
        if (buf.length() == 0) return;
        Node text = new Node();
        text.text = buf.toString();
        stack.get(stack.size() - 1).children.add(text);
        buf.setLength(0);
    }

    private static void push(List<Node> stack, String name, boolean legacy, Modifier mod) {
        Node node = new Node();
        node.name = name;
        node.legacy = legacy;
        node.mod = mod;
        stack.get(stack.size() - 1).children.add(node);
        stack.add(node);
    }

    /** A legacy color ends every legacy code before it (like in the game). */
    private static void closeLegacy(List<Node> stack) {
        for (int i = 1; i < stack.size(); i++) {
            if (stack.get(i).legacy) {
                while (stack.size() > i) stack.remove(stack.size() - 1);
                return;
            }
        }
    }

    private static void legacyHex(int rgb, List<Node> stack) {
        closeLegacy(stack);
        push(stack, "#", true, (st, node) -> {
            st.rgb = rgb;
            st.code = 0;
            st.gradient = null;
        });
    }

    private static void legacyCode(char code, List<Node> stack) {
        if (code == 'r') {
            while (stack.size() > 1) stack.remove(stack.size() - 1);
            return;
        }
        int[] color = legacyColor(code);
        if (color != null) {
            closeLegacy(stack);
            push(stack, "&" + code, true, (st, node) -> {
                st.rgb = color[0];
                st.code = (char) color[1];
                st.gradient = null;
            });
        } else {
            String deco = code == 'l' ? "bold" : code == 'o' ? "italic" : code == 'n' ? "underlined"
                    : code == 'm' ? "strikethrough" : "obfuscated";
            push(stack, "&" + code, true, decoration(deco, true));
        }
    }

    private static int tagEnd(String s, int start) {
        char quote = 0;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '<') {
                return -1;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /** Splits a tag on ':' (quotes respected). */
    private static List<String> args(String tag) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ':') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String rest(List<String> args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.size(); i++) {
            if (i > from) sb.append(':');
            sb.append(args.get(i));
        }
        return sb.toString();
    }

    /** @return true if the tag was understood (it is not printed). */
    private static boolean tag(String tag, StringBuilder buf, List<Node> stack) {
        if (tag.isEmpty()) return false;
        if (tag.charAt(0) == '/') {
            String name = tag.substring(1);
            int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(0, colon);
            name = normalize(name.trim().toLowerCase(Locale.ROOT));
            if (name.isEmpty()) {
                // </> closes the last tag
                if (stack.size() <= 1) return true;
                flush(buf, stack);
                stack.remove(stack.size() - 1);
                return true;
            }
            if (!known(name)) return false;
            flush(buf, stack);
            for (int i = stack.size() - 1; i >= 1; i--) {
                if (name.equals(stack.get(i).name)) {
                    while (stack.size() > i) stack.remove(stack.size() - 1);
                    break;
                }
            }
            return true;
        }

        List<String> args = args(tag);
        String first = args.get(0).trim().toLowerCase(Locale.ROOT);
        boolean negate = first.startsWith("!");
        if (negate) first = first.substring(1);
        String name = normalize(first);
        // 3.x: <GRADIENT:#a-#b>
        if (name.equals("gradient") && args.size() == 2 && args.get(1).contains("-")) {
            List<String> split = new ArrayList<>();
            split.add("gradient");
            for (String part : args.get(1).split("-")) split.add(part);
            args = split;
        }
        switch (name) {
            case "reset":
                flush(buf, stack);
                while (stack.size() > 1) stack.remove(stack.size() - 1);
                return true;
            case "newline":
                buf.append('\n');
                return true;
            case "bold":
            case "italic":
            case "underlined":
            case "strikethrough":
            case "obfuscated": {
                boolean on = !negate && (args.size() <= 1 || !args.get(1).equalsIgnoreCase("false"));
                flush(buf, stack);
                push(stack, name, false, decoration(name, on));
                return true;
            }
            case "color": {
                Integer rgb = args.size() > 1 ? parseColor(args.get(1)) : null;
                if (rgb == null) return false;
                flush(buf, stack);
                pushColor(stack, "color", rgb, args.get(1));
                return true;
            }
            case "gradient": {
                List<Integer> colors = new ArrayList<>();
                for (int i = 1; i < args.size(); i++) {
                    Integer rgb = parseColor(args.get(i));
                    if (rgb != null) colors.add(rgb);
                }
                if (colors.isEmpty()) {
                    colors.add(0xFFFFFF);
                    colors.add(0x000000);
                }
                int[] arr = new int[colors.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = colors.get(i);
                flush(buf, stack);
                push(stack, "gradient", false, (st, node) -> {
                    st.rgb = null;
                    st.code = 0;
                    st.gradient = new Gradient(arr, length(node));
                });
                return true;
            }
            case "rainbow":
                flush(buf, stack);
                push(stack, "rainbow", false, (st, node) -> {
                    int len = Math.max(1, length(node));
                    int[] colors = new int[len];
                    for (int i = 0; i < len; i++) colors[i] = Color.HSBtoRGB((float) i / len, 1f, 1f) & 0xFFFFFF;
                    st.rgb = null;
                    st.code = 0;
                    st.gradient = new Gradient(colors, len) {
                        @Override
                        int next() {
                            return colors[Math.min(index++, colors.length - 1)];
                        }
                    };
                });
                return true;
            case "click": {
                if (args.size() < 3) return false;
                String action = args.get(1).toLowerCase(Locale.ROOT);
                String value = rest(args, 2);
                flush(buf, stack);
                push(stack, "click", false, (st, node) -> {
                    st.clickAction = action;
                    st.clickValue = value;
                });
                return true;
            }
            case "hover": {
                if (args.size() < 3) return false;
                String value = rest(args, 2);
                flush(buf, stack);
                push(stack, "hover", false, (st, node) -> st.hover = value);
                return true;
            }
            case "font":
            case "insertion":
                flush(buf, stack);
                push(stack, name, false, (st, node) -> {
                });
                return true;
            default: {
                Integer rgb = parseColor(first);
                if (rgb == null || !first.startsWith("#") && !NAMED.containsKey(first)) return false;
                flush(buf, stack);
                pushColor(stack, first, rgb, first);
                return true;
            }
        }
    }

    private static void pushColor(List<Node> stack, String name, int rgb, String raw) {
        int[] named = NAMED.get(raw.trim().toLowerCase(Locale.ROOT));
        char code = named != null ? (char) named[1] : 0;
        push(stack, name, false, (st, node) -> {
            st.rgb = rgb;
            st.code = code;
            st.gradient = null;
        });
    }

    private static String normalize(String name) {
        switch (name) {
            case "b": return "bold";
            case "i": case "em": return "italic";
            case "u": case "underline": return "underlined";
            case "st": case "strike": return "strikethrough";
            case "obf": return "obfuscated";
            case "br": return "newline";
            case "c": case "colour": return "color";
            default: return name;
        }
    }

    private static boolean known(String name) {
        switch (name) {
            case "bold": case "italic": case "underlined": case "strikethrough": case "obfuscated":
            case "color": case "gradient": case "rainbow": case "click": case "hover": case "font": case "insertion":
                return true;
            default:
                return NAMED.containsKey(name) || name.startsWith("#") && parseColor(name) != null;
        }
    }

    private static Modifier decoration(String name, boolean on) {
        Boolean value = on;
        switch (name) {
            case "bold": return (st, node) -> st.bold = value;
            case "italic": return (st, node) -> st.italic = value;
            case "underlined": return (st, node) -> st.underlined = value;
            case "strikethrough": return (st, node) -> st.strike = value;
            default: return (st, node) -> st.obf = value;
        }
    }

    private static int length(Node node) {
        if (node.text != null) return node.text.codePointCount(0, node.text.length());
        int n = 0;
        for (Node child : node.children) n += length(child);
        return n;
    }

    private static void walk(Node node, Style style, List<Span> out) {
        if (node.text != null) {
            if (style.gradient != null) {
                Gradient g = style.gradient;
                String t = node.text;
                int i = 0;
                while (i < t.length()) {
                    int cp = t.codePointAt(i);
                    int len = Character.charCount(cp);
                    Style st = style.copy();
                    st.gradient = null;
                    st.rgb = g.next();
                    st.code = 0;
                    out.add(new Span(t.substring(i, i + len), st));
                    i += len;
                }
            } else {
                Style st = style.copy();
                st.gradient = null;
                out.add(new Span(node.text, st));
            }
            return;
        }
        Style st = style;
        if (node.mod != null) {
            st = style.copy();
            node.mod.apply(st, node);
        }
        for (Node child : node.children) walk(child, st, out);
    }

    // ------------------------------------------------------------------ rendering

    private static String render(List<Span> spans, boolean hex) {
        StringBuilder sb = new StringBuilder();
        Style prev = null;
        for (Span span : spans) {
            Style st = span.style;
            // without hex two colors can become the same code: no need to write it again
            if (!hex && st.rgb != null && st.code == 0) {
                st = st.copy();
                st.code = nearest(st.rgb);
                st.rgb = legacyColor(st.code)[0];
            }
            if (prev == null || !sameLook(prev, st)) {
                if (st.rgb != null) {
                    if (st.code != 0) {
                        sb.append('§').append(st.code);
                    } else if (hex) {
                        String h = String.format("%06x", st.rgb);
                        sb.append("§x");
                        for (int i = 0; i < 6; i++) sb.append('§').append(h.charAt(i));
                    } else {
                        sb.append('§').append(nearest(st.rgb));
                    }
                } else if (prev != null) {
                    sb.append("§r");
                }
                if (Boolean.TRUE.equals(st.obf)) sb.append("§k");
                if (Boolean.TRUE.equals(st.bold)) sb.append("§l");
                if (Boolean.TRUE.equals(st.strike)) sb.append("§m");
                if (Boolean.TRUE.equals(st.underlined)) sb.append("§n");
                if (Boolean.TRUE.equals(st.italic)) sb.append("§o");
            }
            sb.append(span.text);
            prev = st;
        }
        return sb.toString();
    }

    private static boolean sameLook(Style a, Style b) {
        return (a.rgb == null ? b.rgb == null : a.rgb.equals(b.rgb))
                && a.code == b.code
                && Boolean.TRUE.equals(a.bold) == Boolean.TRUE.equals(b.bold)
                && Boolean.TRUE.equals(a.italic) == Boolean.TRUE.equals(b.italic)
                && Boolean.TRUE.equals(a.underlined) == Boolean.TRUE.equals(b.underlined)
                && Boolean.TRUE.equals(a.strike) == Boolean.TRUE.equals(b.strike)
                && Boolean.TRUE.equals(a.obf) == Boolean.TRUE.equals(b.obf);
    }

    private static ChatColor chatColor(Style st) {
        if (st.code != 0) return ChatColor.getByChar(st.code);
        if (Ver.hex()) {
            if (!hexChecked) {
                hexChecked = true;
                try {
                    hexColor = ChatColor.class.getMethod("of", String.class);
                } catch (Throwable ignored) {
                }
            }
            if (hexColor != null) {
                try {
                    return (ChatColor) hexColor.invoke(null, String.format("#%06x", st.rgb));
                } catch (Throwable ignored) {
                }
            }
        }
        return ChatColor.getByChar(nearest(st.rgb));
    }

    @SuppressWarnings("deprecation")
    private static BaseComponent bungee(Span span) {
        Style st = span.style;
        TextComponent c = new TextComponent(span.text);
        c.setColor(st.rgb != null ? chatColor(st) : ChatColor.WHITE);
        c.setBold(Boolean.TRUE.equals(st.bold));
        c.setItalic(Boolean.TRUE.equals(st.italic));
        c.setUnderlined(Boolean.TRUE.equals(st.underlined));
        c.setStrikethrough(Boolean.TRUE.equals(st.strike));
        c.setObfuscated(Boolean.TRUE.equals(st.obf));
        if (st.clickAction != null) {
            ClickEvent.Action action = clickAction(st.clickAction);
            if (action != null) c.setClickEvent(new ClickEvent(action, st.clickValue));
        }
        if (st.hover != null) {
            c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{component(st.hover)}));
        }
        return c;
    }

    private static ClickEvent.Action clickAction(String name) {
        String constant;
        switch (name) {
            case "run_command": constant = "RUN_COMMAND"; break;
            case "suggest_command": constant = "SUGGEST_COMMAND"; break;
            case "open_url": constant = "OPEN_URL"; break;
            case "copy_to_clipboard": constant = "COPY_TO_CLIPBOARD"; break;
            case "change_page": constant = "CHANGE_PAGE"; break;
            default: return null;
        }
        try {
            return ClickEvent.Action.valueOf(constant);
        } catch (IllegalArgumentException e) {
            // copy_to_clipboard does not exist on old versions
            return ClickEvent.Action.SUGGEST_COMMAND;
        }
    }

    // ------------------------------------------------------------------ model

    private static class Gradient {
        final int[] colors;
        final int length;
        int index;

        Gradient(int[] colors, int length) {
            this.colors = colors;
            this.length = Math.max(1, length);
        }

        int next() {
            int i = index++;
            if (colors.length == 1) return colors[0];
            double t = length <= 1 ? 0 : (double) i / (length - 1);
            if (t > 1) t = 1;
            double scaled = t * (colors.length - 1);
            int idx = (int) Math.floor(scaled);
            if (idx >= colors.length - 1) return colors[colors.length - 1];
            return mix(colors[idx], colors[idx + 1], scaled - idx);
        }
    }

    private interface Modifier {
        void apply(Style style, Node node);
    }

    private static final class Node {
        String text;
        String name;
        boolean legacy;
        Modifier mod;
        final List<Node> children = new ArrayList<>();
    }

    static final class Span {
        final String text;
        final Style style;

        Span(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    static final class Style {
        Integer rgb;
        char code;
        Boolean bold, italic, underlined, strike, obf;
        String clickAction, clickValue, hover;
        Gradient gradient;

        Style copy() {
            Style s = new Style();
            s.rgb = rgb;
            s.code = code;
            s.bold = bold;
            s.italic = italic;
            s.underlined = underlined;
            s.strike = strike;
            s.obf = obf;
            s.clickAction = clickAction;
            s.clickValue = clickValue;
            s.hover = hover;
            s.gradient = gradient;
            return s;
        }

        boolean same(Style o) {
            return o != null && eq(rgb, o.rgb) && code == o.code && eq(bold, o.bold) && eq(italic, o.italic)
                    && eq(underlined, o.underlined) && eq(strike, o.strike) && eq(obf, o.obf)
                    && eq(clickAction, o.clickAction) && eq(clickValue, o.clickValue) && eq(hover, o.hover);
        }

        private static boolean eq(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }
    }
}
