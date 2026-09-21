package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.AbstractPathfinder;
import de.eisi05.npc.api.pathfinding.Path;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A goal that makes the NPC wander randomly to nearby locations. The NPC will pick a random location within a specified radius and walk to it.
 */
public class WanderGoal extends Goal
{
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_RADIUS = 10;
    public static final int DEFAULT_MIN_DELAY = 40; // 2 seconds
    public static final int DEFAULT_MAX_DELAY = 140; // 7 seconds
    private static final int[] LOCAL_Y_OFFSETS = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5};

    private int radius;
    private int minDelay;
    private int maxDelay;
    private double speed;
    private Path.SerializablePath.SerializableLocation centerLocation;

    private transient WalkToLocationGoal currentWalkGoal;
    private transient int delayTicks;
    private transient Location targetLocation;

    /**
     * Creates a WanderGoal with default settings.
     */
    public WanderGoal()
    {
        this(DEFAULT_RADIUS, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY, WalkToLocationGoal.DEFAULT_SPEED, null);
    }

    /**
     * Creates a WanderGoal with custom radius and default delay.
     *
     * @param radius         The maximum radius to wander (in blocks)
     * @param centerLocation The center location for the wander goal or null to use the NPC's location
     */
    public WanderGoal(int radius, @Nullable Location centerLocation)
    {
        this(radius, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY, WalkToLocationGoal.DEFAULT_SPEED, centerLocation);
    }

    /**
     * Creates a WanderGoal with full configuration.
     *
     * @param radius         The maximum radius to wander (in blocks)
     * @param minDelay       Minimum delay between wander actions (in ticks)
     * @param maxDelay       Maximum delay between wander actions (in ticks)
     * @param speed          The walking speed
     * @param centerLocation The center location for the wander goal or null to use the NPC's location
     */
    public WanderGoal(int radius, int minDelay, int maxDelay, double speed, @Nullable Location centerLocation)
    {
        super(Priority.MEDIUM);
        this.radius = radius;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.speed = Math.clamp(speed, 0.1, 1.0);
        this.centerLocation = centerLocation == null ? null : new Path.SerializablePath.SerializableLocation(centerLocation);
    }

    /**
     * Creates a copy of this goal.
     *
     * @param goal the goal to copy
     */
    private WanderGoal(@NotNull WanderGoal goal)
    {
        super(goal.getPriority());
        this.radius = goal.radius;
        this.minDelay = goal.minDelay;
        this.maxDelay = goal.maxDelay;
        this.speed = goal.speed;
    }

    /**
     * Gets the radius for this goal.
     *
     * @return the radius
     */
    public int getRadius()
    {
        return radius;
    }

    /**
     * Sets the radius for this goal.
     *
     * @param radius the new radius
     */
    public void setRadius(int radius)
    {
        this.radius = radius;
    }

    /**
     * Gets the maximum delay between wander actions.
     *
     * @return the maximum delay
     */
    public int getMaxDelay()
    {
        return maxDelay;
    }

    /**
     * Sets the maximum delay between wander actions.
     *
     * @param maxDelay the new maximum delay
     */
    public void setMaxDelay(int maxDelay)
    {
        this.maxDelay = maxDelay;
    }

    /**
     * Gets the minimum delay between wander actions.
     *
     * @return the minimum delay
     */
    public int getMinDelay()
    {
        return minDelay;
    }

    /**
     * Sets the minimum delay between wander actions.
     *
     * @param minDelay the new minimum delay
     */
    public void setMinDelay(int minDelay)
    {
        this.minDelay = minDelay;
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
     * Gets the center location for this goal.
     *
     * @param fallbackWorld the world to use if the center location is null
     * @return the center location
     */
    public @Nullable Location getCenterLocation(@Nullable World fallbackWorld)
    {
        return centerLocation == null ? null : centerLocation.toLocation(fallbackWorld);
    }

    /**
     * Sets the center location for this goal.
     *
     * @param location the new center location or null to use the NPC's location
     */
    public void setCenterLocation(@Nullable Location location)
    {
        this.centerLocation = location == null ? null : new Path.SerializablePath.SerializableLocation(location);
    }

    /**
     * Checks if this goal can be used by the NPC.
     *
     * @param npc the NPC to check
     * @return true always (this goal can always be used)
     */
    @Override
    public boolean canUse(@NotNull NPC npc)
    {
        return radius >= 0 && minDelay >= 0 && maxDelay >= 0 && speed >= 0 && super.canUse(npc);
    }

    /**
     * Starts the wander goal by picking a new target.
     *
     * @param npc the NPC starting this goal
     */
    @Override
    public void start(@NotNull NPC npc)
    {
        pickNewTarget(npc);
    }

    /**
     * Ticks the wander goal, handling movement and delays.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        if(currentWalkGoal != null)
        {
            currentWalkGoal.tick(npc);
            return;
        }

        if(delayTicks > 0)
        {
            delayTicks--;
            return;
        }

        pickNewTarget(npc);
    }

    /**
     * Stops the wander goal and cleans up state.
     *
     * @param npc the NPC stopping this goal
     */
    @Override
    public void stop(@NotNull NPC npc)
    {
        if(currentWalkGoal != null)
        {
            currentWalkGoal.stop(npc);
            currentWalkGoal = null;
        }
        delayTicks = 0;
        targetLocation = null;
    }

    /**
     * Checks if this goal should continue running.
     *
     * @param npc the NPC to check
     * @return true if a target location is set
     */
    @Override
    public boolean canContinue(@NotNull NPC npc)
    {
        return targetLocation != null && super.canContinue(npc);
    }

    /**
     * Checks if this goal can be interrupted by a new goal selection.
     *
     * @param npc the NPC to check
     * @return true if the NPC is currently waiting (delayTicks == 1)
     */
    @Override
    public boolean canBeInterrupted(@NotNull NPC npc)
    {
        return delayTicks == 1;
    }

    @Override
    public @NotNull Goal copy()
    {
        return new WanderGoal(this);
    }

    /**
     * Picks a new random target location near the NPC.
     */
    private void pickNewTarget(@NotNull NPC npc)
    {
        Location currentLoc = centerLocation == null ? npc.getLocation() : centerLocation.toLocation(npc.getLocation().getWorld());
        World world = currentLoc.getWorld();

        if(world == null)
            return;

        int attempts = 0;
        while(attempts < 10)
        {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double distance = ThreadLocalRandom.current().nextDouble() * radius;

            double x = currentLoc.getX() + Math.cos(angle) * distance;
            double z = currentLoc.getZ() + Math.sin(angle) * distance;

            Location safeSpot = findLocalSafeTarget(world, x, currentLoc.getY(), z);
            if(safeSpot != null)
            {
                targetLocation = safeSpot;
                targetLocation.setYaw(calculateYaw(currentLoc, targetLocation));

                currentWalkGoal = new WalkToLocationGoal.Builder(targetLocation)
                        .speed(speed)
                        .completionCallback(walkingResult ->
                        {
                            currentWalkGoal = null;
                            delayTicks = minDelay + ThreadLocalRandom.current().nextInt(Math.max(1, maxDelay - minDelay));
                        })
                        .build();

                currentWalkGoal.start(npc);
                return;
            }

            attempts++;
        }

        delayTicks = maxDelay;
    }

    /**
     * Finds a safe location to move to by checking the blocks around the given location.
     *
     * @param world the world of the location
     * @param x the x coordinate of the location
     * @param startY the starting y value of the location
     * @param z the z coordinate of the location
     * @return the safe location or null if no safe location is found
     */
    private @Nullable Location findLocalSafeTarget(@NotNull World world, double x, double startY, double z)
    {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int baseY = (int) Math.floor(startY);

        for(int dy : LOCAL_Y_OFFSETS)
        {
            int checkY = baseY + dy;
            if(checkY < world.getMinHeight() + 1 || checkY >= world.getMaxHeight() - 2)
                continue;

            Block floor = world.getBlockAt(blockX, checkY - 1, blockZ);
            Block feet = world.getBlockAt(blockX, checkY, blockZ);
            Block head = world.getBlockAt(blockX, checkY + 1, blockZ);

            if(AbstractPathfinder.isSafeFloor(floor) && feet.isPassable() && head.isPassable())
            {
                double exactY = AbstractPathfinder.getFloorSurfaceY(floor);
                return new Location(world, x, exactY, z);
            }
        }

        return null;
    }

    private float calculateYaw(@NotNull Location current, @NotNull Location target)
    {
        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();

        double yaw = Math.toDegrees(Math.atan2(-dx, dz));

        if(yaw < 0)
            yaw += 360;

        return (float) yaw;
    }
}
