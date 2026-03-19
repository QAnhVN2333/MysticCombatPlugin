package com.lyrinth.mysticcombat.config;

import com.lyrinth.mysticcombat.util.Utils;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigManager {

    private final JavaPlugin plugin;

    private String configVersion;
    private Settings settings;
    private Map<String, RarityConfig> rarities;
    private Map<String, AttributeConfig> attributesPool;

    private static final Pattern CUSTOM_ATTRIBUTE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z0-9_.-]+$");

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean load() {
        FileConfiguration config = plugin.getConfig();

        if (!validateRequiredSections(config)) {
            return false;
        }

        this.configVersion = config.getString("config_version", "1");
        this.settings = parseSettings(config.getConfigurationSection("settings"));
        this.rarities = parseRarities(config.getConfigurationSection("rarities"));
        this.attributesPool = parseAttributes(config.getConfigurationSection("attributes_pool"));

        plugin.getLogger().info("Loaded config version " + configVersion + " with " + rarities.size()
                + " rarities and " + attributesPool.size() + " attribute entries.");
        return true;
    }

    private boolean validateRequiredSections(FileConfiguration config) {
        List<String> required = List.of("settings", "rarities", "attributes_pool");

        for (String section : required) {
            if (!config.isConfigurationSection(section)) {
                plugin.getLogger().severe("Missing required section: " + section);
                return false;
            }
        }
        return true;
    }

    private Settings parseSettings(ConfigurationSection section) {
        if (section == null) {
            return new Settings(
                    true,
                    true,
                    true,
                    "gacha_rolled",
                    "BYTE",
                    1,
                    4,
                    false,
                    "UNIQUE",
                    20,
                    "APPEND",
                    "&7> ",
                    true,
                    1,
                    false,
                    -1L,
                    true,
                    "mysticcombat.reload",
                    "mysticcombat.admin"
            );
        }

        ConfigurationSection triggers = section.getConfigurationSection("triggers");
        ConfigurationSection roll = section.getConfigurationSection("roll");
        ConfigurationSection lore = section.getConfigurationSection("lore");
        ConfigurationSection debug = section.getConfigurationSection("debug");
        ConfigurationSection commands = section.getConfigurationSection("commands");

        String appendMode = lore != null ? lore.getString("append_mode", "APPEND") : "APPEND";
        String normalizedAppendMode = normalizeAppendMode(appendMode);
        String duplicateMode = roll != null ? roll.getString("duplicate_modifier_mode", "UNIQUE") : "UNIQUE";

        return new Settings(
                triggers != null && triggers.getBoolean("crafting", true),
                triggers != null && triggers.getBoolean("enchanting", true),
                triggers != null && triggers.getBoolean("anvil", true),
                section.getString("pdc_key", "gacha_rolled"),
                section.getString("pdc_data_type", "BYTE"),
                roll != null ? roll.getInt("min_attributes_per_item", 1) : 1,
                roll != null ? roll.getInt("max_attributes_per_item", 4) : 4,
                roll != null && roll.getBoolean("allow_duplicate_attribute_ids", false),
                normalizeDuplicateMode(duplicateMode),
                roll != null ? roll.getInt("max_roll_attempts_per_item", 20) : 20,
                normalizedAppendMode,
                lore != null ? lore.getString("stat_line_prefix", "&7> ") : "&7> ",
                lore == null || lore.getBoolean("show_rarity_prefix_once", true),
                lore != null ? lore.getInt("decimal_places", 1) : 1,
                debug != null && debug.getBoolean("enabled", false),
                debug != null ? debug.getLong("seed", -1L) : -1L,
                debug == null || debug.getBoolean("log_invalid_entries", true),
                commands != null ? commands.getString("reload_permission", "mysticcombat.reload") : "mysticcombat.reload",
                commands != null ? commands.getString("admin_permission", "mysticcombat.admin") : "mysticcombat.admin"
        );
    }

    private Map<String, RarityConfig> parseRarities(ConfigurationSection section) {
        Map<String, RarityConfig> map = new LinkedHashMap<>();
        if (section == null) {
            return Collections.unmodifiableMap(map);
        }

        for (String rarityId : section.getKeys(false)) {
            ConfigurationSection raritySection = section.getConfigurationSection(rarityId);
            if (raritySection == null) {
                continue;
            }

            ConfigurationSection statsAmount = raritySection.getConfigurationSection("stats_amount");
            int minStats = statsAmount != null ? statsAmount.getInt("min", 1) : 1;
            int maxStats = statsAmount != null ? statsAmount.getInt("max", 1) : 1;

            if (minStats > maxStats) {
                logInvalid("Rarity '" + rarityId + "' has min stats greater than max stats.");
                continue;
            }

            int weight = raritySection.getInt("weight", 0);
            if (weight <= 0) {
                logInvalid("Rarity '" + rarityId + "' has non-positive weight.");
                continue;
            }

            map.put(rarityId, new RarityConfig(
                    rarityId,
                    weight,
                    raritySection.getString("prefix", ""),
                    minStats,
                    maxStats
            ));
        }

        return Collections.unmodifiableMap(map);
    }

    private Map<String, AttributeConfig> parseAttributes(ConfigurationSection section) {
        Map<String, AttributeConfig> map = new LinkedHashMap<>();
        if (section == null) {
            return Collections.unmodifiableMap(map);
        }

        for (String attributeId : section.getKeys(false)) {
            ConfigurationSection attributeSection = section.getConfigurationSection(attributeId);
            if (attributeSection == null) {
                continue;
            }

            AttributeDescriptor descriptor = parseAttributeDescriptor(attributeSection.getString("attribute"));
            if (descriptor == null) {
                logInvalid("Attribute entry '" + attributeId + "' contains invalid attribute string.");
                continue;
            }

            AttributeModifier.Operation operation = parseOperation(attributeSection.getString("operation"));
            EquipmentSlotGroup slotGroup = parseSlotGroup(attributeSection.getString("slot"));

            if (descriptor.type() == AttributeType.VANILLA && (operation == null || slotGroup == null)) {
                logInvalid("Attribute entry '" + attributeId + "' contains invalid operation/slot for vanilla attribute.");
                continue;
            }

            if (descriptor.type() == AttributeType.CUSTOM) {
                if (operation == null) {
                    operation = AttributeModifier.Operation.ADD_NUMBER;
                }
                if (slotGroup == null) {
                    slotGroup = EquipmentSlotGroup.MAINHAND;
                }
            }

            int weight = attributeSection.getInt("weight", 0);
            if (weight <= 0) {
                logInvalid("Attribute entry '" + attributeId + "' has non-positive weight.");
                continue;
            }

            ConfigurationSection valueSection = attributeSection.getConfigurationSection("value");
            ValueRange defaultValueRange = parseDefaultValueRange(attributeId, valueSection);
            if (defaultValueRange == null) {
                continue;
            }

            Map<String, ValueRange> rarityValueRanges = parseRarityValueRanges(attributeId, valueSection);

            List<String> rarities = attributeSection.getStringList("rarities");
            if (rarities.isEmpty()) {
                logInvalid("Attribute entry '" + attributeId + "' has empty rarities list.");
                continue;
            }

            List<String> applicableItems = attributeSection.getStringList("applicable_items");
            if (applicableItems.isEmpty()) {
                logInvalid("Attribute entry '" + attributeId + "' has empty applicable_items list.");
                continue;
            }

            List<String> blacklist = attributeSection.getStringList("blacklist_attributes");
            String loreFormat = attributeSection.getString("lore_format", "");

            map.put(attributeId, new AttributeConfig(
                    attributeId,
                    descriptor.attributeKey(),
                    descriptor.type(),
                    descriptor.customId(),
                    descriptor.attribute(),
                    operation,
                    slotGroup,
                    rarities,
                    weight,
                    defaultValueRange.min(),
                    defaultValueRange.max(),
                    rarityValueRanges,
                    applicableItems,
                    blacklist,
                    lowerCaseSet(blacklist),
                    loreFormat
            ));
        }

        return Collections.unmodifiableMap(map);
    }

    private AttributeDescriptor parseAttributeDescriptor(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String trimmed = input.trim();
        String normalizedUpper = trimmed.toUpperCase(Locale.ROOT);

        if (normalizedUpper.startsWith("GENERIC_") || normalizedUpper.startsWith("PLAYER_")) {
            Attribute attribute = parseAttribute(normalizedUpper);
            if (attribute == null) {
                return null;
            }
            return new AttributeDescriptor(trimmed, AttributeType.VANILLA, null, attribute);
        }

        if (!CUSTOM_ATTRIBUTE_PATTERN.matcher(trimmed).matches()) {
            return null;
        }

        String customId = trimmed.substring(trimmed.indexOf('.') + 1);
        return new AttributeDescriptor(trimmed, AttributeType.CUSTOM, customId, null);
    }

    private ValueRange parseDefaultValueRange(String attributeId, ConfigurationSection valueSection) {
        double minValue = valueSection != null ? valueSection.getDouble("min", 0.0D) : 0.0D;
        double maxValue = valueSection != null ? valueSection.getDouble("max", 0.0D) : 0.0D;
        if (minValue > maxValue) {
            logInvalid("Attribute entry '" + attributeId + "' has invalid min/max value range.");
            return null;
        }
        return new ValueRange(minValue, maxValue);
    }

    private Map<String, ValueRange> parseRarityValueRanges(String attributeId, ConfigurationSection valueSection) {
        Map<String, ValueRange> ranges = new LinkedHashMap<>();
        if (valueSection == null) {
            return Collections.unmodifiableMap(ranges);
        }

        for (String key : valueSection.getKeys(false)) {
            if (!valueSection.isConfigurationSection(key)) {
                continue;
            }

            ConfigurationSection raritySection = valueSection.getConfigurationSection(key);
            if (raritySection == null) {
                continue;
            }

            double min = raritySection.getDouble("min", Double.NaN);
            double max = raritySection.getDouble("max", Double.NaN);
            if (Double.isNaN(min) || Double.isNaN(max)) {
                logInvalid("Attribute entry '" + attributeId + "' rarity '" + key + "' is missing min/max.");
                continue;
            }
            if (min > max) {
                logInvalid("Attribute entry '" + attributeId + "' rarity '" + key + "' has invalid min/max value range.");
                continue;
            }

            ranges.put(key.toLowerCase(Locale.ROOT), new ValueRange(min, max));
        }

        return Collections.unmodifiableMap(ranges);
    }

    private Set<String> lowerCaseSet(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }

        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private String normalizeDuplicateMode(String duplicateMode) {
        if (duplicateMode == null || duplicateMode.isBlank()) {
            return "UNIQUE";
        }

        String normalized = duplicateMode.toUpperCase(Locale.ROOT);
        if ("UNIQUE".equals(normalized) || "MERGE".equals(normalized)) {
            return normalized;
        }

        logInvalid("Unknown settings.roll.duplicate_modifier_mode '" + duplicateMode + "'. Falling back to UNIQUE.");
        return "UNIQUE";
    }

    private String normalizeAppendMode(String appendMode) {
        if (appendMode == null || appendMode.isBlank()) {
            return "APPEND";
        }

        String normalized = appendMode.toUpperCase(Locale.ROOT);
        if ("APPEND".equals(normalized) || "REPLACE_PLUGIN_SECTION".equals(normalized)) {
            return normalized;
        }
        if ("REPLACE".equals(normalized)) {
            return "REPLACE_PLUGIN_SECTION";
        }

        logInvalid("Unknown settings.lore.append_mode '" + appendMode + "'. Falling back to APPEND.");
        return "APPEND";
    }

    private Attribute parseAttribute(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            return Attribute.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private AttributeModifier.Operation parseOperation(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            return AttributeModifier.Operation.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private EquipmentSlotGroup parseSlotGroup(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        return EquipmentSlotGroup.getByName(input.toLowerCase(Locale.ROOT));
    }

    private void logInvalid(String message) {
        if (settings == null || settings.logInvalidEntries()) {
            plugin.getLogger().warning(message);
        }
    }

    public String getConfigVersion() {
        return configVersion;
    }

    public Settings getSettings() {
        return settings;
    }

    public Map<String, RarityConfig> getRarities() {
        return rarities;
    }

    public Map<String, AttributeConfig> getAttributesPool() {
        return attributesPool;
    }

    public record Settings(
            boolean craftingTrigger,
            boolean enchantingTrigger,
            boolean anvilTrigger,
            String pdcKey,
            String pdcDataType,
            int minAttributesPerItem,
            int maxAttributesPerItem,
            boolean allowDuplicateAttributeIds,
            String duplicateModifierMode,
            int maxRollAttemptsPerItem,
            String appendMode,
            String statLinePrefix,
            boolean showRarityPrefixOnce,
            int decimalPlaces,
            boolean debugEnabled,
            long debugSeed,
            boolean logInvalidEntries,
            String reloadPermission,
            String adminPermission
    ) {
    }

    public record RarityConfig(
            String id,
            int weight,
            String prefix,
            int minStats,
            int maxStats
    ) {
    }

    public record AttributeConfig(
            String id,
            String attributeKey,
            AttributeType attributeType,
            String customId,
            Attribute attribute,
            AttributeModifier.Operation operation,
            EquipmentSlotGroup slotGroup,
            List<String> rarities,
            int weight,
            double minValue,
            double maxValue,
            Map<String, ValueRange> rarityValueRanges,
            List<String> applicableItems,
            List<String> blacklistAttributes,
            Set<String> blacklistLowercaseSet,
            String loreFormat
    ) {
        public ValueRange resolveValueRange(String rarityId) {
            if (rarityId == null || rarityId.isBlank() || rarityValueRanges == null) {
                return new ValueRange(minValue, maxValue);
            }

            ValueRange range = rarityValueRanges.get(rarityId.toLowerCase(Locale.ROOT));
            return range != null ? range : new ValueRange(minValue, maxValue);
        }

        public boolean isApplicableTo(Material material) {
            String materialName = material.name();
            for (String pattern : applicableItems) {
                if (Utils.wildcardMatches(materialName, pattern)) {
                    return true;
                }
            }
            return false;
        }

        public Set<String> raritySet() {
            return Set.copyOf(rarities == null ? new ArrayList<>() : rarities);
        }

        public boolean isVanilla() {
            return attributeType == AttributeType.VANILLA;
        }

        public boolean isCustom() {
            return attributeType == AttributeType.CUSTOM;
        }

        public String displayAttributeName() {
            if (isVanilla() && attribute != null) {
                return attribute.name();
            }
            return attributeKey == null ? id : attributeKey;
        }
    }

    public enum AttributeType {
        VANILLA,
        CUSTOM
    }

    private record AttributeDescriptor(
            String attributeKey,
            AttributeType type,
            String customId,
            Attribute attribute
    ) {
    }

    public record ValueRange(double min, double max) {
    }
}
