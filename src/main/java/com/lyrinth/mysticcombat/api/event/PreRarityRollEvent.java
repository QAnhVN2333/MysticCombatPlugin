package com.lyrinth.mysticcombat.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public final class PreRarityRollEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack itemStack;
    private final RollTriggerSource source;
    private String forcedRarityId;
    private boolean cancelled;

    public PreRarityRollEvent(Player player, ItemStack itemStack, RollTriggerSource source, String forcedRarityId) {
        this.player = player;
        this.itemStack = Objects.requireNonNull(itemStack, "itemStack");
        this.source = Objects.requireNonNull(source, "source");
        this.forcedRarityId = forcedRarityId;
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

    public String getForcedRarityId() {
        return forcedRarityId;
    }

    public void setForcedRarityId(String forcedRarityId) {
        this.forcedRarityId = forcedRarityId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

