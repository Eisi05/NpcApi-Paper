package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public abstract class NpcWalkingEvent extends Event
{
    private static final HandlerList HANDLERS = new HandlerList();
    private final NPC npc;

    public NpcWalkingEvent(NPC npc)
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
