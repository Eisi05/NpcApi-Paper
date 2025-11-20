package de.eisi05.npc.api.scheduler;

import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.events.NpcStopWalkingEvent;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.Path;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
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

    private PathTask(@NotNull Builder builder)
    {
        this.npc = builder.npc;
        this.path = builder.path;
        this.pathPoints = builder.path.asLocations();
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
            finishPath();
            return;
        }

        Vector target = pathPoints.get(index).toVector();
        Vector toTarget = target.clone().subtract(currentPos);

        if(hasReachedWaypoint(toTarget))
        {
            index++;
            return;
        }

        Location nextLoc = pathPoints.get(index);
        /*if(isDoorAtLocation(nextLoc))
        {
            if(currentPos.distanceSquared(nextLoc.toVector()) > 1e-6)
                teleportThroughDoor(nextLoc);

            index++;
            return;
        }*/

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

    private void finishPath()
    {
        finished = true;

        Location last = path.getWaypoints().isEmpty() ? null : path.getWaypoints().getLast();

        if(last != null)
        {
            if(currentPos.distanceSquared(last.toVector()) > 0.04)
                forceTeleport(last);
            else
                smoothEndRotation(last);
        }

        if(updateRealLocation)
        {
            Location loc = path.getWaypoints().isEmpty() ? pathPoints.getLast() : path.getWaypoints().getLast();
            npc.changeRealLocation(loc, viewers);
        }

        if(callback != null)
            callback.accept(WalkingResult.SUCCESS);
        Bukkit.getPluginManager().callEvent(new NpcStopWalkingEvent(npc, WalkingResult.SUCCESS, updateRealLocation));
        cancel();
    }

    private void smoothEndRotation(Location loc)
    {
        if(serverEntity == null)
            return;

        ClientboundRotateHeadPacket head =
                new ClientboundRotateHeadPacket(serverEntity, (byte) (loc.getYaw() * 256 / 360));

        npc.sendNpcMovePackets(null, head, viewers);
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

            if(block.getBlockData() instanceof Openable openable)
            {
                openable.setOpen(true);
                block.setBlockData(openable);
                return y;
            }

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

    private void forceTeleport(Location loc)
    {
        if(serverEntity == null)
            return;

        this.currentPos = loc.toVector();
        this.previousYaw = loc.getYaw();
        this.verticalVelocity = 0;

        Vec3 pos = new Vec3(currentPos.toVector3f());
        Vec3 delta = new Vec3(currentPos.clone().subtract(previousMoveDir).toVector3f());
        ClientboundRotateHeadPacket headPacket = new ClientboundRotateHeadPacket(serverEntity, (byte) (loc.getYaw() * 256 / 360));
        ClientboundTeleportEntityPacket teleportPacket = new ClientboundTeleportEntityPacket(serverEntity.getId(),
                new PositionMoveRotation(pos, delta, loc.getYaw(), loc.getPitch()), Set.of(), true);

        npc.sendNpcMovePackets(teleportPacket, headPacket, viewers);
    }

    private boolean isDoorAtLocation(@NotNull Location loc)
    {
        World world = loc.getWorld();
        if(world == null)
            return false;

        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        Block bFeet = world.getBlockAt(bx, by, bz);
        Block bHead = world.getBlockAt(bx, by + 1, bz);

        if(isOpenable(bFeet) || isOpenable(bHead))
            return true;

        Block bFloor = world.getBlockAt(bx, by - 1, bz);
        if(isOpenable(bFloor))
            return true;

        return false;
    }

    private boolean isOpenable(Block block)
    {
        if(block == null)
            return false;
        try
        {
            return block.getBlockData() instanceof Openable;
        } catch(Throwable t)
        {
            return false;
        }
    }

    private void teleportThroughDoor(@NotNull Location loc)
    {
        World world = loc.getWorld();
        if(world == null)
        {
            forceTeleport(loc);
            return;
        }

        Vector tmp = loc.toVector();
        double groundY = getGroundY(world, tmp);

        double finalY = groundY;
        if(loc.getY() > groundY + 0.5)
            finalY = loc.getY();

        Location teleportLoc = new Location(world, loc.getX(), finalY, loc.getZ(), loc.getYaw(), loc.getPitch());

        this.currentPos = teleportLoc.toVector();
        this.previousYaw = teleportLoc.getYaw();
        this.verticalVelocity = 0;

        forceTeleport(teleportLoc);
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
