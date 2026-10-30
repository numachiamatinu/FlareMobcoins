package dev.tmdevelopment.flaremobcoins.hook;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.data.StatsManager;
import dev.tmdevelopment.flaremobcoins.data.Storage;
import dev.tmdevelopment.flaremobcoins.util.Num;
import dev.tmdevelopment.flaremobcoins.util.Time;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * %flaremobcoins_...%: mobcoins, mobcoins_formatted, mobcoins_commas, multiplier, global_multiplier, position,
 * accounts, top_&lt;n&gt;_name / _mobcoins / _mobcoins_formatted / _mobcoins_commas,
 * normal_shop_&lt;shop&gt;_time / premium_shop_&lt;shop&gt;_time, top_weekly_&lt;n&gt;_... / top_monthly_&lt;n&gt;_...,
 * position_weekly / position_monthly, kills, kills_&lt;mob&gt;, earned, earned_weekly, earned_monthly, golden_kills,
 * best_combo, combo, combo_multiplier, booster, booster_time, event_multiplier, event_time, event_active, hour_left.
 * The 3.x names keep working.
 */
public final class PapiExpansion extends PlaceholderExpansion {

    private static final Pattern SHOP_TIME = Pattern.compile("(normal|premium)_shop_(.+)_time");
    private final FlareMobcoins plugin;

    public PapiExpansion(FlareMobcoins plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "flaremobcoins";
    }

    @Override
    public String getAuthor() {
        return "TMDevelopment";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("top_weekly_") || p.startsWith("top_monthly_")) return periodTop(p);
        if (p.startsWith("top_")) return top(p);
        Matcher shop = SHOP_TIME.matcher(p);
        if (shop.matches()) return plugin.menus().timeLeft(shop.group(2), shop.group(1).equals("premium"));
        switch (p) {
            case "accounts":
                return String.valueOf(plugin.coins().accounts());
            case "global_multiplier":
            case "get_server_multiplier":
            case "server_multiplier":
                return Num.plain(plugin.coins().globalMultiplier());
            case "event_multiplier":
                return Num.plain(plugin.boosts().event());
            case "event_time":
                return plugin.boosts().eventActive() ? Time.format(plugin.boosts().eventLeft()) : "-";
            case "event_active":
                return String.valueOf(plugin.boosts().eventActive());
            default:
                break;
        }
        if (player == null) return "";
        double coins = plugin.coins().balance(player.getUniqueId());
        switch (p) {
            case "mobcoins":
            case "balance":
                return Num.plain(coins);
            case "get_mobcoins":
            case "mobcoins_fixed":
                return Num.fixed(coins);
            case "mobcoins_formatted":
            case "get_formatted_mobcoins":
            case "formatted":
                return Num.shortFormat(coins);
            case "mobcoins_commas":
            case "commas":
                return Num.commas(coins);
            case "get_commas_mobcoins":
                return Num.commasFixed(coins);
            case "multiplier":
            case "get_multiplier":
                return Num.plain(plugin.coins().multiplier(player.getUniqueId()));
            case "position": {
                List<Storage.Account> list = plugin.coins().top();
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).uuid.equals(player.getUniqueId())) return String.valueOf(i + 1);
                }
                return "-";
            }
            case "position_weekly":
                return position(plugin.stats().weekly(), player);
            case "position_monthly":
                return position(plugin.stats().monthly(), player);
            // stats of online players
            case "kills":
                return Num.plain(plugin.stats().get(player.getUniqueId(), StatsManager.KILLS));
            case "earned":
                return Num.commas(plugin.stats().get(player.getUniqueId(), StatsManager.EARNED));
            case "earned_weekly":
                return Num.commas(plugin.stats().get(player.getUniqueId(), StatsManager.week()));
            case "earned_monthly":
                return Num.commas(plugin.stats().get(player.getUniqueId(), StatsManager.month()));
            case "golden_kills":
                return Num.plain(plugin.stats().get(player.getUniqueId(), StatsManager.GOLDEN));
            case "best_combo":
                return Num.plain(plugin.stats().get(player.getUniqueId(), StatsManager.BEST_COMBO));
            case "combo":
                return String.valueOf(plugin.drops().combo(player.getUniqueId()));
            case "combo_multiplier":
                return Num.plain(plugin.drops().comboMultiplier(player.getUniqueId()));
            case "booster": {
                double[] b = plugin.boosts().best(player.getUniqueId());
                return b == null ? "1" : Num.plain(b[0]);
            }
            case "booster_time": {
                double[] b = plugin.boosts().best(player.getUniqueId());
                return b == null ? "-" : Time.format((long) b[1] - System.currentTimeMillis());
            }
            case "hour_left": {
                double left = plugin.drops().leftThisHour(player.getUniqueId());
                return left < 0 ? "-" : Num.commas(left);
            }
            default:
                if (p.startsWith("kills_")) {
                    String mob = params.substring(6);
                    double v = plugin.stats().get(player.getUniqueId(), StatsManager.KILLS + ":" + mob.toUpperCase(Locale.ROOT));
                    if (v == 0) v = plugin.stats().get(player.getUniqueId(), StatsManager.KILLS + ":mythic:" + mob);
                    return Num.plain(v);
                }
                return null;
        }
    }

    private static String position(List<Storage.Account> list, OfflinePlayer player) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).uuid.equals(player.getUniqueId())) return String.valueOf(i + 1);
        return "-";
    }

    /** top_weekly_&lt;n&gt;_name / _mobcoins / _mobcoins_formatted / _mobcoins_commas (also monthly). */
    private String periodTop(String p) {
        String[] parts = p.split("_", 4);
        if (parts.length < 4) return "";
        int pos;
        try {
            pos = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return "";
        }
        List<Storage.Account> list = parts[1].equals("weekly") ? plugin.stats().weekly() : plugin.stats().monthly();
        Storage.Account a = pos >= 1 && pos <= list.size() ? list.get(pos - 1) : null;
        switch (parts[3]) {
            case "name":
                return a != null && a.name != null && !a.name.isEmpty() ? a.name : "-";
            case "mobcoins":
                return a == null ? "0" : Num.plain(a.mobcoins);
            case "mobcoins_formatted":
                return a == null ? "0" : Num.shortFormat(a.mobcoins);
            case "mobcoins_commas":
                return a == null ? "0" : Num.commas(a.mobcoins);
            default:
                return "";
        }
    }

    private String top(String p) {
        String[] parts = p.split("_", 3);
        if (parts.length < 3) return "";
        int pos;
        try {
            pos = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return "";
        }
        List<Storage.Account> list = plugin.coins().top();
        Storage.Account a = pos >= 1 && pos <= list.size() ? list.get(pos - 1) : null;
        switch (parts[2]) {
            case "name":
                return a != null && a.name != null && !a.name.isEmpty() ? a.name : "-";
            case "mobcoins":
                return a == null ? "0" : Num.plain(a.mobcoins);
            case "mobcoins_formatted":
                return a == null ? "0" : Num.shortFormat(a.mobcoins);
            case "mobcoins_commas":
                return a == null ? "0" : Num.commas(a.mobcoins);
            default:
                return "";
        }
    }
}
