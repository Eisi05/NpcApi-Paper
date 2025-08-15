package de.eisi05.npc.api.interfaces;

import de.eisi05.npc.api.events.NpcInteractEvent;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;

/**
 * Functional interface representing an action to be performed
 * when an NPC is clicked.
 */
@FunctionalInterface
public interface NpcClickAction extends Serializable
{
    @Serial
    long serialVersionUID = 1L;

    /**
     * Called when an NPC click event occurs.
     *
     * @param event the NpcInteractEvent containing interaction details
     */
    void call(@NotNull NpcInteractEvent event);

    /**
     * Returns a copy of this NpcClickAction.
     * The default implementation returns the same instance.
     *
     * @return a copy of this action
     */
    default NpcClickAction copy() {return this;}

    /**
     * Initializes this {@link NpcClickAction}.
     * This method can be used for any setup or configuration that needs to occur
     * after the action is created or loaded.
     * The default implementation simply returns the current instance,
     * indicating no specific initialization is required by default.
     *
     * @return The initialized {@link NpcClickAction} instance. By default, it returns {@code this}.
     */
    default NpcClickAction initialize() {return this;}
}
