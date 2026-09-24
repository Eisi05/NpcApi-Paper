package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Called after an NPC has been fully shown to a player and all packets have been sent. This event is called after the NPC is already visible to the player and
 * cannot be canceled.
 */
public class NpcPostShowEvent extends NpcPlayerEvent
{
    private final boolean wasViewer;

    /**
     * Creates a new NpcPostShowEvent.
     *
     * @param player    the player to whom the NPC was shown
     * @param npc       the NPC that was shown
     * @param wasViewer whether the player was previously a viewer of this NPC
     * @throws IllegalArgumentException if player or npc is null
     */
    public NpcPostShowEvent(@NotNull Player player, @NotNull NPC npc, boolean wasViewer)
    {
        super(npc, player);
        this.wasViewer = wasViewer;
    }

    /**
     * Checks if the player was previously a viewer of this NPC. This can be used to determine if the player was seeing this NPC for the first time or if it was
     * a refresh of an existing view.
     *
     * @return true if the player was previously viewing this NPC, false otherwise
     */
    public boolean wasViewer()
    {
        return wasViewer;
    }
}
