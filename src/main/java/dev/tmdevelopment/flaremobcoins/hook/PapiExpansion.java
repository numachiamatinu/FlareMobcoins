package dev.tmdevelopment.flaremobcoins.hook;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import dev.tmdevelopment.flaremobcoins.data.Storage;
import dev.tmdevelopment.flaremobcoins.util.Num;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * %flaremobcoins_...%: mobcoins, mobcoins_formatted, mobcoins_commas, multiplier, global_multiplier, position,
 * accounts, top_&lt;n&gt;_name / _mobcoins / _mobcoins_formatted / _mobcoins_commas,
 * normal_shop_&lt;shop&gt;_time / premium_shop_&lt;shop&gt;_time. The 3.x names keep working.
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
            default:
                return null;
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
