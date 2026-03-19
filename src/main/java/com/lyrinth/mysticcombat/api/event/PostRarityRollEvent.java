package com.lyrinth.mysticcombat.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public final class PostRarityRollEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack itemStack;
    private final RollTriggerSource source;
    private final RollOutcomeStatus status;
    private final String rarityId;
    private final int appliedStatsCount;
    private final String reason;

    public PostRarityRollEvent(
            Player player,
            ItemStack itemStack,
            RollTriggerSource source,
            RollOutcomeStatus status,
            String rarityId,
            int appliedStatsCount,
            String reason
    ) {
        this.player = player;
        this.itemStack = Objects.requireNonNull(itemStack, "itemStack");
        this.source = Objects.requireNonNull(source, "source");
        this.status = Objects.requireNonNull(status, "status");
        this.rarityId = rarityId;
        this.appliedStatsCount = appliedStatsCount;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public RollTriggerSource getSource() {
        return source;
    }

    public RollOutcomeStatus getStatus() {
        return status;
    }

    public String getRarityId() {
        return rarityId;
    }

    public int getAppliedStatsCount() {
        return appliedStatsCount;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

