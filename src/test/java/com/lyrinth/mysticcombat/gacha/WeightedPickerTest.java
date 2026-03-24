package com.lyrinth.mysticcombat.gacha;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.lyrinth.mysticcombat.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeightedPickerTest {

    @Test
    void weightedPickerReturnsEntryWhenWeightsAreValid() {
        GachaManager.WeightedEntry common = () -> 100;
        GachaManager.WeightedEntry rare = () -> 1;

        GachaManager manager = new GachaManager(null, null, new Random(42L));
        GachaManager.WeightedEntry picked = manager.pickWeighted(List.of(common, rare));

        assertNotNull(picked);
    }

    @Test
    void weightedPickerReturnsNullWhenTotalWeightInvalid() {
        GachaManager.WeightedEntry invalid = () -> 0;

        GachaManager manager = new GachaManager(null, null, new Random(42L));
        GachaManager.WeightedEntry picked = manager.pickWeighted(List.of(invalid));

        assertNull(picked);
    }

    @Test
    void eligibleFilterPreventsDuplicatesWhenDisabled() {
        ConfigManager.AttributeConfig first = createAttribute("flat_damage", List.of(), 10);
        Map<String, ConfigManager.AttributeConfig> all = Map.of(first.id(), first);

        GachaManager manager = new GachaManager(null, null, new Random(42L));
        List<ConfigManager.AttributeConfig> eligible = manager.findEligibleCandidates(
                List.of(first),
                all,
                Set.of("flat_damage"),
                false
        );

        assertTrue(eligible.isEmpty());
    }

    @Test
    void eligibleFilterBlocksTwoWayBlacklistConflicts() {
        ConfigManager.AttributeConfig damage = createAttribute("damage", List.of("speed"), 10);
        ConfigManager.AttributeConfig speed = createAttribute("speed", List.of(), 10);

        Map<String, ConfigManager.AttributeConfig> all = new LinkedHashMap<>();
        all.put(damage.id(), damage);
        all.put(speed.id(), speed);

        GachaManager manager = new GachaManager(null, null, new Random(42L));
        List<ConfigManager.AttributeConfig> eligibleAfterDamage = manager.findEligibleCandidates(
                List.of(damage, speed),
                all,
                Set.of("damage"),
                true
        );

        assertEquals(1, eligibleAfterDamage.size());
        assertEquals("damage", eligibleAfterDamage.get(0).id());
    }

    @Test
    void eligibleFilterReturnsEmptyWhenNoCandidatesRemain() {
        ConfigManager.AttributeConfig damage = createAttribute("damage", List.of("speed"), 10);
        ConfigManager.AttributeConfig speed = createAttribute("speed", List.of("damage"), 10);

        Map<String, ConfigManager.AttributeConfig> all = new LinkedHashMap<>();
        all.put(damage.id(), damage);
        all.put(speed.id(), speed);

        GachaManager manager = new GachaManager(null, null, new Random(42L));
        List<ConfigManager.AttributeConfig> eligible = manager.findEligibleCandidates(
                List.of(speed),
                all,
                Set.of("damage"),
                false
        );

        assertTrue(eligible.isEmpty());
    }

    @Test
    void rollForItemDoesNotCrashWhenAttributeSpecificModifiersAreNull() {
        // Build deterministic config with a single valid candidate.
        ConfigManager.Settings settings = createSettings(2, 2, false);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 2, 2);
        ConfigManager.AttributeConfig damage = createAttribute("damage", List.of(), 100);
        GachaManager manager = createManager(settings, Map.of("Common", rarity), Map.of(damage.id(), damage), 42L);

        // Mock runtime item state so attribute-level modifier lookup returns null.
        ItemStack itemStack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);

        when(itemStack.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(itemStack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(false);
        when(meta.getAttributeModifiers()).thenReturn(null);
        when(meta.hasAttributeModifiers()).thenReturn(true);
        when(meta.getAttributeModifiers(eq(Attribute.GENERIC_ATTACK_DAMAGE))).thenReturn(null);
        when(meta.addAttributeModifier(any(Attribute.class), any(AttributeModifier.class))).thenReturn(true);
        when(meta.hasLore()).thenReturn(false);

        GachaManager.RollResult result = manager.rollForItem(itemStack);

        assertTrue(result.rolled());
        assertTrue(result.message().contains("Rolled 1 stats"));
        verify(itemStack).setItemMeta(meta);
    }

    @Test
    void rollForItemSkipsGracefullyWhenBlacklistLeavesNoEligibleCandidate() {
        // Build deterministic config that asks for two stats but can only apply one.
        ConfigManager.Settings settings = createSettings(2, 2, false);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 2, 2);
        ConfigManager.AttributeConfig damage = createAttribute("damage", List.of("speed"), 100);
        ConfigManager.AttributeConfig speed = createAttribute("speed", List.of("damage"), 1);

        Map<String, ConfigManager.AttributeConfig> attributes = new LinkedHashMap<>();
        attributes.put(damage.id(), damage);
        attributes.put(speed.id(), speed);

        GachaManager manager = createManager(settings, Map.of("Common", rarity), attributes, 42L);
        ItemStack itemStack = mockRollableItem(Material.DIAMOND_SWORD);

        GachaManager.RollResult result = manager.rollForItem(itemStack);

        assertTrue(result.rolled());
        assertTrue(result.message().contains("Rolled 1 stats"));
    }

    @Test
    void rollForItemReturnsSkippedWhenRarityHasNoApplicableAttributesForItem() {
        // Build deterministic config where attribute exists but item filter removes all candidates.
        ConfigManager.Settings settings = createSettings(1, 1, false);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig axeOnly = createAttribute("axe_damage", List.of(), 100, List.of("*_AXE"));
        GachaManager manager = createManager(settings, Map.of("Common", rarity), Map.of(axeOnly.id(), axeOnly), 42L);

        ItemStack itemStack = mockRollableItem(Material.DIAMOND_SWORD);
        ItemMeta meta = itemStack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        GachaManager.RollResult result = manager.rollForItem(itemStack);

        assertFalse(result.rolled());
        assertEquals("No matching attributes for item type", result.message());
        verify(pdc, never()).set(any(NamespacedKey.class), any(), any());
        verify(itemStack, never()).setItemMeta(any(ItemMeta.class));
    }

    @Test
    void rollForItemSkipsWhenLegacyStringMarkerExistsEvenIfConfigExpectsByte() {
        ConfigManager.Settings settings = createSettings(1, 1, false);
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig damage = createAttribute("damage", List.of(), 100);
        GachaManager manager = createManager(settings, Map.of("Common", rarity), Map.of(damage.id(), damage), 42L);

        ItemStack itemStack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);

        when(itemStack.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(itemStack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(false);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(true);

        GachaManager.RollResult result = manager.rollForItem(itemStack);

        assertFalse(result.rolled());
        assertEquals("Item already rolled", result.message());
        verify(itemStack, never()).setItemMeta(any(ItemMeta.class));
    }

    @Test
    void rollForItemUsesRaritySpecificRangeWhenConfigured() {
        ConfigManager.Settings settings = createSettings(1, 1, false);
        ConfigManager.RarityConfig rare = new ConfigManager.RarityConfig("Rare", 1, "&9[Rare]", 1, 1);
        ConfigManager.AttributeConfig damage = createAttribute(
                "damage",
                List.of(),
                100,
                List.of("Rare"),
                List.of("*_SWORD"),
                Map.of("rare", new ConfigManager.ValueRange(7.0D, 7.0D))
        );

        GachaManager manager = createManager(settings, Map.of("Rare", rare), Map.of(damage.id(), damage), 42L);

        ItemStack itemStack = mockRollableItem(Material.DIAMOND_SWORD);
        ItemMeta meta = itemStack.getItemMeta();
        when(meta.hasLore()).thenReturn(false);

        GachaManager.RollResult result = manager.rollForItem(itemStack);

        assertTrue(result.rolled());
        org.mockito.ArgumentCaptor<AttributeModifier> modifierCaptor = org.mockito.ArgumentCaptor.forClass(AttributeModifier.class);
        verify(meta).addAttributeModifier(eq(Attribute.GENERIC_ATTACK_DAMAGE), modifierCaptor.capture());
        assertEquals(7.0D, modifierCaptor.getValue().getAmount(), 0.000001D);
    }

    @Test
    void rollForItemLoreDoesNotContainWrapperAndRarityIsAtBottom() {
        ConfigManager.Settings settings = createSettings(1, 1, false);
        ConfigManager.RarityConfig rare = new ConfigManager.RarityConfig("Rare", 1, "&9[Rare]", 1, 1);
        ConfigManager.AttributeConfig damage = createAttribute(
                "damage",
                List.of(),
                100,
                List.of("Rare"),
                List.of("*_SWORD"),
                Map.of()
        );
        GachaManager manager = createManager(settings, Map.of("Rare", rare), Map.of(damage.id(), damage), 42L);

        ItemStack itemStack = mockRollableItem(Material.DIAMOND_SWORD);
        ItemMeta meta = itemStack.getItemMeta();
        when(meta.hasLore()).thenReturn(false);

        GachaManager.RollResult result = manager.rollForItem(itemStack);

        assertTrue(result.rolled());
        org.mockito.ArgumentCaptor<List<String>> loreCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(meta).setLore(loreCaptor.capture());

        List<String> lore = loreCaptor.getValue();
        assertFalse(lore.stream().anyMatch(line -> line.contains("<mysticcombat_stats>")));
        assertFalse(lore.stream().anyMatch(line -> line.contains("</mysticcombat_stats>")));
        assertEquals("", lore.get(lore.size() - 2));
        assertEquals("\u00A79[Rare]", lore.get(lore.size() - 1));
    }

    @Test
    void addAttributeToArmorUsesUniqueModifierKeyPerItem() {
        ConfigManager.Settings settings = createSettings(1, 1, false, "MERGE");
        ConfigManager.RarityConfig rarity = new ConfigManager.RarityConfig("Common", 1, "", 1, 1);
        ConfigManager.AttributeConfig maxHealth = createArmorAttribute("max_health_flat", 100);
        GachaManager manager = createManager(settings, Map.of("Common", rarity), Map.of(maxHealth.id(), maxHealth), 42L);

        ItemStack helmet = mockRollableItem(Material.DIAMOND_HELMET);
        ItemStack chestplate = mockRollableItem(Material.DIAMOND_CHESTPLATE);

        GachaManager.AdminActionResult helmetResult = manager.addAttributeToItem(helmet, "max_health_flat", 4.0D, false);
        GachaManager.AdminActionResult chestplateResult = manager.addAttributeToItem(chestplate, "max_health_flat", 4.0D, false);

        assertTrue(helmetResult.success());
        assertTrue(chestplateResult.success());

        AttributeModifier helmetModifier = helmet.getItemMeta().getAttributeModifiers(Attribute.GENERIC_MAX_HEALTH).iterator().next();
        AttributeModifier chestplateModifier = chestplate.getItemMeta().getAttributeModifiers(Attribute.GENERIC_MAX_HEALTH).iterator().next();

        assertNotEquals(helmetModifier.getKey(), chestplateModifier.getKey());
        assertTrue(helmetModifier.getKey().getKey().startsWith("attr_max_health_flat__"));
        assertTrue(chestplateModifier.getKey().getKey().startsWith("attr_max_health_flat__"));
    }

    private GachaManager createManager(
            ConfigManager.Settings settings,
            Map<String, ConfigManager.RarityConfig> rarities,
            Map<String, ConfigManager.AttributeConfig> attributes,
            long seed
    ) {
        ConfigManager configManager = new ConfigManager(null);
        setConfigField(configManager, "settings", settings);
        setConfigField(configManager, "rarities", rarities);
        setConfigField(configManager, "attributesPool", attributes);

        return new GachaManager(null, configManager, new Random(seed));
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

    private ItemStack mockRollableItem(Material material) {
        ItemStack itemStack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        Multimap<Attribute, AttributeModifier> modifiers = ArrayListMultimap.create();
        Map<NamespacedKey, String> stringValues = new LinkedHashMap<>();
        Map<NamespacedKey, Byte> byteValues = new LinkedHashMap<>();
        AtomicReference<List<String>> loreRef = new AtomicReference<>();

        when(itemStack.getType()).thenReturn(material);
        when(itemStack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenAnswer(invocation -> byteValues.containsKey(invocation.getArgument(0)));
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(invocation -> stringValues.containsKey(invocation.getArgument(0)));
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(invocation -> stringValues.get(invocation.getArgument(0)));
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenAnswer(invocation -> byteValues.get(invocation.getArgument(0)));
        when(pdc.getKeys()).thenAnswer(invocation -> {
            Set<NamespacedKey> keys = new LinkedHashSet<>();
            keys.addAll(stringValues.keySet());
            keys.addAll(byteValues.keySet());
            return keys;
        });

        org.mockito.Mockito.doAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            String value = invocation.getArgument(2);
            stringValues.put(key, value);
            return null;
        }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), any(String.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            Byte value = invocation.getArgument(2);
            byteValues.put(key, value);
            return null;
        }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), any(Byte.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            stringValues.remove(key);
            byteValues.remove(key);
            return null;
        }).when(pdc).remove(any(NamespacedKey.class));

        when(meta.getAttributeModifiers()).thenReturn(modifiers);
        when(meta.getAttributeModifiers(any(Attribute.class))).thenAnswer(invocation -> modifiers.get(invocation.getArgument(0)));
        when(meta.hasAttributeModifiers()).thenAnswer(invocation -> !modifiers.isEmpty());
        when(meta.addAttributeModifier(any(Attribute.class), any(AttributeModifier.class))).thenAnswer(invocation -> {
            modifiers.put(invocation.getArgument(0), invocation.getArgument(1));
            return true;
        });
        when(meta.removeAttributeModifier(any(Attribute.class), any(AttributeModifier.class))).thenAnswer(invocation -> {
            modifiers.remove(invocation.getArgument(0), invocation.getArgument(1));
            return true;
        });
        when(meta.hasLore()).thenAnswer(invocation -> loreRef.get() != null && !loreRef.get().isEmpty());
        when(meta.getLore()).thenAnswer(invocation -> loreRef.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> lore = invocation.getArgument(0);
            loreRef.set(lore == null ? null : List.copyOf(lore));
            return null;
        }).when(meta).setLore(any());

        return itemStack;
    }

    private ConfigManager.Settings createSettings(int minAttributes, int maxAttributes, boolean allowDuplicate) {
        return createSettings(minAttributes, maxAttributes, allowDuplicate, "UNIQUE");
    }

    private ConfigManager.Settings createSettings(int minAttributes, int maxAttributes, boolean allowDuplicate, String duplicateMode) {
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

    private ConfigManager.AttributeConfig createAttribute(String id, List<String> blacklist, int weight) {
        return createAttribute(id, blacklist, weight, List.of("Common"), List.of("*_SWORD"), Map.of());
    }

    private ConfigManager.AttributeConfig createAttribute(String id, List<String> blacklist, int weight, List<String> applicableItems) {
        return createAttribute(id, blacklist, weight, List.of("Common"), applicableItems, Map.of());
    }

    private ConfigManager.AttributeConfig createAttribute(
            String id,
            List<String> blacklist,
            int weight,
            List<String> rarities,
            List<String> applicableItems,
            Map<String, ConfigManager.ValueRange> rarityRanges
    ) {
        return new ConfigManager.AttributeConfig(
                id,
                "GENERIC_ATTACK_DAMAGE",
                ConfigManager.AttributeType.VANILLA,
                null,
                Attribute.GENERIC_ATTACK_DAMAGE,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND,
                rarities,
                weight,
                1.0D,
                2.0D,
                rarityRanges,
                applicableItems,
                blacklist,
                blacklist.stream().map(value -> value.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet()),
                "&7+{value}"
        );
    }

    private ConfigManager.AttributeConfig createArmorAttribute(String id, int weight) {
        return new ConfigManager.AttributeConfig(
                id,
                "GENERIC_MAX_HEALTH",
                ConfigManager.AttributeType.VANILLA,
                null,
                Attribute.GENERIC_MAX_HEALTH,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.ARMOR,
                List.of("Common"),
                weight,
                1.0D,
                8.0D,
                Map.of(),
                List.of("*_HELMET", "*_CHESTPLATE", "*_LEGGINGS", "*_BOOTS"),
                List.of(),
                Set.of(),
                "&7+{value}"
        );
    }
}
