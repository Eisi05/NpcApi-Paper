package de.eisi05.npc.api.pathfinding.algorithm;

import de.eisi05.npc.api.pathfinding.PathResult;
import de.eisi05.npc.api.pathfinding.PathingResult;
import de.eisi05.npc.api.pathfinding.environment.LiveBlockView;
import de.eisi05.npc.api.pathfinding.environment.NavSpace;
import de.eisi05.npc.api.pathfinding.movement.MoveType;
import de.eisi05.npc.api.pathfinding.movement.NavOptions;
import de.eisi05.npc.api.pathfinding.util.PathfindingUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * Block-grid A* pathfinder with exact collision, environment-aware costs and optional natural-path smoothing.
 * <p>
 * Compared to the old implementation it
 * <ul>
 *     <li>works on exact surface heights (slabs, stairs, snow, carpets) and the real collision shapes of every block,</li>
 *     <li>knows about hazards (lava, fire, magma, cactus, campfires, berry bushes, ...), water, webs, doors/gates, ice, soul sand, honey, ledges and walls,
 *     </li>
 *     <li>plans jumps and drops in one move generator with configurable limits ({@link NavOptions}),</li>
 *     <li>uses an indexed heap and per-search memoized block lookups instead of {@code PriorityQueue.contains/remove} and live world access,</li>
 *     <li>straightens the grid path with swept line-of-sight checks (when enabled and diagonal movement is allowed) so NPCs no longer walk staircase
 *     patterns.</li>
 * </ul>
 */
public class AStarPathfinder extends AbstractPathfinder
{
    private final NavOptions options;

    /**
     * Constructs an A* pathfinder with {@link NavOptions#natural()} (smoothing enabled).
     *
     * @param maxIterations the maximum number of iterations allowed during pathfinding
     * @param allowDiagonal whether diagonal movement is permitted
     * @param entityHeight  the height of the entity
     * @param entityWidth   the width of the entity
     */
    public AStarPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
    {
        this(maxIterations, allowDiagonal, entityHeight, entityWidth, NavOptions.natural());
    }

    /**
     * Constructs an A* pathfinder with custom options.
     *
     * @param maxIterations the maximum number of iterations allowed during pathfinding
     * @param allowDiagonal whether diagonal movement is permitted
     * @param entityHeight  the height of the entity
     * @param entityWidth   the width of the entity
     * @param options       movement limits and cost weights
     */
    public AStarPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth, @NotNull NavOptions options)
    {
        super(maxIterations, allowDiagonal, entityHeight, entityWidth);
        this.options = options.copy();
    }

    /**
     * Checks if an entity of the given size fits at a position without touching solid or dangerous blocks. Reads the live world, so call it from the thread
     * that owns the position (main thread on Paper, the owning region thread on Folia); blocks that thread may not read count as empty.
     *
     * @param world        the world in which to check the position
     * @param tx           the target X coordinate (center)
     * @param ty           the target Y coordinate (feet level)
     * @param tz           the target Z coordinate (center)
     * @param entityHeight the height of the entity
     * @param entityWidth  the width of the entity
     * @return true if the position is valid and unoccupied, false otherwise
     */
    public static boolean isPositionValid(@NotNull World world, double tx, double ty, double tz, double entityHeight, double entityWidth)
    {
        return new NavSpace(new LiveBlockView(world), entityHeight, entityWidth, NavOptions.defaults()).fits(tx, ty, tz, 0);
    }

    /**
     * Same as {@link #getPath(Location, Location, BiConsumer)} but also returns the {@link MoveType} of every point. This is the primary calculation method;
     * {@code getPath} just returns its points.
     *
     * @param start            the starting location
     * @param end              the target location
     * @param progressListener A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return the path with move types, or null if no path could be found
     * @throws PathfindingUtils.PathfindingException if the end location has no valid ground (and {@code checkValidPath} is enabled)
     */
    @Override
    public @Nullable PathResult getPathResult(@NotNull Location start, @NotNull Location end, @Nullable BiConsumer<Double, Integer> progressListener)
            throws PathfindingUtils.PathfindingException
    {
        World world = start.getWorld();
        if(world == null || end.getWorld() == null || !world.equals(end.getWorld()))
        {
            report(PathingResult.DIFFERENT_WORLDS, 0);
            return null;
        }

        NavSpace space = new NavSpace(resolveView(world, start, end), entityHeight, entityWidth, effectiveOptions(options));
        GridSearch search = new GridSearch(space, 1.0, false, allowDiagonal, maxIterations);
        search.setCancellation(cancellation());
        search.setVisualizer(visualizer());
        try
        {
            return search.search(start, end, progressListener);
        }
        finally
        {
            report(search.result(), search.iterations());
        }
    }
}