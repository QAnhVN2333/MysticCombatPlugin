package com.lyrinth.mysticcombat.gacha;

import com.lyrinth.mysticcombat.Main;
import com.lyrinth.mysticcombat.api.event.PostRarityRollEvent;
import com.lyrinth.mysticcombat.api.event.PreRarityRollEvent;
import com.lyrinth.mysticcombat.api.event.RollOutcomeStatus;
import com.lyrinth.mysticcombat.config.ConfigManager;
import com.lyrinth.mysticcombat.custom.CustomAttributeDefinition;
import com.lyrinth.mysticcombat.custom.CustomAttributeManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GachaManagerCustomFlowTest {

    @Test
    void addCustomAttributeWritesPdcAndMarker() {
        // Build deterministic manager with a single custom attribute.
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig critRate = createCustomAttribute("custom_crit_rate", "MysticCombat.CRIT_RATE", 100, 0.0D, 100.0D);

        Map<String, CustomAttributeDefinition> definitions = Map.of(
                "mysticcombat.crit_rate",
                new CustomAttributeDefinition("MysticCombat.CRIT_RATE", "CHANCE_ON_HIT", "crit_rate", AttributeModifier.Operation.ADD_NUMBER, 0.0D, 100.0D, 1.5D)
        );

        GachaManager manager = createManager(
                settings,
                Map.of("Common", rarity),
                Map.of(critRate.id(), critRate),
                definitions,
                42L
        );

        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);

        GachaManager.AdminActionResult result = manager.addAttributeToItem(item.itemStack(), "custom_crit_rate", 25.0D, false);

        assertTrue(result.success());
        assertEquals("25.0", item.stringValues().get(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_rate")));
        assertTrue(item.byteValues().containsKey(new NamespacedKey("mysticcombat", "gacha_rolled")));
        assertEquals(25.0D, manager.getCustomAttributeTotal(item.itemStack(), "MysticCombat.CRIT_RATE"), 0.000001D);
    }

    @Test
    void removeCustomAttributeRemovesOnlyMatchingEntry() {
        // Build manager with two custom attributes sharing the same item.
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig critRate = createCustomAttribute("custom_crit_rate", "MysticCombat.CRIT_RATE", 100, 0.0D, 100.0D);
        ConfigManager.AttributeConfig critDamage = createCustomAttribute("custom_crit_damage", "MysticCombat.CRIT_DAMAGE", 100, 1.0D, 5.0D);

        Map<String, CustomAttributeDefinition> definitions = Map.of(
                "mysticcombat.crit_rate",
                new CustomAttributeDefinition("MysticCombat.CRIT_RATE", "CHANCE_ON_HIT", "crit_rate", AttributeModifier.Operation.ADD_NUMBER, 0.0D, 100.0D, 1.5D),
                "mysticcombat.crit_damage",
                new CustomAttributeDefinition("MysticCombat.CRIT_DAMAGE", "MULTIPLIER", "crit_damage", AttributeModifier.Operation.ADD_NUMBER, 1.0D, 5.0D, 1.5D)
        );

        Map<String, ConfigManager.AttributeConfig> attributes = new LinkedHashMap<>();
        attributes.put(critRate.id(), critRate);
        attributes.put(critDamage.id(), critDamage);

        GachaManager manager = createManager(settings, Map.of("Common", rarity), attributes, definitions, 42L);
        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);

        item.stringValues().put(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_rate"), "15.0");
        item.stringValues().put(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_damage"), "2.0");

        GachaManager.AdminActionResult removed = manager.removeAttributeFromItem(item.itemStack(), "custom_crit_rate", 15.0D);

        assertTrue(removed.success());
        assertFalse(item.stringValues().containsKey(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_rate")));
        assertTrue(item.stringValues().containsKey(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_damage")));
    }

    @Test
    void cleanItemRemovesAllCustomValuesAndMarkers() {
        // Build manager and pre-fill runtime keys to simulate a rolled custom item.
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig critRate = createCustomAttribute("custom_crit_rate", "MysticCombat.CRIT_RATE", 100, 0.0D, 100.0D);

        Map<String, CustomAttributeDefinition> definitions = Map.of(
                "mysticcombat.crit_rate",
                new CustomAttributeDefinition("MysticCombat.CRIT_RATE", "CHANCE_ON_HIT", "crit_rate", AttributeModifier.Operation.ADD_NUMBER, 0.0D, 100.0D, 1.5D)
        );

        GachaManager manager = createManager(
                settings,
                Map.of("Common", rarity),
                Map.of(critRate.id(), critRate),
                definitions,
                42L
        );

        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);
        item.stringValues().put(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_rate"), "20.0");
        item.byteValues().put(new NamespacedKey("mysticcombat", "gacha_rolled"), (byte) 1);
        item.stringValues().put(new NamespacedKey("mysticcombat", "gacha_rolled_rarity"), "Common");
        item.stringValues().put(new NamespacedKey("mysticcombat", "gacha_rolled_attribute_ids"), "custom_crit_rate");

        GachaManager.AdminActionResult cleaned = manager.cleanItem(item.itemStack());

        assertTrue(cleaned.success());
        assertEquals(0.0D, manager.getCustomAttributeTotal(item.itemStack(), "MysticCombat.CRIT_RATE"), 0.000001D);
        assertFalse(item.byteValues().containsKey(new NamespacedKey("mysticcombat", "gacha_rolled")));
        assertFalse(item.stringValues().containsKey(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_rate")));
    }

    @Test
    void rollForItemAppliesCustomAttributeWithinConfiguredRange() {
        // Build deterministic roll config with exactly one custom candidate and fixed value.
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig critRate = createCustomAttribute("custom_crit_rate", "MysticCombat.CRIT_RATE", 100, 7.0D, 7.0D);

        Map<String, CustomAttributeDefinition> definitions = Map.of(
                "mysticcombat.crit_rate",
                new CustomAttributeDefinition("MysticCombat.CRIT_RATE", "CHANCE_ON_HIT", "crit_rate", AttributeModifier.Operation.ADD_NUMBER, 0.0D, 100.0D, 1.5D)
        );

        GachaManager manager = createManager(
                settings,
                Map.of("Common", rarity),
                Map.of(critRate.id(), critRate),
                definitions,
                42L
        );

        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);
        GachaManager.RollResult result = manager.rollForItem(item.itemStack());

        assertTrue(result.rolled());
        assertEquals("7.0", item.stringValues().get(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_rate")));
        assertEquals(7.0D, manager.getCustomAttributeTotal(item.itemStack(), "MysticCombat.CRIT_RATE"), 0.000001D);
    }

    @Test
    void rollForItemSkipsWhenCustomDefinitionIsMissing() {
        // Build custom candidate without definition to verify safe skip behavior.
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig critRate = createCustomAttribute("custom_crit_rate", "MysticCombat.CRIT_RATE", 100, 7.0D, 7.0D);

        GachaManager manager = createManager(
                settings,
                Map.of("Common", rarity),
                Map.of(critRate.id(), critRate),
                Map.of(),
                42L
        );

        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);
        GachaManager.RollResult result = manager.rollForItem(item.itemStack());

        assertFalse(result.rolled());
        assertEquals("Could not apply any attribute", result.message());
        assertFalse(item.stringValues().containsKey(new NamespacedKey("mysticcombat", "attr_custom_custom_crit_rate")));
    }

    @Test
    void preEventCanCancelRollWithoutChangingItem() {
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig critRate = createCustomAttribute("custom_crit_rate", "MysticCombat.CRIT_RATE", 100, 5.0D, 5.0D);

        Map<String, CustomAttributeDefinition> definitions = Map.of(
                "mysticcombat.crit_rate",
                new CustomAttributeDefinition("MysticCombat.CRIT_RATE", "CHANCE_ON_HIT", "crit_rate", AttributeModifier.Operation.ADD_NUMBER, 0.0D, 100.0D, 1.5D)
        );

        List<PostRarityRollEvent> postEvents = new ArrayList<>();
        Main plugin = createPluginWithEventHook(event -> {
            if (event instanceof PreRarityRollEvent preEvent) {
                preEvent.setCancelled(true);
            }
            if (event instanceof PostRarityRollEvent postEvent) {
                postEvents.add(postEvent);
            }
        });

        GachaManager manager = createManager(
                plugin,
                settings,
                Map.of("Common", rarity),
                Map.of(critRate.id(), critRate),
                definitions,
                42L
        );

        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);
        GachaManager.RollResult result = manager.rollForItem(item.itemStack());

        assertFalse(result.rolled());
        assertEquals("Roll cancelled by pre-roll event", result.message());
        assertFalse(item.byteValues().containsKey(new NamespacedKey("mysticcombat", "gacha_rolled")));
        assertEquals(1, postEvents.size());
        assertEquals(RollOutcomeStatus.CANCELLED, postEvents.get(0).getStatus());
        assertEquals(0, postEvents.get(0).getAppliedStatsCount());
    }

    @Test
    void preEventCanForceValidRarity() {
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig common = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.RarityConfig rare = new ConfigManager.RarityConfig("Rare", 1, "", 1, 1);
        ConfigManager.AttributeConfig rareOnlyCrit = createCustomAttribute(
                "rare_only_crit_rate",
                "MysticCombat.CRIT_RATE",
                100,
                9.0D,
                9.0D,
                List.of("Rare")
        );

        Map<String, CustomAttributeDefinition> definitions = Map.of(
                "mysticcombat.crit_rate",
                new CustomAttributeDefinition("MysticCombat.CRIT_RATE", "CHANCE_ON_HIT", "crit_rate", AttributeModifier.Operation.ADD_NUMBER, 0.0D, 100.0D, 1.5D)
        );

        List<PostRarityRollEvent> postEvents = new ArrayList<>();
        Main plugin = createPluginWithEventHook(event -> {
            if (event instanceof PreRarityRollEvent preEvent) {
                preEvent.setForcedRarityId("Rare");
            }
            if (event instanceof PostRarityRollEvent postEvent) {
                postEvents.add(postEvent);
            }
        });

        GachaManager manager = createManager(
                plugin,
                settings,
                Map.of("Common", common, "Rare", rare),
                Map.of(rareOnlyCrit.id(), rareOnlyCrit),
                definitions,
                42L
        );

        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);
        GachaManager.RollResult result = manager.rollForItem(item.itemStack());

        assertTrue(result.rolled());
        assertEquals("9.0", item.stringValues().get(new NamespacedKey("mysticcombat", "attr_custom_rare_only_crit_rate")));
        assertEquals("Rare", item.stringValues().get(new NamespacedKey("mysticcombat", "gacha_rolled_rarity")));
        assertEquals(1, postEvents.size());
        assertEquals(RollOutcomeStatus.SUCCESS, postEvents.get(0).getStatus());
        assertEquals("Rare", postEvents.get(0).getRarityId());
    }

    @Test
    void invalidForcedRarityFromPreEventSkipsSafely() {
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig critRate = createCustomAttribute("custom_crit_rate", "MysticCombat.CRIT_RATE", 100, 7.0D, 7.0D);

        Map<String, CustomAttributeDefinition> definitions = Map.of(
                "mysticcombat.crit_rate",
                new CustomAttributeDefinition("MysticCombat.CRIT_RATE", "CHANCE_ON_HIT", "crit_rate", AttributeModifier.Operation.ADD_NUMBER, 0.0D, 100.0D, 1.5D)
        );

        List<PostRarityRollEvent> postEvents = new ArrayList<>();
        Main plugin = createPluginWithEventHook(event -> {
            if (event instanceof PreRarityRollEvent preEvent) {
                preEvent.setForcedRarityId("InvalidRarity");
            }
            if (event instanceof PostRarityRollEvent postEvent) {
                postEvents.add(postEvent);
            }
        });

        GachaManager manager = createManager(
                plugin,
                settings,
                Map.of("Common", rarity),
                Map.of(critRate.id(), critRate),
                definitions,
                42L
        );

        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);
        GachaManager.RollResult result = manager.rollForItem(item.itemStack());

        assertFalse(result.rolled());
        assertEquals("Unknown forced rarity 'InvalidRarity'", result.message());
        assertFalse(item.byteValues().containsKey(new NamespacedKey("mysticcombat", "gacha_rolled")));
        assertEquals(1, postEvents.size());
        assertEquals(RollOutcomeStatus.SKIPPED, postEvents.get(0).getStatus());
        assertEquals("Unknown forced rarity 'InvalidRarity'", postEvents.get(0).getReason());
    }

    @Test
    void postEventIsPublishedForNaturalSkipResult() {
        ConfigManager.Settings settings = createSettings(false, "UNIQUE", 1, 1);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig critRate = createCustomAttribute("custom_crit_rate", "MysticCombat.CRIT_RATE", 100, 7.0D, 7.0D);

        Map<String, CustomAttributeDefinition> definitions = Map.of(
                "mysticcombat.crit_rate",
                new CustomAttributeDefinition("MysticCombat.CRIT_RATE", "CHANCE_ON_HIT", "crit_rate", AttributeModifier.Operation.ADD_NUMBER, 0.0D, 100.0D, 1.5D)
        );

        List<PostRarityRollEvent> postEvents = new ArrayList<>();
        Main plugin = createPluginWithEventHook(event -> {
            if (event instanceof PostRarityRollEvent postEvent) {
                postEvents.add(postEvent);
            }
        });

        GachaManager manager = createManager(
                plugin,
                settings,
                Map.of("Common", rarity),
                Map.of(critRate.id(), critRate),
                definitions,
                42L
        );

        TestItemContext item = createTestItem(Material.DIAMOND_SWORD);
        item.byteValues().put(new NamespacedKey("mysticcombat", "gacha_rolled"), (byte) 1);

        GachaManager.RollResult result = manager.rollForItem(item.itemStack());

        assertFalse(result.rolled());
        assertEquals("Item already rolled", result.message());
        assertEquals(1, postEvents.size());
        assertEquals(RollOutcomeStatus.SKIPPED, postEvents.get(0).getStatus());
        assertEquals("Item already rolled", postEvents.get(0).getReason());
    }

    private GachaManager createManager(
            ConfigManager.Settings settings,
            Map<String, ConfigManager.RarityConfig> rarities,
            Map<String, ConfigManager.AttributeConfig> attributes,
            Map<String, CustomAttributeDefinition> definitions,
            long seed
    ) {
        return createManager(null, settings, rarities, attributes, definitions, seed);
    }

    private GachaManager createManager(
            Main plugin,
            ConfigManager.Settings settings,
            Map<String, ConfigManager.RarityConfig> rarities,
            Map<String, ConfigManager.AttributeConfig> attributes,
            Map<String, CustomAttributeDefinition> definitions,
            long seed
    ) {
        ConfigManager configManager = new ConfigManager(null);
        setConfigField(configManager, "settings", settings);
        setConfigField(configManager, "rarities", rarities);
        setConfigField(configManager, "attributesPool", attributes);

        CustomAttributeManager customAttributeManager = mock(CustomAttributeManager.class);
        when(customAttributeManager.get(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key == null) {
                return null;
            }
            return definitions.get(key.toLowerCase(Locale.ROOT));
        });

        return new GachaManager(plugin, configManager, customAttributeManager, new Random(seed));
    }

    private Main createPluginWithEventHook(Consumer<Event> eventHook) {
        Main plugin = mock(Main.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        Logger logger = mock(Logger.class);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("MysticCombat");
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.isPrimaryThread()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(logger);

        doAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            eventHook.accept(event);
            return event;
        }).when(pluginManager).callEvent(any(Event.class));

        return plugin;
    }

    private void setConfigField(ConfigManager configManager, String fieldName, Object value) {
        try {
            Field field = ConfigManager.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(configManager, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not prepare test config field: " + fieldName, exception);
        }
    }

    private ConfigManager.Settings createSettings(boolean allowDuplicate, String duplicateMode, int minAttributes, int maxAttributes) {
        return new ConfigManager.Settings(
                true,
                true,
                true,
                "gacha_rolled",
                "BYTE",
                minAttributes,
                maxAttributes,
                allowDuplicate,
                duplicateMode,
                20,
                "APPEND",
                "",
                true,
                1,
                false,
                -1L,
                true,
                "mysticcombat.reload",
                "mysticcombat.admin"
        );
    }

    private ConfigManager.AttributeConfig createCustomAttribute(String id, String attributeKey, int weight, double min, double max) {
        return createCustomAttribute(id, attributeKey, weight, min, max, List.of("Common"));
    }

    private ConfigManager.AttributeConfig createCustomAttribute(
            String id,
            String attributeKey,
            int weight,
            double min,
            double max,
            List<String> rarities
    ) {
        return new ConfigManager.AttributeConfig(
                id,
                attributeKey,
                ConfigManager.AttributeType.CUSTOM,
                attributeKey.substring(attributeKey.indexOf('.') + 1),
                null,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND,
                rarities,
                weight,
                min,
                max,
                Map.of(),
                List.of("*_SWORD"),
                List.of(),
                Set.of(),
                "{value}%"
        );
    }

    private TestItemContext createTestItem(Material material) {
        ItemStack itemStack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);

        Map<NamespacedKey, String> stringValues = new LinkedHashMap<>();
        Map<NamespacedKey, Byte> byteValues = new LinkedHashMap<>();
        AtomicReference<List<String>> loreRef = new AtomicReference<>(new ArrayList<>());

        when(itemStack.getType()).thenReturn(material);
        when(itemStack.getItemMeta()).thenReturn(meta);

        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(meta.getAttributeModifiers()).thenReturn(null);
        when(meta.hasAttributeModifiers()).thenReturn(false);

        when(meta.hasLore()).thenAnswer(invocation -> loreRef.get() != null && !loreRef.get().isEmpty());
        when(meta.getLore()).thenAnswer(invocation -> loreRef.get());
        doAnswer(invocation -> {
            List<String> lore = invocation.getArgument(0);
            loreRef.set(lore == null ? null : new ArrayList<>(lore));
            return null;
        }).when(meta).setLore(any());

        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(invocation -> stringValues.containsKey(invocation.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenAnswer(invocation -> byteValues.containsKey(invocation.getArgument(0)));
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(invocation -> stringValues.get(invocation.getArgument(0)));
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenAnswer(invocation -> byteValues.get(invocation.getArgument(0)));

        doAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            String value = invocation.getArgument(2);
            stringValues.put(key, value);
            return null;
        }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), any(String.class));

        doAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            Byte value = invocation.getArgument(2);
            byteValues.put(key, value);
            return null;
        }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), any(Byte.class));

        doAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            stringValues.remove(key);
            byteValues.remove(key);
            return null;
        }).when(pdc).remove(any(NamespacedKey.class));

        when(pdc.getKeys()).thenAnswer(invocation -> {
            Set<NamespacedKey> keys = new LinkedHashSet<>();
            keys.addAll(stringValues.keySet());
            keys.addAll(byteValues.keySet());
            return keys;
        });

        return new TestItemContext(itemStack, stringValues, byteValues);
    }

    private record TestItemContext(
            ItemStack itemStack,
            Map<NamespacedKey, String> stringValues,
            Map<NamespacedKey, Byte> byteValues
    ) {
    }
}

