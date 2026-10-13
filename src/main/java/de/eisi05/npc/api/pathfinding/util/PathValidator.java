package de.eisi05.npc.api.pathfinding.util;

import de.eisi05.npc.api.pathfinding.environment.LiveBlockView;
import de.eisi05.npc.api.pathfinding.environment.NavSpace;
import de.eisi05.npc.api.pathfinding.environment.WorldAccess;
import de.eisi05.npc.api.pathfinding.movement.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Cheap re-validation of the <b>upcoming part of a path</b> against the current state of the world, to notice blocks that were placed or broken after the
 * path was calculated.
 * <p>
 * Every segment is checked with exactly the same collision rules the planner used ({@link NavSpace}): walking, stepping and jumping segments must still be
 * free for the entity's bounding box and keep ground under them, climbs and swims must still fit, flights must still be clear, and gap jumps are re-simulated
 * tick by tick. One call allocates a single small helper object, so it is fine to run every few ticks per walking NPC.
 * <p>
 * <b>Region thread only</b> (the main thread on Paper, the thread owning the NPC's region on Folia). On Folia the check stops at the first segment that is
 * not completely inside chunks owned by the current region (or not loaded): it cannot be verified from here, so it is reported as clear and is checked later,
 * once the NPC (and with it the task) has moved into that region.
 */
public final class PathValidator
{
    private PathValidator()
    {
    }

    /**
     * Finds the first point in {@code [fromIndex, fromIndex + count)} that can no longer be reached from its predecessor.
     *
     * @param world        the world the path is in
     * @param points       the path points ({@code Path#asLocations()})
     * @param moves        how each point is reached ({@code Path#getMoves()}); if its size differs from {@code points} every move counts as walking
     * @param fromIndex    the first index to check (the waypoint the NPC is heading to)
     * @param count        how many points ahead to check
     * @param horizon      stop after this many blocks of path (measured from the first checked point); keeps long straight segments cheap
     * @param entityHeight height of the entity
     * @param entityWidth  width of the entity
     * @param capabilities what the entity can do, or null for the defaults
     * @return the index of the first blocked point, or -1 if the checked part is still clear
     */
    public static int firstBlocked(@NotNull World world, @NotNull List<Location> points, @NotNull List<MoveType> moves, int fromIndex, int count,
                                   double horizon, double entityHeight, double entityWidth, @Nullable NavCapabilities capabilities)
    {
        if(points.size() < 2 || count <= 0)
            return -1;

        NavCapabilities caps = capabilities == null ? NavCapabilities.DEFAULT : capabilities;
        NavSpace space = new NavSpace(new LiveBlockView(world), entityHeight, entityWidth, caps.restrict(NavOptions.defaults()));
        JumpArc arc = caps.jumpArc();
        NavSpace crouchSpace = space.withHeightRatio(caps.canCrouch() ? caps.crouchRatio() : 1.0);
        boolean aquatic = caps.movement() == MovementMode.AQUATIC;
        boolean typed = moves.size() == points.size();

        int end = Math.min(points.size(), Math.max(0, fromIndex) + count);
        double travelled = 0;
        for(int i = Math.max(1, fromIndex); i < end && travelled < horizon; i++)
        {
            Location prev = points.get(i - 1);
            Location p = points.get(i);
            if(p.getWorld() == null || !p.getWorld().equals(world))
                return i;

            int minX = (int) Math.floor(Math.min(prev.getX(), p.getX())) - 2;
            int maxX = (int) Math.floor(Math.max(prev.getX(), p.getX())) + 2;
            int minZ = (int) Math.floor(Math.min(prev.getZ(), p.getZ())) - 2;
            int maxZ = (int) Math.floor(Math.max(prev.getZ(), p.getZ())) + 2;
            if(!WorldAccess.canAccessArea(world, minX, minZ, maxX, maxZ))
                return -1;

            double length = Math.sqrt(prev.distanceSquared(p));
            double remaining = horizon - travelled;
            travelled += length;

            Location target = p;
            if(length > remaining + 1.0E-9 && length > 1.0E-9)
            {
                double t = remaining / length;
                target = new Location(p.getWorld(), prev.getX() + (p.getX() - prev.getX()) * t, prev.getY() + (p.getY() - prev.getY()) * t,
                        prev.getZ() + (p.getZ() - prev.getZ()) * t);
            }

            MoveType type = typed ? moves.get(i) : MoveType.WALK;
            boolean blocked = switch(type)
            {
                case WALK, JUMP, FALL -> !walkable(space, caps, prev, target);
                case CROUCH -> !walkable(crouchSpace, caps, prev, target);
                case CLIMB -> !space.fits(p.getX(), p.getY(), p.getZ(), 0, space.climbRadius(p.getX(), p.getY(), p.getZ()));
                case SWIM -> aquatic ? !space.line3(prev.getX(), prev.getY(), prev.getZ(), target.getX(), target.getY(), target.getZ(), true, 0)
                        : !space.canTravel(prev.getX(), prev.getY(), prev.getZ(), target.getX(), target.getY(), target.getZ());
                case FLY -> !space.line3(prev.getX(), prev.getY(), prev.getZ(), target.getX(), target.getY(), target.getZ(), false, 0);
                case GAP_JUMP ->
                {
                    int ticks = arc.ticksTo(p.getY() - prev.getY());
                    yield ticks < 0 || !space.arcClear(arc, prev.getX(), prev.getY(), prev.getZ(), p.getX(), p.getY(), p.getZ(), ticks);
                }
            };

            if(blocked)
                return i;
        }
        return -1;
    }

    private static boolean walkable(@NotNull NavSpace space, @NotNull NavCapabilities caps, @NotNull Location prev, @NotNull Location p)
    {
        if(!space.canTravel(prev.getX(), prev.getY(), prev.getZ(), p.getX(), p.getY(), p.getZ()))
            return false;

        if(caps.movement() != MovementMode.GROUND)
            return true;

        if(space.isClimbableAt(p.getX(), p.getY(), p.getZ()) || space.isWaterAt(p.getX(), p.getY(), p.getZ()))
            return true;

        if(Double.isNaN(space.supportTop(p.getX(), p.getZ(), space.getSupportRadius(), p.getY() - 0.35, p.getY() + 0.1, false, 0)))
            return false;

        return space.floorAlong(prev.getX(), prev.getY(), prev.getZ(), p.getX(), p.getY(), p.getZ());
    }
}
