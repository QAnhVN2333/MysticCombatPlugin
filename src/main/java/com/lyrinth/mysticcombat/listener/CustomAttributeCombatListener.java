package com.lyrinth.mysticcombat.listener;

import com.lyrinth.mysticcombat.Main;
import com.lyrinth.mysticcombat.custom.CustomAttributeDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

public final class CustomAttributeCombatListener implements Listener {

    private static final String CRIT_RATE_KEY = "MysticCombat.CRIT_RATE";
    private static final String CRIT_DAMAGE_KEY = "MysticCombat.CRIT_DAMAGE";

    private final Main plugin;

    public CustomAttributeCombatListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (plugin.getGachaManager() == null || plugin.getConfigManager() == null) {
            return;
        }

        ItemStack weapon = player.getInventory().getItemInMainHand();
        double critRate = plugin.getGachaManager().getCustomAttributeTotal(weapon, CRIT_RATE_KEY);
        if (critRate <= 0.0D) {
            return;
        }

        double roll = ThreadLocalRandom.current().nextDouble(100.0D);
        if (roll > critRate) {
            return;
        }

        double critDamageMultiplier = plugin.getGachaManager().getCustomAttributeTotal(weapon, CRIT_DAMAGE_KEY);
        if (critDamageMultiplier <= 0.0D) {
            critDamageMultiplier = resolveFallbackMultiplier();
        }

        event.setDamage(event.getDamage() * critDamageMultiplier);

        if (plugin.getConfigManager().getSettings().debugEnabled()) {
            player.sendActionBar("[MysticCombat] CRIT x" + String.format(java.util.Locale.US, "%.2f", critDamageMultiplier));
        }
    }

    private double resolveFallbackMultiplier() {
        if (plugin.getCustomAttributeManager() == null) {
            return 1.5D;
        }

        CustomAttributeDefinition definition = plugin.getCustomAttributeManager().get(CRIT_RATE_KEY);
        if (definition == null) {
            return 1.5D;
        }
        return definition.fallbackMultiplier();
    }
}

