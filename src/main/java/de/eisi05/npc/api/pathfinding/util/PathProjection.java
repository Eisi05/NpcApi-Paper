package de.eisi05.npc.api.pathfinding.util;

import de.eisi05.npc.api.pathfinding.movement.MoveType;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Geometry helpers for paths that are already being walked.
 */
public final class PathProjection
{
    private static final double LEVEL_TOLERANCE = 0.3;

    private PathProjection()
    {
    }

    /**
     * Returns the position {@code distance} blocks ahead of the NPC along its path. Used to start a <b>replacement</b> path where the NPC will be by the time
     * the search finishes, so the new path does not begin behind the NPC (which makes it step back).
     * <p>
     * Only plain level walking is projected: the projection stops in front of the first point that is reached by a jump, fall, climb, swim, flight or gap jump,
     * or that lies at a different height. If nothing can be projected the current position is returned.
     *
     * @param current   the NPC's current position
     * @param points    the path points
     * @param moves     how each point is reached; if its size differs from {@code points} every move counts as walking
     * @param nextIndex the index of the waypoint the NPC is heading to
     * @param distance  how far ahead to project, in blocks
     * @return the projected position (a new instance)
     */
    public static @NotNull Location advance(@NotNull Location current, @NotNull List<Location> points, @NotNull List<MoveType> moves, int nextIndex,
                                            double distance)
    {
        Location from = current.clone();
        if(distance <= 0 || nextIndex < 1 || nextIndex >= points.size())
            return from;

        boolean typed = moves.size() == points.size();
        double remaining = distance;

        for(int i = nextIndex; i < points.size() && remaining > 1.0E-6; i++)
        {
            if(typed && moves.get(i) != MoveType.WALK)
                break;

            Location to = points.get(i);
            if(to.getWorld() == null || !to.getWorld().equals(from.getWorld()) || Math.abs(to.getY() - from.getY()) > LEVEL_TOLERANCE)
                break;

            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double d = Math.hypot(dx, dz);
            if(d < 1.0E-9)
                continue;

            if(d >= remaining)
            {
                double t = remaining / d;
                return new Location(from.getWorld(), from.getX() + dx * t, from.getY() + (to.getY() - from.getY()) * t, from.getZ() + dz * t);
            }

            remaining -= d;
            from = to.clone();
        }
        return from;
    }
}
