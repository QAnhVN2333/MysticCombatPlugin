package com.lyrinth.mysticcombat.listener;

import com.lyrinth.mysticcombat.Main;
import com.lyrinth.mysticcombat.api.event.RollTriggerSource;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GachaListener implements Listener {

    private final Main plugin;

    public GachaListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!isReady() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.getConfigManager().getSettings().craftingTrigger()) {
            return;
        }

        ItemStack currentItem = event.getCurrentItem();

        if (event.isShiftClick()) {
            Material craftedMaterial = resolveCraftedMaterial(event, currentItem);
            if (craftedMaterial == Material.AIR) {
                return;
            }

            Map<Integer, ItemStack> beforeSnapshot = snapshotByMaterial(player.getInventory(), craftedMaterial);
            plugin.getServer().getScheduler().runTask(plugin, () -> rollShiftCraftedItems(player, craftedMaterial, beforeSnapshot));
            return;
        }

        if (!isProcessable(currentItem)) {
            return;
        }

        plugin.getGachaManager().rollForItem(player, currentItem, RollTriggerSource.CRAFT);
        event.setCurrentItem(currentItem);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!isReady()) {
            return;
        }
        if (!plugin.getConfigManager().getSettings().enchantingTrigger()) {
            return;
        }

        Player player = event.getEnchanter();
        ItemStack item = event.getItem();
        if (!isProcessable(item)) {
            return;
        }

        plugin.getGachaManager().rollForItem(player, item, RollTriggerSource.ENCHANT);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilResultClick(InventoryClickEvent event) {
        if (!isReady() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.getConfigManager().getSettings().anvilTrigger()) {
            return;
        }

        if (event.getClickedInventory() == null) {
            return;
        }

        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        // Anvil result slot is index 2 in the top inventory.
        if (event.getRawSlot() != 2) {
            return;
        }

        ItemStack currentItem = event.getCurrentItem();
        if (!isProcessable(currentItem)) {
            return;
        }

        plugin.getGachaManager().rollForItem(player, currentItem, RollTriggerSource.ANVIL);
        event.setCurrentItem(currentItem);
    }

    private Map<Integer, ItemStack> snapshotByMaterial(PlayerInventory inventory, Material material) {
        Map<Integer, ItemStack> snapshot = new LinkedHashMap<>();

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType() != material) {
                continue;
            }
            snapshot.put(slot, current.clone());
        }

        return snapshot;
    }

    private void rollShiftCraftedItems(Player player, Material craftedMaterial, Map<Integer, ItemStack> beforeSnapshot) {
        if (!isReady()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        List<CraftedSlotDelta> deltas = collectCraftedDeltas(inventory, craftedMaterial, beforeSnapshot);

        for (CraftedSlotDelta delta : deltas) {
            ItemStack current = inventory.getItem(delta.slot());
            if (!isProcessable(current) || current.getType() != craftedMaterial) {
                continue;
            }
            if (plugin.getGachaManager().isItemRolled(current)) {
                continue;
            }

            int amountToRoll = Math.min(delta.amount(), current.getAmount());
            if (amountToRoll <= 0) {
                continue;
            }

            if (amountToRoll == current.getAmount()) {
                plugin.getGachaManager().rollForItem(player, current, RollTriggerSource.CRAFT);
                inventory.setItem(delta.slot(), current);
                continue;
            }

            // Keep old stack amount untouched, and extract only crafted delta for rolling.
            ItemStack untouchedPart = current.clone();
            untouchedPart.setAmount(current.getAmount() - amountToRoll);
            inventory.setItem(delta.slot(), untouchedPart);

            ItemStack craftedPart = current.clone();
            craftedPart.setAmount(amountToRoll);
            plugin.getGachaManager().rollForItem(player, craftedPart, RollTriggerSource.CRAFT);

            Map<Integer, ItemStack> overflow = inventory.addItem(craftedPart);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private List<CraftedSlotDelta> collectCraftedDeltas(
            PlayerInventory inventory,
            Material craftedMaterial,
            Map<Integer, ItemStack> beforeSnapshot
    ) {
        List<CraftedSlotDelta> deltas = new ArrayList<>();

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (!isProcessable(current) || current.getType() != craftedMaterial) {
                continue;
            }
            if (plugin.getGachaManager().isItemRolled(current)) {
                continue;
            }

            ItemStack previous = beforeSnapshot.get(slot);
            int craftedAmount = calculateCraftedAmount(previous, current);
            if (craftedAmount > 0) {
                deltas.add(new CraftedSlotDelta(slot, craftedAmount));
            }
        }

        return deltas;
    }

    private Material resolveCraftedMaterial(CraftItemEvent event, ItemStack currentItem) {
        if (isProcessable(currentItem)) {
            return currentItem.getType();
        }

        if (event.getRecipe() == null) {
            return Material.AIR;
        }

        ItemStack recipeResult = event.getRecipe().getResult();
        if (!isProcessable(recipeResult)) {
            return Material.AIR;
        }

        return recipeResult.getType();
    }

    static int calculateCraftedAmount(ItemStack previous, ItemStack current) {
        if (current == null) {
            return 0;
        }
        if (previous == null) {
            return current.getAmount();
        }
        if (!previous.isSimilar(current)) {
            return current.getAmount();
        }

        return Math.max(0, current.getAmount() - previous.getAmount());
    }

    private boolean isReady() {
        return plugin.getConfigManager() != null && plugin.getGachaManager() != null;
    }

    private boolean isProcessable(ItemStack itemStack) {
        if (itemStack == null) {
            return false;
        }
        return itemStack.getType() != Material.AIR;
    }

    private record CraftedSlotDelta(int slot, int amount) {
    }
}
