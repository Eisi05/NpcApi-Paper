package de.eisi05.npc.api.enums;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/**
 * Represents the type of click action that can be performed on an NPC.
 * This includes left click, right click, or both.
 */
public enum ClickActionType implements Serializable
{
    /**
     * Represents a left-click action.
     */
    LEFT("Left"),

    /**
     * Represents a right-click action.
     */
    RIGHT("Right"),
    /**
     * Represents both left and right-click actions.
     */

    BOTH("Left & Right");

    public final @NotNull String title;

    /**
     * Constructs a ClickActionType with the given title.
     *
     * @param title The display name for this click action type.
     */
    ClickActionType(@NotNull String title)
    {
        this.title = title;
    }
}