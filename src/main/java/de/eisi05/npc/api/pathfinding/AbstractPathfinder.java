package de.eisi05.npc.api.pathfinding;

import de.eisi05.npc.api.NpcApi;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Base abstract class for NPC pathfinding algorithms. Handles shared configuration, safety checks, and surface resolution.
 */
public abstract class AbstractPathfinder
{
    protected final int maxIterations;
    protected final boolean allowDiagonal;
    protected final double entityHeight;
    protected final double entityWidth;

    public AbstractPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
    {
        this.maxIterations = maxIterations;
        this.allowDiagonal = allowDiagonal;
        this.entityHeight = entityHeight;
        this.entityWidth = entityWidth;
    }

    /**
     * Checks if a block is safe to stand on.
     */
    public static boolean isSafeFloor(@Nullable Block block)
    {
        if(block == null)
            return false;

        Material type = block.getType();
        if(type.isAir() || block.isLiquid())
            return false;

        return !block.isPassable() && !NpcApi.config.pathfindingPassableOverride().test(block);
    }

    public int getMaxIterations()
    {
        return maxIterations;
    }

    public boolean isAllowDiagonal()
    {
        return allowDiagonal;
    }

    public double getEntityHeight()
    {
        return entityHeight;
    }

    public double getEntityWidth()
    {
        return entityWidth;
    }

    /**
     * Calculates a path from start to end.
     *
     * @param start The starting location
     * @param end   The target location
     * @return A list of locations forming the path, or null if unreachable
     * @throws PathfindingUtils.PathfindingException if start or end location is invalid
     */
    public @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end) throws PathfindingUtils.PathfindingException
    {
        return getPath(start, end, null);
    }

    /**
     * Calculates a path from start to end, reporting calculation progress.
     *
     * @param start            The starting location
     * @param end              The target location
     * @param progressListener A listener that receives a completion percentage between 0.0 and 1.0
     * @return A list of locations forming the path, or null if unreachable
     * @throws PathfindingUtils.PathfindingException if start or end location is invalid
     */
    public abstract @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end, @Nullable Consumer<Double> progressListener) throws PathfindingUtils.PathfindingException;


    /**
     * Functional interface representing a factory that creates Pathfinder instances. This avoids thread-safety issues by providing fresh instances per
     * calculation.
     */
    @FunctionalInterface
    public interface PathfinderFactory<T extends AbstractPathfinder>
    {
        PathfinderFactory<AStarPathfinder> ASTAR = AStarPathfinder::new;
        PathfinderFactory<BoundingBoxPathfinder> BOUNDING_BOX = BoundingBoxPathfinder::new;

        /**
         * Creates a factory for the BoundingBoxPathfinder with a custom grid step.
         *
         * @param gridStep the grid step to use for the pathfinder
         * @return a new PathfinderFactory instance
         */
        static PathfinderFactory<BoundingBoxPathfinder> boundingBox(double gridStep)
        {
            return (maxIterations, allowDiagonal, entityHeight, entityWidth) ->
                    new BoundingBoxPathfinder(maxIterations, allowDiagonal, entityHeight, entityWidth, gridStep);
        }

        @NotNull T create(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth);
    }
}