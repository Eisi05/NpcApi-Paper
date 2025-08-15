package de.eisi05.npc.api.events;

import de.eisi05.npc.api.enums.ClickActionType;
import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/**
 * Event triggered when a player interacts with an NPC.
 * Contains information about the player, the NPC, and the type of click action.
 */
public class NpcInteractEvent extends Event implements Serializable, Cancellable
{
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final NPC npc;
    private final ClickActionType action;
    private boolean cancelled;

    /**
     * Creates a new NpcInteractEvent.
     *
     * @param player the player who interacted with the NPC
     * @param npc    the NPC that was interacted with
     * @param action the type of click action performed
     */
    public NpcInteractEvent(@NotNull Player player, @NotNull NPC npc, @NotNull ClickActionType action)
    {
        this.player = player;
        this.npc = npc;
        this.action = action;
    }

    /**
     * Returns the HandlerList for this event.
     *
     * @return the static HandlerList instance
     */
    public static HandlerList getHandlerList()
    {
        return HANDLERS;
    }

    /**
     * Returns the player who triggered this event.
     *
     * @return the interacting player, never null
     */
    public @NotNull Player getPlayer()
    {
        return player;
    }

    /**
     * Returns the NPC involved in this event.
     *
     * @return the interacted NPC, never null
     */
    public @NotNull NPC getNpc()
    {
        return npc;
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

    @Override
    public @NotNull HandlerList getHandlers()
    {
        return getHandlerList();
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
