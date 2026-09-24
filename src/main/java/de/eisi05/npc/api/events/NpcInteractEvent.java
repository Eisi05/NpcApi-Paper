package de.eisi05.npc.api.events;

import de.eisi05.npc.api.enums.ClickActionType;
import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/**
 * Event triggered when a player interacts with an NPC. Contains information about the player, the NPC, and the type of click action.
 */
public class NpcInteractEvent extends NpcPlayerEvent implements Serializable, Cancellable
{
    private final ClickActionType action;
    private double damage;
    private boolean cancelled;

    /**
     * Creates a new NpcInteractEvent.
     *
     * @param player the player who interacted with the NPC
     * @param npc    the NPC that was interacted with
     * @param action the type of click action performed
     * @param damage the damage dealt to the NPC
     */
    public NpcInteractEvent(@NotNull Player player, @NotNull NPC npc, @NotNull ClickActionType action, double damage)
    {
        super(npc, player);
        this.action = action;
        this.damage = damage;
    }

    /**
     * Creates a new NpcInteractEvent.
     *
     * @param player the player who interacted with the NPC
     * @param npc    the NPC that was interacted with
     * @param action the type of click action performed
     */
    public NpcInteractEvent(@NotNull Player player, @NotNull NPC npc, @NotNull ClickActionType action)
    {
        this(player, npc, action, -1);
    }

    /**
     * Returns the click action type of this interaction.
     *
     * @return the ClickActionType, never null
     */
    public @NotNull ClickActionType getAction()
    {
        return action;
    }

    /**
     * Returns the damage dealt to the NPC.
     *
     * @return the damage amount, -1 if no damage was dealt
     */
    public double getDamage()
    {
        return damage;
    }

    /**
     * Sets the damage dealt to the NPC.
     *
     * @param damage the damage amount, -1 if no damage was dealt
     */
    public void setDamage(double damage)
    {
        this.damage = damage;
    }

    @Override
    public boolean isCancelled()
    {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled)
    {
        this.cancelled = cancelled;
    }
}
