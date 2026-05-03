package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.objects.NPC;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * A goal that makes the NPC wait/idle for a specified duration. This is useful for creating pauses between actions.
 */
public class WaitGoal extends Goal
{
    @Serial
    private static final long serialVersionUID = 1L;

    private int durationTicks;
    private transient int ticksRemaining;

    /**
     * Creates a WaitGoal with the specified duration.
     *
     * @param durationTicks The duration to wait in ticks (20 ticks = 1 second)
     */
    public WaitGoal(int durationTicks)
    {
        super(Priority.LOW);
        this.durationTicks = Math.max(1, durationTicks);
    }

    /**
     * Gets the duration in ticks.
     *
     * @return The duration in ticks
     */
    public int getDurationTicks()
    {
        return durationTicks;
    }

    /**
     * Sets the duration for this goal.
     *
     * @param durationTicks the new duration in ticks
     */
    public void setDurationTicks(int durationTicks)
    {
        this.durationTicks = Math.max(1, durationTicks);
    }

    /**
     * Checks if this goal can be used by the NPC.
     *
     * @param npc the NPC to check
     * @return true if there are remaining ticks or duration is set
     */
    @Override
    public boolean canUse(@NotNull NPC npc)
    {
        return ticksRemaining > 0 || durationTicks > 0;
    }

    /**
     * Starts the wait goal, initializing the tick counter.
     *
     * @param npc the NPC starting this goal
     */
    @Override
    public void start(@NotNull NPC npc)
    {
        ticksRemaining = durationTicks;
    }

    /**
     * Ticks the wait goal, decrementing the remaining ticks.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        ticksRemaining--;
    }

    /**
     * Stops the wait goal and resets the tick counter.
     *
     * @param npc the NPC stopping this goal
     */
    @Override
    public void stop(@NotNull NPC npc)
    {
        ticksRemaining = 0;
    }

    /**
     * Checks if this goal should continue running.
     *
     * @param npc the NPC to check
     * @return true if there are remaining ticks
     */
    @Override
    public boolean canContinue(@NotNull NPC npc)
    {
        return ticksRemaining > 0;
    }

    /**
     * Gets the remaining ticks.
     *
     * @return The remaining ticks
     */
    public int getTicksRemaining()
    {
        return ticksRemaining;
    }

    /**
     * Resets the wait duration.
     */
    public void reset()
    {
        ticksRemaining = durationTicks;
    }
}
