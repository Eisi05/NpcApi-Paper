package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an NPC is hidden from a player's view. This event is called after the NPC has been hidden from the player.
 */
public class NpcHideEvent extends Event
{
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final NPC npc;

    /**
     * Creates a new NpcHideEvent.
     *
     * @param player the player from whom the NPC is being hidden
     * @param npc    the NPC that is being hidden
     * @throws IllegalArgumentException if player or npc is null
     */
    public NpcHideEvent(@NotNull Player player, @NotNull NPC npc)
    {
        this.player = player;
        this.npc = npc;
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
     * Gets the NPC that is being hidden.
     *
     * @return the NPC being hidden, never null
     */
    public @NotNull NPC getNpc()
    {
        return npc;
    }

    /**
     * Gets the player from whom the NPC is being hidden.
     *
     * @return the player, never null
     */
    public @NotNull Player getPlayer()
    {
        return player;
    }
}
