package de.eisi05.npc.api.scheduler;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.events.NpcStopWalkingEvent;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.pathfinding.BoundingBoxPathfinder;
import de.eisi05.npc.api.pathfinding.Path;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * A task that handles the movement of an NPC along a calculated path. This class extends BukkitRunnable to handle the movement in a scheduled task, providing
 * smooth movement, physics, and door interaction capabilities.
 */
public class PathTask extends BukkitRunnable
{
    private static final double GRAVITY = -0.08;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double TERMINAL_VELOCITY = -0.5;
    private static final double STEP_HEIGHT = 0.55;

    private final NPC npc;
    private final double entityWidth;
    private final Path path;
    private final List<Location> pathPoints;
    private final Set<UUID> viewerIds = new HashSet<>();
    private final boolean autoManageWalkingViewers;
    private final Entity serverEntity;
    private final Consumer<WalkingResult> callback;
    private final boolean withRotation;

    private final double speed;
    private final boolean updateRealLocation;

    private final Set<Block> openedDoors = new HashSet<>();
    private final Vector previousMoveDir;

    private boolean finished = false;
    private int index = 0;
    private Vector currentPos;
    private float previousPitch;
    private float previousYaw;
    private double verticalVelocity = 0.0;
    private int viewerRefreshTicks = 0;
    private boolean isWaitingForChunkLoad = false;

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
    }

    /**
     * The main execution method called by the Bukkit scheduler. Handles the NPC's movement along the path, including physics and door interactions.
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

        if(index >= pathPoints.size())
        {
            if(finishPath())
                return;
        }

        Vector target = pathPoints.get(index).toVector();
        Vector toTarget = target.clone().subtract(currentPos);

        if(hasReachedWaypoint(toTarget))
        {
            index++;
            return;
        }

        int chunkX = currentPos.getBlockX() >> 4;
        int chunkZ = currentPos.getBlockZ() >> 4;
        boolean isChunkLoaded = world.isChunkLoaded(chunkX, chunkZ);

        if(isChunkLoaded)
        {
            processDoors();
            cleanupDoors();

            Vector movement = calculateHorizontalMovement(toTarget, target);

            if(movement.lengthSquared() < 1e-6 && index < pathPoints.size() && currentPos.equals(target))
                return;

            PhysicsResult physics = applyPhysics(movement);
            movement.setY(physics.yChange);
            currentPos.add(movement);

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
    }

    /**
     * Handles the logic when an NPC encounters an unloaded chunk.
     */
    private void handleUnloadedChunk(World world, int chunkX, int chunkZ)
    {
        if(NpcApi.config.loadChunksOnPath() && !isWaitingForChunkLoad)
        {
            isWaitingForChunkLoad = true;
            world.getChunkAt(chunkX, chunkZ);
            isWaitingForChunkLoad = false;
        }
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

        if(index < pathPoints.size())
        {
            Location next = pathPoints.get(index);
            if(currentPos.distanceSquared(next.toVector()) < 4.0)
            {
                checkAndOpenDoor(next.getBlock());
                checkAndOpenDoor(next.getBlock().getRelative(BlockFace.UP));
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
        double verticalDiff = Math.abs(toTarget.getY());
        return horizontalDistSq <= 0.04 && verticalDiff < 0.5;
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
        Vector horizontal = new Vector(toTarget.getX(), 0, toTarget.getZ());
        double distSq = horizontal.lengthSquared();
        if(distSq < 1e-6)
            return new Vector(0, 0, 0);

        double dist = Math.sqrt(distSq);
        double currentSpeed = speed;
        double yDiff = targetPoint.getY() - currentPos.getY();
        if(yDiff > STEP_HEIGHT && currentPos.getY() < targetPoint.getY())
            currentSpeed *= 0.6;

        double moveDistance = Math.min(currentSpeed, dist);
        Vector moveStep = horizontal.clone().normalize().multiply(moveDistance);

        if(Math.abs(moveDistance - dist) < 1e-6)
        {
            this.currentPos.setX(targetPoint.getX());
            this.currentPos.setZ(targetPoint.getZ());
            index++;
            return new Vector(0, 0, 0);
        }

        return moveStep;
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

        Vector stepTarget = currentPos.clone().add(movement);
        double targetGroundY = getGroundY(world, stepTarget);

        Location targetWaypoint = pathPoints.get(Math.min(index, pathPoints.size() - 1));
        if(targetGroundY < targetWaypoint.getY() - STEP_HEIGHT)
            targetGroundY = targetWaypoint.getY();

        double currentGroundY = getGroundY(world, currentPos);
        boolean onGround = currentPos.getY() <= currentGroundY + 1e-5;
        double yChange;

        if(onGround)
        {
            double yDiff = targetGroundY - currentPos.getY();

            if(yDiff <= 0 && yDiff >= -STEP_HEIGHT)
            {
                verticalVelocity = 0;
                return new PhysicsResult(yDiff, true);
            }

            if(yDiff > 0 && yDiff <= STEP_HEIGHT)
            {
                verticalVelocity = 0;
                return new PhysicsResult(yDiff, true);
            }

            if(yDiff > STEP_HEIGHT)
            {
                verticalVelocity = JUMP_VELOCITY;
                return new PhysicsResult(JUMP_VELOCITY, false);
            }

            verticalVelocity += GRAVITY;
            if(verticalVelocity < TERMINAL_VELOCITY)
                verticalVelocity = TERMINAL_VELOCITY;

            return new PhysicsResult(verticalVelocity, false);
        }
        else
        {
            verticalVelocity += GRAVITY;
            if(verticalVelocity < TERMINAL_VELOCITY)
                verticalVelocity = TERMINAL_VELOCITY;

            yChange = verticalVelocity;
            if(currentPos.getY() + yChange <= targetGroundY + 1e-5)
            {
                yChange = targetGroundY - currentPos.getY();
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
        BoundingBoxPathfinder.FootSupport support = BoundingBoxPathfinder.resolveGroundSupport(world, pos.getX(), pos.getY(), pos.getZ(), entityWidth);

        if(support.valid())
            return support.feetY();

        Location currentWaypoint = pathPoints.get(Math.min(index, pathPoints.size() - 1));
        double highestY = world.getHighestBlockYAt(pos.getBlockX(), pos.getBlockZ());

        if(Math.abs(currentWaypoint.getX() - pos.getX()) < 1.0 && Math.abs(currentWaypoint.getZ() - pos.getZ()) < 1.0)
        {
            if(highestY < currentWaypoint.getY() - 1.0)
                return currentWaypoint.getY();
        }

        return highestY;
    }

    /**
     * Handles the completion of the path. Performs final cleanup and calls the completion callback.
     *
     * @return true if the path was successfully finished, false otherwise
     */
    private boolean finishPath()
    {
        Location last = path.getWaypoints().isEmpty() ? null : path.getWaypoints().getLast();
        if(last != null)
        {
            if(currentPos.distanceSquared(last.toVector()) > 0.04)
            {
                pathPoints.add(last);
                return false;
            }

            smoothEndRotation(last);
        }

        finished = true;
        forceCloseAllDoors();

        if(callback != null)
            callback.accept(WalkingResult.SUCCESS);

        NpcStopWalkingEvent event = new NpcStopWalkingEvent(npc, WalkingResult.SUCCESS, updateRealLocation);
        Bukkit.getPluginManager().callEvent(event);

        if(event.changeRealLocation())
        {
            Location loc = path.getWaypoints().isEmpty() ? pathPoints.getLast() : path.getWaypoints().getLast();
            npc.changeRealLocation(loc, getViewers());
        }

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
        Vector lookDir;
        if(index + 1 < pathPoints.size())
        {
            Vector p1 = pathPoints.get(index).toVector();
            Vector p2 = pathPoints.get(index + 1).toVector();
            lookDir = p1.add(p2).multiply(0.5).subtract(currentPos);
        }
        else
            lookDir = pathPoints.get(Math.min(index, pathPoints.size() - 1)).toVector().subtract(currentPos);

        Vector horizontalLook = new Vector(lookDir.getX(), 0, lookDir.getZ());
        if(horizontalLook.lengthSquared() < 1e-6)
            horizontalLook.copy(previousMoveDir.clone());

        float targetYaw = (float) (Math.toDegrees(Math.atan2(horizontalLook.getZ(), horizontalLook.getX())) - 90);
        targetYaw = normalizeAngle(targetYaw);

        float diff = normalizeAngle(targetYaw - previousYaw);
        diff = Math.clamp(diff, -15f, 15f);

        float yaw = previousYaw + diff;
        previousYaw = yaw;
        previousMoveDir.copy(horizontalLook);

        Vector targetVec = pathPoints.get(Math.min(index + 1, pathPoints.size() - 1)).toVector().subtract(currentPos);
        double hLen = Math.sqrt(targetVec.getX() * targetVec.getX() + targetVec.getZ() * targetVec.getZ());
        float pitch = (float) (-Math.toDegrees(Math.atan2(targetVec.getY(), hLen))) / 1.5f;

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
        World world = npc.getLocation().getWorld();
        return currentPos.toLocation(world);
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
    }

    /**
     * Gets all online viewers currently attached to this path task.
     *
     * @return online viewers
     */
    private Player @NotNull [] getViewers()
    {
        return viewerIds.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toArray(Player[]::new);
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
    @Override
    public synchronized void cancel() throws IllegalStateException
    {
        if(finished)
        {
            super.cancel();
            return;
        }

        finished = true;
        forceCloseAllDoors();
        super.cancel();

        if(callback != null)
            callback.accept(WalkingResult.CANCELLED);

        NpcStopWalkingEvent event = new NpcStopWalkingEvent(npc, WalkingResult.CANCELLED, updateRealLocation);
        Bukkit.getPluginManager().callEvent(event);

        if(event.changeRealLocation())
        {
            World world = path.getWaypoints().isEmpty() ? pathPoints.getLast().getWorld() :
                    path.getWaypoints().getLast().getWorld();
            Location loc = new Location(world, currentPos.getX(), currentPos.getY(), currentPos.getZ());
            npc.changeRealLocation(loc, getViewers());
        }
        npc.clearWalkingTask(this);
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
}