package de.eisi05.npc.api.enums;

import java.io.Serial;
import java.io.Serializable;

/**
 * Represents the visibility state of an NPC.
 */
public enum NpcVisibility implements Serializable
{
    /**
     * The NPC is fully visible to the player.
     */
    FULLY_VISIBLE,

    /**
     * The NPC is transparent - the player can see through it, but it's still visible.
     */
    TRANSPARENT,

    /**
     * The NPC is completely invisible to the player.
     */
    INVISIBLE;

    @Serial
    private static final long serialVersionUID = 1L;
}
