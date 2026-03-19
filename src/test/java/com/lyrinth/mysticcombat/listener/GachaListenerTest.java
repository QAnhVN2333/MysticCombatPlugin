package com.lyrinth.mysticcombat.listener;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GachaListenerTest {

    @Test
    void calculateCraftedAmountReturnsWholeAmountForNewSlot() {
        ItemStack current = mock(ItemStack.class);
        when(current.getAmount()).thenReturn(5);

        int craftedAmount = GachaListener.calculateCraftedAmount(null, current);

        assertEquals(5, craftedAmount);
    }

    @Test
    void calculateCraftedAmountReturnsDeltaForMergedStack() {
        ItemStack previous = mock(ItemStack.class);
        ItemStack current = mock(ItemStack.class);

        when(previous.isSimilar(current)).thenReturn(true);
        when(previous.getAmount()).thenReturn(10);
        when(current.getAmount()).thenReturn(14);

        int craftedAmount = GachaListener.calculateCraftedAmount(previous, current);

        assertEquals(4, craftedAmount);
    }

    @Test
    void calculateCraftedAmountReturnsZeroWhenAmountDidNotIncrease() {
        ItemStack previous = mock(ItemStack.class);
        ItemStack current = mock(ItemStack.class);

        when(previous.isSimilar(current)).thenReturn(true);
        when(previous.getAmount()).thenReturn(10);
        when(current.getAmount()).thenReturn(8);

        int craftedAmount = GachaListener.calculateCraftedAmount(previous, current);

        assertEquals(0, craftedAmount);
    }

    @Test
    void calculateCraftedAmountTreatsMetaReplacementAsFreshCraftedStack() {
        ItemStack previous = mock(ItemStack.class);
        ItemStack current = mock(ItemStack.class);

        when(previous.isSimilar(current)).thenReturn(false);
        when(current.getAmount()).thenReturn(2);

        int craftedAmount = GachaListener.calculateCraftedAmount(previous, current);

        assertEquals(2, craftedAmount);
    }
}
