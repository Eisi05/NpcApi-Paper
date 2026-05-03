package de.eisi05.npc.api.ai;

import de.eisi05.npc.api.objects.NPC;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;

/**
 * Base abstract class for NPC AI goals. Goals represent specific behaviors that an NPC can perform, such as walking to a location, attacking an entity, or
 * looking around. Extend this class to create custom goal behaviors.
 */
public abstract class Goal implements Serializable
{
    @Serial
    private final static long serialVersionUID = 1L;
    private Priority priority;

    /**
     * Creates a new goal with the specified priority.
     *
     * @param priority The priority level for this goal
     */
    protected Goal(@NotNull Priority priority)
    {
        this.priority = priority;
    }

    private Goal()
    {
    }

    /**
     * Gets the priority of this goal.
     *
     * @return The priority of this goal
     */
    public final @NotNull Priority getPriority()
    {
        return priority;
    }

    /**
     * Sets the priority of this goal.
     *
     * @param priority The new priority for this goal
     */
    public final void setPriority(@NotNull Priority priority)
    {
        this.priority = priority;
    }

    /**
     * Checks whether this goal can be used right now. This is called each tick to determine if the goal should be considered for execution.
     *
     * @param npc The NPC to check for
     * @return true if this goal can be used, false otherwise
     */
    protected abstract boolean canUse(@NotNull NPC npc);

    /**
     * Called when this goal starts executing. Use this to initialize any state needed for the goal.
     *
     * @param npc The NPC starting this goal
     */
    protected abstract void start(@NotNull NPC npc);

    /**
     * Called each tick while this goal is active. Use this to update the goal's behavior.
     *
     * @param npc The NPC executing this goal
     */
    protected abstract void tick(@NotNull NPC npc);

    /**
     * Called when this goal stops executing. Use this to clean up any state or cancel ongoing tasks.
     *
     * @param npc The NPC stopping this goal
     */
    protected abstract void stop(@NotNull NPC npc);

    /**
     * Checks whether this goal can continue executing. If this returns false, the goal will be stopped and a new goal will be selected.
     *
     * @param npc The NPC to check for
     * @return true if this goal can continue, false otherwise
     */
    protected boolean canContinue(@NotNull NPC npc)
    {
        return canUse(npc);
    }

    /**
     * Checks whether this goal can be interrupted by a new goal selection. If this returns true, the goal selector may switch to a different goal even if this
     * goal can continue. This is useful for goals that have natural break points (e.g., waiting periods).
     *
     * @param npc The NPC to check for
     * @return true if this goal can be interrupted, false otherwise
     */
    protected boolean canBeInterrupted(@NotNull NPC npc)
    {
        return false;
    }

    /**
     * Priority levels for goal selection. Higher priority goals are more likely to be selected. ALWAYS priority goals are always preferred over other goals.
     * Other priorities use weighted random selection where higher weight = higher chance of being selected.
     */
    public enum Priority implements Serializable
    {
        /**
         * This goal is always preferred over other goals and will be selected deterministically.
         */
        ALWAYS(100),

        /**
         * Very high chance of being selected when among non-ALWAYS goals.
         */
        HIGHEST(4),

        /**
         * High chance of being selected when among non-ALWAYS goals.
         */
        HIGH(3),

        /**
         * Medium chance of being selected when among non-ALWAYS goals.
         */
        MEDIUM(2),

        /**
         * Low chance of being selected when among non-ALWAYS goals.
         */
        LOW(1);

        @Serial
        private final static long serialVersionUID = 1L;
        final int weight;

        Priority(int weight)
        {
            this.weight = weight;
        }
    }
}
