package de.eisi05.npc.api.pathfinding.algorithm;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.pathfinding.PathResult;
import de.eisi05.npc.api.pathfinding.PathingResult;
import de.eisi05.npc.api.pathfinding.environment.*;
import de.eisi05.npc.api.pathfinding.movement.MoveType;
import de.eisi05.npc.api.pathfinding.movement.NavCapabilities;
import de.eisi05.npc.api.pathfinding.movement.NavOptions;
import de.eisi05.npc.api.pathfinding.util.PathVisualizer;
import de.eisi05.npc.api.pathfinding.util.PathfindingUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Base abstract class for NPC pathfinding algorithms. Handles shared configuration, block classification helpers and world access.
 * <p>
 * Block data is read through a {@link BlockView}. By default, every search takes {@link ChunkSnapshotView snapshots} of the chunks it needs (safe for async
 * use); {@link PathfindingUtils} shares one prefetched view across all segments of a multi-waypoint path via {@link #useBlockView(BlockView)}.
 */
public abstract class AbstractPathfinder
{
    protected final int maxIterations;
    protected final boolean allowDiagonal;
    protected final double entityHeight;
    protected final double entityWidth;

    private @Nullable BlockView blockView;
    private @Nullable NavCapabilities capabilities;
    private @Nullable BooleanSupplier cancellation;
    private @Nullable PathVisualizer visualizer;
    private PathingResult lastResult = PathingResult.NO_PATH;
    private int lastIterations;

    /**
     * Constructs a new AbstractPathfinder with the specified configuration.
     *
     * @param maxIterations the maximum number of iterations allowed for pathfinding
     * @param allowDiagonal whether diagonal movement is permitted
     * @param entityHeight  the height of the entity
     * @param entityWidth   the width of the entity
     */
    public AbstractPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
    {
        this.maxIterations = maxIterations;
        this.allowDiagonal = allowDiagonal;
        this.entityHeight = entityHeight;
        this.entityWidth = entityWidth;
    }

    /**
     * Checks if a block is safe to stand on. Reads the live world: call it from the thread that owns the block (main thread on Paper, the owning region thread
     * on Folia). A block that the current thread may not read is reported as not safe.
     *
     * @param block the block to check
     * @return true if the block is safe to stand on, false otherwise
     */
    public static boolean isSafeFloor(@Nullable Block block)
    {
        if(block == null || !WorldAccess.canAccess(block.getWorld(), block.getX(), block.getZ()))
            return false;

        Material type = block.getType();
        if(type.isAir() || block.isLiquid())
            return false;

        return !block.isPassable() && !NpcApi.config.pathfindingPassableOverride().test(block);
    }

    /**
     * Retrieves the collision boxes for a block. Backed by the global {@link BlockProfile} cache (one entry per block state, no per-call allocation of Bukkit
     * voxel shapes).
     *
     * @param block the block to check
     * @return bounding box collection (block-local coordinates) for the block's current state
     */
    public static @NotNull Collection<BoundingBox> getBlockBoxes(@NotNull Block block)
    {
        return BlockProfile.of(block.getBlockData(), block.getWorld(), block.getX(), block.getY(), block.getZ()).shape();
    }

    /**
     * Clears the block collision cache if memory needs to be freed or block states are reloaded.
     */
    public static void clearBoxCache()
    {
        BlockProfile.clearCache();
        PassableOverrides.clear();
    }

    /**
     * Packs block coordinates into a single unique long identifier hash.
     *
     * @param x the block X coordinate
     * @param y the block Y coordinate
     * @param z the block Z coordinate
     * @return the packed coordinate hash
     */
    public static long packBlockCoord(int x, int y, int z)
    {
        return ((long) (x & 0x3FFFFFF)) | (((long) (z & 0x3FFFFFF)) << 26) | (((long) (y & 0xFFF)) << 52);
    }

    /**
     * Makes this pathfinder read blocks from the given view instead of creating its own snapshots. Used to share one prefetched view across all segments of a
     * path. The view must only be used by this pathfinder (and thread) while it is set.
     *
     * @param view the view, or null to go back to per-search snapshots
     * @return this pathfinder
     */
    public @NotNull AbstractPathfinder useBlockView(@Nullable BlockView view)
    {
        this.blockView = view;
        return this;
    }

    /**
     * Restricts the moves this pathfinder may plan to what an entity can actually do (ladders, swimming, gap jumps, doors, ...). Capabilities only take
     * abilities away from the configured {@link NavOptions}. See {@link NavCapabilities#of(org.bukkit.entity.EntityType)}.
     *
     * @param capabilities the capabilities, or null for no restriction
     * @return this pathfinder
     */
    public @NotNull AbstractPathfinder useCapabilities(@Nullable NavCapabilities capabilities)
    {
        this.capabilities = capabilities;
        return this;
    }

    /**
     * Sets a visualizer callback that receives the location of every <b>block</b> the search visited (deduplicated, so a fine grid does not report the same
     * block several times). The callback is called on the calling thread for synchronous searches and on the pathfinding thread for async searches, so it must
     * be thread-safe and must not touch the world or entities directly. For more detail (final path, jumps, search front) use
     * {@link #usePathVisualizer(PathVisualizer)}.
     *
     * @param visualizer a consumer that receives visited block locations, or null to disable visualization
     * @return this pathfinder
     */
    public @NotNull AbstractPathfinder useVisualizer(@Nullable Consumer<Location> visualizer)
    {
        this.visualizer = visualizer == null ? null : PathVisualizer.ofVisited(visualizer);
        return this;
    }

    /**
     * Sets a {@link PathVisualizer} that receives only the relevant events of the search: visited blocks, the search front, special moves and the final path.
     *
     * @param visualizer the visualizer, or null to disable visualization
     * @return this pathfinder
     */
    public @NotNull AbstractPathfinder usePathVisualizer(@Nullable PathVisualizer visualizer)
    {
        this.visualizer = visualizer;
        return this;
    }

    /**
     * Lets the caller abort a running search: the supplier is polled every few dozen iterations and, once it returns true, the search stops with
     * {@link PathingResult#CANCELLED}. This is what makes cancelling the future returned by {@code findPathAsync} actually stop the work.
     *
     * @param cancellation returns true when the result is no longer needed, or null
     * @return this pathfinder
     */
    public @NotNull AbstractPathfinder useCancellation(@Nullable BooleanSupplier cancellation)
    {
        this.cancellation = cancellation;
        return this;
    }

    /**
     * @return the visualizer set with {@link #useVisualizer(Consumer)} or {@link #usePathVisualizer(PathVisualizer)}, or null
     */
    protected @Nullable PathVisualizer visualizer()
    {
        return visualizer;
    }

    /**
     * @return the cancellation check set with {@link #useCancellation(BooleanSupplier)}, or null
     */
    protected @Nullable BooleanSupplier cancellation()
    {
        return cancellation;
    }

    /**
     * @return the capabilities set with {@link #useCapabilities(NavCapabilities)}, or null
     */
    public @Nullable NavCapabilities getCapabilities()
    {
        return capabilities;
    }

    /**
     * Returns the outcome of the most recent {@link #getPathResult} call on this instance: {@link PathingResult#SUCCESS}, or the reason why it returned null /
     * threw. Pathfinder instances are created per calculation, so this is only meaningful on the instance that did the calculation.
     *
     * @return the last result
     */
    public @NotNull PathingResult getLastResult()
    {
        return lastResult;
    }

    /**
     * @return the number of iterations the most recent search used
     */
    public int getLastIterations()
    {
        return lastIterations;
    }

    /**
     * Records the outcome of a search. Called by implementations at the end of {@link #getPathResult}.
     *
     * @param result     the outcome
     * @param iterations the iterations used
     */
    protected void report(@NotNull PathingResult result, int iterations)
    {
        this.lastResult = result;
        this.lastIterations = iterations;
    }

    /**
     * Applies the capabilities (if any) to the configured options.
     *
     * @param configured the options the pathfinder was configured with
     * @return the options to search with
     */
    protected @NotNull NavOptions effectiveOptions(@NotNull NavOptions configured)
    {
        return capabilities == null ? configured : capabilities.restrict(configured);
    }

    /**
     * Returns the view to search in: the shared one if it matches the world, otherwise fresh snapshots around start and end.
     *
     * @param world the world of the search
     * @param start the start location
     * @param end   the end location
     * @return the view
     */
    protected @NotNull BlockView resolveView(@NotNull World world, @NotNull Location start, @NotNull Location end)
    {
        BlockView view = this.blockView;
        if(view != null && view.getWorld().equals(world))
            return view;

        return ChunkSnapshotView.create(world, List.of(start, end));
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
     * Calculates a path from start to end, reporting calculation progress. Convenience wrapper that returns only the points of
     * {@link #getPathResult(Location, Location, BiConsumer)}; use that method if you also need to know how each point is reached.
     *
     * @param start            The starting location
     * @param end              The target location
     * @param progressListener A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return A list of locations forming the path, or null if unreachable
     * @throws PathfindingUtils.PathfindingException if start or end location is invalid
     */
    public final @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end, @Nullable BiConsumer<Double, Integer> progressListener)
            throws PathfindingUtils.PathfindingException
    {
        PathResult result = getPathResult(start, end, progressListener);
        return result == null ? null : result.points();
    }

    /**
     * Calculates a path from start to end and returns the points together with the {@link MoveType} of every point (walking, jumping, climbing, swimming, gap
     * jumps, ...). This is the method pathfinder implementations provide; {@link #getPath(Location, Location, BiConsumer)} is derived from it.
     *
     * @param start            The starting location
     * @param end              The target location
     * @param progressListener A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return the path points with their move types, or null if unreachable
     * @throws PathfindingUtils.PathfindingException if start or end location is invalid
     */
    public abstract @Nullable PathResult getPathResult(@NotNull Location start, @NotNull Location end, @Nullable BiConsumer<Double, Integer> progressListener)
            throws PathfindingUtils.PathfindingException;

    /**
     * Interface representing a factory that creates Pathfinder instances. This avoids thread-safety issues by providing fresh instances per calculation: a
     * pathfinder instance (and the {@link BlockView} and visualizer it uses) belongs to one search on one thread, while the factories themselves are immutable
     * and may be shared freely.
     */
    public interface PathfinderFactory<T extends AbstractPathfinder>
    {
        PathfinderFactory<AStarPathfinder> ASTAR = new AStarFactory(null);
        PathfinderFactory<AStarPathfinder> ASTAR_GRID = new AStarFactory(NavOptions.defaults());
        PathfinderFactory<BoundingBoxPathfinder> BOUNDING_BOX = new BoundingBoxFactory(0.25, null);

        static PathfinderFactory<BoundingBoxPathfinder> boundingBox(double gridStep)
        {
            return new BoundingBoxFactory(gridStep, null);
        }

        static PathfinderFactory<BoundingBoxPathfinder> boundingBox(double gridStep, @NotNull NavOptions options)
        {
            return new BoundingBoxFactory(gridStep, options);
        }

        static PathfinderFactory<AStarPathfinder> astar(@NotNull NavOptions options)
        {
            return new AStarFactory(options);
        }

        T create(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth);

        @Nullable NavOptions getOptions();

        record AStarFactory(@Nullable NavOptions options) implements PathfinderFactory<AStarPathfinder>
        {
            @Override
            public @NotNull AStarPathfinder create(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
            {
                return options == null
                        ? new AStarPathfinder(maxIterations, allowDiagonal, entityHeight, entityWidth)
                        : new AStarPathfinder(maxIterations, allowDiagonal, entityHeight, entityWidth, options);
            }

            @Override
            public NavOptions getOptions()
            {
                return options;
            }
        }

        record BoundingBoxFactory(double gridStep, @Nullable NavOptions options) implements PathfinderFactory<BoundingBoxPathfinder>
        {
            @Override
            public @NotNull BoundingBoxPathfinder create(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
            {
                return options == null
                        ? new BoundingBoxPathfinder(maxIterations, allowDiagonal, entityHeight, entityWidth, gridStep)
                        : new BoundingBoxPathfinder(maxIterations, allowDiagonal, entityHeight, entityWidth, gridStep, options);
            }

            @Override
            public @Nullable NavOptions getOptions()
            {
                return options;
            }
        }
    }
}