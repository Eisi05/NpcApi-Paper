package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called when an NPC dies.
 * <p>
 * This event can be cancelled to prevent the NPC from dying.
 */
public class NpcDeathEvent extends NpcPlayerEvent implements Cancellable
{
    private boolean cancelled;

    /**
     * Creates a new NPC death event.
     *
     * @param npc    the NPC that died
     * @param killer the player who killed the NPC
     */
    public NpcDeathEvent(@NotNull NPC npc, @Nullable Player killer)
    {
        super(npc, killer);
    }

    /**
     * Gets the player who killed the NPC.
     *
     * @return the killer
     */
    public @Nullable Player getKiller()
    {
        return player;
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