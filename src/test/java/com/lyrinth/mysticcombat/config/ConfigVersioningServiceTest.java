package com.lyrinth.mysticcombat.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigVersioningServiceTest {

    @Test
    void mergeDefaultsKeepsUserValuesAndAddsMissingNestedKeys() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("config_version", 2);
        defaults.put("settings", mapOf(
                "commands", mapOf(
                        "reload_permission", "mysticcombat.reload",
                        "admin_permission", "mysticcombat.admin"
                ),
                "roll", mapOf(
                        "allow_duplicate_attribute_ids", false,
                        "duplicate_modifier_mode", "UNIQUE"
                )
        ));

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("config_version", 1);
        user.put("settings", mapOf(
                "commands", mapOf(
                        "reload_permission", "custom.reload"
                ),
                "roll", mapOf(
                        "allow_duplicate_attribute_ids", true
                ),
                "custom_extra", "kept"
        ));
        user.put("user_defined_root_key", "keep");

        Map<String, Object> merged = ConfigVersioningService.mergeDefaults(defaults, user);

        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) merged.get("settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) settings.get("commands");
        @SuppressWarnings("unchecked")
        Map<String, Object> roll = (Map<String, Object>) settings.get("roll");

        assertEquals(1, merged.get("config_version"));
        assertEquals("keep", merged.get("user_defined_root_key"));
        assertEquals("custom.reload", commands.get("reload_permission"));
        assertEquals("mysticcombat.admin", commands.get("admin_permission"));
        assertEquals(true, roll.get("allow_duplicate_attribute_ids"));
        assertEquals("UNIQUE", roll.get("duplicate_modifier_mode"));
        assertEquals("kept", settings.get("custom_extra"));
        assertTrue(settings.containsKey("commands"));
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put((String) values[i], values[i + 1]);
        }
        return map;
    }
}

