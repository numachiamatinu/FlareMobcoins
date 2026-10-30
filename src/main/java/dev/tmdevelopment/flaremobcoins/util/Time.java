package dev.tmdevelopment.flaremobcoins.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations: "30m", "1h30m", "2d", "90s", "1h 30m" and a short text back ("1h 30m", "4m 10s"). */
public final class Time {

    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(d|h|m|s|w)?");

    private Time() {
    }

    /** Milliseconds, -1 if the text is not a duration. A number alone is minutes. */
    public static long parse(String text) {
        if (text == null) return -1;
        String s = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) return -1;
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            end = m.end();
            double v = Double.parseDouble(m.group(1));
            String unit = m.group(2) == null ? "m" : m.group(2);
            switch (unit) {
                case "w":
                    total += (long) (v * 604_800_000L);
                    break;
                case "d":
                    total += (long) (v * 86_400_000L);
                    break;
                case "h":
                    total += (long) (v * 3_600_000L);
                    break;
                case "s":
                    total += (long) (v * 1000L);
                    break;
                default:
                    total += (long) (v * 60_000L);
            }
        }
        return end == s.length() && total > 0 ? total : -1;
    }

    /** "2d 3h", "1h 30m", "4m 10s", "12s": the two biggest parts. */
    public static String format(long millis) {
        long s = Math.max(0, (millis + 999) / 1000);
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60, sec = s % 60;
        if (d > 0) return d + "d" + (h > 0 ? " " + h + "h" : "");
        if (h > 0) return h + "h" + (m > 0 ? " " + m + "m" : "");
        if (m > 0) return m + "m" + (sec > 0 ? " " + sec + "s" : "");
        return sec + "s";
    }
}
