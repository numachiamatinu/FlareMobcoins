package dev.tmdevelopment.flaremobcoins.data;

import dev.tmdevelopment.flaremobcoins.FlareMobcoins;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * data/state.yml: the global multiplier and the state of the rotating shops (items, stock, last rotation).
 * Saved a moment after a change, on another thread.
 */
public final class StateFile {

    private final FlareMobcoins plugin;
    private final File file;
    private YamlConfiguration yaml = new YamlConfiguration();
    private boolean saveQueued;

    public StateFile(FlareMobcoins plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/state.yml");
    }

    public void load() {
        yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    public YamlConfiguration yaml() {
        return yaml;
    }

    /** Saves within a second (many changes in a row = one save). */
    public void save() {
        if (saveQueued) return;
        saveQueued = true;
        try {
            Bukkit.getScheduler().runTaskLater(plugin, this::saveNow, 20L);
        } catch (Throwable t) {
            saveNow();
        }
    }

    public void saveNow() {
        saveQueued = false;
        String text = yaml.saveToString();
        Runnable write = () -> {
            try {
                if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not save data/state.yml: " + e.getMessage());
            }
        };
        if (plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
                return;
            } catch (Throwable ignored) {
            }
        }
        write.run();
    }

    /** On shutdown: written right now on this thread. */
    public void saveSync() {
        saveQueued = false;
        try {
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            Files.write(file.toPath(), yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save data/state.yml: " + e.getMessage());
        }
    }
}
