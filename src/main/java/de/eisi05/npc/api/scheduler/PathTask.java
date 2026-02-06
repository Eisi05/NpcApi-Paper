package de.eisi05.npc.api.scheduler;

import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.events.NpcStopWalkingEvent;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.utils.Var;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * A task that handles the movement of an NPC along a calculated path.
 * This class extends BukkitRunnable to handle the movement in a scheduled task,
 * providing smooth movement, physics, and door interaction capabilities.
 */
public class PathTask extends BukkitRunnable
{
    private static final double gravity = -0.08;
    private static final double jumpVelocity = 0.5;
    private static final double terminalVelocity = -0.5;
    private static final double stepHeight = -0.5;

    private final NPC npc;
    private final Path path;
    private final List<Location> pathPoints;
    private final Player[] viewers;
    private final ServerPlayer serverEntity;
    private final Consumer<WalkingResult> callback;

    // Settings
    private final double speed;
    private final boolean updateRealLocation;

    // State
    private boolean finished = false;
    private int index = 0;
    private Vector currentPos;
    private Vector previousMoveDir;
    private float previousYaw;
    private double verticalVelocity = 0.0;

    private final Set<Block> openedDoors = new HashSet<>();

    /**
     * Private constructor used by the Builder pattern.
     *
     * @param builder The builder containing all necessary parameters
     */
    private PathTask(@NotNull Builder builder)
    {
        this.npc = builder.npc;
        this.path = builder.path;
        this.pathPoints = new ArrayList<>(builder.path.asLocations());
        this.viewers = builder.viewers;
        this.callback = builder.callback;

        this.speed = builder.speed;
        this.updateRealLocation = builder.updateRealLocation;

        this.currentPos = npc.getLocation().toVector();
        this.previousYaw = npc.getLocation().getYaw();
        this.previousMoveDir = npc.getLocation().getDirection();
        this.serverEntity = (ServerPlayer) npc.getServerPlayer();
    }

    /**
     * The main execution method called by the Bukkit scheduler.
     * Handles the NPC's movement along the path, including physics and door interactions.
     */
    @Override
    public void run()
    {
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

        processDoors();
        cleanupDoors();

        Vector movement = calculateHorizontalMovement(toTarget, target);

        if(movement.lengthSquared() < 1e-6 && index < pathPoints.size() && currentPos.equals(target))
            return;

        PhysicsResult physics = applyPhysics(movement, toTarget);

        movement.setY(physics.yChange);

        currentPos.add(movement);

        float[] rotation = calculateSmoothRotation();
        float yaw = rotation[0];
        float pitch = rotation[1];

        sendMovePackets(movement, yaw, pitch, physics.isGrounded);
    }

    /**
     * Processes door interactions along the NPC's path.
     * Opens doors that are in the NPC's path and within interaction range.
     */
    private void processDoors() {
        World world = npc.getLocation().getWorld();
        if(world == null)
            return;

        checkAndOpenDoor(currentPos.toLocation(world).getBlock());
        checkAndOpenDoor(currentPos.toLocation(world).getBlock().getRelative(BlockFace.UP));

        if (index < pathPoints.size()) {
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
     * Cleans up opened doors that are no longer near the NPC.
     * Closes doors that the NPC has moved away from.
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
     * Forces all doors opened by this path task to close.
     * Used when the path is completed or cancelled.
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
     * Handles the completion of the path.
     * Performs final cleanup and calls the completion callback.
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
            else
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
            npc.changeRealLocation(loc, viewers);
        }

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
        ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(serverEntity.getId(),
                new PositionMoveRotation(vec, vec, loc.getYaw(), loc.getPitch()), Set.of(), true);

        npc.sendNpcMovePackets(teleport, head, viewers);
        npc.sendNpcBodyPackets(body, viewers);
    }

    /**
     * Checks if the NPC has reached the current waypoint.
     *
     * @param toTarget The vector to the target waypoint
     * @return true if the waypoint has been reached, false otherwise
     */
    private boolean hasReachedWaypoint(@NotNull Vector toTarget)
    {
        return toTarget.lengthSquared() < 0.04 && Math.abs(toTarget.getY()) < 0.2;
    }

    /**
     * Calculates the horizontal movement vector for the NPC.
     *
     * @param toTarget The vector to the target waypoint
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
        double moveDistance = Math.min(speed, dist);
        Vector moveStep = horizontal.clone().normalize().multiply(moveDistance);

        if(Math.abs(moveDistance - dist) < 1e-6)
        {
            this.currentPos = targetPoint.clone();
            index++;
            return new Vector(0, 0, 0);
        }

        return moveStep;
    }

    /**
     * Applies physics (gravity, jumping, collision) to the NPC's movement.
     *
     * @param movement The current movement vector
     * @param toTarget The vector to the target waypoint
     * @return A PhysicsResult containing the vertical movement and ground state
     */
    private @NotNull PhysicsResult applyPhysics(Vector movement, Vector toTarget)
    {
        World world = npc.getLocation().getWorld();
        if(world == null)
            return new PhysicsResult(0, false);

        double groundY = getGroundY(world, currentPos);
        boolean onGround = currentPos.getY() <= groundY + 1e-5;
        double yChange = 0;

        if(onGround)
        {
            if(toTarget.getY() > 0 && toTarget.getY() <= stepHeight && movement.lengthSquared() > 1e-6)
            {
                yChange = Math.min(toTarget.getY(), stepHeight);
                verticalVelocity = 0;
                return new PhysicsResult(yChange, true);
            }
            else if(toTarget.getY() > 0.5)
            {
                verticalVelocity = jumpVelocity;
                onGround = false;
            }
            else
            {
                verticalVelocity = 0;
                if(Math.abs(currentPos.getY() - groundY) > 1e-6)
                    currentPos.setY(groundY);
                return new PhysicsResult(0, true);
            }
        }

        if(!onGround)
        {
            verticalVelocity += gravity;
            if(verticalVelocity < terminalVelocity)
                verticalVelocity = terminalVelocity;
            yChange = verticalVelocity;

            if(currentPos.getY() + yChange <= groundY)
            {
                yChange = groundY - currentPos.getY();
                verticalVelocity = 0;
                onGround = true;
            }
        }

        return new PhysicsResult(yChange, onGround);
    }

    /**
     * Calculates the Y-coordinate of the ground at a given position.
     *
     * @param world The world to check in
     * @param pos The position to check
     * @return The Y-coordinate of the ground
     */
    private double getGroundY(@NotNull World world, @NotNull Vector pos)
    {
        int bx = pos.getBlockX();
        int bz = pos.getBlockZ();
        int startY = pos.getBlockY();

        for(int y = startY; y >= startY - 3; y--)
        {
            Block block = world.getBlockAt(bx, y, bz);

            if(block.getBlockData() instanceof Openable)
                continue;

            if(Var.isCarpet(block.getType()) && Var.isCarpet(block.getRelative(BlockFace.UP).getType()))
                return ++y;

            if(!block.getType().isSolid() || block.isPassable())
                return y;

            OptionalDouble maxY = block.getCollisionShape().getBoundingBoxes().stream().mapToDouble(BoundingBox::getMaxY).max();
            OptionalDouble minY = block.getCollisionShape().getBoundingBoxes().stream().mapToDouble(BoundingBox::getMinY).min();

            if(minY.isPresent() && maxY.isPresent())
                return y + minY.getAsDouble() + (maxY.getAsDouble() - minY.getAsDouble());
        }

        return world.getHighestBlockYAt(bx, bz);
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
            horizontalLook = previousMoveDir.clone();

        float targetYaw = (float) (Math.toDegrees(Math.atan2(horizontalLook.getZ(), horizontalLook.getX())) - 90);
        targetYaw = normalizeAngle(targetYaw);

        float diff = normalizeAngle(targetYaw - previousYaw);
        diff = Math.max(-15f, Math.min(15f, diff));

        float yaw = previousYaw + diff;
        previousYaw = yaw;
        previousMoveDir = horizontalLook;

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
     * Sends movement and rotation packets to update the NPC's position for viewers.
     *
     * @param movement The movement vector
     * @param yaw The yaw rotation
     * @param pitch The pitch rotation
     * @param onGround Whether the NPC is on the ground
     */
    private void sendMovePackets(Vector movement, float yaw, float pitch, boolean onGround)
    {
        if(serverEntity == null)
            return;

        ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(serverEntity, (byte) (yaw * 256 / 360));

        Vec3 currentVec = new Vec3(currentPos.getX(), currentPos.getY(), currentPos.getZ());
        Vec3 movementVec = new Vec3(movement.getX(), movement.getY(), movement.getZ());

        ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(serverEntity.getId(),
                new PositionMoveRotation(currentVec, movementVec, yaw, pitch), Set.of(), onGround);

        npc.sendNpcMovePackets(teleport, head, viewers);
    }

    /**
     * Cancels the path task and cleans up resources.
     * Calls the callback with CANCELLED status if not already finished.
     *
     * @throws IllegalStateException if the task was already cancelled
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
            World world = path.getWaypoints().isEmpty() ? pathPoints.getLast().getWorld() : path.getWaypoints().getLast().getWorld();
            Location loc = new Location(world, currentPos.getX(), currentPos.getY(), currentPos.getZ());
            npc.changeRealLocation(loc, viewers);
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
     * @param yChange The vertical movement to apply
     * @param isGrounded Whether the NPC is on the ground
     */
    private record PhysicsResult(double yChange, boolean isGrounded) {}

    // --- Builder Class ---

    /**
     * Builder class for creating PathTask instances with a fluent API.
     * Allows for optional configuration of the path task.
     */
    public static class Builder
    {
        private final NPC npc;
        private final Path path;

        private Player[] viewers = null;
        private Consumer<WalkingResult> callback = null;
        private double speed = 1.0;
        private boolean updateRealLocation = false;

        /**
         * Creates a new Builder for a PathTask.
         *
         * @param npc The NPC that will follow the path
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
         * Sets the callback to be executed when the path is completed or cancelled.
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
