package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.AStarPathfinder;
import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.pathfinding.PathfindingUtils;
import de.eisi05.npc.api.scheduler.Tasks;
import de.eisi05.npc.api.utils.SerializableConsumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A goal that makes the NPC walk to a specific location using pathfinding.
 */
public class WalkToLocationGoal extends Goal
{
    static final int RECALCULATION_COOLDOWN = 20;

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_MAX_ITERATIONS = 5000;
    public static final double DEFAULT_SPEED = 0.25;
    private static final int PATH_CHECK_AHEAD = 5;
    private static final long PATHABILITY_CHECK_INTERVAL_MS = 5000;

    private Path.SerializablePath.SerializableLocation serializableLocation;
    private double speed;
    private int maxIterations;
    private boolean allowDiagonal;
    private SerializableConsumer<WalkingResult> completionCallback;
    private boolean withRotation;

    private transient Location targetLocation;
    private transient CompletableFuture<Path> pathfindingFuture;
    private transient Path currentPath;
    private transient boolean isWalking;
    private transient int pathRecalculationCooldown = 0;
    private transient boolean pathable = true;
    private transient long lastPathabilityCheckTime = 0;

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
        this.speed = Math.max(0.1, Math.min(1.0, builder.speed));
        this.maxIterations = builder.maxIterations;
        this.allowDiagonal = builder.allowDiagonal;
        this.completionCallback = builder.completionCallback;
        this.withRotation = builder.withRotation;
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
        this.speed = Math.max(0.1, Math.min(1.0, speed));
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
     * Checks if this goal can be used by the NPC.
     *
     * @param npc the NPC to check
     * @return true if the NPC is not already walking and target is valid
     */
    @Override
    public boolean canUse(@NotNull NPC npc)
    {
        if(isWalking)
            return true;

        if(targetLocation == null || !targetLocation.getWorld().equals(npc.getLocation().getWorld()))
            return false;

        if(npc.getLocation().distance(targetLocation) <= 1.0)
            return false;

        if(lastPathabilityCheckTime == 0 || System.currentTimeMillis() - lastPathabilityCheckTime > PATHABILITY_CHECK_INTERVAL_MS)
            checkPathabilityAsync(npc);

        if(!pathable)
            return false;

        return true;
    }

    /**
     * Starts the walk to location goal, calculating the path.
     *
     * @param npc the NPC starting this goal
     */
    @Override
    public void start(@NotNull NPC npc)
    {
        if(targetLocation == null)
            return;

        calculatePath(npc);
    }

    /**
     * Ticks the walk to location goal, checking for path validity and recalculating if needed.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        if(pathRecalculationCooldown > 0)
        {
            pathRecalculationCooldown--;
            return;
        }

        if(isPathInvalid(npc))
        {
            calculatePath(npc);
            pathRecalculationCooldown = RECALCULATION_COOLDOWN;
        }
    }

    /**
     * Stops the walk to location goal and cancels pathfinding.
     *
     * @param npc the NPC stopping this goal
     */
    @Override
    public void stop(@NotNull NPC npc)
    {
        if(pathfindingFuture != null)
        {
            pathfindingFuture.cancel(true);
            pathfindingFuture = null;
        }

        cancelWalking(npc);
        currentPath = null;
        pathable = true;
        lastPathabilityCheckTime = 0;
    }

    /**
     * Checks if this goal should continue running.
     *
     * @param npc the NPC to check
     * @return true if the NPC is still walking and not at target
     */
    @Override
    public boolean canContinue(@NotNull NPC npc)
    {
        return isWalking && currentPath != null && targetLocation != null && npc.getLocation().distance(targetLocation) > 1.0;
    }

    /**
     * Gets the target location.
     *
     * @return The target location
     */
    public @NotNull Location getTargetLocation()
    {
        return targetLocation.clone();
    }

    /**
     * Sets the target location for this goal.
     *
     * @param targetLocation the new target location
     */
    public void setTargetLocation(@NotNull Location targetLocation)
    {
        this.targetLocation = targetLocation;
        this.serializableLocation = new Path.SerializablePath.SerializableLocation(targetLocation);
    }

    /**
     * Asynchronously checks if a path exists to the target location. Updates the pathable cache based on the result.
     *
     * @param npc the NPC to check pathability for
     */
    private void checkPathabilityAsync(@NotNull NPC npc)
    {
        lastPathabilityCheckTime = System.currentTimeMillis();
        Location start = npc.getLocation();
        Location end = targetLocation;

        if(end == null || !end.getWorld().equals(start.getWorld()))
        {
            pathable = false;
            return;
        }

        CompletableFuture<Path> future = PathfindingUtils.findPathAsync(List.of(start, end), maxIterations, allowDiagonal, null);
        Tasks.trackFuture(future);
        future.thenAcceptAsync(path -> pathable = path != null, task -> Bukkit.getScheduler().runTask(NpcApi.plugin, task))
                .exceptionally(e ->
                {
                    pathable = false;
                    return null;
                });
    }

    /**
     * Calculates the path to the target location asynchronously.
     *
     * @param npc the NPC to calculate path for
     */
    private void calculatePath(@NotNull NPC npc)
    {
        Location start = npc.getLocation();
        Location end = targetLocation;

        if(!end.getWorld().equals(start.getWorld()))
        {
            if(completionCallback != null)
                completionCallback.accept(WalkingResult.CANCELLED);
            cancelWalking(npc);
            return;
        }

        isWalking = true;

        pathfindingFuture = PathfindingUtils.findPathAsync(List.of(start, end), maxIterations, allowDiagonal, null);
        Tasks.trackFuture(pathfindingFuture);
        pathfindingFuture.thenAcceptAsync(path ->
                {
                    isWalking = false;
                    if(path != null)
                    {
                        currentPath = path;
                        startWalking(npc);
                    }
                    else
                    {
                        cancelWalking(npc);

                        if(completionCallback != null)
                            completionCallback.accept(WalkingResult.CANCELLED);
                    }
                }, task -> Bukkit.getScheduler().runTask(NpcApi.plugin, task))
                .exceptionally(e ->
                {
                    Bukkit.getScheduler().runTask(NpcApi.plugin, () ->
                    {
                        if(completionCallback != null)
                            completionCallback.accept(WalkingResult.CANCELLED);
                        cancelWalking(npc);
                    });
                    return null;
                });
    }

    /**
     * Starts the actual walking animation along the calculated path.
     *
     * @param npc the NPC to start walking
     */
    private void startWalking(@NotNull NPC npc)
    {
        List<Player> viewers = npc.getViewers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();

        npc.walkTo(currentPath, speed, true, result ->
        {
            isWalking = false;
            if(completionCallback != null)
                completionCallback.accept(result);
            if(result == WalkingResult.SUCCESS)
                npc.changeRealLocation(targetLocation);
        }, withRotation, viewers);

        isWalking = true;
    }

    /**
     * Checks if the current path is invalid due to block changes. Uses similar validation logic to AStarPathfinder for consistency.
     *
     * @param npc the NPC to check path for
     * @return true if the path is invalid and should be recalculated
     */
    private boolean isPathInvalid(@NotNull NPC npc)
    {
        if(currentPath == null || currentPath.getWaypoints().isEmpty())
            return true;

        Location npcLoc = npc.getLocation();
        if(npcLoc.getWorld() == null)
            return true;

        int checkAhead = Math.min(PATH_CHECK_AHEAD, currentPath.getWaypoints().size());
        for(int i = 0; i < checkAhead; i++)
        {
            Location waypoint = currentPath.getWaypoints().get(i);
            if(!waypoint.getWorld().equals(npcLoc.getWorld()))
                return true;

            Block floor = waypoint.getBlock();
            Block spaceFeet = waypoint.getBlock().getRelative(BlockFace.UP);
            Block spaceHead = waypoint.getBlock().getRelative(BlockFace.UP).getRelative(BlockFace.UP);

            if(!AStarPathfinder.isSafeFloor(floor))
                return true;

            if(AStarPathfinder.isSolid(spaceFeet) || AStarPathfinder.isSolid(spaceHead))
                return true;
        }

        return false;
    }

    /**
     * Cancels the NPC's walking task for all viewers.
     *
     * @param npc the NPC to stop walking
     */
    private void cancelWalking(@NotNull NPC npc)
    {
        isWalking = false;

        List<Player> viewers = npc.getViewers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();

        for(Player viewer : viewers)
        {
            if(npc.isWalking(viewer))
                npc.cancelWalking(viewer);
        }
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
        this.targetLocation = serializableLocation.toLocation();
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
            this.speed = Math.max(0.1, Math.min(1.0, speed));
            return this;
        }

        /**
         * Sets the maximum iterations for pathfinding.
         *
         * @param maxIterations Maximum iterations for pathfinding
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
         * @param allowDiagonal Whether diagonal movement is allowed
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
