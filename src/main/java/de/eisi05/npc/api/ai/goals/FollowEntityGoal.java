package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.utils.LocationUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * A goal that makes the NPC follow a target entity. The NPC will maintain a specified distance from the target and pathfind to them if too far.
 */
public class FollowEntityGoal extends Goal
{
    @Serial
    private static final long serialVersionUID = 1L;

    public static final double DEFAULT_FOLLOW_DISTANCE = 10.0;
    public static final double DEFAULT_STOP_DISTANCE = 1.5;

    private double followDistance;
    private double stopDistance;
    private double speed;
    private UUID targetEntityId;

    private transient WalkToLocationGoal currentWalkGoal;
    private transient LivingEntity target;
    private transient int pathRecalculationCooldown;
    private transient Location lastTargetLocation;
    private transient List<Player> cachedViewers;

    /**
     * Creates a FollowEntityGoal with a fixed target entity ID and default distances.
     *
     * @param targetEntityId The UUID of the entity to follow
     */
    public FollowEntityGoal(@NotNull UUID targetEntityId)
    {
        this(targetEntityId, DEFAULT_FOLLOW_DISTANCE, DEFAULT_STOP_DISTANCE, WalkToLocationGoal.DEFAULT_SPEED);
    }

    /**
     * Creates a FollowEntityGoal with a fixed target entity ID and full configuration.
     *
     * @param targetEntityId The UUID of the entity to follow
     * @param followDistance The distance at which the NPC will start following
     * @param stopDistance   The distance at which the NPC will stop moving
     * @param speed          The walking speed
     */
    public FollowEntityGoal(@NotNull UUID targetEntityId, double followDistance, double stopDistance, double speed)
    {
        super(Priority.ALWAYS);
        this.targetEntityId = targetEntityId;
        this.followDistance = Math.max(stopDistance + 0.5, followDistance);
        this.stopDistance = Math.max(0.5, stopDistance);
        this.speed = Math.max(0.1, Math.min(1.0, speed));
    }

    /**
     * Gets the follow distance for this goal.
     *
     * @return the follow distance
     */
    public double getFollowDistance()
    {
        return followDistance;
    }

    /**
     * Sets the follow distance for this goal.
     *
     * @param followDistance the new follow distance
     */
    public void setFollowDistance(double followDistance)
    {
        this.followDistance = followDistance;
    }

    /**
     * Gets the stop distance for this goal.
     *
     * @return the stop distance
     */
    public double getStopDistance()
    {
        return stopDistance;
    }

    /**
     * Sets the stop distance for this goal.
     *
     * @param stopDistance the new stop distance
     */
    public void setStopDistance(double stopDistance)
    {
        this.stopDistance = stopDistance;
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
        this.speed = speed;
    }

    /**
     * Gets the target entity ID for this goal.
     *
     * @return the target entity ID
     */
    public @Nullable UUID getTargetEntityId()
    {
        return targetEntityId;
    }

    /**
     * Sets the target entity ID for this goal.
     *
     * @param targetEntityId the new target entity ID
     */
    public void setTargetEntityId(@NotNull UUID targetEntityId)
    {
        this.targetEntityId = targetEntityId;
    }

    /**
     * Checks if this goal can be used by the NPC.
     *
     * @param npc the NPC to check
     * @return true if a valid target exists within follow distance
     */
    @Override
    public boolean canUse(@NotNull NPC npc)
    {
        if(targetEntityId == null)
            return false;

        LivingEntity potentialTarget = target;
        if(potentialTarget == null || !potentialTarget.isValid())
        {
            Entity entity = Bukkit.getEntity(targetEntityId);
            if(!(entity instanceof LivingEntity le))
                return false;
            potentialTarget = le;
        }

        if(!potentialTarget.isValid())
            return false;

        Location npcLoc = npc.getLocation();
        Location targetLoc = potentialTarget.getLocation();

        if(!npcLoc.getWorld().equals(targetLoc.getWorld()))
            return false;

        double distance = npcLoc.distance(targetLoc);
        return distance <= followDistance && distance > stopDistance;
    }

    /**
     * Starts the follow goal, initializing the target and path.
     *
     * @param npc the NPC starting this goal
     */
    @Override
    public void start(@NotNull NPC npc)
    {
        Entity entity = Bukkit.getEntity(targetEntityId);

        if(!(entity instanceof LivingEntity le))
            return;

        this.target = le;
        this.pathRecalculationCooldown = 0;
        this.lastTargetLocation = target.getLocation().clone();
        if(target != null && target.isValid())
        {
            currentWalkGoal = new WalkToLocationGoal.Builder(target.getLocation()).speed(speed).withRotation(false).build();
            currentWalkGoal.start(npc);
        }

        updateCachedViewers(npc);
    }

    /**
     * Ticks the follow goal, updating movement and path recalculation.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        if(target == null || !target.isValid())
        {
            Entity entity = Bukkit.getEntity(targetEntityId);
            if(entity instanceof LivingEntity newTarget && newTarget != null && newTarget.isValid())
                target = newTarget;
            else
            {
                stop(npc);
                return;
            }
        }

        Location targetLoc = target.getLocation();
        if(cachedViewers == null || cachedViewers.size() != npc.getViewers().size())
            updateCachedViewers(npc);

        for(Player viewer : cachedViewers)
            npc.lookAtEntity(target, viewer, true);

        targetLoc.setYaw(npc.getLocation().getYaw());
        targetLoc.setPitch(npc.getLocation().getPitch());

        OptionalInt safeY = LocationUtils.findSafeY(targetLoc);
        targetLoc.setY(safeY.isPresent() ? safeY.getAsInt() : targetLoc.getY());

        double distance = npc.getLocation().distance(targetLoc);
        if(distance <= stopDistance)
        {
            if(currentWalkGoal != null)
            {
                currentWalkGoal.stop(npc);
                currentWalkGoal = null;
            }
            return;
        }

        if(currentWalkGoal == null)
        {
            currentWalkGoal = new WalkToLocationGoal.Builder(targetLoc).speed(speed).withRotation(false).build();
            currentWalkGoal.start(npc);
            pathRecalculationCooldown = 10;
            lastTargetLocation = targetLoc.clone();
        }
        else
        {
            Location currentTarget = currentWalkGoal.getTargetLocation();
            boolean shouldRecalculate = currentTarget.distance(targetLoc) > 2.0;

            if(!shouldRecalculate && lastTargetLocation != null)
            {
                double verticalChange = Math.abs(targetLoc.getY() - lastTargetLocation.getY());
                if(verticalChange > 0.5)
                    shouldRecalculate = true;
            }

            if(!shouldRecalculate && pathRecalculationCooldown <= 0)
            {
                shouldRecalculate = true;
                pathRecalculationCooldown = 10;
            }

            if(shouldRecalculate)
            {
                currentWalkGoal.stop(npc);
                currentWalkGoal = new WalkToLocationGoal.Builder(targetLoc).speed(speed).withRotation(false).build();
                currentWalkGoal.start(npc);
                pathRecalculationCooldown = 10;
                lastTargetLocation = targetLoc.clone();
            }
            else
            {
                currentWalkGoal.tick(npc);
                pathRecalculationCooldown--;
            }
        }
    }

    /**
     * Stops the follow goal and cleans up state.
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

        if(cachedViewers == null || cachedViewers.size() != npc.getViewers().size())
            updateCachedViewers(npc);

        for(Player viewer : cachedViewers)
            npc.lookAtEntity(target, viewer, true);

        target = null;
        lastTargetLocation = null;
    }

    /**
     * Checks if this goal should continue running.
     *
     * @param npc the NPC to check
     * @return true if the target is still valid and within range
     */
    @Override
    public boolean canContinue(@NotNull NPC npc)
    {
        if(target == null || !target.isValid())
            return false;

        Location npcLoc = npc.getLocation();
        Location targetLoc = target.getLocation();

        if(!npcLoc.getWorld().equals(targetLoc.getWorld()))
            return false;

        double distance = npcLoc.distance(targetLoc);
        return distance <= followDistance * 2 && distance > stopDistance;
    }

    /**
     * Updates the cached viewers list.
     */
    private void updateCachedViewers(@NotNull NPC npc)
    {
        cachedViewers = npc.getViewers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();
    }
}
