package dev.tmdevelopment.flaremobcoins.config;

import dev.tmdevelopment.flaremobcoins.util.J8;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Changes one value of a file of the plugin (config.yml, drops.yml ...) keeping its comments. */
public final class ConfigWriter {

    private ConfigWriter() {
    }

    /** @return true if the file now has the value */
    public static boolean set(Plugin plugin, String file, String path, Object value) {
        File f = new File(plugin.getDataFolder(), file);
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String written = value instanceof String ? YamlPatch.quote((String) value) : String.valueOf(value);
            String changed = YamlPatch.set(text, path, written);
            if (changed.equals(text)) {
                return text.contains(path.substring(path.lastIndexOf('.') + 1) + ": " + written);
            }
            J8.write(f.toPath(), changed, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not change " + path + " in " + file + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean setList(Plugin plugin, String file, String path, List<String> values) {
        File f = new File(plugin.getDataFolder(), file);
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String changed = YamlPatch.setList(text, path, values);
            if (changed.equals(text)) return false;
            J8.write(f.toPath(), changed, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not change " + path + " in " + file + ": " + e.getMessage());
            return false;
        }
    }
}
