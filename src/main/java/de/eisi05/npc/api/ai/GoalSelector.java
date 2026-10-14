package de.eisi05.npc.api.ai;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.environment.WorldAccess;
import de.eisi05.npc.api.scheduler.PluginTask;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Manages and selects goals for an NPC. The goal selector evaluates the available goals and selects the highest priority goal that can be used.
 * <p>
 * Runs once per tick for <b>every</b> NPC with goals, so it is written to be cheap:
 * <ul>
 *     <li><b>No per-tick allocation</b>: the goal list is cached (rebuilt when it changes) and selection uses reusable buffers instead of copies and streams.</li>
 *     <li><b>No pointless evaluation</b>: while the running goal cannot be interrupted, only {@link Goal.Priority#ALWAYS} goals can take over, so only those
 *     are evaluated. {@code canUse} of all other goals - which can mean expression predicates, entity searches or pathfinding checks - is not called.</li>
 *     <li><b>Idle throttling</b>: an NPC that has no goal running and that nobody can see only re-evaluates every {@link #getIdleSelectionInterval()} ticks,
 *     and can optionally not start goals at all ({@link #setPauseWithoutViewers(boolean)}).</li>
 *     <li><b>Error isolation</b>: a goal that throws is stopped and logged (rate limited) instead of breaking the selector or spamming the console every
 *     tick.</li>
 * </ul>
 * <p>
 * <b>Paper and Folia:</b> instead of one repeating Bukkit task the selector re-schedules itself every {@link #getTickInterval()} ticks <i>at the NPC's
 * location</i>, so on Folia every tick (and with it every goal) runs on the thread that owns the region the NPC is in, where block and entity access is legal;
 * on Paper it is the ordinary main-thread tick. An NPC whose chunk is not loaded has no region to run in: its goals pause (polled from the global scheduler)
 * until the chunk is loaded again. All public methods may be called from any thread: the ones that run goal code ({@link #stop()}, {@link #forceGoal},
 * {@link #stopGoalIfRunning}) hand the work to the NPC's region (inline if the caller already is that thread), and an internal lock keeps a tick, such a hand-over
 * and a goal callback from ever running at the same time, even when the NPC walks from one region into another.
 */
public class GoalSelector
{
    private static final int DEFAULT_IDLE_SELECTION_INTERVAL = 20;
    private static final int FULL_REFRESH_TICKS = 100;
    private static final int ERROR_COOLDOWN_TICKS = 40;
    private static final long ERROR_LOG_INTERVAL_MS = 10_000;

    private final NPC npc;
    private final Set<Goal> removalQueue = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<PluginTask> scheduled = new AtomicReference<>();
    private final List<Goal> usableBuffer = new ArrayList<>();
    private final Map<String, Long> lastErrorLog = new HashMap<>();

    private volatile Goal currentGoal;
    private volatile boolean running;
    private volatile long tickInterval;

    private volatile boolean pauseWithoutViewers = false;
    private volatile int idleSelectionInterval = DEFAULT_IDLE_SELECTION_INTERVAL;
    private int idleCounter = DEFAULT_IDLE_SELECTION_INTERVAL;
    private int errorCooldown;

    private List<Goal> cachedSource;
    private volatile int cachedSize = -1;
    private int refreshCounter;
    private Goal[] goals = new Goal[0];
    private Goal[] alwaysGoals = new Goal[0];

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
        invalidateGoalCache();
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
        invalidateGoalCache();
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
            runInRegion(() ->
            {
                if(currentGoal == goal)
                {
                    safeStop(goal);
                    currentGoal = null;
                }
            });
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
        if(currentGoal != goal)
            return true;

        Location location = npc.getLocation();
        if(location.getWorld() != null && !WorldAccess.isOwned(location))
        {
            // canBeRemovedNow may read blocks, which this thread must not: let the tick (in the NPC's region) decide
            removalQueue.add(goal);
            return false;
        }

        lock.lock();
        try
        {
            if(goal.canBeRemovedNow(npc))
                return true;

            removalQueue.add(goal);
            return false;
        }
        finally
        {
            lock.unlock();
        }
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
     * Starts the goal selector. This will begin evaluating and executing goals. Safe to call from any thread.
     */
    public void start()
    {
        synchronized(this)
        {
            if(running)
                return;

            running = true;
            idleCounter = idleSelectionInterval;
            scheduleTick(generation.incrementAndGet(), 1L);
        }
    }

    /**
     * Stops the goal selector. This will stop the current goal and cease evaluation. Safe to call from any thread; the running goal is stopped by the thread
     * that owns the NPC's region (inline if the caller is that thread).
     */
    public void stop()
    {
        synchronized(this)
        {
            if(!running)
                return;

            running = false;
            generation.incrementAndGet();

            PluginTask task = scheduled.getAndSet(null);
            if(task != null)
                task.cancel();
        }

        runInRegion(() ->
        {
            Goal goal = currentGoal;
            if(goal != null)
            {
                safeStop(goal);
                currentGoal = null;
            }
        });
    }

    /**
     * Schedules the next tick of the given generation on the thread that owns the NPC's current position.
     */
    private void scheduleTick(int gen, long delay)
    {
        if(!running || gen != generation.get())
            return;

        try
        {
            Location location = npc.getLocation().clone();
            World world = location.getWorld();

            if(world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
            {
                // no region exists for an unloaded chunk, so nothing could run there: poll until it is loaded again (goals are paused meanwhile)
                remember(SchedulerProvider.get().runDelayed(() -> scheduleTick(gen, 1L), 20L), gen);
                return;
            }

            remember(SchedulerProvider.get().runLaterAtLocation(location, () -> runTick(gen), Math.max(1L, delay)), gen);
        }
        catch(Throwable throwable)
        {
            running = false; // plugin disabling: scheduling is refused
        }
    }

    private void remember(@Nullable PluginTask task, int gen)
    {
        scheduled.set(task);
        if(task != null && gen != generation.get())
            task.cancel();
    }

    /**
     * One scheduled tick: runs in the NPC's region, then schedules the next one.
     */
    private void runTick(int gen)
    {
        if(!running || gen != generation.get())
            return;

        boolean wrongRegion = false;
        lock.lock();
        try
        {
            if(!running || gen != generation.get())
                return;

            Location location = npc.getLocation();
            if(location.getWorld() != null && !WorldAccess.isOwned(location))
                wrongRegion = true;
            else
                tick();
        }
        catch(Throwable throwable)
        {
            try
            {
                NpcApi.plugin.getLogger().log(Level.WARNING, "NPC goal selector failed", throwable);
            }
            catch(Throwable ignored)
            {
                // never let logging break the selector
            }
        }
        finally
        {
            lock.unlock();
        }

        scheduleTick(gen, wrongRegion ? 1L : tickInterval);
    }

    /**
     * Runs a job that executes goal code (and may therefore read blocks) in the NPC's region: inline if the caller owns it, otherwise through the region
     * scheduler. Always under the selector lock.
     */
    private void runInRegion(@NotNull Runnable job)
    {
        Runnable locked = () ->
        {
            lock.lock();
            try
            {
                job.run();
            }
            finally
            {
                lock.unlock();
            }
        };

        Location location = npc.getLocation();
        World world = location.getWorld();

        try
        {
            if(world == null || WorldAccess.isOwned(location))
                locked.run();
            else if(!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
                SchedulerProvider.get().runSync(locked);
            else
                SchedulerProvider.get().runSyncAtLocation(location.clone(), locked);
        }
        catch(Throwable throwable)
        {
            locked.run();
        }
    }

    /**
     * An executor that runs tasks in the NPC's region under the selector lock (inline if the caller already owns that region). Goals use it for callbacks that
     * arrive from other threads (finished searches, finished walks), so those never run concurrently with a tick of the same goal.
     *
     * @return the executor
     */
    public @NotNull java.util.concurrent.Executor executor()
    {
        return this::runInRegion;
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
     * @return whether the NPC does not start new goals while nobody can see it
     */
    public boolean isPauseWithoutViewers()
    {
        return pauseWithoutViewers;
    }

    /**
     * If enabled, an NPC that has no goal running does not start one while it has no viewers (like entities in unloaded areas, it simply waits until someone
     * can see it). Goals that are already running finish normally. Default: false.
     * <p>
     * This is the single biggest saving on servers with many NPCs in places where no player is around, at the price that unobserved NPCs do not wander,
     * patrol or fight until a player comes near.
     *
     * @param pauseWithoutViewers true to pause goal selection for NPCs without viewers
     */
    public void setPauseWithoutViewers(boolean pauseWithoutViewers)
    {
        this.pauseWithoutViewers = pauseWithoutViewers;
    }

    /**
     * @return how many ticks an idle NPC without viewers waits between goal evaluations
     */
    public int getIdleSelectionInterval()
    {
        return idleSelectionInterval;
    }

    /**
     * Sets how many ticks an NPC that has no goal running and no viewers waits between goal evaluations (default 20). NPCs with viewers are always evaluated
     * every tick.
     *
     * @param ticks the interval in ticks, at least 1
     */
    public void setIdleSelectionInterval(int ticks)
    {
        this.idleSelectionInterval = Math.max(1, ticks);
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

        runInRegion(() ->
        {
            if(currentGoal != null)
                safeStop(currentGoal);

            currentGoal = null;
            if(safeStart(goal))
                currentGoal = goal;
        });
    }

    /**
     * Invalidates the cached goal arrays, forcing a rebuild on the next tick.
     */
    private void invalidateGoalCache()
    {
        cachedSize = -1;
    }

    /**
     * Rebuilds the cached goal arrays when the NPC's goal list changed (detected by identity and size, plus a periodic full refresh).
     */
    private void refreshGoals()
    {
        List<Goal> source = npc.getGoals();
        int size = source == null ? 0 : source.size();

        if(source == cachedSource && size == cachedSize && ++refreshCounter < FULL_REFRESH_TICKS)
            return;

        refreshCounter = 0;
        cachedSource = source;
        cachedSize = size;

        if(source == null || size == 0)
        {
            goals = new Goal[0];
            alwaysGoals = new Goal[0];
            return;
        }

        goals = source.toArray(new Goal[0]);

        int always = 0;
        for(Goal goal : goals)
        {
            if(goal.getPriority() == Goal.Priority.ALWAYS)
                always++;
        }

        alwaysGoals = new Goal[always];
        int i = 0;
        for(Goal goal : goals)
        {
            if(goal.getPriority() == Goal.Priority.ALWAYS)
                alwaysGoals[i++] = goal;
        }
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
                invalidateGoalCache();

                if(goal == currentGoal)
                    currentGoal = null;
            }
        }
    }

    /**
     * Picks one of the usable {@link Goal.Priority#ALWAYS} goals. The running goal is kept if it is still usable, so two always-usable goals do not flip back
     * and forth every tick.
     */
    private @Nullable Goal selectAlways(@Nullable Goal current)
    {
        List<Goal> usable = usableBuffer;
        usable.clear();

        for(Goal goal : alwaysGoals)
        {
            if(!removalQueue.contains(goal) && safeCanUse(goal))
                usable.add(goal);
        }

        if(usable.isEmpty())
            return null;

        if(current != null && usable.contains(current))
            return current;

        return usable.get(ThreadLocalRandom.current().nextInt(usable.size()));
    }

    /**
     * Evaluates every goal and picks one: an {@link Goal.Priority#ALWAYS} goal if one is usable, otherwise a weighted random choice by priority.
     */
    private @Nullable Goal selectAny(@Nullable Goal current)
    {
        Goal always = selectAlways(current);
        if(always != null)
            return always;

        List<Goal> usable = usableBuffer;
        usable.clear();

        for(Goal goal : goals)
        {
            if(goal.getPriority() != Goal.Priority.ALWAYS && !removalQueue.contains(goal) && safeCanUse(goal))
                usable.add(goal);
        }

        if(usable.isEmpty())
            return null;

        if(usable.size() == 1)
            return usable.getFirst();

        int totalWeight = 0;
        for(Goal goal : usable)
            totalWeight += goal.getPriority().weight;

        if(totalWeight == 0)
            return usable.get(ThreadLocalRandom.current().nextInt(usable.size()));

        int randomWeight = ThreadLocalRandom.current().nextInt(totalWeight);
        int currentWeight = 0;
        for(Goal goal : usable)
        {
            currentWeight += goal.getPriority().weight;
            if(randomWeight < currentWeight)
                return goal;
        }
        return usable.getLast();
    }

    /**
     * Whether an NPC without a running goal should skip evaluation this tick.
     */
    private boolean idleThrottled()
    {
        if(!npc.getViewers().isEmpty())
        {
            idleCounter = idleSelectionInterval;
            return false;
        }

        if(pauseWithoutViewers)
            return true;

        if(++idleCounter < idleSelectionInterval)
            return true;

        idleCounter = 0;
        return false;
    }

    /**
     * The main tick method that evaluates and executes goals.
     */
    private void tick()
    {
        if(errorCooldown > 0)
        {
            errorCooldown--;
            return;
        }

        processRemovalQueue();
        refreshGoals();

        Goal current = currentGoal;

        Goal selected;
        if(current == null)
        {
            if(goals.length == 0 || idleThrottled())
                return;

            selected = selectAny(null);
        }
        else
        {
            selected = selectAlways(current);
            if(selected == null && safeCanBeInterrupted(current))
                selected = selectAny(current);
        }

        if(current != null)
        {
            if(selected != null && selected != current && (selected.getPriority() == Goal.Priority.ALWAYS || safeCanBeInterrupted(current)))
            {
                safeStop(current);
                currentGoal = null;
                if(safeStart(selected))
                    currentGoal = selected;
                return;
            }

            if(safeCanContinue(current))
            {
                if(!safeTick(current))
                {
                    safeStop(current);
                    currentGoal = null;
                }
                return;
            }

            safeStop(current);
            currentGoal = null;

            if(selected == null)
                selected = selectAny(null);
        }

        if(selected != null && safeStart(selected))
            currentGoal = selected;
    }

    private boolean safeCanUse(@NotNull Goal goal)
    {
        try
        {
            return goal.canUse(npc);
        }
        catch(Throwable throwable)
        {
            report(goal, "canUse", throwable);
            return false;
        }
    }

    private boolean safeCanContinue(@NotNull Goal goal)
    {
        try
        {
            return goal.canContinue(npc);
        }
        catch(Throwable throwable)
        {
            report(goal, "canContinue", throwable);
            return false;
        }
    }

    private boolean safeCanBeInterrupted(@NotNull Goal goal)
    {
        try
        {
            return goal.canBeInterrupted(npc);
        }
        catch(Throwable throwable)
        {
            report(goal, "canBeInterrupted", throwable);
            return false;
        }
    }

    private boolean safeStart(@NotNull Goal goal)
    {
        try
        {
            goal.start(npc);
            return true;
        }
        catch(Throwable throwable)
        {
            report(goal, "start", throwable);
            safeStop(goal);
            return false;
        }
    }

    private boolean safeTick(@NotNull Goal goal)
    {
        try
        {
            goal.tick(npc);
            return true;
        }
        catch(Throwable throwable)
        {
            report(goal, "tick", throwable);
            return false;
        }
    }

    private void safeStop(@NotNull Goal goal)
    {
        try
        {
            goal.stop(npc);
        }
        catch(Throwable throwable)
        {
            report(goal, "stop", throwable);
        }
    }

    /**
     * Logs a failing goal (at most once per goal type every few seconds) and pauses the selector briefly, so a broken goal cannot flood the console or be
     * restarted every single tick.
     */
    private void report(@NotNull Goal goal, @NotNull String phase, @NotNull Throwable throwable)
    {
        errorCooldown = ERROR_COOLDOWN_TICKS;

        String key = goal.getClass().getName() + "#" + phase;
        long now = System.currentTimeMillis();
        Long last = lastErrorLog.get(key);
        if(last != null && now - last < ERROR_LOG_INTERVAL_MS)
            return;

        lastErrorLog.put(key, now);
        try
        {
            NpcApi.plugin.getLogger().log(Level.WARNING, "Goal " + goal.getClass().getSimpleName() + " threw an exception in " + phase + "(); the goal was stopped",
                    throwable);
        }
        catch(Throwable ignored)
        {
            // never let logging break the selector
        }
    }
}
