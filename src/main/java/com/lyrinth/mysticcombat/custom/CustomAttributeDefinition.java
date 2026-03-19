package com.lyrinth.mysticcombat.custom;

import org.bukkit.attribute.AttributeModifier;

public record CustomAttributeDefinition(
        String attributeKey,
        String type,
        String pdcKey,
        AttributeModifier.Operation defaultOperation,
        double minValue,
        double maxValue,
        double fallbackMultiplier
) {
}

