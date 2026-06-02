package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.utils.SerializableConsumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * A goal that makes the NPC walk along a predefined path of waypoints.
 * The NPC will walk the entire path without recalculating, using the pre-calculated path.
 */
public class WalkPathGoal extends Goal
{
    @Serial
    private static final long serialVersionUID = 1L;

    private static final double DEFAULT_SPEED = 0.25;
    private static final double START_LOCATION_THRESHOLD = 50.0 * 50.0;

    private Path.SerializablePath serializablePath;
    private double speed;
    private SerializableConsumer<WalkingResult> completionCallback;
    private boolean withRotation;

    private transient Path path;
    private transient boolean isWalking;
    private transient WalkToLocationGoal walkToStartGoal;
    private transient boolean walkingToStart;

    /**
     * Creates a WalkPathGoal with full configuration options.
     *
     * @param builder the builder containing the configuration options
     */
    public WalkPathGoal(@NotNull Builder builder)
    {
        super(Priority.MEDIUM);
        this.path = builder.path;
        this.serializablePath = path != null ? path.toSerializablePath() : null;
        this.speed = Math.max(0.1, Math.min(1.0, builder.speed));
        this.completionCallback = builder.completionCallback;
        this.withRotation = builder.withRotation;
    }

    /**
     * Creates a copy of this goal.
     *
     * @param goal the goal to copy
     */
    private WalkPathGoal(@NotNull WalkPathGoal goal)
    {
        super(goal.getPriority());
        this.serializablePath = goal.serializablePath;
        this.speed = goal.speed;
        this.completionCallback = goal.completionCallback;
        this.withRotation = goal.withRotation;
    }

    /**
     * Gets the path for this goal.
     *
     * @return the path
     */
    public @Nullable Path getPath()
    {
        return path;
    }

    /**
     * Sets the path for this goal.
     *
     * @param path the new path
     */
    public void setPath(@NotNull Path path)
    {
        this.path = path;
        this.serializablePath = path.toSerializablePath();
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
     * Gets the completion callback for the walk path goal.
     *
     * @return the completion callback
     */
    public @Nullable SerializableConsumer<WalkingResult> getCompletionCallback()
    {
        return completionCallback;
    }

    /**
     * Sets the completion callback for the walk path goal.
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
     * @return true if the path is valid, start location is close enough, and not already walking
     */
    @Override
    public boolean canUse(@NotNull NPC npc)
    {
        if(isWalking)
            return true;

        if(!super.canUse(npc))
            return false;

        if(path == null)
            return false;

        List<Location> waypoints = path.asLocations();
        if(waypoints == null || waypoints.isEmpty())
            return false;

        Location startLocation = waypoints.getFirst();
        if(!startLocation.getWorld().equals(npc.getLocation().getWorld()))
            return false;

        double distance = npc.getLocation().distanceSquared(startLocation);
        return distance <= START_LOCATION_THRESHOLD;
    }

    /**
     * Starts the walk path goal, first walking to the start location if needed, then walking the entire path.
     *
     * @param npc the NPC starting this goal
     */
    @Override
    public void start(@NotNull NPC npc)
    {
        if(path == null)
            return;

        List<Location> waypoints = path.asLocations();
        if(waypoints == null || waypoints.isEmpty())
            return;

        Location startLocation = waypoints.getFirst();
        double distance = npc.getLocation().distance(startLocation);

        isWalking = true;

        if(distance > START_LOCATION_THRESHOLD)
        {
            walkingToStart = true;
            walkToStartGoal = new WalkToLocationGoal.Builder(startLocation)
                    .speed(speed)
                    .withRotation(withRotation)
                    .completionCallback(result ->
                    {
                        walkToStartGoal = null;
                        walkingToStart = false;

                        if(result == WalkingResult.SUCCESS)
                            walkPath(npc);
                        else
                        {
                            isWalking = false;
                            if(completionCallback != null)
                                completionCallback.accept(result);
                        }
                    })
                    .build();
            walkToStartGoal.start(npc);
        }
        else
            walkPath(npc);
    }

    /**
     * Walks the actual path after reaching the start location.
     *
     * @param npc the NPC to walk
     */
    private void walkPath(@NotNull NPC npc)
    {
        if(path == null)
            return;

        List<Location> waypoints = path.asLocations();
        if(waypoints == null || waypoints.isEmpty())
            return;

        List<Player> viewers = npc.getViewers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();

        Location finalLocation = waypoints.getLast();

        npc.walkTo(path, speed, true, result ->
        {
            isWalking = false;
            if(completionCallback != null)
                completionCallback.accept(result);
            if(result == WalkingResult.SUCCESS)
                npc.changeRealLocation(finalLocation);
        }, withRotation, viewers);
    }

    /**
     * Ticks the walk path goal, handling both walking to start and walking the path.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        if(walkingToStart && walkToStartGoal != null)
        {
            walkToStartGoal.tick(npc);
        }
    }

    /**
     * Stops the walk path goal and cancels current walking.
     *
     * @param npc the NPC stopping this goal
     */
    @Override
    public void stop(@NotNull NPC npc)
    {
        if(!isWalking)
            return;

        if(walkToStartGoal != null)
        {
            walkToStartGoal.stop(npc);
            walkToStartGoal = null;
        }

        List<Player> viewers = npc.getViewers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();

        for(Player viewer : viewers)
        {
            if(npc.isWalking(viewer))
                npc.cancelWalking(viewer);
        }

        isWalking = false;
        walkingToStart = false;
    }

    /**
     * Checks if this goal should continue running.
     *
     * @param npc the NPC to check
     * @return true if still walking and has waypoints remaining
     */
    @Override
    public boolean canContinue(@NotNull NPC npc)
    {
        return isWalking && super.canContinue(npc);
    }

    @Override
    protected boolean canBeRemovedNow(@NotNull NPC npc)
    {
        if(super.canBeRemovedNow(npc))
            return true;

        if(isWalking)
        {
            Location npcLoc = npc.getLocation();
            Location below = npcLoc.clone().subtract(0, 0.1, 0);

            if(below.getBlock().getType().isAir())
                return false;
        }
        return true;
    }

    @Override
    public @NotNull Goal copy()
    {
        return new WalkPathGoal(this);
    }

    /**
     * Builder class for creating WalkPathGoal instances with a fluent API.
     */
    public static class Builder
    {
        private final Path path;
        private double speed = DEFAULT_SPEED;
        private SerializableConsumer<WalkingResult> completionCallback;
        private boolean withRotation = true;

        /**
         * Creates a new Builder with the required path.
         *
         * @param path The path to walk along
         */
        public Builder(@NotNull Path path)
        {
            this.path = path;
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
         * Sets the completion callback.
         *
         * @param completionCallback Callback called when path walking completes
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
         * Builds the WalkPathGoal instance.
         *
         * @return A new WalkPathGoal instance
         */
        public WalkPathGoal build()
        {
            return new WalkPathGoal(this);
        }
    }
}
