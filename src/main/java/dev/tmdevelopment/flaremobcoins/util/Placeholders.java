package dev.tmdevelopment.flaremobcoins.util;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.hook.PapiHook;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {placeholders} in texts. Every {name} also works in the 3.x form %pl_name%.
 * Then PlaceholderAPI, when installed.
 */
public final class Placeholders {

    private static final Pattern MISSING = Pattern.compile("\\{missing:([0-9.,kKmMbB]+)}");
    private static boolean papi;

    private Placeholders() {
    }

    public static void setPapi(boolean on) {
        papi = on;
    }

    public static boolean papi() {
        return papi;
    }

    /** Replaces pairs ("{amount}", "10", ...). "{x}" also replaces "%pl_x%". */
    public static String pairs(String text, String... pairs) {
        if (text == null) return "";
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = pairs[i];
            String value = pairs[i + 1] == null ? "" : pairs[i + 1];
            if (text.indexOf(key) >= 0) text = text.replace(key, value);
            if (key.length() > 2 && key.charAt(0) == '{' && key.charAt(key.length() - 1) == '}') {
                String old = "%pl_" + key.substring(1, key.length() - 1) + "%";
                if (text.indexOf(old) >= 0) text = text.replace(old, value);
            }
        }
        return text;
    }

    /** Context pairs first, then the player values, then PlaceholderAPI. */
    public static String apply(String text, OfflinePlayer player, String... pairs) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        text = pairs(text, pairs);
        FlareMobcoins plugin = FlareMobcoins.get();
        if (plugin != null) {
            text = pairs(text, "{prefix}", plugin.lang().prefix(), "{version}", plugin.getDescription().getVersion());
            if (plugin.coins() != null && text.contains("global_multiplier")) {
                text = pairs(text, "{global_multiplier}", Num.plain(plugin.coins().globalMultiplier()));
            }
            if (player != null && (text.indexOf('{') >= 0 || text.contains("%pl_"))) {
                double coins = plugin.coins() == null ? 0 : plugin.coins().balance(player.getUniqueId());
                double mult = plugin.coins() == null ? 1 : plugin.coins().multiplier(player.getUniqueId());
                if (text.contains("{booster") && plugin.boosts() != null) {
                    double[] b = plugin.boosts().best(player.getUniqueId());
                    text = pairs(text, "{booster_time}", b == null ? "-" : Time.format((long) b[1] - System.currentTimeMillis()),
                            "{booster}", b == null ? "1" : Num.plain(b[0]));
                }
                if (text.contains("{event_") && plugin.boosts() != null) {
                    text = pairs(text, "{event_multiplier}", Num.plain(plugin.boosts().event()),
                            "{event_time}", plugin.boosts().eventActive() ? Time.format(plugin.boosts().eventLeft()) : "-");
                }
                if ((text.contains("{kills}") || text.contains("{earned")) && plugin.stats() != null) {
                    java.util.UUID id = player.getUniqueId();
                    text = pairs(text, "{kills}", Num.commas(plugin.stats().get(id, "kills")),
                            "{earned_weekly}", Num.commas(plugin.stats().get(id, dev.tmdevelopment.flaremobcoins.data.StatsManager.week())),
                            "{earned_monthly}", Num.commas(plugin.stats().get(id, dev.tmdevelopment.flaremobcoins.data.StatsManager.month())),
                            "{earned}", Num.commas(plugin.stats().get(id, "earned")));
                }
                text = pairs(text,
                        "{player}", player.getName(),
                        "{mobcoins_formatted}", Num.shortFormat(coins),
                        "{mobcoins_commas}", Num.commas(coins),
                        "{mobcoins}", Num.plain(coins),
                        "{multiplier}", Num.plain(mult),
                        "{world}", player instanceof Player ? ((Player) player).getWorld().getName() : "");
                if (text.contains("{missing:")) {
                    Matcher m = MISSING.matcher(text);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        double need = Num.parse(m.group(1));
                        m.appendReplacement(sb, Matcher.quoteReplacement(Num.commas(Math.max(0, need - coins))));
                    }
                    m.appendTail(sb);
                    text = sb.toString();
                }
            }
        }
        if (papi && player != null && text.indexOf('%') >= 0) text = PapiHook.apply(player, text);
        return text;
    }
}
