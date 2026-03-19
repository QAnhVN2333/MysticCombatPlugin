package com.lyrinth.mysticcombat.config;

import com.lyrinth.mysticcombat.util.Utils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public final class MessagesManager {

    private final JavaPlugin plugin;
    private FileConfiguration messagesConfig;

    public MessagesManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messagesConfig = YamlConfiguration.loadConfiguration(file);
        return true;
    }

    public String getVersion() {
        if (messagesConfig == null) {
            return "unknown";
        }
        return messagesConfig.getString("messages_version", "unknown");
    }

    public String get(String path) {
        if (messagesConfig == null) {
            return path;
        }
        String value = messagesConfig.getString(path, path);
        return Utils.colorize(value);
    }

    public String format(String path, Map<String, String> placeholders) {
        String result = get(path);
        if (placeholders == null || placeholders.isEmpty()) {
            return result;
        }

        String formatted = result;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            formatted = formatted.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return formatted;
    }
}

