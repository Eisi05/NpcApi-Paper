package de.eisi05.npc.api.pathfinding.movement;

import de.eisi05.npc.api.pathfinding.environment.LiveBlockView;
import de.eisi05.npc.api.pathfinding.environment.NavSpace;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Live-world collision helper for the executor ({@code PathTask}): uses exactly the box collision of the planner ({@link NavSpace#fits}) so an NPC that is in
 * the air (falling off a ledge, jumping up a wall) can never move into a block, it slides along it instead, like vanilla entities do.
 * <p>
 * <b>Region thread only</b> (reads the live world; blocks in chunks the current Folia region does not own count as empty, see
 * {@code LiveBlockView}).
 */
public final class BodyCollider
{
    private BodyCollider()
    {
    }

    /**
     * Restricts a movement of an airborne entity so that its box does not end up inside a block. The vertical part is never changed (landing is handled by
     * the caller); the horizontal part is reduced to the X-only or Z-only component, or removed, if the full move would collide. If the entity already
     * overlaps a block at its current position, the movement is returned unchanged so it can never get stuck.
     *
     * @param world        the world
     * @param x            current centre X
     * @param y            current feet Y
     * @param z            current centre Z
     * @param move         the intended movement (dx, dy, dz)
     * @param entityHeight height of the entity
     * @param entityWidth  width of the entity
     * @return the allowed movement
     */
    public static @NotNull Vector slide(@NotNull World world, double x, double y, double z, @NotNull Vector move, double entityHeight, double entityWidth)
    {
        if(Math.abs(move.getX()) < 1.0E-9 && Math.abs(move.getZ()) < 1.0E-9)
            return move;

        NavSpace space = new NavSpace(new LiveBlockView(world), entityHeight, entityWidth, NavOptions.defaults());
        if(!space.fits(x, y, z, 0))
            return move;

        double ny = y + move.getY();
        if(space.fits(x + move.getX(), ny, z + move.getZ(), 0))
            return move;

        if(Math.abs(move.getX()) > 1.0E-9 && space.fits(x + move.getX(), ny, z, 0))
            return new Vector(move.getX(), move.getY(), 0);

        if(Math.abs(move.getZ()) > 1.0E-9 && space.fits(x, ny, z + move.getZ(), 0))
            return new Vector(0, move.getY(), move.getZ());

        return new Vector(0, move.getY(), 0);
    }
}