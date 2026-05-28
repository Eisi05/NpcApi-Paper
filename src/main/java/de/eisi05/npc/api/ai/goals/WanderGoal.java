package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.utils.LocationUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.util.OptionalInt;
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

    private int radius;
    private int minDelay;
    private int maxDelay;
    private double speed;

    private transient WalkToLocationGoal currentWalkGoal;
    private transient int delayTicks;
    private transient Location targetLocation;

    /**
     * Creates a WanderGoal with default settings.
     */
    public WanderGoal()
    {
        this(DEFAULT_RADIUS, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY, WalkToLocationGoal.DEFAULT_SPEED);
    }

    /**
     * Creates a WanderGoal with custom radius and default delay.
     *
     * @param radius The maximum radius to wander (in blocks)
     */
    public WanderGoal(int radius)
    {
        this(radius, DEFAULT_MIN_DELAY, DEFAULT_MAX_DELAY, WalkToLocationGoal.DEFAULT_SPEED);
    }

    /**
     * Creates a WanderGoal with full configuration.
     *
     * @param radius   The maximum radius to wander (in blocks)
     * @param minDelay Minimum delay between wander actions (in ticks)
     * @param maxDelay Maximum delay between wander actions (in ticks)
     * @param speed    The walking speed
     */
    public WanderGoal(int radius, int minDelay, int maxDelay, double speed)
    {
        super(Priority.MEDIUM);
        this.radius = radius;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.speed = Math.max(0.1, Math.min(1.0, speed));
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
        this.speed = Math.max(0.1, Math.min(1.0, speed));
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
        return radius >= 0 && minDelay >= 0 && maxDelay >= 0 && speed >= 0;
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

            if(!currentWalkGoal.canContinue(npc))
            {
                currentWalkGoal.stop(npc);
                currentWalkGoal = null;
                delayTicks = minDelay + ThreadLocalRandom.current().nextInt(maxDelay - minDelay);
            }
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
        return targetLocation != null;
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

    /**
     * Picks a new random target location near the NPC.
     */
    private void pickNewTarget(@NotNull NPC npc)
    {
        Location currentLoc = npc.getLocation();
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

            Location potentialLoc = new Location(world, x, currentLoc.getY(), z);
            OptionalInt y = LocationUtils.findSafeY(potentialLoc);

            if(y.isPresent())
            {
                targetLocation = new Location(world, x, y.getAsInt(), z);
                currentWalkGoal = new WalkToLocationGoal.Builder(targetLocation).speed(speed).build();
                currentWalkGoal.start(npc);
                return;
            }

            attempts++;
        }

        delayTicks = maxDelay;
    }
}
