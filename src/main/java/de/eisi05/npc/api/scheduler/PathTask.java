package de.eisi05.npc.api.scheduler;

import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.events.NpcStopWalkingEvent;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.Path;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

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

    // Door State Tracking
    private final Set<Block> openedDoors = new HashSet<>();

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

    private void processDoors() {
        World world = npc.getLocation().getWorld();
        if (world == null) return;

        // Check current position (Feet and Head)
        checkAndOpenDoor(currentPos.toLocation(world).getBlock());
        checkAndOpenDoor(currentPos.toLocation(world).getBlock().getRelative(BlockFace.UP));

        // Check next target position (if close enough to interact)
        if (index < pathPoints.size()) {
            Location next = pathPoints.get(index);
            if (currentPos.distanceSquared(next.toVector()) < 4.0) { // 2 block reach
                checkAndOpenDoor(next.getBlock());
                checkAndOpenDoor(next.getBlock().getRelative(BlockFace.UP));
            }
        }
    }

    private void checkAndOpenDoor(Block block) {
        if (block.getBlockData() instanceof Openable openable) {
            if (!openable.isOpen()) {
                openable.setOpen(true);
                block.setBlockData(openable);
                block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);

                openedDoors.add(block);
            }
        }
    }

    private void cleanupDoors() {
        if (openedDoors.isEmpty()) return;

        Iterator<Block> iterator = openedDoors.iterator();
        while (iterator.hasNext()) {
            Block door = iterator.next();
            if (!(door.getBlockData() instanceof Openable openable)) {
                iterator.remove();
                continue;
            }

            double distSq = Math.pow(door.getX() + 0.5 - currentPos.getX(), 2) + Math.pow(door.getZ() + 0.5 - currentPos.getZ(), 2);

            if (distSq > 1.69) {
                if (openable.isOpen()) {
                    openable.setOpen(false);
                    door.setBlockData(openable);
                    door.getWorld().playSound(door.getLocation(), org.bukkit.Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 1f);
                }
                iterator.remove();
            }
        }
    }

    private void forceCloseAllDoors() {
        for (Block door : openedDoors) {
            if (door.getBlockData() instanceof Openable openable && openable.isOpen()) {
                openable.setOpen(false);
                door.setBlockData(openable);
                door.getWorld().playSound(door.getLocation(), org.bukkit.Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 1f);
            }
        }
        openedDoors.clear();
    }

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

    private void smoothEndRotation(Location loc)
    {
        if(serverEntity == null)
            return;

        ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(serverEntity, (byte) (loc.getYaw() * 256 / 360));
        ClientboundMoveEntityPacket.Rot body = new ClientboundMoveEntityPacket.Rot(serverEntity.getId(), (byte) (loc.getYaw() * 256 / 360),
                (byte) (loc.getPitch() * 256 / 360), true);

        npc.sendNpcMovePackets(null, head, viewers);
        npc.sendNpcBodyPackets(body, viewers);
    }

    private boolean hasReachedWaypoint(@NotNull Vector toTarget)
    {
        return toTarget.lengthSquared() < 0.04 && Math.abs(toTarget.getY()) < 0.2;
    }

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

    private double getGroundY(@NotNull World world, @NotNull Vector pos)
    {
        int bx = pos.getBlockX();
        int bz = pos.getBlockZ();
        int startY = pos.getBlockY();

        for(int y = startY; y >= startY - 3; y--)
        {
            Block block = world.getBlockAt(bx, y, bz);

            if(block.getBlockData() instanceof Openable)
            {
                continue;
            }

            if(Tag.WOOL_CARPETS.isTagged(block.getType()) && Tag.WOOL_CARPETS.isTagged(block.getRelative(BlockFace.UP).getType()))
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

    private float normalizeAngle(float angle)
    {
        while(angle > 180)
            angle -= 360;
        while(angle < -180)
            angle += 360;
        return angle;
    }

    private void sendMovePackets(Vector movement, float yaw, float pitch, boolean onGround)
    {
        if(serverEntity == null)
            return;

        ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(serverEntity, (byte) (yaw * 256 / 360));
        ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(serverEntity.getId(),
                new PositionMoveRotation(new Vec3(currentPos.toVector3f()), new Vec3(movement.toVector3f()), yaw, pitch), Set.of(), onGround);

        npc.sendNpcMovePackets(teleport, head, viewers);
    }

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

        Bukkit.getPluginManager().callEvent(new NpcStopWalkingEvent(npc, WalkingResult.CANCELLED, false));
    }

    public boolean isFinished()
    {
        return finished;
    }

    private record PhysicsResult(double yChange, boolean isGrounded) {}

    // --- Builder Class ---

    public static class Builder
    {
        private final NPC npc;
        private final Path path;

        // Optional / Default Params
        private Player[] viewers = null;
        private Consumer<WalkingResult> callback = null;
        private double speed = 1.0;
        private boolean updateRealLocation = false;

        public Builder(@NotNull NPC npc, @NotNull Path path)
        {
            this.npc = npc;
            this.path = path;
        }

        public @NotNull Builder viewers(@Nullable Player... viewers)
        {
            this.viewers = viewers;
            return this;
        }

        public @NotNull Builder speed(double speed)
        {
            this.speed = speed;
            return this;
        }

        public @NotNull Builder updateRealLocation(boolean update)
        {
            this.updateRealLocation = update;
            return this;
        }

        public @NotNull Builder callback(@Nullable Consumer<WalkingResult> callback)
        {
            this.callback = callback;
            return this;
        }

        public @NotNull PathTask build()
        {
            return new PathTask(this);
        }
    }
}
