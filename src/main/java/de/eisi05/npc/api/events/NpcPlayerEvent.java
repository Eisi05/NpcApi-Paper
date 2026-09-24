package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;

/**
 * Base class for all events that are related to a player and an NPC.
 */
public abstract class NpcPlayerEvent extends Event implements Serializable
{
    private static final HandlerList HANDLERS = new HandlerList();

    @Serial
    private static final long serialVersionUID = 1L;

    protected final NPC npc;
    protected final Player player;

    /**
     * Constructs a new NpcPlayerEvent.
     *
     * @param npc the NPC associated with the event
     * @param player the player associated with the event
     */
    public NpcPlayerEvent(@NotNull NPC npc, @Nullable Player player)
    {
        this.npc = npc;
        this.player = player;
    }

    /**
     * Gets the NPC associated with the event.
     *
     * @return the NPC associated with the event
     */
    public @NotNull NPC getNpc()
    {
        return npc;
    }

    /**
     * Gets the player associated with the event.
     *
     * @return the player associated with the event
     */
    public @Nullable Player getPlayer()
    {
        return player;
    }

    /**
     * Gets the handler list for this event.
     *
     * @return the event's handler list
     */
    public @NotNull
    static HandlerList getHandlerList()
    {
        return HANDLERS;
    }

    /**
     * Gets the handler list for this event.
     *
     * @return the event's handler list
     */
    @Override
    public @NotNull HandlerList getHandlers()
    {
        return HANDLERS;
    }
}
