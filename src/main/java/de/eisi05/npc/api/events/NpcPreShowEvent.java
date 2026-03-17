package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an NPC is shown to a player. This event is called before the NPC is shown to the player. The event can be canceled to prevent the NPC from being
 * shown.
 */
public class NpcPreShowEvent extends Event implements Cancellable
{
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final NPC npc;
    private final boolean wasViewer;
    private boolean cancelled;

    /**
     * Creates a new NpcPreShowEvent.
     *
     * @param player    the player to whom the NPC is being shown
     * @param npc       the NPC that is being shown
     * @param wasViewer whether the player was previously a viewer of this NPC
     * @throws IllegalArgumentException if player or npc is null
     */
    public NpcPreShowEvent(@NotNull Player player, @NotNull NPC npc, boolean wasViewer)
    {
        this.player = player;
        this.npc = npc;
        this.wasViewer = wasViewer;
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

    @Override
    public @NotNull HandlerList getHandlers()
    {
        return getHandlerList();
    }

    /**
     * {@inheritDoc}
     *
     * @return true if this event is canceled
     */
    @Override
    public boolean isCancelled()
    {
        return cancelled;
    }

    /**
     * {@inheritDoc}
     *
     * @param cancel true if you wish to cancel this event
     */
    @Override
    public void setCancelled(boolean cancel)
    {
        cancelled = cancel;
    }

    /**
     * Gets the NPC that is being shown.
     *
     * @return the NPC being shown, never null
     */
    public @NotNull NPC getNpc()
    {
        return npc;
    }

    /**
     * Gets the player to whom the NPC is being shown.
     *
     * @return the player, never null
     */
    public @NotNull Player getPlayer()
    {
        return player;
    }

    /**
     * Checks if the player was previously a viewer of this NPC. This can be used to determine if the player is seeing this NPC for the first time or if it's a
     * refresh of an existing view.
     *
     * @return true if the player was previously viewing this NPC, false otherwise
     */
    public boolean wasViewer()
    {
        return wasViewer;
    }
}
