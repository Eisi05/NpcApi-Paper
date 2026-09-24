package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an NPC is hidden from a player's view. This event is called after the NPC has been hidden from the player.
 */
public class NpcHideEvent extends NpcPlayerEvent
{
    /**
     * Creates a new NpcHideEvent.
     *
     * @param player the player from whom the NPC is being hidden
     * @param npc    the NPC that is being hidden
     * @throws IllegalArgumentException if player or npc is null
     */
    public NpcHideEvent(@NotNull Player player, @NotNull NPC npc)
    {
        super(npc, player);
    }
}
