package de.eisi05.npc.api.ai;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.objects.NPC;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages and selects goals for an NPC. The goal selector evaluates all available goals each tick and selects the highest priority goal that can be used.
 */
public class GoalSelector
{
    private final NPC npc;
    private final Set<Goal> removalQueue = new HashSet<>();
    private Goal currentGoal;
    private BukkitTask task;
    private boolean running;
    private long tickInterval;

    /**
     * Creates a new GoalSelector for the specified NPC.
     *
     * @param npc The NPC to manage goals for
     */
    public GoalSelector(@NotNull NPC npc)
    {
        this.npc = npc;
        this.tickInterval = 1L;
    }

    /**
     * Adds a goal to this selector.
     *
     * @param goal The goal to add
     * @return This selector for method chaining
     */
    public @NotNull GoalSelector addGoal(@NotNull Goal goal)
    {
        npc.addGoal(goal);
        return this;
    }

    /**
     * Removes a goal from this selector.
     *
     * @param goal The goal to remove
     * @return This selector for method chaining
     */
    public @NotNull GoalSelector removeGoal(@NotNull Goal goal)
    {
        npc.removeGoal(goal);
        return this;
    }

    /**
     * Stops the goal if it is running
     *
     * @param goal The goal to check
     * @return This selector for method chaining
     */
    public @NotNull GoalSelector stopGoalIfRunning(@NotNull Goal goal)
    {
        if(currentGoal == goal)
        {
            currentGoal.stop(npc);
            currentGoal = null;
        }
        return this;
    }

    /**
     * Queues a goal for removal. If the goal is currently running, it will be removed immediately if it can be removed now, otherwise it will be queued for
     * removal and removed after it finishes naturally. If the goal is not currently running, it will be removed immediately from the NPC's goal list.
     *
     * @param goal The goal to queue for removal
     * @return true if the goal was removed immediately or is not currently running, false if the goal was queued for removal
     */
    public boolean queueGoalForRemoval(@NotNull Goal goal)
    {
        if(currentGoal == goal)
        {
            if(goal.canBeRemovedNow(npc))
                return true;
            else
            {
                removalQueue.add(goal);
                return false;
            }
        }
        else
            return true;
    }

    /**
     * Checks if a goal is queued for removal.
     * <p>
     * Goals that are currently running cannot be removed immediately and are instead queued for removal once they finish naturally. This method allows checking
     * whether a specific goal is in that removal queue.
     *
     * @param goal The goal to check
     * @return true if the goal is queued for removal, false otherwise
     */
    public boolean isGoalQueuedForRemoval(@NotNull Goal goal)
    {
        return removalQueue.contains(goal);
    }

    /**
     * Gets all goals registered with this selector.
     *
     * @return A list of all goals
     */
    public @NotNull ArrayList<Goal> getGoals()
    {
        return new ArrayList<>(npc.getGoals());
    }

    /**
     * Gets the currently active goal.
     *
     * @return The current goal, or null if no goal is active
     */
    public @Nullable Goal getCurrentGoal()
    {
        return currentGoal;
    }

    /**
     * Starts the goal selector. This will begin evaluating and executing goals.
     */
    public void start()
    {
        if(running)
            return;

        running = true;
        task = Bukkit.getScheduler().runTaskTimer(NpcApi.plugin, this::tick, 0L, tickInterval);
    }

    /**
     * Stops the goal selector. This will stop the current goal and cease evaluation.
     */
    public void stop()
    {
        if(!running)
            return;

        running = false;
        if(task != null)
        {
            task.cancel();
            task = null;
        }

        if(currentGoal != null)
        {
            currentGoal.stop(npc);
            currentGoal = null;
        }
    }

    /**
     * Checks whether the goal selector is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning()
    {
        return running;
    }

    /**
     * Gets the current tick interval.
     *
     * @return The tick interval in ticks
     */
    public long getTickInterval()
    {
        return tickInterval;
    }

    /**
     * Sets the tick interval for goal evaluation.
     *
     * @param ticks The number of ticks between evaluations (1 = every tick)
     */
    public void setTickInterval(long ticks)
    {
        this.tickInterval = ticks;
        if(running)
        {
            stop();
            start();
        }
    }

    /**
     * Manually forces a specific goal to start, bypassing normal selection. This is useful for triggering goals based on external events.
     *
     * @param goal The goal to force start
     */
    public void forceGoal(@NotNull Goal goal)
    {
        if(!getGoals().contains(goal))
            return;

        if(currentGoal != null)
            currentGoal.stop(npc);

        currentGoal = goal;
        currentGoal.start(npc);
    }

    /**
     * Processes the removal queue, removing goals that have finished running.
     */
    private void processRemovalQueue()
    {
        if(removalQueue.isEmpty())
            return;

        List<Goal> copy = new ArrayList<>(removalQueue);
        for(Goal goal : copy)
        {
            if(goal != null && removalQueue.contains(goal) && goal.canBeRemovedNow(npc))
            {
                removalQueue.remove(goal);
                npc.removeGoal(goal);

                if(goal == currentGoal)
                    currentGoal = null;
            }
        }
    }

    /**
     * The main tick method that evaluates and executes goals.
     */
    private void tick()
    {
        processRemovalQueue();

        List<Goal> usableGoals = getGoals().stream()
                .filter(goal -> goal.canUse(npc))
                .toList();

        if(usableGoals.isEmpty())
        {
            if(currentGoal != null)
            {
                currentGoal.stop(npc);
                currentGoal = null;
            }
            return;
        }

        List<Goal> alwaysGoals = usableGoals.stream()
                .filter(goal -> goal.getPriority() == Goal.Priority.ALWAYS)
                .toList();

        Goal selectedGoal;
        if(!alwaysGoals.isEmpty())
            selectedGoal = alwaysGoals.get(ThreadLocalRandom.current().nextInt(alwaysGoals.size()));
        else
        {
            if(usableGoals.isEmpty())
                selectedGoal = null;
            else if(usableGoals.size() == 1)
                selectedGoal = usableGoals.getFirst();
            else
            {
                int totalWeight = usableGoals.stream()
                        .mapToInt(goal -> goal.getPriority().weight)
                        .sum();

                if(totalWeight == 0)
                    selectedGoal = usableGoals.get(ThreadLocalRandom.current().nextInt(usableGoals.size()));
                else
                {
                    int randomWeight = ThreadLocalRandom.current().nextInt(totalWeight);
                    int currentWeight = 0;

                    selectedGoal = null;
                    for(Goal goal : usableGoals)
                    {
                        currentWeight += goal.getPriority().weight;
                        if(randomWeight < currentWeight)
                        {
                            selectedGoal = goal;
                            break;
                        }
                    }

                    if(selectedGoal == null)
                        selectedGoal = usableGoals.getLast();
                }
            }
        }

        if(currentGoal != null)
        {
            if(selectedGoal != null && selectedGoal != currentGoal)
            {
                if(selectedGoal.getPriority() == Goal.Priority.ALWAYS || currentGoal.canBeInterrupted(npc))
                {
                    currentGoal.stop(npc);
                    currentGoal = selectedGoal;
                    currentGoal.start(npc);
                    return;
                }
            }

            if(currentGoal.canContinue(npc))
            {
                currentGoal.tick(npc);
                return;
            }
            else
            {
                currentGoal.stop(npc);
                currentGoal = null;
            }
        }

        if(selectedGoal != null)
        {
            currentGoal = selectedGoal;
            currentGoal.start(npc);
        }
    }
}
