package com.lyrinth.mysticcombat.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigManagerTest {

    @Test
    void loadParsesVanillaAndCustomAttributes() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConfigManagerTest"));

        FileConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                config_version: 1
                settings:
                  pdc_key: gacha_rolled
                  pdc_data_type: BYTE
                  roll:
                    min_attributes_per_item: 1
                    max_attributes_per_item: 2
                    allow_duplicate_attribute_ids: false
                    duplicate_modifier_mode: UNIQUE
                    max_roll_attempts_per_item: 20
                  lore:
                    append_mode: APPEND
                    stat_line_prefix: ""
                    show_rarity_prefix_once: true
                    decimal_places: 1
                  debug:
                    enabled: false
                    seed: -1
                    log_invalid_entries: true
                  commands:
                    reload_permission: mysticcombat.reload
                    admin_permission: mysticcombat.admin
                rarities:
                  Common:
                    weight: 1
                    prefix: ""
                    stats_amount:
                      min: 1
                      max: 1
                attributes_pool:
                  flat_damage:
                    attribute: GENERIC_ATTACK_DAMAGE
                    operation: ADD_NUMBER
                    slot: MAINHAND
                    rarities: [Common]
                    weight: 10
                    value:
                      min: 1.0
                      max: 2.0
                    applicable_items: ["*_SWORD"]
                    blacklist_attributes: []
                    lore_format: "{value} damage"
                  custom_crit_rate:
                    attribute: MysticCombat.CRIT_RATE
                    operation: ADD_NUMBER
                    slot: MAINHAND
                    rarities: [Common]
                    weight: 10
                    value:
                      min: 1.0
                      max: 2.0
                    applicable_items: ["*_SWORD"]
                    blacklist_attributes: []
                    lore_format: "{value}% crit"
                """);

        when(plugin.getConfig()).thenReturn(config);

        ConfigManager manager = new ConfigManager(plugin);
        assertTrue(manager.load());

        ConfigManager.AttributeConfig vanilla = manager.getAttributesPool().get("flat_damage");
        assertNotNull(vanilla);
        assertEquals(ConfigManager.AttributeType.VANILLA, vanilla.attributeType());
        assertNotNull(vanilla.attribute());

        ConfigManager.AttributeConfig custom = manager.getAttributesPool().get("custom_crit_rate");
        assertNotNull(custom);
        assertEquals(ConfigManager.AttributeType.CUSTOM, custom.attributeType());
        assertEquals("MysticCombat.CRIT_RATE", custom.attributeKey());
        assertNull(custom.attribute());
    }
}
