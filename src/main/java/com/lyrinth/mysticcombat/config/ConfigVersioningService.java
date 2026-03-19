package com.lyrinth.mysticcombat.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigVersioningService {

    private final JavaPlugin plugin;

    public ConfigVersioningService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean ensureMerged(String fileName, String versionPath) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
            return true;
        }

        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaultConfig = loadDefaultConfig(fileName);
        if (defaultConfig == null) {
            plugin.getLogger().severe("Could not load bundled default " + fileName);
            return false;
        }

        Map<String, Object> userMap = toNestedMap(userConfig);
        Map<String, Object> defaultMap = toNestedMap(defaultConfig);
        Map<String, Object> merged = mergeDefaults(defaultMap, userMap);

        Object defaultVersion = readPath(defaultMap, versionPath);
        if (defaultVersion != null) {
            writePath(merged, versionPath, defaultVersion);
        }

        YamlConfiguration mergedConfig = new YamlConfiguration();
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            mergedConfig.set(entry.getKey(), entry.getValue());
        }

        try {
            mergedConfig.save(file);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save merged " + fileName + ": " + exception.getMessage());
            return false;
        }
    }

    static Map<String, Object> mergeDefaults(Map<String, Object> defaults, Map<String, Object> userValues) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(userValues);

        for (Map.Entry<String, Object> defaultEntry : defaults.entrySet()) {
            String key = defaultEntry.getKey();
            Object defaultValue = defaultEntry.getValue();

            if (!merged.containsKey(key)) {
                merged.put(key, defaultValue);
                continue;
            }

            Object userValue = merged.get(key);
            if (defaultValue instanceof Map<?, ?> defaultMap && userValue instanceof Map<?, ?> userMap) {
                merged.put(
                        key,
                        mergeDefaults(castMap(defaultMap), castMap(userMap))
                );
            }
        }

        return merged;
    }

    private YamlConfiguration loadDefaultConfig(String fileName) {
        try (InputStream stream = plugin.getResource(fileName)) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not read bundled " + fileName + ": " + exception.getMessage());
            return null;
        }
    }

    private Map<String, Object> toNestedMap(YamlConfiguration configuration) {
        return readSection(configuration);
    }

    private Map<String, Object> readSection(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                values.put(key, readSection(child));
            } else {
                values.put(key, section.get(key));
            }
        }
        return values;
    }

    private static Map<String, Object> castMap(Map<?, ?> input) {
        Map<String, Object> casted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            casted.put(key, entry.getValue());
        }
        return casted;
    }

    private static Object readPath(Map<String, Object> map, String path) {
        String[] keys = path.split("\\.");
        Object current = map;

        for (String key : keys) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(key);
            if (current == null) {
                return null;
            }
        }

        return current;
    }

    private static void writePath(Map<String, Object> map, String path, Object value) {
        String[] keys = path.split("\\.");
        Map<String, Object> current = map;

        for (int index = 0; index < keys.length - 1; index++) {
            String key = keys[index];
            Object next = current.get(key);

            if (next instanceof Map<?, ?> nextMap) {
                Map<String, Object> nextTyped = castMap(nextMap);
                current.put(key, nextTyped);
                current = nextTyped;
                continue;
            }

            Map<String, Object> created = new LinkedHashMap<>();
            current.put(key, created);
            current = created;
        }

        current.put(keys[keys.length - 1], value);
    }
}
