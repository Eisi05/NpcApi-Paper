package de.eisi05.npc.api.pathfinding.util;

import de.eisi05.npc.api.pathfinding.environment.LiveBlockView;
import de.eisi05.npc.api.pathfinding.environment.NavSpace;
import de.eisi05.npc.api.pathfinding.movement.NavCapabilities;
import de.eisi05.npc.api.pathfinding.movement.NavOptions;
import de.eisi05.npc.api.pathfinding.environment.WorldAccess;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Answers "can an entity of this size stand / hover / swim at this position?" with exactly the collision rules the pathfinders use (real collision shapes,
 * hazards, doors, slabs and stairs, the entity's full bounding box). Meant for picking targets, for example by wander goals: a position accepted here fits the
 * entity and is a position the pathfinder can plan to.
 * <p>
 * One probe memoized the blocks it has read, so use one probe for a batch of candidate positions and drop it afterward. <b>Region thread only</b> (reads the
 * live world: the main thread on Paper, the thread owning the probed chunks on Folia; positions in chunks the current thread does not own simply
 * report "nothing to stand on", see {@link #isReadable}).
 */
public final class PlacementProbe
{
    private final NavSpace space;
    private final World world;

    /**
     * Creates a probe for an entity of the given size.
     *
     * @param world        the world to probe
     * @param entityHeight the entity's height (including its scale)
     * @param entityWidth  the entity's width (including its scale)
     * @param capabilities what the entity can do, or null for the defaults
     */
    public PlacementProbe(@NotNull World world, double entityHeight, double entityWidth, @Nullable NavCapabilities capabilities)
    {
        this.world = world;
        NavCapabilities capabilities1 = capabilities == null ? NavCapabilities.DEFAULT : capabilities;
        this.space = new NavSpace(new LiveBlockView(world), entityHeight, entityWidth, capabilities1.restrict(NavOptions.defaults()));
    }

    /**
     * @return true if the current thread may read the blocks around this column (owned by the current region and loaded). Positions for which this is false
     * can never be accepted by this probe on Folia; pick candidates inside the NPC's own region or hop to the region owning the target first.
     */
    public boolean isReadable(double x, double z)
    {
        return WorldAccess.canAccess(world, (int) Math.floor(x), (int) Math.floor(z));
    }

    /**
     * Finds a surface the entity can stand on near a height: the closest one to {@code nearY} (searching up and down in one-block bands) where the whole
     * bounding box fits and which is not a hazard.
     *
     * @param x     X coordinate (centre of the entity)
     * @param nearY the height to search around (typically the entity's current feet height)
     * @param z     Z coordinate (centre of the entity)
     * @param range how many blocks up and down to search
     * @return the exact feet height (supports slabs, stairs, snow layers, ...), or {@link Double#NaN} if there is no such surface
     */
    public double groundY(double x, double nearY, double z, int range)
    {
        for(int k = 0; k <= range; k++)
        {
            for(int sign = 1; sign >= -1; sign -= 2)
            {
                if(k == 0 && sign == -1)
                    continue;

                double y = space.supportTop(x, z, space.getSupportRadius(), nearY + sign * k - 0.5, nearY + sign * k + 0.5, true, 0);
                if(!Double.isNaN(y))
                    return y;
            }
        }
        return Double.NaN;
    }

    /**
     * @return true if the entity fits at this position (free of solid blocks and hazards), e.g. for flying entities
     */
    public boolean fitsAt(double x, double feetY, double z)
    {
        return space.fits(x, feetY, z, 0);
    }

    /**
     * @return true if the entity fits at this position and its body is inside water, as aquatic entities need
     */
    public boolean fitsInWater(double x, double feetY, double z)
    {
        return space.fits3(x, feetY, z, true);
    }

    /**
     * @return true if the entity's feet are in water
     */
    public boolean isInWater(double x, double feetY, double z)
    {
        return space.isWaterAt(x, feetY, z);
    }

    /**
     * @return true if the entity is fully underwater (feet and head)
     */
    public boolean isSubmerged(double x, double feetY, double z)
    {
        return space.isWaterAt(x, feetY, z) && space.isDeepWaterAt(x, feetY, z);
    }
}
