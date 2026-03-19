package com.lyrinth.mysticcombat.util;

import org.bukkit.attribute.AttributeModifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilsTest {

    @Test
    void wildcardMatchesSuffixPattern() {
        assertTrue(Utils.wildcardMatches("DIAMOND_SWORD", "*_SWORD"));
        assertFalse(Utils.wildcardMatches("DIAMOND_AXE", "*_SWORD"));
    }

    @Test
    void wildcardMatchesExactPattern() {
        assertTrue(Utils.wildcardMatches("BOW", "BOW"));
        assertFalse(Utils.wildcardMatches("CROSSBOW", "BOW"));
    }

    @Test
    void formatLoreValueAddNumber() {
        String result = Utils.formatLoreValue(1.234D, 1, AttributeModifier.Operation.ADD_NUMBER);
        assertEquals("1.2", result);
    }

    @Test
    void formatLoreValueAddScalar() {
        String result = Utils.formatLoreValue(0.125D, 1, AttributeModifier.Operation.ADD_SCALAR);
        assertEquals("12.5", result);
    }

    @Test
    void formatLoreValueMultiplyScalar() {
        String result = Utils.formatLoreValue(0.10D, 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        assertEquals("10.0", result);
    }
}

