package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an event that is called when an NPC's walking state changes. This is an abstract base class for NPC walking-related events.
 */
public abstract class NpcWalkingEvent extends Event
{
    private static final HandlerList HANDLERS = new HandlerList();
    private final NPC npc;

    /**
     * Creates a new NpcWalkingEvent.
     *
     * @param npc the NPC whose walking state is changing
     * @throws IllegalArgumentException if npc is null
     */
    public NpcWalkingEvent(@NotNull NPC npc)
    {
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

    /**
     * Returns the NPC involved in this event.
     *
     * @return the interacted NPC, never null
     */
    public @NotNull NPC getNpc()
    {
        return npc;
    }

    @Override
    public @NotNull HandlerList getHandlers()
    {
        return getHandlerList();
    }
}
