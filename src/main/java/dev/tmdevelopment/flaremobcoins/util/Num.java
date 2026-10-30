package dev.tmdevelopment.flaremobcoins.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Mobcoin amounts: always rounded to 2 decimals. Parsing of "2.5k", "1m", "1,000.50". */
public final class Num {

    /** Highest balance: above this a double can't keep 2 exact decimals. */
    public static final double MAX = 90_000_000_000_000d;

    private static final DecimalFormatSymbols US = DecimalFormatSymbols.getInstance(Locale.US);
    private static String[] suffixes = {"k", "M", "B", "T", "Q"};

    private Num() {
    }

    public static void setSuffixes(List<String> list) {
        if (list != null && list.size() >= 5) suffixes = list.subList(0, 5).toArray(new String[0]);
    }

    /** Rounds to 2 decimals and keeps the value between -MAX and MAX. */
    public static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        if (v > MAX) v = MAX;
        if (v < -MAX) v = -MAX;
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 10 -> "10", 2.5 -> "2.5", 0.25 -> "0.25". */
    public static String plain(double v) {
        return new DecimalFormat("0.##", US).format(round(v));
    }

    /** Always 2 decimals: 10 -> "10.00". */
    public static String fixed(double v) {
        return new DecimalFormat("0.00", US).format(round(v));
    }

    /** 1234567.5 -> "1,234,567.5". */
    public static String commas(double v) {
        return new DecimalFormat("#,##0.##", US).format(round(v));
    }

    /** 1234567.5 -> "1,234,567.50" (3.x %flaremobcoins_get_commas_mobcoins%). */
    public static String commasFixed(double v) {
        return new DecimalFormat("#,##0.00", US).format(round(v));
    }

    /** 1500 -> "1.5k", 2000000 -> "2M", 950.5 -> "950.5". */
    public static String shortFormat(double v) {
        double a = Math.abs(v);
        if (a < 1000) return plain(v);
        int exp = (int) (Math.log(a) / Math.log(1000));
        if (exp > suffixes.length) exp = suffixes.length;
        DecimalFormat f = new DecimalFormat("0.#", US);
        f.setRoundingMode(RoundingMode.DOWN);
        String s = f.format(v / Math.pow(1000, exp));
        if (s.replace("-", "").equals("1000") && exp < suffixes.length) {
            exp++;
            s = f.format(v / Math.pow(1000, exp));
        }
        return s + suffixes[exp - 1];
    }

    /**
     * "100", "2.5", "2.5k", "1m", "3b", "1,000" -> amount (2 decimals). -1 if not a valid amount.
     * Suffixes: k thousand, m million, b billion, t trillion.
     */
    public static double parse(String s) {
        if (s == null) return -1;
        String t = s.trim().replace(",", "").replace("_", "").toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return -1;
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k') mult = 1e3;
        else if (last == 'm') mult = 1e6;
        else if (last == 'b') mult = 1e9;
        else if (last == 't') mult = 1e12;
        if (mult > 1) t = t.substring(0, t.length() - 1);
        try {
            double v = Double.parseDouble(t) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > MAX) return -1;
            return round(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * A drop value: "5", "2.5", "1-10" or "1 to 10" (random), "random_number(1,10)" (random whole number),
     * "random_decimal(1,2)" (random with decimals, 3.x).
     */
    public static double roll(String value) {
        if (value == null) return 0;
        String t = value.trim().toLowerCase(Locale.ROOT);
        boolean decimals = t.startsWith("random_decimal");
        boolean whole = t.startsWith("random_number");
        int open = t.indexOf('('), close = t.lastIndexOf(')');
        if (open >= 0 && close > open) t = t.substring(open + 1, close);
        String[] parts = t.split("[;,]|(?<=\\d)\\s*-\\s*(?=\\d)|\\s+to\\s+");
        try {
            if (parts.length >= 2) {
                double a = Double.parseDouble(parts[0].trim());
                double b = Double.parseDouble(parts[1].trim());
                double lo = Math.min(a, b), hi = Math.max(a, b);
                boolean ints = !decimals && (whole || lo == Math.floor(lo) && hi == Math.floor(hi));
                if (ints) {
                    long l = (long) Math.ceil(lo), h = (long) Math.floor(hi);
                    return h < l ? l : ThreadLocalRandom.current().nextLong(l, h + 1);
                }
                if (hi <= lo) return round(lo);
                return round(lo + ThreadLocalRandom.current().nextDouble() * (hi - lo));
            }
            return round(Double.parseDouble(t.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** [min, max] of a drop value, null if it is a fixed number. */
    public static double[] range(String value) {
        if (value == null) return null;
        String t = value.trim().toLowerCase(Locale.ROOT);
        int open = t.indexOf('('), close = t.lastIndexOf(')');
        if (open >= 0 && close > open) t = t.substring(open + 1, close);
        String[] parts = t.split("[;,]|(?<=\\d)\\s*-\\s*(?=\\d)|\\s+to\\s+");
        if (parts.length < 2) return null;
        try {
            double a = Double.parseDouble(parts[0].trim()), b = Double.parseDouble(parts[1].trim());
            return new double[]{Math.min(a, b), Math.max(a, b)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static double parseDouble(String s, double def) {
        try {
            double v = Double.parseDouble(s.trim());
            // "NaN" and "Infinity" are numbers for Java, not for us
            return Double.isNaN(v) || Double.isInfinite(v) ? def : v;
        } catch (Exception e) {
            return def;
        }
    }
}
