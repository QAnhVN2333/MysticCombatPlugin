package com.lyrinth.mysticcombat.custom;

import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CustomAttributeManager {

    private final JavaPlugin plugin;

    private String version;
    private Map<String, CustomAttributeDefinition> definitions;

    public CustomAttributeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.definitions = Collections.emptyMap();
        this.version = "unknown";
    }

    public boolean load() {
        File file = new File(plugin.getDataFolder(), "custom_attributes.yml");
        if (!file.exists()) {
            plugin.saveResource("custom_attributes.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        this.version = config.getString("custom_attributes_version", "1");
        this.definitions = parseDefinitions(config.getConfigurationSection("custom_attributes"));

        plugin.getLogger().info("Loaded custom attributes version " + version + " with " + definitions.size() + " definitions.");
        return true;
    }

    public String getVersion() {
        return version;
    }

    public CustomAttributeDefinition get(String attributeKey) {
        if (attributeKey == null || attributeKey.isBlank()) {
            return null;
        }
        return definitions.get(attributeKey.toLowerCase(Locale.ROOT));
    }

    public Map<String, CustomAttributeDefinition> getAll() {
        return definitions;
    }

    private Map<String, CustomAttributeDefinition> parseDefinitions(ConfigurationSection section) {
        Map<String, CustomAttributeDefinition> parsed = new LinkedHashMap<>();
        if (section == null) {
            return Collections.unmodifiableMap(parsed);
        }

        for (String attributeKey : section.getKeys(false)) {
            ConfigurationSection attributeSection = section.getConfigurationSection(attributeKey);
            if (attributeSection == null) {
                continue;
            }

            String pdcKey = attributeSection.getString("pdc_key", "").trim();
            if (pdcKey.isEmpty()) {
                plugin.getLogger().warning("Custom attribute '" + attributeKey + "' is missing pdc_key.");
                continue;
            }

            AttributeModifier.Operation defaultOperation = parseOperation(attributeSection.getString("default_operation"));
            if (defaultOperation == null) {
                defaultOperation = AttributeModifier.Operation.ADD_NUMBER;
            }

            ConfigurationSection valueLimits = attributeSection.getConfigurationSection("value_limits");
            double min = valueLimits == null ? Double.NEGATIVE_INFINITY : valueLimits.getDouble("min", Double.NEGATIVE_INFINITY);
            double max = valueLimits == null ? Double.POSITIVE_INFINITY : valueLimits.getDouble("max", Double.POSITIVE_INFINITY);
            if (min > max) {
                plugin.getLogger().warning("Custom attribute '" + attributeKey + "' has invalid value_limits min/max.");
                continue;
            }

            double fallbackMultiplier = readFallbackMultiplier(attributeSection);

            CustomAttributeDefinition definition = new CustomAttributeDefinition(
                    attributeKey,
                    attributeSection.getString("type", "GENERIC"),
                    pdcKey,
                    defaultOperation,
                    min,
                    max,
                    fallbackMultiplier
            );
            parsed.put(attributeKey.toLowerCase(Locale.ROOT), definition);
        }

        return Collections.unmodifiableMap(parsed);
    }

    private AttributeModifier.Operation parseOperation(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            return AttributeModifier.Operation.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private double readFallbackMultiplier(ConfigurationSection attributeSection) {
        ConfigurationSection runtimeSection = attributeSection.getConfigurationSection("runtime");
        if (runtimeSection == null) {
            return 1.5D;
        }

        for (Map<?, ?> effect : runtimeSection.getMapList("effects")) {
            Object raw = effect.get("fallback_multiplier");
            if (raw instanceof Number number) {
                return number.doubleValue();
            }
            if (raw instanceof String text) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                    return 1.5D;
                }
            }
        }

        return 1.5D;
    }
}

