package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import de.eisi05.npc.api.utils.LocationUtils;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Base class for goals that follow or attack a {@link LivingEntity}. Resolves "where is the ground under the target" once per target <b>block change</b>
 * instead of every tick.
 * <p>
 * <b>Paper and Folia:</b> the goal runs in the NPC's region, but the target can be in another one, and on Folia an entity may only be read by the thread that
 * owns it. The goal therefore never calls methods on {@link #target} directly. {@link #refreshTarget} captures an immutable snapshot (validity, position,
 * floor, ground, eye height) <i>on the target's own thread</i>: immediately when the caller owns the target (always the case on Paper, so behaviour there is
 * unchanged), otherwise through the target's entity scheduler, in which case the data is at most a tick or two old. Everything that wants to look at the target
 * reads the snapshot ({@link #targetValid}, {@link #targetPosition}, {@link #targetEyeHeight}, {@link #getTargetLocation}, {@link #getTargetGround}); everything
 * that wants to change it (damage, knockback) goes through {@link #runOnTarget}.
 * <p>
 * <b>Note:</b> subclasses must use the inherited {@link #target} field and must not declare their own field with that name; a field of the same name would
 * shadow it and leave this class without a target. Set it to null with {@link #clearTarget()}.
 */
public abstract class EntityTargetGoal extends PathfindingGoal
{
    private static final Vector DOWN = new Vector(0, -1, 0);
    private static final long SNAPSHOT_MAX_AGE_MS = 1500L;
    private static final long REFRESH_RETRY_MS = 1000L;
    private static final int MAX_AWAIT_TICKS = 40;

    protected transient LivingEntity target;

    private transient volatile TargetState state;
    private transient volatile long refreshRequestedAt;
    private transient int awaitTicks;

    // block-change cache; only touched while capturing, i.e. by the thread that owns the target
    private transient World lastWorld;
    private transient int lastBlockX;
    private transient int lastBlockY;
    private transient int lastBlockZ;
    private transient Location cachedFloorLocation;
    private transient Location cachedGround;

    /**
     * Immutable snapshot of the target, taken by the thread that owns it.
     */
    private record TargetState(@NotNull LivingEntity entity, boolean valid, @Nullable World world, double x, double y, double z, double eyeHeight,
                               @Nullable Location floor, @Nullable Location ground, long capturedAt)
    {
    }

    /**
     * Creates a new goal with the specified priority.
     *
     * @param priority The priority level for this goal
     */
    protected EntityTargetGoal(@NotNull Priority priority)
    {
        super(priority);
    }

    /**
     * Creates a new goal with the same priority and pathfinder as the given goal.
     *
     * @param goal The goal to copy
     */
    protected EntityTargetGoal(@NotNull EntityTargetGoal goal)
    {
        super(goal);
    }

    /**
     * Updates the snapshot of the current {@link #target}. Call it at the start of {@code canUse}, {@code canContinue} and {@code tick}. Cheap: on Paper it
     * reads the entity directly, on Folia it only requests a new snapshot from the target's thread (at most one request in flight).
     *
     * @param npc the NPC (needed to find a standable spot at the target)
     */
    protected final void refreshTarget(@NotNull NPC npc)
    {
        LivingEntity entity = target;
        TargetState current = state;

        if(entity == null)
        {
            state = null;
            awaitTicks = 0;
            return;
        }

        if(current != null && current.entity() != entity)
        {
            state = null;
            current = null;
        }

        if(Bukkit.isOwnedByCurrentRegion(entity))
        {
            state = capture(entity, npc);
            refreshRequestedAt = 0;
            awaitTicks = 0;
            return;
        }

        long now = System.currentTimeMillis();
        long requested = refreshRequestedAt;
        if(requested == 0 || now - requested > REFRESH_RETRY_MS)
        {
            refreshRequestedAt = now;
            try
            {
                SchedulerProvider.get().runSyncForEntity(entity, () ->
                {
                    try
                    {
                        TargetState captured = capture(entity, npc);
                        if(target == entity)
                            state = captured;
                    }
                    catch(Throwable ignored)
                    {
                        // the next refresh tries again
                    }
                    finally
                    {
                        refreshRequestedAt = 0;
                    }
                });
            }
            catch(Throwable throwable)
            {
                refreshRequestedAt = 0;
            }
        }

        awaitTicks = current == null ? awaitTicks + 1 : 0;
    }

    /**
     * Takes the snapshot. Runs on the thread that owns the entity.
     */
    private @NotNull TargetState capture(@NotNull LivingEntity entity, @NotNull NPC npc)
    {
        boolean valid = entity.isValid();
        Location current = entity.getLocation();
        World world = current.getWorld();
        int bx = current.getBlockX();
        int by = current.getBlockY();
        int bz = current.getBlockZ();
        double eye = entity.getEyeHeight();

        if(cachedFloorLocation == null || world != lastWorld || bx != lastBlockX || by != lastBlockY || bz != lastBlockZ)
        {
            lastWorld = world;
            lastBlockX = bx;
            lastBlockY = by;
            lastBlockZ = bz;

            Location floor = current;
            if(!entity.isOnGround() && world != null)
            {
                try
                {
                    RayTraceResult result = world.rayTraceBlocks(current, DOWN, 256, FluidCollisionMode.NEVER, true);
                    if(result != null && result.getHitBlock() != null)
                        floor = result.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
                }
                catch(Throwable ignored)
                {
                    // ray left the area this thread may read: use the current position
                }
            }
            cachedFloorLocation = floor;

            Location ground = floor.clone();
            try
            {
                OptionalInt safeY = LocationUtils.findSafeY(ground, npc);
                if(safeY.isPresent())
                    ground.setY(safeY.getAsInt());
            }
            catch(Throwable ignored)
            {
                // keep the floor as ground
            }
            cachedGround = ground;
        }

        return new TargetState(entity, valid, world, current.getX(), current.getY(), current.getZ(), eye, cachedFloorLocation, cachedGround,
                System.currentTimeMillis());
    }

    /**
     * @return true while the target is set, still valid and the snapshot is recent
     */
    protected final boolean targetValid()
    {
        TargetState snapshot = state;
        LivingEntity entity = target;
        return entity != null && snapshot != null && snapshot.entity() == entity && snapshot.valid() &&
                System.currentTimeMillis() - snapshot.capturedAt() < SNAPSHOT_MAX_AGE_MS;
    }

    /**
     * @return true if a target is set but its first snapshot has not arrived yet (only on Folia, when the target is in another region). Goals should simply
     * wait a tick in that case instead of treating the target as invalid. Gives up after about two seconds.
     */
    protected final boolean awaitingSnapshot()
    {
        return target != null && state == null && awaitTicks < MAX_AWAIT_TICKS;
    }

    /**
     * Sets the target to null and drops its snapshot.
     */
    protected final void clearTarget()
    {
        target = null;
        state = null;
        awaitTicks = 0;
    }

    /**
     * @return a copy of the target's position from the snapshot, or null if there is none
     */
    protected final @Nullable Location targetPosition()
    {
        TargetState snapshot = state;
        if(snapshot == null || snapshot.world() == null)
            return null;

        return new Location(snapshot.world(), snapshot.x(), snapshot.y(), snapshot.z());
    }

    /**
     * @return the target's eye height from the snapshot (0 if there is none)
     */
    protected final double targetEyeHeight()
    {
        TargetState snapshot = state;
        return snapshot == null ? 0 : snapshot.eyeHeight();
    }

    /**
     * Runs a job that changes an entity (damage, knockback, ...) on the thread that owns it: inline if the caller owns it (always on Paper), otherwise through
     * the entity's scheduler.
     *
     * @param entity the entity
     * @param job    the job
     */
    protected static void runOnTarget(@NotNull Entity entity, @NotNull Runnable job)
    {
        if(Bukkit.isOwnedByCurrentRegion(entity))
        {
            job.run();
            return;
        }

        try
        {
            SchedulerProvider.get().runSyncForEntity(entity, job);
        }
        catch(Throwable ignored)
        {
            // plugin disabling
        }
    }

    /**
     * How far a block/entity search around a location may reach with the current thread: the wanted range, reduced to what lies inside the chunks this thread
     * owns (on Paper that is always the wanted range).
     *
     * @param location the centre
     * @param wanted   the wanted range in blocks
     * @return the usable range, possibly 0
     */
    protected static double ownedRange(@NotNull Location location, double wanted)
    {
        if(location.getWorld() == null)
            return 0;

        int radius = (int) Math.ceil(wanted / 16.0) + 1;
        while(radius > 0 && !Bukkit.isOwnedByCurrentRegion(location, radius))
            radius--;

        return Math.min(wanted, radius * 16.0);
    }

    /**
     * Gets the floor location below the target (from the snapshot, so the ray trace only ever runs when the target moved to another block, and always on the
     * target's own thread).
     * <p>
     * The returned instance is cached and shared: <b>do not modify it</b>, clone it first.
     *
     * @return the floor location, or null if there is no target snapshot
     */
    protected final @Nullable Location getTargetLocation()
    {
        TargetState snapshot = state;
        return snapshot == null ? null : snapshot.floor();
    }

    /**
     * Gets a standable location at the target's feet, i.e. {@link #getTargetLocation()} adjusted to a safe height for this NPC. Recomputed (by the target's
     * thread) only when the target moved to another block.
     * <p>
     * The returned instance is cached and shared: <b>do not modify it</b>, clone it first.
     *
     * @param npc the NPC that wants to walk there
     * @return the ground location, or null if there is no target snapshot
     */
    protected final @Nullable Location getTargetGround(@NotNull NPC npc)
    {
        TargetState snapshot = state;
        return snapshot == null ? null : snapshot.ground();
    }

    /**
     * Turns the NPC towards the target using the snapshot (see {@link LookController}).
     *
     * @return true if rotation packets were sent
     */
    final boolean lookAtTarget(@NotNull NPC npc, @NotNull LookController look)
    {
        Location position = targetPosition();
        return position != null && look.lookAt(npc, position, targetEyeHeight());
    }

    /**
     * Turns an NPC towards an entity with as little overhead as possible. Replaces calling {@code npc.lookAtEntity(target, viewer, true)} once per viewer per
     * tick, which for every viewer and every tick
     * <ul>
     *     <li>recomputes the angles,</li>
     *     <li>calls {@code npc.setLocation(...)} (position manager update + "unsaved changes" flag), and</li>
     *     <li>sends two packets - even when nothing changed.</li>
     * </ul>
     * This controller computes the angles <b>once</b> per call and only does anything when the <b>quantised</b> angles (the client only receives them in 1.4°
     * steps) actually changed, or as a periodic refresh. The packets and the location update are then sent once for all viewers. The result is visually
     * identical.
     * <p>
     * Instances are cheap and not thread-safe (they are only used from the goal, i.e. under the goal selector's lock); keep one per goal (lazily created, goals are deserialized without constructors).
     */
    static final class LookController
    {
        /**
         * Even if nothing changed, resend after this many calls (self-heals if something else rotated the NPC).
         */
        private static final int REFRESH_TICKS = 20;
        private static final int VIEWER_REFRESH_TICKS = 40;

        private int lastYaw = Integer.MIN_VALUE;
        private int lastPitch = Integer.MIN_VALUE;
        private int sinceSend;
        private int viewerAge;
        private int viewerCount = -1;
        private List<Player> viewers = List.of();

        /**
         * Forget the last sent angles, so the next {@link #lookAt} always sends.
         */
        void reset()
        {
            lastYaw = Integer.MIN_VALUE;
            lastPitch = Integer.MIN_VALUE;
            sinceSend = REFRESH_TICKS;
        }

        /**
         * Makes the NPC look at a position (taken from a target snapshot, so no entity is read here).
         *
         * @param npc             the NPC
         * @param targetLoc       the target's position
         * @param targetEyeHeight the target's eye height
         * @return true if rotation packets were sent
         */
        boolean lookAt(@NotNull NPC npc, @NotNull Location targetLoc, double targetEyeHeight)
        {
            Location npcLoc = npc.getLocation();
            if(npcLoc.getWorld() != targetLoc.getWorld() || npc.getEntity() == null)
                return false;

            double dx = targetLoc.getX() - npcLoc.getX();
            double dy = (targetLoc.getY() + targetEyeHeight) - (npcLoc.getY() + npc.getEyeHeight(null));
            double dz = targetLoc.getZ() - npcLoc.getZ();

            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

            byte yawByte = (byte) (yaw * 256 / 360);
            byte pitchByte = (byte) (pitch * 256 / 360);

            sinceSend++;
            if(yawByte == lastYaw && pitchByte == lastPitch && sinceSend < REFRESH_TICKS)
                return false;

            lastYaw = yawByte;
            lastPitch = pitchByte;
            sinceSend = 0;

            npc.setLocation(new Location(npcLoc.getWorld(), npcLoc.getX(), npcLoc.getY(), npcLoc.getZ(), yaw, pitch));

            List<Player> current = viewers(npc);
            if(current.isEmpty())
                return true;

            net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity) npc.getEntity();
            ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(entity, yawByte);
            ClientboundMoveEntityPacket.Rot body = new ClientboundMoveEntityPacket.Rot(entity.getId(), yawByte, pitchByte, entity.onGround());
            for(Player viewer : current)
            {
                ((CraftPlayer) viewer).getHandle().connection.send(head);
                ((CraftPlayer) viewer).getHandle().connection.send(body);
            }
            return true;
        }

        /**
         * The NPC's current viewers as players, cached until the viewer count changes (or every couple of seconds).
         *
         * @param npc the NPC
         * @return the online viewers
         */
        @NotNull List<Player> viewers(@NotNull NPC npc)
        {
            int size = npc.getViewers().size();
            if(size != viewerCount || ++viewerAge >= VIEWER_REFRESH_TICKS)
            {
                viewerAge = 0;
                viewerCount = size;
                viewers = npc.getViewers().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
            }
            return viewers;
        }
    }
}
