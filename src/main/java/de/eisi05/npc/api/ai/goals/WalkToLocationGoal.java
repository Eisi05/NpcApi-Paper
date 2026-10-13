package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.pathfinding.algorithm.AbstractPathfinder;
import de.eisi05.npc.api.pathfinding.util.PathProjection;
import de.eisi05.npc.api.pathfinding.util.PathValidator;
import de.eisi05.npc.api.scheduler.tasks.PathTask;
import de.eisi05.npc.api.scheduler.tasks.Tasks;
import de.eisi05.npc.api.utils.SerializableConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A goal that makes the NPC walk to a specific location using pathfinding.
 * <p>
 * Designed to stay smooth when the target keeps changing (follow / attack goals re-target many times per second):
 * <ul>
 *     <li><b>Explicit state</b> ({@code IDLE → CALCULATING → WALKING}): the goal keeps running while a path is being calculated and while a replacement path
 *     is on its way, instead of being torn down and restarted.</li>
 *     <li><b>Seamless re-pathing</b>: replacement paths are swapped into the running walk ({@link PathTask#updatePath}); the NPC never stops. The
 *     replacement starts a few ticks <i>ahead</i> of the NPC along its current path, so it does not begin behind it and step back.</li>
 *     <li><b>One search instead of two</b>: the pathability check and the real path request share the same search; a finished search is reused for a second
 *     if the NPC has barely moved.</li>
 *     <li><b>Real cancellation</b>: a superseded or abandoned search is aborted, not run to completion.</li>
 *     <li><b>Cheap safety nets</b>: the next few waypoints are re-validated against the world every few ticks (with the same collision rules the planner
 *     used), and stuck detection samples every 10 ticks.</li>
 *     <li><b>Quiet internal restarts</b>: re-pathing never reports a {@code CANCELLED} result to the completion callback; the callback fires exactly once
 *     per run, when the NPC arrived, or the goal failed or was cancelled.</li>
 * </ul>
 */
public class WalkToLocationGoal extends PathfindingGoal
{
    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * Minimum number of ticks between two path requests (retargeting is coalesced).
     */

    public static final int DEFAULT_MAX_ITERATIONS = 5000;
    public static final double DEFAULT_SPEED = 0.25;
    static final int RECALCULATION_COOLDOWN = 20;

    private static final int MIN_REPATH_INTERVAL_TICKS = 10;
    private static final int PATH_CHECK_INTERVAL_TICKS = 5;
    private static final int PATH_CHECK_POINTS_AHEAD = 8;
    private static final double PATH_CHECK_HORIZON = 14.0;
    private static final int STUCK_SAMPLE_INTERVAL_TICKS = 5;
    private static final int STUCK_STRIKES = 3;
    private static final double STUCK_MIN_MOVEMENT_SQ = 0.04;
    private static final int MAX_STUCK_RETRIES = 3;
    private static final int CALCULATION_TIMEOUT_TICKS = 200;
    private static final int PREDICT_TICKS = 4;
    private static final long REQUEST_REUSE_MS = 1000;
    private static final double REQUEST_REUSE_START_SQ = 2.25;
    private static final double REQUEST_REUSE_TARGET_SQ = 0.25;
    private static final long PATHABILITY_CHECK_INTERVAL_MS = 5000;

    private Path.SerializablePath.SerializableLocation serializableLocation;
    private double speed;
    private int maxIterations;
    private boolean allowDiagonal;
    private SerializableConsumer<WalkingResult> completionCallback;
    private boolean withRotation;

    private transient State state;
    private transient PathTask activeTask;
    private transient Location targetLocation;
    private transient Path currentPath;
    private transient PathRequest request;
    private transient int generation;
    private transient int walkId;
    private transient boolean finished;
    private transient boolean targetDirty;
    private transient int ticksSinceRequest;
    private transient int calculationTicks;
    private transient int validityTicks;
    private transient int stuckSampleTicks;
    private transient int stuckStrikes;
    private transient int stuckRetries;
    private transient Location stuckAnchor;
    private transient volatile boolean unreachable;
    private transient long lastPathabilityCheckTime;
    private transient double entityHeight;
    private transient double entityWidth;

    /**
     * Creates a WalkToLocationGoal with full configuration options.
     *
     * @param builder the builder containing the configuration options
     */
    public WalkToLocationGoal(@NotNull Builder builder)
    {
        super(Priority.MEDIUM);
        this.targetLocation = builder.targetLocation.clone();
        this.serializableLocation = new Path.SerializablePath.SerializableLocation(targetLocation);
        this.speed = Math.clamp(builder.speed, 0.1, 1.0);
        this.maxIterations = builder.maxIterations;
        this.allowDiagonal = builder.allowDiagonal;
        this.completionCallback = builder.completionCallback;
        this.withRotation = builder.withRotation;
        this.pathfinder = builder.pathfinder;
    }

    /**
     * Creates a copy of this goal.
     *
     * @param goal the goal to copy
     */
    private WalkToLocationGoal(@NotNull WalkToLocationGoal goal)
    {
        super(goal);
        this.serializableLocation = goal.serializableLocation;
        this.speed = goal.speed;
        this.maxIterations = goal.maxIterations;
        this.allowDiagonal = goal.allowDiagonal;
        this.completionCallback = goal.completionCallback;
        this.withRotation = goal.withRotation;
    }

    /**
     * {@code PathTask} already moves the real location while walking and when it ends; only fall back to an explicit teleport if it is clearly out of sync
     * (avoids sending a redundant teleport packet to every viewer at the end of each walk).
     */
    private static void syncRealLocation(@NotNull NPC npc, @NotNull Location location)
    {
        Location current = npc.getLocation();
        if(current.getWorld() != location.getWorld() || current.distanceSquared(location) > 0.01)
            npc.changeRealLocation(location);
    }

    private static boolean isCancellation(@Nullable Throwable throwable)
    {
        for(Throwable t = throwable; t != null; t = t.getCause())
        {
            if(t instanceof CancellationException)
                return true;
            if(!(t instanceof CompletionException) && t.getCause() == null)
                return false;
        }
        return false;
    }

    /**
     * Gets the speed for this goal.
     *
     * @return the speed
     */
    public double getSpeed()
    {
        return speed;
    }

    /**
     * Sets the speed for this goal.
     *
     * @param speed the new speed
     */
    public void setSpeed(double speed)
    {
        this.speed = Math.clamp(speed, 0.1, 1.0);
    }

    /**
     * Gets the maximum number of iterations for pathfinding.
     *
     * @return the maximum number of iterations
     */
    public int getMaxIterations()
    {
        return maxIterations;
    }

    /**
     * Sets the maximum number of iterations for pathfinding.
     *
     * @param maxIterations the new maximum number of iterations
     */
    public void setMaxIterations(int maxIterations)
    {
        this.maxIterations = maxIterations;
    }

    /**
     * Gets whether diagonal movement is allowed.
     *
     * @return true if diagonal movement is allowed
     */
    public boolean isAllowDiagonal()
    {
        return allowDiagonal;
    }

    /**
     * Sets whether diagonal movement is allowed.
     *
     * @param allowDiagonal the new diagonal movement setting
     */
    public void setAllowDiagonal(boolean allowDiagonal)
    {
        this.allowDiagonal = allowDiagonal;
    }

    /**
     * Gets the completion callback for the walk to location goal.
     *
     * @return the completion callback
     */
    public @Nullable SerializableConsumer<WalkingResult> getCompletionCallback()
    {
        return completionCallback;
    }

    /**
     * Sets the completion callback for the walk to location goal.
     *
     * @param completionCallback the new completion callback
     */
    public void setCompletionCallback(@Nullable SerializableConsumer<WalkingResult> completionCallback)
    {
        this.completionCallback = completionCallback;
    }

    /**
     * Gets whether the NPC should rotate to face the target location.
     *
     * @return true if the NPC should rotate
     */
    public boolean isWithRotation()
    {
        return withRotation;
    }

    /**
     * Sets whether the NPC should rotate to face the target location.
     *
     * @param withRotation the new rotation setting
     */
    public void setWithRotation(boolean withRotation)
    {
        this.withRotation = withRotation;
    }

    /**
     * Gets the target location.
     *
     * @param fallbackWorld the world to use if the target location is not set
     * @return The target location
     */
    public @NotNull Location getTargetLocation(@NotNull World fallbackWorld)
    {
        if(targetLocation == null && serializableLocation != null)
            targetLocation = serializableLocation.toLocation(fallbackWorld);

        return targetLocation.clone();
    }

    /**
     * Sets the target location for this goal. The next pathability check is not rate limited any more (the old verdict belonged to the old target).
     *
     * @param targetLocation the new target location
     */
    public void setTargetLocation(@NotNull Location targetLocation)
    {
        this.targetLocation = targetLocation.clone();
        this.serializableLocation = new Path.SerializablePath.SerializableLocation(targetLocation);
        this.unreachable = false;
        this.lastPathabilityCheckTime = 0;
    }

    /**
     * Updates the target location of a running goal. The new path is requested within a few ticks (retargeting is coalesced so a target that changes every tick
     * causes at most one search per {@value #MIN_REPATH_INTERVAL_TICKS} ticks) and swapped into the current walk without stopping the NPC.
     *
     * @param newTarget the new target location
     * @param npc       the NPC (kept for API compatibility)
     */
    void updateTargetLocation(@NotNull Location newTarget, @NotNull NPC npc)
    {
        if(this.targetLocation != null && this.targetLocation.distanceSquared(newTarget) < 0.25)
            return;

        setTargetLocation(newTarget);
        targetDirty = true;
    }

    /**
     * Checks if the NPC is currently walking.
     *
     * @return true if the NPC is walking, false otherwise
     */
    public boolean isWalking()
    {
        return activeTask != null;
    }

    /**
     * @return true while the goal is calculating a path or walking; false once it has finished, failed or was stopped
     */
    boolean isActive()
    {
        return state() != State.IDLE;
    }

    /**
     * @return true if the last attempt found no path to the target (cleared as soon as the target changes)
     */
    boolean isUnreachable()
    {
        return unreachable;
    }

    /**
     * Squared horizontal distance between the current target and the given location (no allocation).
     *
     * @param other the location to compare with
     * @return the squared horizontal distance, or infinity if there is no target
     */
    double horizontalTargetDistanceSq(@NotNull Location other)
    {
        if(targetLocation == null)
            return Double.POSITIVE_INFINITY;

        double dx = targetLocation.getX() - other.getX();
        double dz = targetLocation.getZ() - other.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Absolute height difference between the current target and the given location (no allocation).
     *
     * @param other the location to compare with
     * @return the height difference, or infinity if there is no target
     */
    double verticalTargetDistance(@NotNull Location other)
    {
        return targetLocation == null ? Double.POSITIVE_INFINITY : Math.abs(targetLocation.getY() - other.getY());
    }

    @Override
    protected @Nullable Location getLocation()
    {
        return targetLocation;
    }

    /**
     * Checks if this goal can be used by the NPC.
     *
     * @param npc the NPC to check
     * @return true if the NPC is already walking to the target, or the target is valid, not reached and (according to a throttled, shared pathfinding check)
     * reachable
     */
    @Override
    public boolean canUse(@NotNull NPC npc)
    {
        if(state() != State.IDLE)
            return true;

        if(!super.canUse(npc))
            return false;

        if(!resolveTarget(npc))
            return false;

        if(targetLocation.getWorld() == null || !targetLocation.getWorld().equals(npc.getLocation().getWorld()))
            return false;

        if(npc.getLocation().distanceSquared(targetLocation) <= 1.0)
            return false;

        long now = System.currentTimeMillis();
        if(lastPathabilityCheckTime == 0 || now - lastPathabilityCheckTime > PATHABILITY_CHECK_INTERVAL_MS)
            checkPathability(npc, now);

        return !unreachable;
    }

    /**
     * Starts the walk to location goal, requesting the path.
     *
     * @param npc the NPC starting this goal
     */
    @Override
    public void start(@NotNull NPC npc)
    {
        if(state() != State.IDLE)
            return;

        if(!resolveTarget(npc))
            return;

        AABB boundingBox = ((Entity) npc.getEntity()).getBoundingBox();
        double scale = npc.getOption(NpcOption.SCALE);
        entityHeight = boundingBox.getYsize() * scale;
        entityWidth = boundingBox.getXsize() * scale;

        finished = false;
        targetDirty = false;
        stuckRetries = 0;
        resetStuckDetection();
        calculationTicks = 0;
        currentPath = null;

        state = State.CALCULATING;
        requestPath(npc, npc.getLocation(), true);
    }

    /**
     * Ticks the goal: applies coalesced retargeting, validates the upcoming path, watches for being stuck and guards against searches that never answer.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        ticksSinceRequest++;

        switch(state())
        {
            case WALKING -> tickWalking(npc);
            case CALCULATING ->
            {
                if(++calculationTicks > CALCULATION_TIMEOUT_TICKS)
                {
                    fail(npc);
                    return;
                }

                if(targetDirty && ticksSinceRequest >= MIN_REPATH_INTERVAL_TICKS)
                {
                    targetDirty = false;
                    requestPath(npc, npc.getLocation(), false);
                }
            }
            case IDLE ->
            {
            }
        }
    }

    /**
     * Stops the goal: aborts any running search, cancels the walk and invalidates every callback that is still on its way.
     *
     * @param npc the NPC stopping this goal
     */
    @Override
    public void stop(@NotNull NPC npc)
    {
        generation++;
        cancelRequest();
        cancelWalking(npc);

        state = State.IDLE;
        currentPath = null;
        targetDirty = false;
        resetStuckDetection();
    }

    @Override
    public boolean canContinue(@NotNull NPC npc)
    {
        if(!resolveTarget(npc))
            return false;

        State current = state();
        return (current == State.WALKING || current == State.CALCULATING) && super.canContinue(npc);
    }

    @Override
    protected boolean canBeRemovedNow(@NotNull NPC npc)
    {
        if(super.canBeRemovedNow(npc))
            return true;

        if(isWalking())
        {
            if(isAirBelow(npc))
                return false;
        }
        return true;
    }

    @Override
    public @NotNull Goal copy()
    {
        return new WalkToLocationGoal(this);
    }

    private void tickWalking(@NotNull NPC npc)
    {
        if(targetDirty && ticksSinceRequest >= MIN_REPATH_INTERVAL_TICKS)
        {
            targetDirty = false;
            requestPath(npc, predictStart(npc), false);
        }

        if(++validityTicks >= PATH_CHECK_INTERVAL_TICKS)
        {
            validityTicks = 0;
            checkPathAhead(npc);
        }

        if(++stuckSampleTicks >= STUCK_SAMPLE_INTERVAL_TICKS)
        {
            stuckSampleTicks = 0;
            checkStuck(npc);
        }
    }

    /**
     * Re-validates the next few waypoints against the current world. If one is blocked, a replacement path is requested; if the very next waypoint is blocked
     * the NPC stops first so it does not walk into the obstacle while the new path is being calculated.
     */
    private void checkPathAhead(@NotNull NPC npc)
    {
        if(currentPath == null || activeTask == null || ticksSinceRequest < MIN_REPATH_INTERVAL_TICKS)
            return;

        World world = npc.getLocation().getWorld();
        if(world == null)
            return;

        int index = activeTask.getIndex();
        int blocked = PathValidator.firstBlocked(world, currentPath.asLocations(), currentPath.getMoves(), index, PATH_CHECK_POINTS_AHEAD, PATH_CHECK_HORIZON,
                entityHeight, entityWidth, npc.getNavCapabilities());
        if(blocked < 0)
            return;

        if(blocked <= index + 1)
        {
            Location here = activeTask.getCurrentLocation();
            cancelWalking(npc);
            state = State.CALCULATING;
            calculationTicks = 0;
            requestPath(npc, here, false);
        }
        else
            requestPath(npc, predictStart(npc), false);
    }

    private void checkStuck(@NotNull NPC npc)
    {
        Location current = npc.getLocation();
        if(stuckAnchor == null || stuckAnchor.getWorld() == null || !stuckAnchor.getWorld().equals(current.getWorld()))
        {
            stuckAnchor = current.clone();
            stuckStrikes = 0;
            return;
        }

        if(current.distanceSquared(stuckAnchor) >= STUCK_MIN_MOVEMENT_SQ)
        {
            stuckAnchor = current.clone();
            stuckStrikes = 0;
            return;
        }

        if(++stuckStrikes < STUCK_STRIKES)
            return;

        stuckStrikes = 0;
        if(++stuckRetries > MAX_STUCK_RETRIES)
        {
            fail(npc);
            return;
        }

        if(ticksSinceRequest >= MIN_REPATH_INTERVAL_TICKS)
            requestPath(npc, current, false);
    }

    private void startWalking(@NotNull NPC npc)
    {
        if(currentPath == null)
        {
            fail(npc);
            return;
        }

        int id = ++walkId;

        var result = npc.walkTo(currentPath, speed, true, walkingResult -> npc.getGoalExecutor().execute(() -> onWalkEnd(npc, walkingResult, id)),
                withRotation);
        if(result == null)
        {
            fail(npc);
            return;
        }

        activeTask = result.pathTask();
        state = State.WALKING;
        validityTicks = 0;
        resetStuckDetection();
    }

    private void onWalkEnd(@NotNull NPC npc, @NotNull WalkingResult result, int id)
    {
        if(id != walkId)
            return;

        PathTask task = activeTask;
        activeTask = null;

        if(result == WalkingResult.SUCCESS && task != null)
            syncRealLocation(npc, task.getCurrentLocation());

        deliver(result);
        stop(npc);
    }

    private void cancelWalking(@NotNull NPC npc)
    {
        if(activeTask == null)
            return;

        PathTask task = activeTask;
        activeTask = null;
        walkId++;

        syncRealLocation(npc, task.getCurrentLocation());
        task.cancel();
    }

    private void fail(@NotNull NPC npc)
    {
        unreachable = true;
        lastPathabilityCheckTime = System.currentTimeMillis();
        generation++;
        cancelRequest();
        cancelWalking(npc);
        state = State.IDLE;
        currentPath = null;
        deliver(WalkingResult.CANCELLED);
    }

    private void deliver(@NotNull WalkingResult result)
    {
        if(finished)
            return;

        finished = true;
        if(completionCallback != null)
            completionCallback.accept(result);
    }

    /**
     * Where a replacement path should start: a few ticks ahead of the NPC on its current path, so it does not begin behind the NPC by the time the search
     * finishes.
     */
    private @NotNull Location predictStart(@NotNull NPC npc)
    {
        if(activeTask == null || currentPath == null)
            return npc.getLocation();

        return PathProjection.advance(activeTask.getCurrentLocation(), currentPath.asLocations(), currentPath.getMoves(), activeTask.getIndex(),
                speed * PREDICT_TICKS);
    }

    /**
     * Requests a path and applies it when it arrives: swapped into the running walk, or used to start walking.
     *
     * @param reuse whether a recent search for the same start and target may be reused. Only the very first request does that (it picks up the pathability
     *              check); a re-path always searches afresh, because it is requested precisely because the previous result did not work out.
     */
    private void requestPath(@NotNull NPC npc, @NotNull Location from, boolean reuse)
    {
        if(targetLocation == null || targetLocation.getWorld() == null || from.getWorld() == null || !targetLocation.getWorld().equals(from.getWorld()))
        {
            fail(npc);
            return;
        }

        ticksSinceRequest = 0;
        int myGeneration = ++generation;
        CompletableFuture<Path> future = searchFor(npc, from, targetLocation, reuse);

        if(future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled() && Bukkit.isOwnedByCurrentRegion(npc.getLocation()))
        {
            onPath(npc, future.getNow(null), myGeneration);
            return;
        }

        future.thenAcceptAsync(path -> onPath(npc, path, myGeneration), npc.getGoalExecutor())
                .exceptionally(throwable ->
                {
                    if(!isCancellation(throwable))
                        npc.getGoalExecutor().execute(() -> onPathFailed(npc, myGeneration));
                    return null;
                });
    }

    private void onPath(@NotNull NPC npc, @Nullable Path path, int expectedGeneration)
    {
        if(expectedGeneration != generation || state() == State.IDLE)
            return;

        if(path == null)
        {
            fail(npc);
            return;
        }

        currentPath = path;
        stuckStrikes = 0;

        if(state() == State.WALKING && activeTask != null)
        {
            activeTask.updatePath(path);
            validityTicks = 0;
        }
        else
            startWalking(npc);
    }

    private void onPathFailed(@NotNull NPC npc, int expectedGeneration)
    {
        if(expectedGeneration != generation || state() == State.IDLE)
            return;

        fail(npc);
    }

    /**
     * Starts a search, or returns a recent one for (almost) the same start and target. The pathability check in {@link #canUse} and the real request in
     * {@link #start} therefore share one search instead of running two.
     */
    private @NotNull CompletableFuture<Path> searchFor(@NotNull NPC npc, @NotNull Location from, @NotNull Location to, boolean reuse)
    {
        long now = System.currentTimeMillis();
        PathRequest existing = request;
        if(reuse && existing != null && now - existing.createdAt < REQUEST_REUSE_MS && !existing.future.isCancelled() &&
                !existing.future.isCompletedExceptionally() &&
                existing.target.getWorld() == to.getWorld() && existing.target.distanceSquared(to) < REQUEST_REUSE_TARGET_SQ &&
                existing.from.getWorld() == from.getWorld() && existing.from.distanceSquared(from) < REQUEST_REUSE_START_SQ)
            return existing.future;

        cancelRequest();

        CompletableFuture<Path> future = npc.findPathAsync(null, List.of(from.clone(), to.clone()), maxIterations, allowDiagonal, npc.getNavCapabilities(),
                null);
        Tasks.trackFuture(future);
        request = new PathRequest(future, to.clone(), from.clone(), now);
        return future;
    }

    /**
     * Aborts a search that is still running (the search itself polls for cancellation, so this really stops the work).
     */
    private void cancelRequest()
    {
        PathRequest existing = request;
        request = null;
        if(existing != null && !existing.future.isDone())
            existing.future.cancel(true);
    }

    private void checkPathability(@NotNull NPC npc, long now)
    {
        lastPathabilityCheckTime = now;
        CompletableFuture<Path> future = searchFor(npc, npc.getLocation(), targetLocation, true);

        future.thenAcceptAsync(path -> unreachable = path == null, npc.getGoalExecutor())
                .exceptionally(throwable ->
                {
                    if(!isCancellation(throwable))
                        unreachable = true;
                    return null;
                });
    }

    private @NotNull State state()
    {
        return state == null ? State.IDLE : state;
    }

    private boolean resolveTarget(@NotNull NPC npc)
    {
        if(targetLocation == null && serializableLocation != null)
            targetLocation = serializableLocation.toLocation(npc.getLocation().getWorld());

        return targetLocation != null;
    }

    private void resetStuckDetection()
    {
        stuckAnchor = null;
        stuckStrikes = 0;
        stuckSampleTicks = 0;
    }

    /**
     * Serializes the goal for storage.
     *
     * @param out the output stream
     * @throws IOException if serialization fails
     */
    @Serial
    private void writeObject(@NotNull ObjectOutputStream out) throws IOException
    {
        out.defaultWriteObject();
    }

    /**
     * Deserializes the goal from storage.
     *
     * @param in the input stream
     * @throws IOException            if deserialization fails
     * @throws ClassNotFoundException if class is not found
     */
    @Serial
    private void readObject(@NotNull ObjectInputStream in) throws IOException, ClassNotFoundException
    {
        in.defaultReadObject();
        this.targetLocation = serializableLocation.toLocation(null);
        if(targetLocation.getWorld() == null)
            targetLocation = null;
    }

    private enum State
    {
        IDLE,
        CALCULATING,
        WALKING
    }

    private record PathRequest(CompletableFuture<Path> future, Location target, Location from, long createdAt)
    {
    }

    // --- Builder Class ---

    /**
     * Builder class for creating WalkToLocationGoal instances with a fluent API.
     */
    public static class Builder
    {
        private final Location targetLocation;
        private double speed = DEFAULT_SPEED;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private boolean allowDiagonal = true;
        private SerializableConsumer<WalkingResult> completionCallback;
        private boolean withRotation = true;
        private AbstractPathfinder.PathfinderFactory<? extends AbstractPathfinder> pathfinder = NpcApi.config.pathfinderFactory();

        /**
         * Creates a new Builder with the required target location.
         *
         * @param targetLocation The location to walk to
         */
        public Builder(@NotNull Location targetLocation)
        {
            this.targetLocation = targetLocation;
        }

        /**
         * Sets the walking speed.
         *
         * @param speed The walking speed (0.1 to 1.0)
         * @return this builder for chaining
         */
        public Builder speed(double speed)
        {
            this.speed = Math.clamp(speed, 0.1, 1.0);
            return this;
        }

        /**
         * Sets the maximum number of pathfinding iterations.
         *
         * @param maxIterations The maximum number of iterations
         * @return this builder for chaining
         */
        public Builder maxIterations(int maxIterations)
        {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets whether diagonal movement is allowed.
         *
         * @param allowDiagonal true to allow diagonal movement
         * @return this builder for chaining
         */
        public Builder allowDiagonal(boolean allowDiagonal)
        {
            this.allowDiagonal = allowDiagonal;
            return this;
        }

        /**
         * Sets the completion callback.
         *
         * @param completionCallback Callback called when walking completes
         * @return this builder for chaining
         */
        public Builder completionCallback(@NotNull SerializableConsumer<WalkingResult> completionCallback)
        {
            this.completionCallback = completionCallback;
            return this;
        }

        /**
         * Sets whether rotation packets should be sent.
         *
         * @param withRotation If true, includes rotation packets in the movement; otherwise only position packets are sent.
         * @return this builder for chaining
         */
        public Builder withRotation(boolean withRotation)
        {
            this.withRotation = withRotation;
            return this;
        }

        /**
         * Sets the pathfinder factory.
         *
         * @param pathfinder The pathfinder factory
         * @return this builder for chaining
         */
        public Builder pathfinder(@NotNull AbstractPathfinder.PathfinderFactory<? extends AbstractPathfinder> pathfinder)
        {
            this.pathfinder = pathfinder;
            return this;
        }

        /**
         * Builds the WalkToLocationGoal instance.
         *
         * @return A new WalkToLocationGoal instance
         */
        public WalkToLocationGoal build()
        {
            return new WalkToLocationGoal(this);
        }
    }
}