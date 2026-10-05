package de.eisi05.npc.api.scheduler.tasks;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.events.NpcStopWalkingEvent;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.pathfinding.AbstractPathfinder;
import de.eisi05.npc.api.pathfinding.BoundingBoxPathfinder;
import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.scheduler.PluginTask;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import de.eisi05.npc.api.wrapper.packets.TeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * A task that handles the movement of an NPC along a calculated path. This class implements Runnable to handle the movement in a scheduled task compatible with
 * both Paper and Folia, providing smooth movement, physics, and door interaction capabilities.
 */
public class PathTask implements Runnable
{
    private static final double GRAVITY = -0.08;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double TERMINAL_VELOCITY = -3.92;
    private static final double STEP_HEIGHT = 0.55;
    private static final int STUCK_DETECTION_TICKS = 10;
    private static final double STUCK_THRESHOLD = 0.05;

    private final NPC npc;
    private final double entityWidth;
    private Path path;
    private final List<Location> pathPoints;
    private final List<Vector> pathPointVectors;
    private final Set<UUID> viewerIds = new HashSet<>();
    private Player[] cachedViewers;
    private final boolean autoManageWalkingViewers;
    private final Entity serverEntity;
    private final Consumer<WalkingResult> callback;
    private final boolean withRotation;

    private final double speed;
    private final boolean updateRealLocation;

    private final Set<Block> openedDoors = new HashSet<>();
    private final Vector previousMoveDir;

    private PluginTask task;
    private boolean finished = false;
    private int index = 0;
    private Vector currentPos;
    private float previousPitch;
    private float previousYaw;
    private double verticalVelocity = 0.0;
    private int viewerRefreshTicks = 0;
    private boolean isWaitingForChunkLoad = false;

    private final Vector[] positionHistory;
    private int positionHistoryIndex = 0;
    private int stuckCounter = 0;
    private boolean bypassCollisionChecks = false;

    private long periodTicks = 1L;

    /**
     * Private constructor used by the Builder pattern.
     *
     * @param builder The builder containing all necessary parameters
     */
    private PathTask(@NotNull Builder builder)
    {
        this.npc = builder.npc;
        double scale = npc.getOption(NpcOption.SCALE);
        this.entityWidth = ((Entity) npc.getEntity()).getBoundingBox().getXsize() * scale;
        this.path = builder.path;
        this.pathPoints = new ArrayList<>(builder.path.asLocations());
        this.pathPointVectors = new ArrayList<>(builder.path.asVectors());
        if(builder.viewers != null)
        {
            for(Player viewer : builder.viewers)
            {
                if(viewer != null)
                    this.viewerIds.add(viewer.getUniqueId());
            }
        }
        this.autoManageWalkingViewers = builder.autoManageWalkingViewers;
        this.callback = builder.callback;
        this.withRotation = builder.withRotation;

        this.speed = builder.speed;
        this.updateRealLocation = builder.updateRealLocation;

        this.currentPos = npc.getLocation().toVector();
        this.previousPitch = npc.getLocation().getPitch();
        this.previousYaw = npc.getLocation().getYaw();
        this.previousMoveDir = npc.getLocation().getDirection();
        this.serverEntity = (Entity) npc.getEntity();

        this.positionHistory = new Vector[STUCK_DETECTION_TICKS];
        for(int i = 0; i < STUCK_DETECTION_TICKS; i++)
            this.positionHistory[i] = new Vector();
        this.positionHistory[0] = currentPos.clone();
    }

    /**
     * Dynamically updates the active path while the NPC is moving.
     *
     * @param newPath the new calculated path
     */
    public synchronized void updatePath(@NotNull Path newPath)
    {
        List<Location> newLocations = newPath.asLocations();
        if(newLocations.isEmpty())
            return;

        this.path = newPath;
        this.pathPoints.clear();
        this.pathPoints.addAll(newLocations);

        this.pathPointVectors.clear();
        for(Location loc : this.pathPoints)
            this.pathPointVectors.add(loc.toVector());

        this.stuckCounter = 0;
        this.bypassCollisionChecks = false;

        this.index = calculateBestStartingIndex(newLocations);
        this.cachedViewers = null;
    }

    /**
     * Calculates the most appropriate starting waypoint index when updating the path mid-movement,
     * preventing the NPC from walking backwards to an outdated start position.
     *
     * @param locations The list of locations in the new path
     * @return The optimal index to resume movement from
     */
    private int calculateBestStartingIndex(@NotNull List<Location> locations)
    {
        int size = locations.size();
        if(size <= 1)
            return 0;

        int bestIndex = 1;
        double minDistanceSq = Double.MAX_VALUE;

        List<Vector> vectors = new ArrayList<>(size);
        for(Location loc : locations)
            vectors.add(loc.toVector());

        for(int i = 0; i < size - 1; i++)
        {
            Vector p1 = vectors.get(i);
            Vector p2 = vectors.get(i + 1);

            double segX = p2.getX() - p1.getX();
            double segZ = p2.getZ() - p1.getZ();
            double segLenSq = segX * segX + segZ * segZ;

            if(segLenSq < 1e-6)
                continue;

            double toCurrX = currentPos.getX() - p1.getX();
            double toCurrZ = currentPos.getZ() - p1.getZ();

            double t = Math.clamp((toCurrX * segX + toCurrZ * segZ) / segLenSq, 0.0, 1.0);
            double projX = p1.getX() + segX * t;
            double projZ = p1.getZ() + segZ * t;

            double dx = currentPos.getX() - projX;
            double dz = currentPos.getZ() - projZ;
            double distSq = dx * dx + dz * dz;

            if(distSq < minDistanceSq)
            {
                minDistanceSq = distSq;
                bestIndex = Math.min(i + 1, size - 1);
            }
        }

        return bestIndex;
    }

    /**
     * Gets the current index of the path task.
     *
     * @return the current index
     */
    public int getIndex()
    {
        return index;
    }

    /**
     * Starts the path task using the cross-compatible SchedulerProvider.
     *
     * @param delayTicks  The delay before the first execution
     * @param periodTicks The period between executions
     * @return The PluginTask instance
     */
    public PluginTask start(long delayTicks, long periodTicks)
    {
        this.periodTicks = periodTicks;
        return this.task = SchedulerProvider.get().runLaterAtLocation(getCurrentLocation(), this, delayTicks);
    }

    /**
     * The main execution method called by the Bukkit/Folia scheduler. Handles the NPC's movement along the path, including physics and door interactions.
     */
    @Override
    public void run()
    {
        World world = npc.getLocation().getWorld();
        if(world == null)
            return;

        int currentChunkX = currentPos.getBlockX() >> 4;
        int currentChunkZ = currentPos.getBlockZ() >> 4;

        if(!world.isChunkLoaded(currentChunkX, currentChunkZ))
        {
            handleUnloadedChunk(world, currentChunkX, currentChunkZ);
            return;
        }

        if(index < pathPoints.size())
        {
            Location next = pathPoints.get(index);
            int nextChunkX = next.getBlockX() >> 4;
            int nextChunkZ = next.getBlockZ() >> 4;

            if(!world.isChunkLoaded(nextChunkX, nextChunkZ))
            {
                handleUnloadedChunk(world, nextChunkX, nextChunkZ);
                return;
            }
        }

        isWaitingForChunkLoad = false;

        if(autoManageWalkingViewers && viewerRefreshTicks++ >= 10)
        {
            viewerRefreshTicks = 0;
            npc.refreshWalkingViewers();
        }

        while (index < pathPointVectors.size())
        {
            Vector target = pathPointVectors.get(index);
            Vector toTarget = new Vector(target.getX() - currentPos.getX(), target.getY() - currentPos.getY(), target.getZ() - currentPos.getZ());

            if (hasReachedWaypoint(toTarget))
                index++;
            else
                break;
        }

        if (index >= pathPointVectors.size())
        {
            if (finishPath())
                return;
        }

        Vector target = pathPointVectors.get(Math.min(index, pathPointVectors.size() - 1));
        Vector toTarget = new Vector(target.getX() - currentPos.getX(), target.getY() - currentPos.getY(), target.getZ() - currentPos.getZ());

        int chunkX = currentPos.getBlockX() >> 4;
        int chunkZ = currentPos.getBlockZ() >> 4;
        boolean isChunkLoaded = world.isChunkLoaded(chunkX, chunkZ);

        if(isChunkLoaded)
        {
            processDoors();
            cleanupDoors();

            Vector movement = calculateHorizontalMovement(toTarget, target);
            PhysicsResult physics = applyPhysics(movement);
            movement.setY(physics.yChange);
            currentPos.add(movement);

            detectAndHandleStuck();

            float yaw, pitch;
            if(withRotation)
            {
                float[] rotation = calculateSmoothRotation();
                yaw = rotation[0];
                pitch = rotation[1];
            }
            else
            {
                yaw = npc.getLocation().getYaw();
                pitch = npc.getLocation().getPitch();
            }

            sendMovePackets(movement, yaw, pitch, physics.isGrounded);
        }
        else
        {
            double distanceToTarget = toTarget.length();
            double moveDist = Math.min(speed, distanceToTarget);

            if(distanceToTarget > 1e-6)
            {
                Vector movement = toTarget.normalize().multiply(moveDist);
                currentPos.add(movement);
            }
            else
                currentPos = target.clone();

            if(withRotation)
            {
                float[] rotation = calculateSmoothRotation();
                previousYaw = rotation[0];
                previousPitch = rotation[1];
            }

            if(updateRealLocation)
                npc.setLocation(currentPos.toLocation(world));
        }

        scheduleNextTick(periodTicks);
    }

    /**
     * Schedules the next tick of the path task.
     *
     * @param delayTicks The delay in ticks before the next execution
     */
    private void scheduleNextTick(long delayTicks)
    {
        if(!finished)
            this.task = SchedulerProvider.get().runLaterAtLocation(getCurrentLocation(), this, delayTicks);
    }

    /**
     * Handles the logic when an NPC encounters an unloaded chunk. Uses Folia-compatible asynchronous chunk loading.
     */
    private void handleUnloadedChunk(World world, int chunkX, int chunkZ)
    {
        if(NpcApi.config.loadChunksOnPath() && !isWaitingForChunkLoad)
        {
            isWaitingForChunkLoad = true;
            world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk ->
            {
                isWaitingForChunkLoad = false;
                scheduleNextTick(1L);
            });
        }
        else if(!isWaitingForChunkLoad)
            scheduleNextTick(10L);
    }

    /**
     * Processes door interactions along the NPC's path. Opens doors that are in the NPC's path and within interaction range.
     */
    private void processDoors()
    {
        World world = npc.getLocation().getWorld();
        if(world == null)
            return;

        checkAndOpenDoor(currentPos.toLocation(world).getBlock());
        checkAndOpenDoor(currentPos.toLocation(world).getBlock().getRelative(BlockFace.UP));

        if(index < pathPointVectors.size())
        {
            Vector next = pathPointVectors.get(index);
            double dx = next.getX() - currentPos.getX();
            double dz = next.getZ() - currentPos.getZ();
            if(dx * dx + dz * dz < 4.0)
            {
                Location nextLoc = pathPoints.get(index);
                checkAndOpenDoor(nextLoc.getBlock());
                checkAndOpenDoor(nextLoc.getBlock().getRelative(BlockFace.UP));
            }
        }
    }

    /**
     * Checks if a block is a door and opens it if it's closed.
     *
     * @param block The block to check for door interaction
     */
    private void checkAndOpenDoor(@NotNull Block block)
    {
        if(block.getBlockData() instanceof Openable openable)
        {
            if(!openable.isOpen())
            {
                openable.setOpen(true);
                block.setBlockData(openable);
                block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);

                openedDoors.add(block);
            }
        }
    }

    /**
     * Cleans up opened doors that are no longer near the NPC. Closes doors that the NPC has moved away from.
     */
    private void cleanupDoors()
    {
        if(openedDoors.isEmpty())
            return;

        Iterator<Block> iterator = openedDoors.iterator();
        while(iterator.hasNext())
        {
            Block door = iterator.next();
            if(!(door.getBlockData() instanceof Openable openable))
            {
                iterator.remove();
                continue;
            }

            double distSq = Math.pow(door.getX() + 0.5 - currentPos.getX(), 2) + Math.pow(door.getZ() + 0.5 - currentPos.getZ(), 2);
            if(distSq > 1.69)
            {
                if(openable.isOpen())
                {
                    openable.setOpen(false);
                    door.setBlockData(openable);
                    door.getWorld().playSound(door.getLocation(), org.bukkit.Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 1f);
                }
                iterator.remove();
            }
        }
    }

    /**
     * Forces all doors opened by this path task to close. Used when the path is completed or canceled.
     */
    private void forceCloseAllDoors()
    {
        for(Block door : openedDoors)
        {
            if(door.getBlockData() instanceof Openable openable && openable.isOpen())
            {
                openable.setOpen(false);
                door.setBlockData(openable);
                door.getWorld().playSound(door.getLocation(), org.bukkit.Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 1f);
            }
        }
        openedDoors.clear();
    }

    /**
     * Checks if the NPC has reached the current waypoint.
     *
     * @param toTarget The vector to the target waypoint
     * @return true if the waypoint has been reached, false otherwise
     */
    private boolean hasReachedWaypoint(@NotNull Vector toTarget)
    {
        double horizontalDistSq = (toTarget.getX() * toTarget.getX()) + (toTarget.getZ() * toTarget.getZ());
        if (horizontalDistSq > 0.04)
            return false;

        if (toTarget.getY() <= 0)
            return true;

        return toTarget.getY() < 0.5;
    }

    /**
     * Calculates the horizontal movement vector for the NPC.
     *
     * @param toTarget    The vector to the target waypoint
     * @param targetPoint The absolute target point
     * @return A vector representing the horizontal movement
     */
    private @NotNull Vector calculateHorizontalMovement(@NotNull Vector toTarget, @NotNull Vector targetPoint)
    {
        double hX = toTarget.getX();
        double hZ = toTarget.getZ();
        double distSq = hX * hX + hZ * hZ;
        if(distSq < 1e-6)
            return new Vector(0, 0, 0);

        double dist = Math.sqrt(distSq);
        double currentSpeed = speed;
        double yDiff = targetPoint.getY() - currentPos.getY();
        if(yDiff > STEP_HEIGHT && currentPos.getY() < targetPoint.getY())
            currentSpeed *= 0.6;

        double moveDistance = Math.min(currentSpeed, dist);
        double moveStepX = (hX / dist) * moveDistance;
        double moveStepZ = (hZ / dist) * moveDistance;

        if(Math.abs(moveDistance - dist) < 1e-6)
        {
            double currentGroundY = getGroundY(npc.getLocation().getWorld(), currentPos);
            boolean onGround = currentPos.getY() <= currentGroundY + 1e-5;

            if(onGround)
            {
                this.currentPos.setX(targetPoint.getX());
                this.currentPos.setZ(targetPoint.getZ());
                return new Vector(0, 0, 0);
            }
        }

        return new Vector(moveStepX, 0, moveStepZ);
    }

    /**
     * Applies physics (gravity, jumping) to the NPC's movement. Excludes strict bounding-box checks because the pathfinder guarantees a safe route.
     *
     * @param movement The current horizontal movement vector
     * @return A PhysicsResult containing the vertical movement and ground state
     */
    private @NotNull PhysicsResult applyPhysics(Vector movement)
    {
        World world = npc.getLocation().getWorld();
        if(world == null)
            return new PhysicsResult(0, false);

        double effectiveStepHeight = bypassCollisionChecks ? STEP_HEIGHT * 2.0 : STEP_HEIGHT;

        double stepTargetX = currentPos.getX() + movement.getX();
        double stepTargetY = currentPos.getY() + movement.getY();
        double stepTargetZ = currentPos.getZ() + movement.getZ();
        Vector stepTarget = new Vector(stepTargetX, stepTargetY, stepTargetZ);
        double targetGroundY = getGroundY(world, stepTarget);

        double futureGroundY = targetGroundY;
        double moveLenSq = movement.lengthSquared();
        if(moveLenSq > 1e-6)
        {
            double minLookAhead = (entityWidth / 2.0) + 0.1;
            double moveLen = Math.sqrt(moveLenSq);
            double desiredLookAhead = Math.clamp(moveLen * 4.5, minLookAhead, 0.8);

            double tracePosX = currentPos.getX();
            double tracePosY = currentPos.getY();
            double tracePosZ = currentPos.getZ();
            double remainingDist = desiredLookAhead;

            for(int i = index; i < pathPointVectors.size(); i++)
            {
                Vector wp = pathPointVectors.get(i);
                double toWpX = wp.getX() - tracePosX;
                double toWpZ = wp.getZ() - tracePosZ;
                double distToWp = Math.sqrt(toWpX * toWpX + toWpZ * toWpZ);

                if(distToWp >= remainingDist)
                {
                    if(distToWp > 0)
                    {
                        double ratio = remainingDist / distToWp;
                        tracePosX += toWpX * ratio;
                        tracePosZ += toWpZ * ratio;
                    }
                    remainingDist = 0;
                    break;
                }
                else
                {
                    tracePosX = wp.getX();
                    tracePosZ = wp.getZ();
                    remainingDist -= distToWp;
                }
            }

            if(remainingDist > 0)
            {
                double hMoveX = movement.getX();
                double hMoveZ = movement.getZ();
                double hMoveLenSq = hMoveX * hMoveX + hMoveZ * hMoveZ;
                if(hMoveLenSq > 0)
                {
                    double hMoveLen = Math.sqrt(hMoveLenSq);
                    double ratio = remainingDist / hMoveLen;
                    tracePosX += hMoveX * ratio;
                    tracePosZ += hMoveZ * ratio;
                }
            }

            futureGroundY = getGroundY(world, new Vector(tracePosX, tracePosY, tracePosZ));
        }

        Location targetWaypoint = pathPoints.get(Math.min(index, pathPoints.size() - 1));

        if(targetGroundY < targetWaypoint.getY() - effectiveStepHeight)
            targetGroundY = targetWaypoint.getY();

        if(futureGroundY < targetWaypoint.getY() - effectiveStepHeight)
            futureGroundY = targetWaypoint.getY();

        double currentGroundY = getGroundY(world, currentPos);
        boolean onGround = (currentPos.getY() <= currentGroundY + 1e-5) && (currentGroundY - currentPos.getY() <= effectiveStepHeight);
        double yChange;

        if(onGround)
        {
            double yDiff = targetGroundY - currentPos.getY();
            double futureYDiff = futureGroundY - currentPos.getY();

            if(yDiff > effectiveStepHeight || futureYDiff > effectiveStepHeight)
            {
                verticalVelocity = JUMP_VELOCITY;
                return new PhysicsResult(JUMP_VELOCITY, false);
            }

            if(yDiff <= 0 && yDiff >= -effectiveStepHeight)
            {
                verticalVelocity = 0;
                return new PhysicsResult(yDiff, true);
            }

            if(yDiff > 0 && yDiff <= effectiveStepHeight)
            {
                verticalVelocity = 0;
                return new PhysicsResult(yDiff, true);
            }

            verticalVelocity += GRAVITY;
            verticalVelocity *= 0.98;

            if(verticalVelocity < TERMINAL_VELOCITY)
                verticalVelocity = TERMINAL_VELOCITY;

            return new PhysicsResult(verticalVelocity, false);
        }
        else
        {
            verticalVelocity += GRAVITY;
            verticalVelocity *= 0.98;

            if(verticalVelocity < TERMINAL_VELOCITY)
                verticalVelocity = TERMINAL_VELOCITY;

            yChange = verticalVelocity;

            double landingY = targetGroundY;
            if(currentPos.getY() < targetGroundY - effectiveStepHeight)
                landingY = getGroundY(world, currentPos);

            if(verticalVelocity <= 0 && currentPos.getY() + yChange <= landingY + 1e-5)
            {
                yChange = landingY - currentPos.getY();
                verticalVelocity = 0;
                onGround = true;
            }

            return new PhysicsResult(yChange, onGround);
        }
    }

    /**
     * Calculates the feet Y-coordinate of the ground at a given position. Delegates to {@link BoundingBoxPathfinder#resolveGroundSupport} so ground detection
     * uses the exact same full-footprint, collision-shape-based logic the pathfinder used when it planned the route.
     *
     * @param world The world to check in
     * @param pos   The position to check
     * @return The Y-coordinate where the NPC's feet should be
     */
    private double getGroundY(@NotNull World world, @NotNull Vector pos)
    {
        Location currentWaypoint = pathPoints.get(Math.min(index, pathPoints.size() - 1));
        double waypointY = currentWaypoint.getY();

        if (bypassCollisionChecks)
            return waypointY;

        BoundingBoxPathfinder.FootSupport support = BoundingBoxPathfinder.resolveGroundSupport(world, pos.getX(), pos.getY(), pos.getZ(), entityWidth);

        if (support.valid())
        {
            if (Math.abs(support.feetY() - waypointY) <= 1.5)
                return support.feetY();
        }

        return waypointY;
    }

    /**
     * Handles the completion of the path. Performs final cleanup and calls the completion callback.
     *
     * @return true if the path was successfully finished, false otherwise
     */
    private boolean finishPath()
    {
        if(!pathPointVectors.isEmpty())
        {
            Vector last = pathPointVectors.getLast();
            double dx = last.getX() - currentPos.getX();
            double dy = last.getY() - currentPos.getY();
            double dz = last.getZ() - currentPos.getZ();
            if(dx * dx + dy * dy + dz * dz > 0.04)
                return false;

            smoothEndRotation(pathPoints.getLast());
        }

        finished = true;
        forceCloseAllDoors();

        NpcStopWalkingEvent event = new NpcStopWalkingEvent(npc, WalkingResult.SUCCESS, updateRealLocation);
        Bukkit.getPluginManager().callEvent(event);

        if(event.changeRealLocation()  && !pathPoints.isEmpty())
        {
            Location loc = pathPoints.getLast();
            npc.changeRealLocation(loc, getViewers());
            currentPos = loc.toVector();
            ensureOnSolidGround();
        }

        if(callback != null)
            callback.accept(WalkingResult.SUCCESS);

        npc.clearWalkingTask(this);
        cancel();
        return true;
    }

    /**
     * Smoothly rotates the NPC to face the final direction when reaching the end of the path.
     *
     * @param loc The target location to face
     */
    private void smoothEndRotation(Location loc)
    {
        if(serverEntity == null)
            return;

        ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(serverEntity, (byte) (loc.getYaw() * 256 / 360));
        ClientboundMoveEntityPacket.Rot body = new ClientboundMoveEntityPacket.Rot(serverEntity.getId(), (byte) (loc.getYaw() * 256 / 360),
                (byte) (loc.getPitch() * 256 / 360), true);

        Vec3 vec = new Vec3(loc.toVector().getX(), loc.toVector().getY(), loc.toVector().getZ());
        ClientboundTeleportEntityPacket teleport = (ClientboundTeleportEntityPacket) TeleportEntityPacket.create(serverEntity, vec, Vec3.ZERO,
                loc.getYaw(), loc.getPitch(), Set.of(), true);

        npc.sendNpcMovePackets(teleport, head, getViewers());
        npc.sendNpcBodyPackets(body, getViewers());
    }

    /**
     * Calculates smooth rotation for the NPC's head and body.
     *
     * @return An array containing [yaw, pitch] for the NPC's rotation
     */
    private float @NotNull [] calculateSmoothRotation()
    {
        double lookDirX, lookDirZ;
        if(index + 1 < pathPointVectors.size())
        {
            Vector p1 = pathPointVectors.get(index);
            Vector p2 = pathPointVectors.get(index + 1);
            lookDirX = (p1.getX() + p2.getX()) * 0.5 - currentPos.getX();
            lookDirZ = (p1.getZ() + p2.getZ()) * 0.5 - currentPos.getZ();
        }
        else
        {
            Vector p = pathPointVectors.get(Math.min(index, pathPointVectors.size() - 1));
            lookDirX = p.getX() - currentPos.getX();
            lookDirZ = p.getZ() - currentPos.getZ();
        }

        double hLookX = lookDirX;
        double hLookZ = lookDirZ;
        double hLookLenSq = hLookX * hLookX + hLookZ * hLookZ;

        if(hLookLenSq < 1e-6)
        {
            hLookX = previousMoveDir.getX();
            hLookZ = previousMoveDir.getZ();
        }

        float targetYaw = (float) (Math.toDegrees(Math.atan2(hLookZ, hLookX)) - 90);
        targetYaw = normalizeAngle(targetYaw);

        float diff = normalizeAngle(targetYaw - previousYaw);
        diff = Math.clamp(diff, -15f, 15f);

        float yaw = previousYaw + diff;
        previousYaw = yaw;
        previousMoveDir.setX(hLookX);
        previousMoveDir.setY(0);
        previousMoveDir.setZ(hLookZ);

        Vector targetVec = pathPointVectors.get(Math.min(index + 1, pathPointVectors.size() - 1));
        double tVecX = targetVec.getX() - currentPos.getX();
        double tVecY = targetVec.getY() - currentPos.getY();
        double tVecZ = targetVec.getZ() - currentPos.getZ();
        double hLen = Math.sqrt(tVecX * tVecX + tVecZ * tVecZ);
        float pitch = (float) (-Math.toDegrees(Math.atan2(tVecY, hLen))) / 1.5f;

        return new float[]{yaw, pitch};
    }

    /**
     * Normalizes an angle to be between -180 and 180 degrees.
     *
     * @param angle The angle to normalize
     * @return The normalized angle
     */
    private float normalizeAngle(float angle)
    {
        while(angle > 180)
            angle -= 360;
        while(angle < -180)
            angle += 360;
        return angle;
    }

    /**
     * Checks whether this task allows automatic walking viewer management.
     *
     * @return true if viewers can be added automatically; false otherwise
     */
    public boolean isAutoManageWalkingViewers()
    {
        return autoManageWalkingViewers;
    }

    /**
     * Gets the NPC's current walking location.
     *
     * @return the current walking location
     */
    public @NotNull Location getCurrentLocation()
    {
        return currentPos.toLocation(npc.getLocation().getWorld());
    }

    /**
     * Adds a viewer to this path task and immediately syncs the NPC's current walking position.
     *
     * @param player the player to add
     * @return true if the player was newly added; false if the player was already a viewer
     */
    public boolean addViewer(@NotNull Player player)
    {
        if(finished)
            return false;

        if(!viewerIds.add(player.getUniqueId()))
            return false;

        cachedViewers = null;
        sendCurrentPosition(player);
        return true;
    }

    /**
     * Checks whether the specified player is already attached to this path task.
     *
     * @param player the player to check
     * @return true if the player is already a viewer of this walking task; false otherwise
     */
    public boolean hasViewer(@NotNull Player player)
    {
        return viewerIds.contains(player.getUniqueId());
    }

    /**
     * Removes a viewer from this path task.
     *
     * @param player the player to remove
     */
    public void removeViewer(@NotNull Player player)
    {
        viewerIds.remove(player.getUniqueId());
        cachedViewers = null;
    }

    /**
     * Gets all online viewers currently attached to this path task.
     *
     * @return online viewers
     */
    private Player @NotNull [] getViewers()
    {
        if(cachedViewers != null)
            return cachedViewers;

        cachedViewers = viewerIds.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toArray(Player[]::new);
        return cachedViewers;
    }

    /**
     * Sends the NPC's current walking position and rotation to a viewer.
     *
     * @param player the viewer to sync
     */
    private void sendCurrentPosition(@NotNull Player player)
    {
        if(serverEntity == null)
            return;

        ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(
                serverEntity,
                (byte) (previousYaw * 256 / 360)
        );

        Vec3 currentVec = new Vec3(currentPos.getX(), currentPos.getY(), currentPos.getZ());

        ClientboundTeleportEntityPacket teleport = (ClientboundTeleportEntityPacket) TeleportEntityPacket.create(serverEntity, currentVec, Vec3.ZERO,
                previousYaw, previousPitch, Set.of(), true);

        npc.sendNpcMovePackets(teleport, head, player);
    }

    /**
     * Sends movement and rotation packets to update the NPC's position for viewers.
     *
     * @param movement The movement vector
     * @param yaw      The yaw rotation
     * @param pitch    The pitch rotation
     * @param onGround Whether the NPC is on the ground
     */
    private void sendMovePackets(Vector movement, float yaw, float pitch, boolean onGround)
    {
        if(serverEntity == null)
            return;

        previousYaw = yaw;
        previousPitch = pitch;

        ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(serverEntity, (byte) (yaw * 256 / 360));

        Vec3 currentVec = new Vec3(currentPos.getX(), currentPos.getY(), currentPos.getZ());
        Vec3 movementVec = new Vec3(movement.getX(), movement.getY(), movement.getZ());

        ClientboundTeleportEntityPacket teleport = (ClientboundTeleportEntityPacket) TeleportEntityPacket.create(serverEntity, currentVec, movementVec,
                yaw, pitch, Set.of(), onGround);

        npc.sendNpcMovePackets(teleport, head, getViewers());

        if(updateRealLocation)
            npc.setLocation(currentPos.toLocation(npc.getLocation().getWorld()));
    }

    /**
     * Cancels the path task and cleans up resources. Calls the callback with CANCELLED status if not already finished.
     *
     * @throws IllegalStateException if the task was already canceled
     */
    public synchronized void cancel() throws IllegalStateException
    {
        if(finished)
        {
            if(task != null)
            {
                task.cancel();
                task = null;
            }
            return;
        }

        finished = true;
        forceCloseAllDoors();

        if(task != null)
        {
            task.cancel();
            task = null;
        }

        if(callback != null)
            callback.accept(WalkingResult.CANCELLED);

        if(!NpcApi.plugin.isEnabled())
        {
            npc.clearWalkingTask(this);
            return;
        }

        NpcStopWalkingEvent event = new NpcStopWalkingEvent(npc, WalkingResult.CANCELLED, updateRealLocation);
        Bukkit.getPluginManager().callEvent(event);

        if(event.changeRealLocation())
        {
            World world = path.getWaypoints().isEmpty() ? pathPoints.getLast().getWorld() :
                    path.getWaypoints().getLast().getWorld();
            Location loc = new Location(world, currentPos.getX(), currentPos.getY(), currentPos.getZ());
            if(!ensureOnSolidGround())
                npc.changeRealLocation(loc, getViewers());
        }
        npc.clearWalkingTask(this);
    }

    /**
     * Checks if the NPC is currently standing on solid ground. If not, finds the nearest solid floor beneath it and snaps the NPC there.
     *
     * @return true if the location was adjusted; false otherwise
     */
    public boolean ensureOnSolidGround()
    {
        Location location = getCurrentLocation();
        if(location == null || location.getWorld() == null)
            return false;

        BoundingBoxPathfinder.FootSupport support = BoundingBoxPathfinder.resolveGroundSupport(location.getWorld(), location.getX(), location.getY(), location.getZ(), entityWidth);
        if(support.valid())
        {
            Location grounded = findSolidGroundBeneath(location);
            if(grounded != null)
            {
                npc.changeRealLocation(grounded);
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the nearest solid floor beneath the given location.
     *
     * @param start the starting search location
     * @return grounded location, or null if no floor was found
     */
    public @Nullable Location findSolidGroundBeneath(@NotNull Location start)
    {
        World world = start.getWorld();
        if(world == null)
            return null;

        try
        {
            BoundingBoxPathfinder.FootSupport support = BoundingBoxPathfinder.resolveGroundSupport(world, start.getX(), start.getY(), start.getZ(),
                    entityWidth);
            if(support.valid())
            {
                double targetY = support.feetY();
                if(isLocationCollisionFree(world, start.getX(), targetY, start.getZ()))
                {
                    Location grounded = start.clone();
                    grounded.setY(targetY);
                    return grounded;
                }
            }
        }
        catch(Exception e)
        {
            return null;
        }

        return null;
    }

    /**
     * Checks if a location is collision-free.
     *
     * @param world the world
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @return true if the location is collision-free, false otherwise
     */
    private boolean isLocationCollisionFree(@NotNull World world, double x, double y, double z)
    {
        if(bypassCollisionChecks)
            return true;

        double scale = npc.getOption(NpcOption.SCALE);
        double entityHeight = serverEntity.getBoundingBox().getYsize() * scale;
        double radius = entityWidth / 2.0;

        BoundingBox box = new BoundingBox(
                x - radius + 0.001, y + 0.001, z - radius + 0.001,
                x + radius - 0.001, y + entityHeight - 0.001, x + radius - 0.001
        );

        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for(int bx = minX; bx <= maxX; bx++)
        {
            for(int by = minY; by <= maxY; by++)
            {
                for(int bz = minZ; bz <= maxZ; bz++)
                {
                    Block block = world.getBlockAt(bx, by, bz);
                    if(!block.isEmpty() && !block.isPassable())
                    {
                        for(BoundingBox bb : AbstractPathfinder.getBlockBoxes(block))
                        {
                            BoundingBox worldBB = bb.clone().shift(bx, by, bz);
                            if(box.overlaps(worldBB))
                                return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Checks if the NPC is inside a block.
     *
     * @return true if the NPC is inside a block
     */
    private boolean isInsideBlock()
    {
        World world = npc.getLocation().getWorld();
        if(world == null)
            return false;

        double scale = npc.getOption(NpcOption.SCALE);
        double entityHeight = serverEntity.getBoundingBox().getYsize() * scale;
        double radius = entityWidth / 2.0;

        BoundingBox box = new BoundingBox(
                currentPos.getX() - radius + 0.001, currentPos.getY() + 0.001, currentPos.getZ() - radius + 0.001,
                currentPos.getX() + radius - 0.001, currentPos.getY() + entityHeight - 0.001, currentPos.getZ() + radius - 0.001
        );

        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for(int bx = minX; bx <= maxX; bx++)
        {
            for(int by = minY; by <= maxY; by++)
            {
                for(int bz = minZ; bz <= maxZ; bz++)
                {
                    Block block = world.getBlockAt(bx, by, bz);
                    if(!block.isEmpty() && !block.isPassable())
                    {
                        for(BoundingBox bb : AbstractPathfinder.getBlockBoxes(block))
                        {
                            BoundingBox worldBB = bb.clone().shift(bx, by, bz);
                            if(box.overlaps(worldBB))
                                return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Detects if the NPC is stuck and handles recovery by temporarily bypassing collision checks.
     *
     */
    private void detectAndHandleStuck()
    {
        positionHistory[positionHistoryIndex] = currentPos.clone();
        positionHistoryIndex = (positionHistoryIndex + 1) % STUCK_DETECTION_TICKS;

        boolean insideBlock = isInsideBlock();

        if(insideBlock)
        {
            bypassCollisionChecks = true;
            stuckCounter = 0;
            return;
        }

        int oldestIndex = positionHistoryIndex;
        Vector oldestPos = positionHistory[oldestIndex];
        if(oldestPos == null)
            return;

        double distanceMoved = currentPos.distance(oldestPos);
        if(distanceMoved < STUCK_THRESHOLD)
        {
            stuckCounter++;
            if(stuckCounter >= 3)
                bypassCollisionChecks = true;
        }
        else
        {
            if(bypassCollisionChecks)
            {
                if(!insideBlock && distanceMoved > STUCK_THRESHOLD * 4)
                {
                    stuckCounter = 0;
                    bypassCollisionChecks = false;
                }
            }
            else
                stuckCounter = 0;
        }
    }

    /**
     * Checks if the path task has been completed.
     *
     * @return true if the task is finished, false otherwise
     */
    public boolean isFinished()
    {
        return finished;
    }

    /**
     * A record representing the result of physics calculations.
     *
     * @param yChange    The vertical movement to apply
     * @param isGrounded Whether the NPC is on the ground
     */
    private record PhysicsResult(double yChange, boolean isGrounded) {}

    // --- Builder Class ---

    /**
     * Builder class for creating PathTask instances with a fluent API. Allows for optional configuration of the path task.
     */
    public static class Builder
    {
        private final NPC npc;
        private final Path path;

        private Player[] viewers = null;
        private Consumer<WalkingResult> callback = null;
        private double speed = 0.25;
        private boolean updateRealLocation = false;
        private boolean withRotation = true;
        private boolean autoManageWalkingViewers = false;

        /**
         * Creates a new Builder for a PathTask.
         *
         * @param npc  The NPC that will follow the path
         * @param path The path for the NPC to follow
         */
        public Builder(@NotNull NPC npc, @NotNull Path path)
        {
            this.npc = npc;
            this.path = path;
        }

        /**
         * Sets the viewers who can see the NPC's movement.
         *
         * @param viewers Array of players who can see the NPC
         * @return This builder instance for method chaining
         */
        public @NotNull Builder viewers(@Nullable Player... viewers)
        {
            this.viewers = viewers;
            return this;
        }

        /**
         * Sets whether this path task should allow automatic walking viewer management.
         * <p>
         * When enabled, external library listeners may add eligible players to this task while the NPC is already walking.
         *
         * @param autoManageWalkingViewers true to allow automatic walking viewer management, false otherwise
         * @return This builder instance for method chaining
         */
        public @NotNull Builder autoManageWalkingViewers(boolean autoManageWalkingViewers)
        {
            this.autoManageWalkingViewers = autoManageWalkingViewers;
            return this;
        }

        /**
         * Sets the movement speed of the NPC.
         *
         * @param speed The movement speed (blocks per tick)
         * @return This builder instance for method chaining
         */
        public @NotNull Builder speed(double speed)
        {
            this.speed = speed;
            return this;
        }

        /**
         * Sets whether to update the NPC's actual location after movement.
         *
         * @param update true to update the NPC's real location, false otherwise
         * @return This builder instance for method chaining
         */
        public @NotNull Builder updateRealLocation(boolean update)
        {
            this.updateRealLocation = update;
            return this;
        }

        /**
         * Sets the callback to be executed when the path is completed or canceled.
         *
         * @param callback The callback to execute
         * @return This builder instance for method chaining
         */
        public @NotNull Builder callback(@Nullable Consumer<WalkingResult> callback)
        {
            this.callback = callback;
            return this;
        }

        /**
         * Sets whether to include rotation packets in the movement.
         *
         * @param withRotation true to include rotation packets, false otherwise
         * @return This builder instance for method chaining
         */
        public @NotNull Builder withRotation(boolean withRotation)
        {
            this.withRotation = withRotation;
            return this;
        }

        /**
         * Builds and returns a new PathTask instance.
         *
         * @return A new PathTask with the configured settings
         */
        public @NotNull PathTask build()
        {
            return new PathTask(this);
        }
    }

    /**
     * A record containing the PathTask and BukkitTask.
     */
    public record WalkToResult(@NotNull PathTask pathTask, @NotNull PluginTask movementTask)
    {
    }
}