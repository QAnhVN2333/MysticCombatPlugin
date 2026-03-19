package com.lyrinth.mysticcombat.util;

import com.google.common.collect.Multimap;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Utils {

    private Utils() {
    }

    public static String colorize(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public static boolean wildcardMatches(String materialName, String pattern) {
        if (materialName == null || pattern == null || pattern.isBlank()) {
            return false;
        }

        if (pattern.startsWith("*")) {
            return materialName.endsWith(pattern.substring(1));
        }

        return materialName.equalsIgnoreCase(pattern);
    }

    public static String formatLoreValue(double value, int decimalPlaces, AttributeModifier.Operation operation) {
        double displayValue = value;

        // Scalar operations are displayed as percent values in lore.
        if (operation == AttributeModifier.Operation.ADD_SCALAR || operation == AttributeModifier.Operation.MULTIPLY_SCALAR_1) {
            displayValue = value * 100.0D;
        }

        return formatDecimal(displayValue, decimalPlaces);
    }

    public static String formatDecimal(double value, int decimalPlaces) {
        StringBuilder pattern = new StringBuilder("0");
        if (decimalPlaces > 0) {
            pattern.append('.');
            pattern.append("0".repeat(decimalPlaces));
        }

        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat decimalFormat = new DecimalFormat(pattern.toString(), symbols);
        decimalFormat.setRoundingMode(RoundingMode.HALF_UP);
        return decimalFormat.format(value);
    }

    public static void ensureVanillaBaseAttributes(Material material, ItemMeta meta) {
        if (material == null || material == Material.AIR || meta == null) {
            return;
        }

        Multimap<Attribute, AttributeModifier> defaultModifiers;
        try {
            defaultModifiers = material.getDefaultAttributeModifiers();
        } catch (Throwable ignored) {
            // Skip vanilla re-apply when registry APIs are not bootstrapped.
            return;
        }
        if (defaultModifiers == null || defaultModifiers.isEmpty()) {
            return;
        }

        // Force-serialize vanilla defaults before first custom modifier is written.
        if (!meta.hasAttributeModifiers()) {
            for (Map.Entry<Attribute, AttributeModifier> entry : defaultModifiers.entries()) {
                meta.addAttributeModifier(entry.getKey(), entry.getValue());
            }
            return;
        }

        Set<NamespacedKey> existingKeys = new HashSet<>();
        Multimap<Attribute, AttributeModifier> currentModifiers = meta.getAttributeModifiers();
        if (currentModifiers != null) {
            for (AttributeModifier modifier : currentModifiers.values()) {
                NamespacedKey key = modifier.getKey();
                if (key != null) {
                    existingKeys.add(key);
                }
            }
        }

        // Keep base vanilla modifiers so adding custom stats does not hide default combat values.
        for (Map.Entry<Attribute, AttributeModifier> entry : defaultModifiers.entries()) {
            Attribute attribute = entry.getKey();
            AttributeModifier modifier = entry.getValue();
            NamespacedKey key = modifier.getKey();

            if (key != null && existingKeys.contains(key)) {
                continue;
            }
            if (meta.addAttributeModifier(attribute, modifier) && key != null) {
                existingKeys.add(key);
            }
        }
    }
}
