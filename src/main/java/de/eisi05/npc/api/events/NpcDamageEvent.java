package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a player damages an NPC.
 * <p>
 * This event can be cancelled to prevent the damage from being applied.
 */
public class NpcDamageEvent extends NpcPlayerEvent implements Cancellable
{
    private final boolean critical;
    private final boolean magic;
    private double damage;
    private boolean cancelled;

    /* Creates a new NPC damage event.
     *
     * @param npc      the NPC being damaged
     * @param damager  the player dealing the damage
     * @param damage   the amount of damage dealt
     * @param critical whether the damage is a critical hit
     * @param magic    whether the damage is magical
     */
    public NpcDamageEvent(@NotNull NPC npc, @NotNull Player damager, double damage, boolean critical, boolean magic)
    {
        super(npc, damager);
        this.damage = damage;
        this.critical = critical;
        this.magic = magic;
    }

    /**
     * Gets the player dealing the damage.
     *
     * @return the damaging player
     */
    public @NotNull Player getDamager()
    {
        return player;
    }

    /**
     * Gets the amount of damage.
     *
     * @return the damage amount
     */
    public double getDamage()
    {
        return damage;
    }

    /**
     * Sets the amount of damage.
     * <p>
     * Negative damage values are clamped to {@code 0}.
     *
     * @param damage the new damage amount
     */
    public void setDamage(double damage)
    {
        this.damage = Math.max(0, damage);
    }

    /**
     * Checks whether the damage is a critical hit.
     *
     * @return {@code true} if the damage is critical, otherwise {@code false}
     */
    public boolean isCritical()
    {
        return critical;
    }

    /**
     * Checks whether the damage is magical.
     *
     * @return {@code true} if the damage is magical, otherwise {@code false}
     */
    public boolean isMagic()
    {
        return magic;
    }

    /**
     * Checks whether this event is cancelled.
     *
     * @return {@code true} if cancelled, otherwise {@code false}
     */
    @Override
    public boolean isCancelled()
    {
        return cancelled;
    }

    /**
     * Sets whether this event is cancelled.
     *
     * @param cancel {@code true} to cancel the event, {@code false} otherwise
     */
    @Override
    public void setCancelled(boolean cancel)
    {
        this.cancelled = cancel;
    }
}