package dev.tmdevelopment.flaremobcoins.api;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Called before the balance of an online player changes. Cancel it to block the change. */
public final class MobcoinsChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID player;
    private final double before;
    private final double after;
    private final Type type;
    private final String cause;
    private boolean cancelled;

    public MobcoinsChangeEvent(UUID player, double before, double after, Type type, String cause) {
        this.player = player;
        this.before = before;
        this.after = after;
        this.type = type;
        this.cause = cause;
    }

    public UUID getPlayer() {
        return player;
    }

    public double getBefore() {
        return before;
    }

    public double getAfter() {
        return after;
    }

    public double getDifference() {
        return after - before;
    }

    public Type getType() {
        return type;
    }

    /** What changed it: "drop ZOMBIE", "shop main", "pay to Steve", "command by Notch", "api"... */
    public String getCause() {
        return cause;
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

    public enum Type {
        GIVE, TAKE, SET
    }
}
