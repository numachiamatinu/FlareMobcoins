package net.flares.flaremobcoins.API;

import net.flares.flaremobcoins.drops.EntityConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Called when a player kills a mob that drops mobcoins (same class as 3.x).
 * Cancel it to give nothing. {@link #setDroppedAmount(double)} changes the amount.
 */
@SuppressWarnings("deprecation")
public class MobCoinReceiveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final Player player;
    private final MobcoinsPlayer mobCoinsPlayer;
    private final String entity;
    private final EntityConfiguration entityConfiguration;
    private double droppedAmount;
    private boolean cancelled;

    public MobCoinReceiveEvent(Player player, MobcoinsPlayer mobCoinsPlayer, String entity,
                               EntityConfiguration entityConfiguration, double droppedAmount) {
        this.player = player;
        this.mobCoinsPlayer = mobCoinsPlayer;
        this.entity = entity;
        this.entityConfiguration = entityConfiguration;
        this.droppedAmount = droppedAmount;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public Player getPlayer() {
        return player;
    }

    public MobcoinsPlayer getMobCoinsPlayer() {
        return mobCoinsPlayer;
    }

    /** Entity type name, e.g. ZOMBIE. */
    public String getEntity() {
        return entity;
    }

    public EntityConfiguration getEntityConfiguration() {
        return entityConfiguration;
    }

    public double getDroppedAmount() {
        return droppedAmount;
    }

    public void setDroppedAmount(double droppedAmount) {
        this.droppedAmount = droppedAmount;
    }
}
