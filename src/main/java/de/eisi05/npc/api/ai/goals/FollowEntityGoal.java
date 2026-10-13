package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.objects.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.util.UUID;

/**
 * A goal that makes the NPC follow a target entity. The NPC will maintain a specified distance from the target and pathfind to them if too far.
 */
public class FollowEntityGoal extends EntityTargetGoal
{
    @Serial
    private static final long serialVersionUID = 1L;

    public static final double DEFAULT_FOLLOW_DISTANCE = 10.0;
    public static final double DEFAULT_STOP_DISTANCE = 1.5;

    private double followDistance;
    private double stopDistance;
    private double speed;
    private UUID targetEntityId;

    private static final long RETRY_AFTER_FAILURE_MS = 500;

    private transient WalkToLocationGoal currentWalkGoal;
    private transient LookController look;
    private transient long retryWalkAt;

    private @NotNull LookController look()
    {
        if(look == null)
            look = new LookController();
        return look;
    }

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
        this.speed = Math.clamp(speed, 0.1, 1.0);
    }

    /**
     * Creates a copy of this goal.
     *
     * @param goal the goal to copy
     */
    private FollowEntityGoal(@NotNull FollowEntityGoal goal)
    {
        super(goal);
        this.targetEntityId = goal.targetEntityId;
        this.followDistance = goal.followDistance;
        this.stopDistance = goal.stopDistance;
        this.speed = goal.speed;
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
        this.speed = Math.clamp(speed, 0.1, 1.0);
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

        if(!super.canUse(npc))
            return false;

        refreshTarget(npc);
        if(target == null || !targetValid())
        {
            Entity entity = Bukkit.getEntity(targetEntityId);
            if(!(entity instanceof LivingEntity le))
                return false;

            if(le != target)
                target = le;
            refreshTarget(npc);
        }

        if(!targetValid())
            return false;

        Location npcLoc = npc.getLocation();
        Location targetLoc = targetPosition();
        if(targetLoc == null)
            return false;

        if(!npcLoc.getWorld().equals(targetLoc.getWorld()))
            return false;

        double distanceSq = npcLoc.distanceSquared(targetLoc);
        return distanceSq <= followDistance * followDistance && distanceSq > stopDistance * stopDistance;
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
        this.retryWalkAt = 0;
        look().reset();
        refreshTarget(npc);

        Location ground = getTargetGround(npc);
        if(ground != null)
            startWalking(npc, ground);
    }

    /**
     * Ticks the follow goal: keeps looking at the target and keeps one walk goal retargeted at the target's ground position.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        refreshTarget(npc);
        if(awaitingSnapshot())
            return;

        if(target == null || !targetValid())
        {
            Entity entity = Bukkit.getEntity(targetEntityId);
            if(entity instanceof LivingEntity newTarget && newTarget != target)
            {
                target = newTarget;
                refreshTarget(npc);
                if(awaitingSnapshot())
                    return;
            }

            if(!targetValid())
            {
                stop(npc);
                return;
            }
        }

        lookAtTarget(npc, look());

        Location ground = getTargetGround(npc);
        if(ground == null)
            return;

        double distanceSq = npc.getLocation().distanceSquared(ground);
        if(distanceSq <= stopDistance * stopDistance)
        {
            stopWalking(npc);
            return;
        }

        if(currentWalkGoal != null && !currentWalkGoal.isActive())
        {
            if(currentWalkGoal.isUnreachable())
                retryWalkAt = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS;
            currentWalkGoal = null;
        }

        if(currentWalkGoal == null)
        {
            double startWalkingThreshold = stopDistance + 0.75;
            if(distanceSq >= startWalkingThreshold * startWalkingThreshold && System.currentTimeMillis() >= retryWalkAt)
                startWalking(npc, ground);
            return;
        }

        double tolerance = Math.clamp(Math.sqrt(distanceSq) * 0.2, 1.0, 4.0);
        if(currentWalkGoal.horizontalTargetDistanceSq(ground) > tolerance * tolerance || currentWalkGoal.verticalTargetDistance(ground) > 1.5)
            currentWalkGoal.updateTargetLocation(ground.clone(), npc);

        currentWalkGoal.tick(npc);
    }

    /**
     * Stops the follow goal and cleans up state.
     *
     * @param npc the NPC stopping this goal
     */
    @Override
    public void stop(@NotNull NPC npc)
    {
        stopWalking(npc);

        if(target != null)
            lookAtTarget(npc, look());

        clearTarget();
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
        if(target == null)
            return false;

        refreshTarget(npc);
        if(awaitingSnapshot())
            return super.canContinue(npc);

        if(!targetValid())
            return false;

        if(!super.canContinue(npc))
            return false;

        Location npcLoc = npc.getLocation();
        Location targetLoc = targetPosition();
        if(targetLoc == null)
            return false;

        if(!npcLoc.getWorld().equals(targetLoc.getWorld()))
            return false;

        double distanceSq = npcLoc.distanceSquared(targetLoc);
        double maxDistance = followDistance * 2;
        return distanceSq <= maxDistance * maxDistance && distanceSq > stopDistance * stopDistance;
    }

    @Override
    protected boolean canBeRemovedNow(@NotNull NPC npc)
    {
        if(super.canBeRemovedNow(npc))
            return true;

        if(currentWalkGoal != null && currentWalkGoal.canContinue(npc))
        {
            if(isAirBelow(npc))
                return false;
        }
        return true;
    }

    @Override
    public @NotNull Goal copy()
    {
        return new FollowEntityGoal(this);
    }

    private void startWalking(@NotNull NPC npc, @NotNull Location ground)
    {
        stopWalking(npc);
        currentWalkGoal = new WalkToLocationGoal.Builder(ground.clone()).speed(speed).pathfinder(pathfinder).withRotation(false).build();
        currentWalkGoal.start(npc);
    }

    private void stopWalking(@NotNull NPC npc)
    {
        if(currentWalkGoal != null)
        {
            currentWalkGoal.stop(npc);
            currentWalkGoal = null;
        }
    }
}
