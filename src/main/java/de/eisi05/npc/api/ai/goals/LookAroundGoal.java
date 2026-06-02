package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.ai.Goal;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A goal that makes the NPC look around randomly. This can serve as a "wait" goal or an idle behavior when no other goals are active.
 */
public class LookAroundGoal extends Goal
{
    public static final int DEFAULT_MIN_DURATION = 20; // 1 second
    public static final int DEFAULT_MAX_DURATION = 80; // 4 seconds
    @Serial
    private static final long serialVersionUID = 1L;
    private static final float YAW_CHANGE_MAX = 120f;
    private static final float PITCH_CHANGE_MAX = 40f;
    private static final double ENTITY_LOOK_RANGE = 8.0;
    private static final double ENTITY_LOOK_CHANCE = 0.25;
    private static final double STARING_CHANCE = 0.2;
    private static final float PITCH_MIN_MAX = 50f;
    private static final int MIN_STARE_DURATION = 60; // 2 seconds
    private static final int MAX_STARE_DURATION = 120; // 5 seconds

    private int minDuration;
    private int maxDuration;

    private transient int ticksRemaining;
    private transient float targetYaw;
    private transient float targetPitch;
    private transient boolean isLooking;
    private transient float rotationSpeed;
    private transient LivingEntity staringEntity;

    /**
     * Creates a LookAroundGoal with default duration.
     */
    public LookAroundGoal()
    {
        this(DEFAULT_MIN_DURATION, DEFAULT_MAX_DURATION);
    }

    /**
     * Creates a LookAroundGoal with custom duration range.
     *
     * @param minDuration Minimum duration in ticks
     * @param maxDuration Maximum duration in ticks
     */
    public LookAroundGoal(int minDuration, int maxDuration)
    {
        super(Priority.LOW);
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
    }

    /**
     * Creates a copy of this goal.
     *
     * @param goal the goal to copy
     */
    private LookAroundGoal(@NotNull LookAroundGoal goal)
    {
        super(goal.getPriority());
        this.minDuration = goal.minDuration;
        this.maxDuration = goal.maxDuration;
    }

    /**
     * Gets the maximum duration for this goal.
     *
     * @return the maximum duration
     */
    public int getMaxDuration()
    {
        return maxDuration;
    }

    /**
     * Sets the maximum duration for this goal.
     *
     * @param maxDuration the new maximum duration
     */
    public void setMaxDuration(int maxDuration)
    {
        this.maxDuration = maxDuration;
    }

    /**
     * Gets the minimum duration for this goal.
     *
     * @return the minimum duration
     */
    public int getMinDuration()
    {
        return minDuration;
    }

    /**
     * Sets the minimum duration for this goal.
     *
     * @param minDuration the new minimum duration
     */
    public void setMinDuration(int minDuration)
    {
        this.minDuration = minDuration;
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
        return minDuration >= 0 && maxDuration >= 0 && super.canUse(npc);
    }

    /**
     * Starts the look around goal, initializing rotation state.
     *
     * @param npc the NPC starting this goal
     */
    @Override
    public void start(@NotNull NPC npc)
    {
        ticksRemaining = minDuration + ThreadLocalRandom.current().nextInt(maxDuration - minDuration);
        isLooking = true;
        rotationSpeed = 0.05f + ThreadLocalRandom.current().nextFloat() * 0.15f;
        pickNewLookDirection(npc);
    }

    /**
     * Ticks the look around goal, updating head rotation.
     *
     * @param npc the NPC to update
     */
    @Override
    public void tick(@NotNull NPC npc)
    {
        if(ticksRemaining <= 0 && staringEntity == null)
        {
            decideNextAction(npc);
            ticksRemaining = minDuration + ThreadLocalRandom.current().nextInt(maxDuration - minDuration);
        }

        if(staringEntity != null && ticksRemaining > 0)
            lookAtEntity(npc, staringEntity);
        else if(staringEntity != null)
            staringEntity = null;

        Location currentLoc = npc.getLocation();
        float currentYaw = currentLoc.getYaw();
        float currentPitch = currentLoc.getPitch();

        float newYaw, newPitch;
        if(staringEntity != null && ticksRemaining > 0)
        {
            newYaw = targetYaw;
            newPitch = targetPitch;
        }
        else
        {
            newYaw = lerpAngle(currentYaw, targetYaw, rotationSpeed);
            newPitch = lerp(currentPitch, targetPitch, rotationSpeed);
        }

        newPitch = Math.max(-PITCH_MIN_MAX, Math.min(PITCH_MIN_MAX, newPitch));

        currentLoc.setYaw(newYaw);
        currentLoc.setPitch(newPitch);

        npc.rotateHead(newYaw, newPitch);
        npc.setLocation(currentLoc);

        ticksRemaining--;
    }

    /**
     * Stops the look around goal and cleans up state.
     *
     * @param npc the NPC stopping this goal
     */
    @Override
    public void stop(@NotNull NPC npc)
    {
        isLooking = false;
        ticksRemaining = 0;
        staringEntity = null;
    }

    /**
     * Checks if this goal should continue running.
     *
     * @param npc the NPC to check
     * @return true if the NPC is still looking
     */
    @Override
    public boolean canContinue(@NotNull NPC npc)
    {
        return isLooking && super.canContinue(npc);
    }

    @Override
    public @NotNull Goal copy()
    {
        return new LookAroundGoal(this);
    }

    /**
     * Decides the next action: look around randomly, stare at current direction, or look at a nearby entity.
     */
    private void decideNextAction(@NotNull NPC npc)
    {
        if(ThreadLocalRandom.current().nextDouble() < ENTITY_LOOK_CHANCE)
        {
            LivingEntity nearbyEntity = findNearbyEntity(npc);
            if(nearbyEntity != null)
            {
                staringEntity = nearbyEntity;
                ticksRemaining = MIN_STARE_DURATION + ThreadLocalRandom.current().nextInt(MAX_STARE_DURATION - MIN_STARE_DURATION);
                lookAtEntity(npc, nearbyEntity);
                rotationSpeed = 0.1f + ThreadLocalRandom.current().nextFloat() * 0.1f;
                return;
            }
        }

        if(ThreadLocalRandom.current().nextDouble() < STARING_CHANCE)
            rotationSpeed = 0.02f + ThreadLocalRandom.current().nextFloat() * 0.03f;
        else
        {
            pickNewLookDirection(npc);
            rotationSpeed = 0.05f + ThreadLocalRandom.current().nextFloat() * 0.15f;
        }
    }

    /**
     * Finds a nearby living entity to look at.
     */
    private LivingEntity findNearbyEntity(@NotNull NPC npc)
    {
        Location npcLoc = npc.getLocation();
        for(Entity entity : npcLoc.getWorld().getNearbyEntities(npcLoc, ENTITY_LOOK_RANGE, ENTITY_LOOK_RANGE, ENTITY_LOOK_RANGE))
        {
            if(entity instanceof LivingEntity livingEntity && !entity.equals(((net.minecraft.world.entity.Entity) npc.getEntity()).getBukkitEntity()))
                return livingEntity;
        }
        return null;
    }

    /**
     * Sets the target direction to look at an entity.
     */
    private void lookAtEntity(@NotNull NPC npc, @NotNull LivingEntity entity)
    {
        net.minecraft.world.entity.Entity npcEntity = (net.minecraft.world.entity.Entity) npc.getEntity();
        Location npcLoc = npc.getLocation();
        Location targetLoc = entity.getLocation();

        double dx = targetLoc.getX() - npcLoc.getX();

        double eyeHeight = (npcEntity.getBukkitEntity() instanceof LivingEntity le ? le.getEyeHeight() :
                npcEntity.getBukkitEntity().getHeight()) - (npc.getOption(NpcOption.POSE) == org.bukkit.entity.Pose.SITTING ? 0.625 : 0);

        double dy = (targetLoc.getY() + entity.getEyeHeight()) - (npcLoc.getY() + (eyeHeight * npc.getOption(NpcOption.SCALE)));
        double dz = targetLoc.getZ() - npcLoc.getZ();

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);
        targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) Math.toDegrees(-Math.atan2(dy, distanceXZ));

        targetPitch = Math.max(-PITCH_MIN_MAX, Math.min(PITCH_MIN_MAX, targetPitch));
    }

    /**
     * Picks a new random look direction.
     */
    private void pickNewLookDirection(@NotNull NPC npc)
    {
        Location currentLoc = npc.getLocation();
        float currentYaw = currentLoc.getYaw();
        float currentPitch = currentLoc.getPitch();

        float yawMultiplier = ThreadLocalRandom.current().nextDouble() < 0.3 ? 1.5f : 1.0f;
        targetYaw = currentYaw + (ThreadLocalRandom.current().nextFloat() * 2 - 1) * YAW_CHANGE_MAX * yawMultiplier;

        float pitchUpAvailable = PITCH_MIN_MAX - currentPitch;
        float pitchDownAvailable = currentPitch - (-PITCH_MIN_MAX);
        float maxPitchChange = Math.min(PITCH_CHANGE_MAX, Math.min(pitchUpAvailable, pitchDownAvailable) * 2f);

        if(pitchUpAvailable < 5f)
            targetPitch = currentPitch - ThreadLocalRandom.current().nextFloat() * PITCH_CHANGE_MAX;
        else if(pitchDownAvailable < 5f)
            targetPitch = currentPitch + ThreadLocalRandom.current().nextFloat() * PITCH_CHANGE_MAX;
        else
            targetPitch = currentPitch + (ThreadLocalRandom.current().nextFloat() * 2 - 1) * maxPitchChange;
    }

    /**
     * Linearly interpolates between two angles, handling wraparound.
     */
    private float lerpAngle(float from, float to, float alpha)
    {
        float diff = to - from;
        while(diff < -180)
            diff += 360;
        while(diff > 180)
            diff -= 360;
        return from + diff * alpha;
    }

    /**
     * Linearly interpolates between two values.
     */
    private float lerp(float from, float to, float alpha)
    {
        return from + (to - from) * alpha;
    }
}
