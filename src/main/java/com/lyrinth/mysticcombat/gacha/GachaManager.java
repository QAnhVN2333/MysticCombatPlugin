package com.lyrinth.mysticcombat.gacha;

import com.google.common.collect.Multimap;
import com.lyrinth.mysticcombat.Main;
import com.lyrinth.mysticcombat.api.event.PostRarityRollEvent;
import com.lyrinth.mysticcombat.api.event.PreRarityRollEvent;
import com.lyrinth.mysticcombat.api.event.RollOutcomeStatus;
import com.lyrinth.mysticcombat.api.event.RollTriggerSource;
import com.lyrinth.mysticcombat.config.ConfigManager;
import com.lyrinth.mysticcombat.custom.CustomAttributeDefinition;
import com.lyrinth.mysticcombat.custom.CustomAttributeManager;
import com.lyrinth.mysticcombat.util.Utils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class GachaManager {

    private static final String DUPLICATE_MODE_MERGE = "MERGE";
    private static final String RUNTIME_KEY_PREFIX = "attr_";
    private static final String RUNTIME_CUSTOM_KEY_PREFIX = RUNTIME_KEY_PREFIX + "custom_";
    private static final String RUNTIME_DUPLICATE_SEPARATOR = "__";
    private static final String RUNTIME_LORE_CACHE_SUFFIX = "_lore_cache";
    private static final String RUNTIME_ITEM_SCOPE_SUFFIX = "_item_scope";
    private static final double REMOVE_VALUE_EPSILON = 0.000001D;

    private final Main plugin;
    private final ConfigManager configManager;
    private final CustomAttributeManager customAttributeManager;
    private final Random random;

    public GachaManager(Main plugin, ConfigManager configManager) {
        this(plugin, configManager, null, createRandom(configManager));
    }

    public GachaManager(Main plugin, ConfigManager configManager, CustomAttributeManager customAttributeManager) {
        this(plugin, configManager, customAttributeManager, createRandom(configManager));
    }

    GachaManager(Main plugin, ConfigManager configManager, Random random) {
        this(plugin, configManager, null, random);
    }

    GachaManager(Main plugin, ConfigManager configManager, CustomAttributeManager customAttributeManager, Random random) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.customAttributeManager = customAttributeManager;
        this.random = random;
    }

    private static Random createRandom(ConfigManager configManager) {
        if (configManager == null || configManager.getSettings() == null) {
            return new Random();
        }

        long seed = configManager.getSettings().debugSeed();
        return seed >= 0 ? new Random(seed) : new Random();
    }

    public RollResult rollForItem(ItemStack itemStack) {
        return rollForItem(null, itemStack, RollTriggerSource.API);
    }

    public RollResult rollForItem(Player player, ItemStack itemStack, RollTriggerSource source) {
        return rollForItemInternal(player, itemStack, source, null);
    }

    public RollResult rollForItemWithRarity(ItemStack itemStack, String rarityId) {
        return rollForItemWithRarity(null, itemStack, RollTriggerSource.API, rarityId);
    }

    public RollResult rollForItemWithRarity(Player player, ItemStack itemStack, RollTriggerSource source, String rarityId) {
        return rollForItemInternal(player, itemStack, source, rarityId);
    }

    public boolean isItemRolled(ItemStack itemStack) {
        if (!isEditableItem(itemStack) || configManager == null || configManager.getSettings() == null) {
            return false;
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return false;
        }

        return hasRollMarker(meta, configManager.getSettings());
    }

    public AdminActionResult addAttributeToItem(ItemStack itemStack, String attributeId, double amount, boolean force) {
        if (!isEditableItem(itemStack)) {
            return AdminActionResult.failed("Invalid item");
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || configManager == null || configManager.getSettings() == null) {
            return AdminActionResult.failed("Config is unavailable");
        }

        // Keep default material attributes so custom values are additive.
        Utils.ensureVanillaBaseAttributes(itemStack.getType(), meta);

        ConfigManager.AttributeConfig config = findAttribute(attributeId);
        if (config == null) {
            return AdminActionResult.failed("Unknown attribute id '" + attributeId + "'");
        }

        ConfigManager.Settings settings = configManager.getSettings();
        Set<String> selectedIds = collectPluginAttributeIds(meta);

        if (!force) {
            if (!config.isApplicableTo(itemStack.getType())) {
                return AdminActionResult.failed("Attribute is not applicable to this item type");
            }
            if (amount < config.minValue() || amount > config.maxValue()) {
                return AdminActionResult.failed("Value is outside configured min/max range");
            }
            if (!settings.allowDuplicateAttributeIds() && selectedIds.contains(config.id())) {
                return AdminActionResult.failed("Duplicate attribute id is disabled in config");
            }
            if (isBlacklistedBySelection(config, selectedIds, configManager.getAttributesPool())) {
                return AdminActionResult.failed("Attribute conflicts with current blacklist selection");
            }
        }

        if (config.isCustom()) {
            CustomAttributeDefinition definition = customDefinitionFor(config);
            if (definition == null) {
                return AdminActionResult.failed("Missing custom attribute definition for '" + config.attributeKey() + "'");
            }
            if (!force && (amount < definition.minValue() || amount > definition.maxValue())) {
                return AdminActionResult.failed("Value is outside custom attribute value_limits");
            }

            String baseKeyPart = RUNTIME_CUSTOM_KEY_PREFIX + sanitizeKeyPart(config.id());
            NamespacedKey customKey = resolveCustomKeyForApply(meta, baseKeyPart, settings, force);

            double finalAmount = amount;
            if (isMergeDuplicateMode(settings)) {
                finalAmount = readCustomValue(meta, customKey) + amount;
            }

            removeExistingCustomValueWithSameKey(meta, customKey);
            writeCustomValue(meta, customKey, finalAmount);
        } else {
            String baseKeyPart = RUNTIME_KEY_PREFIX + sanitizeKeyPart(config.id());
            NamespacedKey modifierKey = resolveModifierKeyForApply(meta, config.attribute(), baseKeyPart, settings, force);

            double finalAmount = amount;
            if (isMergeDuplicateMode(settings)) {
                AttributeModifier existing = findModifierByKey(meta, config.attribute(), modifierKey);
                if (existing != null) {
                    finalAmount = existing.getAmount() + amount;
                } else {
                    AttributeModifier legacy = consumeLegacyMergeModifier(meta, config.attribute(), baseKeyPart, modifierKey);
                    if (legacy != null) {
                        finalAmount = legacy.getAmount() + amount;
                    }
                }
            }

            removeExistingModifierWithSameKey(meta, config.attribute(), modifierKey);

            AttributeModifier modifier = new AttributeModifier(
                    modifierKey,
                    finalAmount,
                    config.operation(),
                    config.slotGroup()
            );

            if (!meta.addAttributeModifier(config.attribute(), modifier)) {
                return AdminActionResult.failed("Could not add attribute modifier to item");
            }
        }

        rewritePluginLore(meta, settings, "");
        refreshRollMarker(meta, settings, "admin");

        itemStack.setItemMeta(meta);
        return AdminActionResult.success("Added attribute '" + config.id() + "' with value " + amount);
    }

    public AdminActionResult removeAttributeFromItem(ItemStack itemStack, String attributeId, Double amount) {
        if (!isEditableItem(itemStack)) {
            return AdminActionResult.failed("Invalid item");
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || configManager == null || configManager.getSettings() == null) {
            return AdminActionResult.failed("Config is unavailable");
        }

        ConfigManager.AttributeConfig config = findAttribute(attributeId);
        String targetId = config == null ? attributeId : config.id();

        List<PluginModifierInstance> removableVanilla = new ArrayList<>();
        for (PluginModifierInstance instance : collectPluginModifiers(meta)) {
            if (!instance.attributeId().equalsIgnoreCase(targetId)) {
                continue;
            }
            if (amount != null && Math.abs(instance.modifier().getAmount() - amount) > REMOVE_VALUE_EPSILON) {
                continue;
            }
            removableVanilla.add(instance);
        }

        for (PluginModifierInstance instance : removableVanilla) {
            meta.removeAttributeModifier(instance.attribute(), instance.modifier());
        }

        List<CustomAttributeInstance> removableCustom = findCustomInstancesById(meta, targetId, amount);
        for (CustomAttributeInstance instance : removableCustom) {
            meta.getPersistentDataContainer().remove(instance.key());
        }

        int removedTotal = removableVanilla.size() + removableCustom.size();
        if (removedTotal <= 0) {
            return AdminActionResult.failed("No matching attribute found on item");
        }

        ConfigManager.Settings settings = configManager.getSettings();
        rewritePluginLore(meta, settings, "");
        refreshRollMarker(meta, settings, "admin");

        itemStack.setItemMeta(meta);
        return AdminActionResult.success("Removed " + removedTotal + " modifier(s) for '" + targetId + "'");
    }

    public AdminActionResult cleanItem(ItemStack itemStack) {
        if (!isEditableItem(itemStack)) {
            return AdminActionResult.failed("Invalid item");
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || configManager == null || configManager.getSettings() == null) {
            return AdminActionResult.failed("Config is unavailable");
        }

        for (PluginModifierInstance instance : collectPluginModifiers(meta)) {
            meta.removeAttributeModifier(instance.attribute(), instance.modifier());
        }
        for (CustomAttributeInstance instance : collectCustomAttributeInstances(meta)) {
            meta.getPersistentDataContainer().remove(instance.key());
        }

        List<String> existingLore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();
        List<String> cleanedLore = stripPluginLoreSection(meta, configManager.getSettings(), existingLore);
        meta.setLore(cleanedLore.isEmpty() ? null : cleanedLore);

        clearRollMarkers(meta, configManager.getSettings());
        itemStack.setItemMeta(meta);
        return AdminActionResult.success("Cleaned all MysticCombat attributes from item");
    }

    public List<ConfigManager.AttributeConfig> listConfiguredAttributes() {
        return configManager.getAttributesPool().values().stream()
                .sorted(Comparator.comparing(ConfigManager.AttributeConfig::id))
                .toList();
    }

    public double getCustomAttributeTotal(ItemStack itemStack, String attributeKey) {
        if (!isEditableItem(itemStack) || attributeKey == null || attributeKey.isBlank()) {
            return 0.0D;
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || configManager == null) {
            return 0.0D;
        }

        Set<String> matchingIds = new LinkedHashSet<>();
        for (ConfigManager.AttributeConfig config : configManager.getAttributesPool().values()) {
            if (config.isCustom() && attributeKey.equalsIgnoreCase(config.attributeKey())) {
                matchingIds.add(config.id().toLowerCase(Locale.ROOT));
            }
        }
        if (matchingIds.isEmpty()) {
            return 0.0D;
        }

        double total = 0.0D;
        for (CustomAttributeInstance instance : collectCustomAttributeInstances(meta)) {
            if (matchingIds.contains(instance.attributeId().toLowerCase(Locale.ROOT))) {
                total += instance.amount();
            }
        }

        return total;
    }

    public NamespacedKey createRuntimeKey(String key) {
        if (plugin == null) {
            return new NamespacedKey("mysticcombat", key);
        }
        return new NamespacedKey(plugin, key);
    }

    public <T extends WeightedEntry> T pickWeighted(List<T> entries) {
        int totalWeight = entries.stream().mapToInt(WeightedEntry::weight).sum();
        if (entries.isEmpty() || totalWeight <= 0) {
            return null;
        }

        int randomValue = random.nextInt(totalWeight);
        int cursor = 0;

        // Weighted picker: each entry occupies a [cursor, cursor+weight) range.
        for (T entry : entries) {
            cursor += entry.weight();
            if (randomValue < cursor) {
                return entry;
            }
        }

        return entries.get(entries.size() - 1);
    }

    private RollResult rollForItemInternal(
            Player player,
            ItemStack itemStack,
            RollTriggerSource source,
            String initialForcedRarityId
    ) {
        RollTriggerSource resolvedSource = source == null ? RollTriggerSource.API : source;

        if (!isEditableItem(itemStack)) {
            return completeRoll(itemStack, player, resolvedSource, RollOutcomeStatus.SKIPPED, null, 0, "Invalid item");
        }
        if (configManager == null || configManager.getSettings() == null) {
            return completeRoll(itemStack, player, resolvedSource, RollOutcomeStatus.SKIPPED, null, 0, "Config is unavailable");
        }
        if (plugin != null && plugin.getServer() != null && !plugin.getServer().isPrimaryThread()) {
            return RollResult.skipped("Rarity roll must run on main thread");
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return completeRoll(itemStack, player, resolvedSource, RollOutcomeStatus.SKIPPED, null, 0, "Item meta is unavailable");
        }

        ConfigManager.Settings settings = configManager.getSettings();
        if (hasRollMarker(meta, settings)) {
            return completeRoll(itemStack, player, resolvedSource, RollOutcomeStatus.SKIPPED, null, 0, "Item already rolled");
        }

        // Re-apply default Material modifiers first so vanilla stats are not lost.
        Utils.ensureVanillaBaseAttributes(itemStack.getType(), meta);

        String forcedRarityId = normalizeRarityId(initialForcedRarityId);
        if (plugin != null) {
            PreRarityRollEvent preEvent = new PreRarityRollEvent(player, itemStack, resolvedSource, forcedRarityId);
            plugin.getServer().getPluginManager().callEvent(preEvent);
            if (preEvent.isCancelled()) {
                return completeRoll(
                        itemStack,
                        player,
                        resolvedSource,
                        RollOutcomeStatus.CANCELLED,
                        null,
                        0,
                        "Roll cancelled by pre-roll event"
                );
            }
            forcedRarityId = normalizeRarityId(preEvent.getForcedRarityId());
        }

        ConfigManager.RarityConfig rarity = resolveRarity(forcedRarityId);
        if (rarity == null) {
            String reason;
            if (forcedRarityId != null) {
                reason = "Unknown forced rarity '" + forcedRarityId + "'";
                if (plugin != null) {
                    plugin.getLogger().warning("Skipping rarity roll because forced rarity is invalid: " + forcedRarityId);
                }
            } else {
                reason = "No valid rarity configured";
            }
            return completeRoll(itemStack, player, resolvedSource, RollOutcomeStatus.SKIPPED, null, 0, reason);
        }

        int statsTarget = pickStatsTarget(settings, rarity);
        if (statsTarget <= 0) {
            return completeRoll(itemStack, player, resolvedSource, RollOutcomeStatus.SKIPPED, rarity.id(), 0, "Rolled zero stats");
        }

        List<ConfigManager.AttributeConfig> baseCandidates = findCandidates(itemStack.getType(), rarity.id());
        if (baseCandidates.isEmpty()) {
            return completeRoll(itemStack, player, resolvedSource, RollOutcomeStatus.SKIPPED, rarity.id(), 0, "No matching attributes for item type");
        }

        Map<String, ConfigManager.AttributeConfig> allAttributesById = configManager.getAttributesPool();
        List<AppliedAttribute> appliedAttributes = new ArrayList<>();
        Set<String> selectedIds = new LinkedHashSet<>();
        Map<String, Integer> sessionCounters = new LinkedHashMap<>();

        int attempts = 0;
        int maxAttempts = Math.max(statsTarget, settings.maxRollAttemptsPerItem());

        while (appliedAttributes.size() < statsTarget && attempts < maxAttempts) {
            attempts++;

            List<WeightedAttribute> eligible = toWeightedAttributes(findEligibleCandidates(
                    baseCandidates,
                    allAttributesById,
                    selectedIds,
                    settings.allowDuplicateAttributeIds()
            ));
            if (eligible.isEmpty()) {
                break;
            }

            WeightedAttribute picked = pickWeighted(eligible);
            if (picked == null) {
                break;
            }

            ConfigManager.AttributeConfig config = picked.attributeConfig();
            ConfigManager.ValueRange valueRange = config.resolveValueRange(rarity.id());
            double rolledAmount = rollValue(valueRange.min(), valueRange.max());

            boolean applied;
            if (config.isCustom()) {
                String baseKeyPart = RUNTIME_CUSTOM_KEY_PREFIX + sanitizeKeyPart(config.id());
                applied = applyCustomAttribute(meta, config, rolledAmount, baseKeyPart, settings, sessionCounters);
            } else {
                String baseKeyPart = RUNTIME_KEY_PREFIX + sanitizeKeyPart(config.id());
                applied = applyVanillaAttribute(meta, config, rolledAmount, baseKeyPart, settings, sessionCounters);
            }

            if (!applied) {
                continue;
            }

            selectedIds.add(config.id());
            appliedAttributes.add(new AppliedAttribute(config, rolledAmount));
        }

        if (appliedAttributes.isEmpty()) {
            return completeRoll(itemStack, player, resolvedSource, RollOutcomeStatus.SKIPPED, rarity.id(), 0, "Could not apply any attribute");
        }

        rewritePluginLore(meta, settings, rarity.prefix());
        refreshRollMarker(meta, settings, rarity.id());

        itemStack.setItemMeta(meta);
        return completeRoll(
                itemStack,
                player,
                resolvedSource,
                RollOutcomeStatus.SUCCESS,
                rarity.id(),
                appliedAttributes.size(),
                "Rolled " + appliedAttributes.size() + " stats with rarity " + rarity.id()
        );
    }

    private ConfigManager.RarityConfig resolveRarity(String forcedRarityId) {
        if (forcedRarityId == null) {
            return pickRarity();
        }
        return findRarity(forcedRarityId);
    }

    private String normalizeRarityId(String rarityId) {
        if (rarityId == null) {
            return null;
        }
        String trimmed = rarityId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private RollResult completeRoll(
            ItemStack itemStack,
            Player player,
            RollTriggerSource source,
            RollOutcomeStatus status,
            String rarityId,
            int appliedStatsCount,
            String reason
    ) {
        firePostRarityRollEvent(itemStack, player, source, status, rarityId, appliedStatsCount, reason);
        if (status == RollOutcomeStatus.SUCCESS) {
            return RollResult.success(reason);
        }
        return RollResult.skipped(reason);
    }

    private void firePostRarityRollEvent(
            ItemStack itemStack,
            Player player,
            RollTriggerSource source,
            RollOutcomeStatus status,
            String rarityId,
            int appliedStatsCount,
            String reason
    ) {
        if (plugin == null || itemStack == null) {
            return;
        }

        PostRarityRollEvent event = new PostRarityRollEvent(
                player,
                itemStack,
                source,
                status,
                rarityId,
                appliedStatsCount,
                reason
        );
        plugin.getServer().getPluginManager().callEvent(event);
    }

    private boolean applyVanillaAttribute(
            ItemMeta meta,
            ConfigManager.AttributeConfig config,
            double rolledAmount,
            String baseKeyPart,
            ConfigManager.Settings settings,
            Map<String, Integer> sessionCounters
    ) {
        NamespacedKey modifierKey = resolveModifierKey(meta, config.attribute(), baseKeyPart, settings, sessionCounters);

        double finalAmount = rolledAmount;
        if (isMergeDuplicateMode(settings)) {
            AttributeModifier existing = findModifierByKey(meta, config.attribute(), modifierKey);
            if (existing != null) {
                finalAmount = existing.getAmount() + rolledAmount;
            } else {
                AttributeModifier legacy = consumeLegacyMergeModifier(meta, config.attribute(), baseKeyPart, modifierKey);
                if (legacy != null) {
                    finalAmount = legacy.getAmount() + rolledAmount;
                }
            }
        }

        removeExistingModifierWithSameKey(meta, config.attribute(), modifierKey);

        AttributeModifier modifier = new AttributeModifier(
                modifierKey,
                finalAmount,
                config.operation(),
                config.slotGroup()
        );

        return meta.addAttributeModifier(config.attribute(), modifier);
    }

    private boolean applyCustomAttribute(
            ItemMeta meta,
            ConfigManager.AttributeConfig config,
            double rolledAmount,
            String baseKeyPart,
            ConfigManager.Settings settings,
            Map<String, Integer> sessionCounters
    ) {
        CustomAttributeDefinition definition = customDefinitionFor(config);
        if (definition == null) {
            if (plugin != null) {
                plugin.getLogger().warning("Skipping custom attribute '" + config.id() + "' because definition is missing: " + config.attributeKey());
            }
            return false;
        }

        if (rolledAmount < definition.minValue() || rolledAmount > definition.maxValue()) {
            return false;
        }

        NamespacedKey customKey = resolveCustomKey(meta, baseKeyPart, settings, sessionCounters);
        double finalAmount = rolledAmount;
        if (isMergeDuplicateMode(settings)) {
            finalAmount = readCustomValue(meta, customKey) + rolledAmount;
        }

        removeExistingCustomValueWithSameKey(meta, customKey);
        writeCustomValue(meta, customKey, finalAmount);
        return true;
    }

    private ConfigManager.RarityConfig pickRarity() {
        List<WeightedRarity> rarities = configManager.getRarities()
                .values()
                .stream()
                .map(WeightedRarity::new)
                .toList();
        WeightedRarity picked = pickWeighted(rarities);
        return picked == null ? null : picked.rarityConfig();
    }

    private ConfigManager.RarityConfig findRarity(String rarityId) {
        if (rarityId == null || rarityId.isBlank()) {
            return null;
        }
        for (ConfigManager.RarityConfig rarity : configManager.getRarities().values()) {
            if (rarity.id().equalsIgnoreCase(rarityId)) {
                return rarity;
            }
        }
        return null;
    }

    private ConfigManager.AttributeConfig findAttribute(String attributeId) {
        if (attributeId == null || attributeId.isBlank()) {
            return null;
        }
        for (ConfigManager.AttributeConfig attribute : configManager.getAttributesPool().values()) {
            if (attribute.id().equalsIgnoreCase(attributeId)) {
                return attribute;
            }
        }
        return null;
    }

    private int pickStatsTarget(ConfigManager.Settings settings, ConfigManager.RarityConfig rarity) {
        int min = Math.max(0, Math.max(settings.minAttributesPerItem(), rarity.minStats()));
        int max = Math.max(min, Math.min(settings.maxAttributesPerItem(), rarity.maxStats()));

        if (min == max) {
            return min;
        }
        return random.nextInt(max - min + 1) + min;
    }

    private List<ConfigManager.AttributeConfig> findCandidates(Material material, String rarityId) {
        return configManager.getAttributesPool()
                .values()
                .stream()
                .filter(attribute -> hasRarity(attribute, rarityId))
                .filter(attribute -> attribute.isApplicableTo(material))
                .toList();
    }

    List<ConfigManager.AttributeConfig> findEligibleCandidates(
            List<ConfigManager.AttributeConfig> candidates,
            Map<String, ConfigManager.AttributeConfig> allAttributesById,
            Set<String> selectedIds,
            boolean allowDuplicate
    ) {
        List<ConfigManager.AttributeConfig> eligible = new ArrayList<>();

        for (ConfigManager.AttributeConfig candidate : candidates) {
            if (!allowDuplicate && selectedIds.contains(candidate.id())) {
                continue;
            }
            if (isBlacklistedBySelection(candidate, selectedIds, allAttributesById)) {
                continue;
            }
            eligible.add(candidate);
        }

        return eligible;
    }

    private List<WeightedAttribute> toWeightedAttributes(List<ConfigManager.AttributeConfig> eligible) {
        return eligible.stream().map(WeightedAttribute::new).toList();
    }

    private boolean isBlacklistedBySelection(
            ConfigManager.AttributeConfig candidate,
            Set<String> selectedIds,
            Map<String, ConfigManager.AttributeConfig> allAttributesById
    ) {
        String candidateLower = candidate.id().toLowerCase(Locale.ROOT);

        for (String selectedId : selectedIds) {
            String selectedLower = selectedId.toLowerCase(Locale.ROOT);
            if (candidate.blacklistLowercaseSet().contains(selectedLower)) {
                return true;
            }

            ConfigManager.AttributeConfig selectedConfig = allAttributesById.get(selectedId);
            if (selectedConfig == null) {
                continue;
            }

            if (selectedConfig.blacklistLowercaseSet().contains(candidateLower)) {
                return true;
            }
        }

        return false;
    }

    private NamespacedKey resolveModifierKey(
            ItemMeta meta,
            Attribute attribute,
            String baseKeyPart,
            ConfigManager.Settings settings,
            Map<String, Integer> sessionCounters
    ) {
        String scopedBaseKeyPart = withItemScope(baseKeyPart, meta, settings);
        if (!settings.allowDuplicateAttributeIds() || isMergeDuplicateMode(settings)) {
            return createRuntimeKey(scopedBaseKeyPart);
        }

        int index = Math.max(1, sessionCounters.getOrDefault(scopedBaseKeyPart, 1));
        while (hasModifierWithKey(meta, attribute, createRuntimeKey(scopedBaseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + index))) {
            index++;
        }
        sessionCounters.put(scopedBaseKeyPart, index + 1);
        return createRuntimeKey(scopedBaseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + index);
    }

    private NamespacedKey resolveModifierKeyForApply(
            ItemMeta meta,
            Attribute attribute,
            String baseKeyPart,
            ConfigManager.Settings settings,
            boolean force
    ) {
        String scopedBaseKeyPart = withItemScope(baseKeyPart, meta, settings);
        if (!settings.allowDuplicateAttributeIds() && !force) {
            return createRuntimeKey(scopedBaseKeyPart);
        }
        if (isMergeDuplicateMode(settings)) {
            return createRuntimeKey(scopedBaseKeyPart);
        }

        int index = 1;
        while (hasModifierWithKey(meta, attribute, createRuntimeKey(scopedBaseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + index))) {
            index++;
        }
        return createRuntimeKey(scopedBaseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + index);
    }

    private NamespacedKey resolveCustomKey(
            ItemMeta meta,
            String baseKeyPart,
            ConfigManager.Settings settings,
            Map<String, Integer> sessionCounters
    ) {
        if (!settings.allowDuplicateAttributeIds() || isMergeDuplicateMode(settings)) {
            return createRuntimeKey(baseKeyPart);
        }

        int index = Math.max(1, sessionCounters.getOrDefault(baseKeyPart, 1));
        while (hasCustomValueWithKey(meta, createRuntimeKey(baseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + index))) {
            index++;
        }
        sessionCounters.put(baseKeyPart, index + 1);
        return createRuntimeKey(baseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + index);
    }

    private NamespacedKey resolveCustomKeyForApply(
            ItemMeta meta,
            String baseKeyPart,
            ConfigManager.Settings settings,
            boolean force
    ) {
        if (!settings.allowDuplicateAttributeIds() && !force) {
            return createRuntimeKey(baseKeyPart);
        }
        if (isMergeDuplicateMode(settings)) {
            return createRuntimeKey(baseKeyPart);
        }

        int index = 1;
        while (hasCustomValueWithKey(meta, createRuntimeKey(baseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + index))) {
            index++;
        }
        return createRuntimeKey(baseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + index);
    }

    private AttributeModifier consumeLegacyMergeModifier(
            ItemMeta meta,
            Attribute attribute,
            String baseKeyPart,
            NamespacedKey resolvedKey
    ) {
        NamespacedKey legacyKey = createRuntimeKey(baseKeyPart);
        if (legacyKey.equals(resolvedKey)) {
            return null;
        }

        AttributeModifier legacyModifier = findModifierByKey(meta, attribute, legacyKey);
        if (legacyModifier != null) {
            meta.removeAttributeModifier(attribute, legacyModifier);
        }
        return legacyModifier;
    }

    private String withItemScope(String baseKeyPart, ItemMeta meta, ConfigManager.Settings settings) {
        String itemScope = getOrCreateItemScope(meta, settings);
        return baseKeyPart + RUNTIME_DUPLICATE_SEPARATOR + itemScope;
    }

    private String getOrCreateItemScope(ItemMeta meta, ConfigManager.Settings settings) {
        NamespacedKey scopeKey = createRuntimeKey(settings.pdcKey() + RUNTIME_ITEM_SCOPE_SUFFIX);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String existing = pdc.get(scopeKey, PersistentDataType.STRING);
        if (existing != null && !existing.isBlank()) {
            return sanitizeKeyPart(existing);
        }

        String generated = UUID.randomUUID().toString().replace("-", "");
        pdc.set(scopeKey, PersistentDataType.STRING, generated);
        return generated;
    }

    private boolean hasModifierWithKey(ItemMeta meta, Attribute attribute, NamespacedKey key) {
        Iterable<AttributeModifier> modifiers = meta.getAttributeModifiers(attribute);
        if (modifiers == null) {
            return false;
        }

        for (AttributeModifier modifier : modifiers) {
            if (key.equals(modifier.getKey())) {
                return true;
            }
        }
        return false;
    }

    private AttributeModifier findModifierByKey(ItemMeta meta, Attribute attribute, NamespacedKey key) {
        Iterable<AttributeModifier> modifiers = meta.getAttributeModifiers(attribute);
        if (modifiers == null) {
            return null;
        }

        for (AttributeModifier modifier : modifiers) {
            if (key.equals(modifier.getKey())) {
                return modifier;
            }
        }
        return null;
    }

    private void removeExistingModifierWithSameKey(ItemMeta meta, Attribute attribute, NamespacedKey key) {
        if (!meta.hasAttributeModifiers()) {
            return;
        }

        Iterable<AttributeModifier> existingModifiers = meta.getAttributeModifiers(attribute);
        if (existingModifiers == null) {
            return;
        }

        List<AttributeModifier> existing = new ArrayList<>();
        existingModifiers.forEach(existing::add);
        for (AttributeModifier modifier : existing) {
            if (key.equals(modifier.getKey())) {
                meta.removeAttributeModifier(attribute, modifier);
            }
        }
    }

    private boolean hasCustomValueWithKey(ItemMeta meta, NamespacedKey key) {
        return meta.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    private void removeExistingCustomValueWithSameKey(ItemMeta meta, NamespacedKey key) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(key, PersistentDataType.STRING)) {
            pdc.remove(key);
        }
    }

    private void writeCustomValue(ItemMeta meta, NamespacedKey key, double value) {
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, Double.toString(value));
    }

    private double readCustomValue(ItemMeta meta, NamespacedKey key) {
        String raw = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return 0.0D;
        }

        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    private CustomAttributeDefinition customDefinitionFor(ConfigManager.AttributeConfig config) {
        if (config == null || !config.isCustom() || customAttributeManager == null) {
            return null;
        }
        return customAttributeManager.get(config.attributeKey());
    }

    private double rollValue(double min, double max) {
        if (Double.compare(min, max) == 0) {
            return min;
        }
        return min + (max - min) * random.nextDouble();
    }

    private void rewritePluginLore(ItemMeta meta, ConfigManager.Settings settings, String rarityPrefixRaw) {
        List<String> existingLore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();
        List<String> baseLore = stripPluginLoreSection(meta, settings, existingLore);

        List<PluginStatInstance> pluginStats = collectPluginStats(meta);
        if (pluginStats.isEmpty()) {
            meta.setLore(baseLore.isEmpty() ? null : baseLore);
            clearLoreCache(meta, settings);
            return;
        }

        List<String> rolledLore = new ArrayList<>();
        String rarityPrefix = Utils.colorize(rarityPrefixRaw == null ? "" : rarityPrefixRaw);

        for (PluginStatInstance instance : pluginStats) {
            ConfigManager.AttributeConfig config = instance.config();
            String id = config == null ? instance.attributeId() : config.id();
            AttributeModifier.Operation operation = config == null ? AttributeModifier.Operation.ADD_NUMBER : config.operation();

            String formattedValue = Utils.formatLoreValue(instance.amount(), settings.decimalPlaces(), operation);
            String template = config == null || config.loreFormat() == null || config.loreFormat().isBlank()
                    ? "{value} " + id
                    : config.loreFormat();

            String lineBody = template.replace("{value}", formattedValue);
            rolledLore.add(Utils.colorize(settings.statLinePrefix() + lineBody));
        }

        if (!rarityPrefix.isBlank()) {
            rolledLore.add("");
            rolledLore.add(rarityPrefix);
        }

        List<String> rebuiltLore = new ArrayList<>(baseLore);
        rebuiltLore.addAll(rolledLore);
        meta.setLore(rebuiltLore);
        writeLoreCache(meta, settings, rolledLore);
    }

    private List<String> stripPluginLoreSection(ItemMeta meta, ConfigManager.Settings settings, List<String> lore) {
        List<String> cleaned = new ArrayList<>(lore);
        List<String> cachedPluginLore = readLoreCache(meta, settings);
        if (!cachedPluginLore.isEmpty()) {
            removeTrailingBlock(cleaned, cachedPluginLore);
        }
        return cleaned;
    }

    private void removeTrailingBlock(List<String> lore, List<String> block) {
        if (block.isEmpty() || lore.size() < block.size()) {
            return;
        }

        int start = lore.size() - block.size();
        for (int i = 0; i < block.size(); i++) {
            if (!block.get(i).equals(lore.get(start + i))) {
                return;
            }
        }

        for (int i = lore.size() - 1; i >= start; i--) {
            lore.remove(i);
        }
    }

    private List<String> readLoreCache(ItemMeta meta, ConfigManager.Settings settings) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String serialized = pdc.get(createRuntimeKey(settings.pdcKey() + RUNTIME_LORE_CACHE_SUFFIX), PersistentDataType.STRING);
        if (serialized == null || serialized.isEmpty()) {
            return List.of();
        }
        return List.of(serialized.split("\\n", -1));
    }

    private void writeLoreCache(ItemMeta meta, ConfigManager.Settings settings, List<String> pluginLoreLines) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(
                createRuntimeKey(settings.pdcKey() + RUNTIME_LORE_CACHE_SUFFIX),
                PersistentDataType.STRING,
                String.join("\n", pluginLoreLines)
        );
    }

    private void clearLoreCache(ItemMeta meta, ConfigManager.Settings settings) {
        meta.getPersistentDataContainer().remove(createRuntimeKey(settings.pdcKey() + RUNTIME_LORE_CACHE_SUFFIX));
    }

    private boolean hasRollMarker(ItemMeta meta, ConfigManager.Settings settings) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey key = createRuntimeKey(settings.pdcKey());

        // Backward/forward compatible marker check to prevent bypass when type changes.
        return pdc.has(key, PersistentDataType.BYTE) || pdc.has(key, PersistentDataType.STRING);
    }

    private void refreshRollMarker(ItemMeta meta, ConfigManager.Settings settings, String rarityId) {
        Set<String> selectedIds = collectPluginAttributeIds(meta);
        if (selectedIds.isEmpty()) {
            clearRollMarkers(meta, settings);
            return;
        }
        writeRollMarker(meta, settings, rarityId, selectedIds);
    }

    private void clearRollMarkers(ItemMeta meta, ConfigManager.Settings settings) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(createRuntimeKey(settings.pdcKey()));
        pdc.remove(createRuntimeKey(settings.pdcKey() + "_rarity"));
        pdc.remove(createRuntimeKey(settings.pdcKey() + "_attribute_ids"));
        pdc.remove(createRuntimeKey(settings.pdcKey() + RUNTIME_ITEM_SCOPE_SUFFIX));
        clearLoreCache(meta, settings);
    }

    private void writeRollMarker(
            ItemMeta meta,
            ConfigManager.Settings settings,
            String rarityId,
            Set<String> selectedIds
    ) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey markerKey = createRuntimeKey(settings.pdcKey());
        String type = settings.pdcDataType() == null ? "BYTE" : settings.pdcDataType().toUpperCase(Locale.ROOT);

        if ("STRING".equals(type)) {
            pdc.set(markerKey, PersistentDataType.STRING, "1");
        } else {
            pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        }

        // Store debug metadata for migration/auditing.
        pdc.set(createRuntimeKey(settings.pdcKey() + "_rarity"), PersistentDataType.STRING, rarityId);
        pdc.set(
                createRuntimeKey(settings.pdcKey() + "_attribute_ids"),
                PersistentDataType.STRING,
                String.join(",", selectedIds)
        );
    }

    private Set<String> collectPluginAttributeIds(ItemMeta meta) {
        Set<String> ids = new LinkedHashSet<>();
        for (PluginModifierInstance instance : collectPluginModifiers(meta)) {
            ids.add(instance.attributeId());
        }
        for (CustomAttributeInstance instance : collectCustomAttributeInstances(meta)) {
            ids.add(instance.attributeId());
        }
        return ids;
    }

    private List<PluginStatInstance> collectPluginStats(ItemMeta meta) {
        List<PluginStatInstance> stats = new ArrayList<>();

        for (PluginModifierInstance instance : collectPluginModifiers(meta)) {
            stats.add(new PluginStatInstance(
                    instance.attributeId(),
                    instance.modifier().getAmount(),
                    instance.modifier().getKey().toString(),
                    instance.config()
            ));
        }

        for (CustomAttributeInstance instance : collectCustomAttributeInstances(meta)) {
            stats.add(new PluginStatInstance(
                    instance.attributeId(),
                    instance.amount(),
                    instance.key().toString(),
                    instance.config()
            ));
        }

        stats.sort(Comparator.comparing(PluginStatInstance::sortKey));
        return stats;
    }

    private List<PluginModifierInstance> collectPluginModifiers(ItemMeta meta) {
        List<PluginModifierInstance> collected = new ArrayList<>();
        Multimap<Attribute, AttributeModifier> modifiers = meta.getAttributeModifiers();
        if (modifiers == null || modifiers.isEmpty()) {
            return collected;
        }

        Map<String, String> sanitizedToId = new LinkedHashMap<>();
        for (ConfigManager.AttributeConfig config : configManager.getAttributesPool().values()) {
            if (config.isVanilla()) {
                sanitizedToId.put(sanitizeKeyPart(config.id()), config.id());
            }
        }

        for (Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
            AttributeModifier modifier = entry.getValue();
            NamespacedKey key = modifier.getKey();
            if (!isPluginVanillaKey(key)) {
                continue;
            }

            String attributeId = extractAttributeId(key.getKey(), RUNTIME_KEY_PREFIX, sanitizedToId);
            if (attributeId == null) {
                continue;
            }

            collected.add(new PluginModifierInstance(
                    attributeId,
                    entry.getKey(),
                    modifier,
                    configManager.getAttributesPool().get(attributeId)
            ));
        }

        collected.sort(Comparator.comparing(instance -> instance.modifier().getKey().toString()));
        return collected;
    }

    private List<CustomAttributeInstance> collectCustomAttributeInstances(ItemMeta meta) {
        List<CustomAttributeInstance> collected = new ArrayList<>();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        Map<String, String> sanitizedToId = new LinkedHashMap<>();
        for (ConfigManager.AttributeConfig config : configManager.getAttributesPool().values()) {
            if (config.isCustom()) {
                sanitizedToId.put(sanitizeKeyPart(config.id()), config.id());
            }
        }

        for (NamespacedKey key : pdc.getKeys()) {
            if (!isPluginCustomKey(key)) {
                continue;
            }

            String attributeId = extractAttributeId(key.getKey(), RUNTIME_CUSTOM_KEY_PREFIX, sanitizedToId);
            if (attributeId == null) {
                continue;
            }

            String raw = pdc.get(key, PersistentDataType.STRING);
            if (raw == null || raw.isBlank()) {
                continue;
            }

            double amount;
            try {
                amount = Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                continue;
            }

            collected.add(new CustomAttributeInstance(
                    attributeId,
                    key,
                    amount,
                    configManager.getAttributesPool().get(attributeId)
            ));
        }

        collected.sort(Comparator.comparing(instance -> instance.key().toString()));
        return collected;
    }

    private List<CustomAttributeInstance> findCustomInstancesById(ItemMeta meta, String targetId, Double amount) {
        List<CustomAttributeInstance> matches = new ArrayList<>();
        for (CustomAttributeInstance instance : collectCustomAttributeInstances(meta)) {
            if (!instance.attributeId().equalsIgnoreCase(targetId)) {
                continue;
            }
            if (amount != null && Math.abs(instance.amount() - amount) > REMOVE_VALUE_EPSILON) {
                continue;
            }
            matches.add(instance);
        }
        return matches;
    }

    private boolean isPluginVanillaKey(NamespacedKey key) {
        if (key == null) {
            return false;
        }

        String expectedNamespace = createRuntimeKey("dummy").getNamespace();
        if (!expectedNamespace.equals(key.getNamespace())) {
            return false;
        }

        String runtimeKey = key.getKey();
        return runtimeKey.startsWith(RUNTIME_KEY_PREFIX) && !runtimeKey.startsWith(RUNTIME_CUSTOM_KEY_PREFIX);
    }

    private boolean isPluginCustomKey(NamespacedKey key) {
        if (key == null) {
            return false;
        }

        String expectedNamespace = createRuntimeKey("dummy").getNamespace();
        return expectedNamespace.equals(key.getNamespace()) && key.getKey().startsWith(RUNTIME_CUSTOM_KEY_PREFIX);
    }

    private String extractAttributeId(String fullKey, String prefix, Map<String, String> sanitizedToId) {
        if (fullKey == null || !fullKey.startsWith(prefix)) {
            return null;
        }

        String sanitized = fullKey.substring(prefix.length());
        int duplicateIndex = sanitized.indexOf(RUNTIME_DUPLICATE_SEPARATOR);
        if (duplicateIndex >= 0) {
            sanitized = sanitized.substring(0, duplicateIndex);
        }

        return sanitizedToId.get(sanitized);
    }

    private boolean hasRarity(ConfigManager.AttributeConfig attribute, String rarityId) {
        for (String rarity : attribute.rarities()) {
            if (rarityId.equalsIgnoreCase(rarity)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMergeDuplicateMode(ConfigManager.Settings settings) {
        return DUPLICATE_MODE_MERGE.equalsIgnoreCase(settings.duplicateModifierMode());
    }

    private boolean isEditableItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return false;
        }
        return itemStack.getItemMeta() != null;
    }

    private String sanitizeKeyPart(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    public interface WeightedEntry {
        int weight();
    }

    private record WeightedRarity(ConfigManager.RarityConfig rarityConfig) implements WeightedEntry {
        @Override
        public int weight() {
            return rarityConfig.weight();
        }
    }

    private record WeightedAttribute(ConfigManager.AttributeConfig attributeConfig) implements WeightedEntry {
        @Override
        public int weight() {
            return attributeConfig.weight();
        }
    }

    private record AppliedAttribute(ConfigManager.AttributeConfig attributeConfig, double amount) {
    }

    private record PluginModifierInstance(
            String attributeId,
            Attribute attribute,
            AttributeModifier modifier,
            ConfigManager.AttributeConfig config
    ) {
    }

    private record PluginStatInstance(
            String attributeId,
            double amount,
            String sortKey,
            ConfigManager.AttributeConfig config
    ) {
    }

    private record CustomAttributeInstance(
            String attributeId,
            NamespacedKey key,
            double amount,
            ConfigManager.AttributeConfig config
    ) {
    }

    public record RollResult(boolean rolled, String message) {
        public static RollResult success(String message) {
            return new RollResult(true, message);
        }

        public static RollResult skipped(String message) {
            return new RollResult(false, message);
        }
    }

    public record AdminActionResult(boolean success, String message) {
        public static AdminActionResult success(String message) {
            return new AdminActionResult(true, message);
        }

        public static AdminActionResult failed(String message) {
            return new AdminActionResult(false, message);
        }
    }
}
