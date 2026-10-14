package de.eisi05.npc.api.pathfinding.algorithm;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.pathfinding.PathResult;
import de.eisi05.npc.api.pathfinding.PathingResult;
import de.eisi05.npc.api.pathfinding.environment.BlockProfile;
import de.eisi05.npc.api.pathfinding.environment.NavSpace;
import de.eisi05.npc.api.pathfinding.environment.WorldAccess;
import de.eisi05.npc.api.pathfinding.movement.MoveType;
import de.eisi05.npc.api.pathfinding.movement.NavOptions;
import de.eisi05.npc.api.pathfinding.util.PathfindingUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * Fine-grained any-angle (Theta*) pathfinder with exact bounding-box collision.
 * <p>
 * Nodes lie on a sub-cell grid anchored at the start position, every move is validated against the real collision shapes with the full entity box, and lazily
 * evaluated line-of-sight shortcuts let the path leave the grid axes, which yields smooth, natural routes that still keep a small clearance to walls and
 * ledges. All collision logic is shared with {@link AStarPathfinder} through {@link NavSpace}.
 */
public class BoundingBoxPathfinder extends AbstractPathfinder
{
    private final double gridStep;
    private final NavOptions options;

    /**
     * Constructs a new BoundingBoxPathfinder with default step and movement settings.
     *
     * @param maxIterations the maximum number of iterations allowed
     * @param allowDiagonal whether diagonal movement is permitted
     * @param entityHeight  the height of the entity
     * @param entityWidth   the width of the entity
     */
    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
    {
        this(maxIterations, allowDiagonal, entityHeight, entityWidth, 0.25);
    }

    /**
     * Constructs a new BoundingBoxPathfinder with a custom grid step and default movement limits.
     *
     * @param maxIterations the maximum number of iterations allowed
     * @param allowDiagonal whether diagonal movement is permitted
     * @param entityHeight  the height of the entity
     * @param entityWidth   the width of the entity
     * @param gridStep      the sub-grid step size (clamped to 0.05 - 1.0)
     */
    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth, double gridStep)
    {
        this(maxIterations, allowDiagonal, entityHeight, entityWidth, gridStep, defaultOptions());
    }

    /**
     * Constructs a new BoundingBoxPathfinder with explicit jump and fall limits.
     *
     * @param maxIterations   the maximum number of iterations allowed
     * @param allowDiagonal   whether diagonal movement is permitted
     * @param entityHeight    the height of the entity
     * @param entityWidth     the width of the entity
     * @param gridStep        the sub-grid step size (clamped to 0.05 - 1.0)
     * @param maxJumpHeight   the maximum height the entity can jump
     * @param maxFallDistance the maximum safe fall distance for the entity
     */
    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth, double gridStep, double maxJumpHeight,
                                 double maxFallDistance)
    {
        this(maxIterations, allowDiagonal, entityHeight, entityWidth, gridStep, defaultOptions().maxJumpHeight(maxJumpHeight).maxFallDistance(maxFallDistance));
    }

    /**
     * Constructs a new BoundingBoxPathfinder with fully customised options.
     *
     * @param maxIterations the maximum number of iterations allowed
     * @param allowDiagonal whether diagonal movement is permitted
     * @param entityHeight  the height of the entity
     * @param entityWidth   the width of the entity
     * @param gridStep      the sub-grid step size (clamped to 0.05 - 1.0)
     * @param options       movement limits and cost weights
     */
    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth, double gridStep,
                                 @NotNull NavOptions options)
    {
        super(maxIterations, allowDiagonal, entityHeight, entityWidth);
        this.gridStep = Math.clamp(gridStep, 0.05, 1.0);
        this.options = options.copy();
    }

    private static @NotNull NavOptions defaultOptions()
    {
        return NavOptions.natural().heuristicWeight(1.25);
    }

    /**
     * Resolves ground support for an entity at the specified coordinates using default jump and fall constraints. Reads the live world, so call it from the
     * thread that owns the position (main thread on Paper, the owning region thread on Folia); columns that thread may not read count as "no ground".
     *
     * @param world       the world to check in
     * @param x           the target X coordinate
     * @param currentY    the current Y coordinate
     * @param z           the target Z coordinate
     * @param entityWidth the width of the entity
     * @return a {@link FootSupport} instance describing the ground details
     */
    public static @NotNull FootSupport resolveGroundSupport(@NotNull World world, double x, double currentY, double z, double entityWidth)
    {
        return resolveGroundSupport(world, x, currentY, z, entityWidth, 1.25, 3.0);
    }

    /**
     * Resolves ground support for an entity at the specified coordinates with custom movement limitations. This is what {@code PathTask} uses while walking, so
     * it uses the same footprint and collision data as the planner (via {@link BlockProfile}), but without per-call allocation.
     * <p>
     * <b>Region thread only.</b> Block columns that the current thread may not read (owned by another Folia region, or not loaded) are skipped instead of
     * throwing or loading chunks; if none of the footprint is readable the result is {@link FootSupport#INVALID}, which callers treat as "unknown".
     *
     * @param world           the world to check in
     * @param x               the target X coordinate
     * @param currentY        the current Y coordinate
     * @param z               the target Z coordinate
     * @param entityWidth     the width of the entity
     * @param maxJumpHeight   the maximum jump height allowed
     * @param maxFallDistance the maximum fall distance allowed
     * @return a {@link FootSupport} instance describing the ground details
     */
    public static @NotNull FootSupport resolveGroundSupport(@NotNull World world, double x, double currentY, double z, double entityWidth, double maxJumpHeight,
                                                            double maxFallDistance)
    {
        double sr = Math.max(0.05, entityWidth / 2.0 * 0.85);
        double minX = x - sr;
        double maxX = x + sr;
        double minZ = z - sr;
        double maxZ = z + sr;

        int x0 = (int) Math.floor(minX);
        int x1 = (int) Math.floor(maxX);
        int z0 = (int) Math.floor(minZ);
        int z1 = (int) Math.floor(maxZ);

        int depth = z1 - z0 + 1;
        boolean[] readable = new boolean[(x1 - x0 + 1) * depth];
        boolean anyReadable = false;
        for(int bx = x0; bx <= x1; bx++)
        {
            for(int bz = z0; bz <= z1; bz++)
            {
                boolean ok = WorldAccess.canAccess(world, bx, bz);
                readable[(bx - x0) * depth + (bz - z0)] = ok;
                anyReadable |= ok;
            }
        }

        if(!anyReadable)
            return FootSupport.INVALID;

        double high = currentY + maxJumpHeight;
        double low = currentY - maxFallDistance;
        int minWorld = world.getMinHeight();
        int maxWorld = world.getMaxHeight();

        for(int by = (int) Math.floor(high); by >= (int) Math.ceil(low) - 1; by--)
        {
            if(by < minWorld || by >= maxWorld)
                continue;

            double best = Double.NaN;
            double hazard = 0.0;

            for(int bx = x0; bx <= x1; bx++)
            {
                for(int bz = z0; bz <= z1; bz++)
                {
                    if(!readable[(bx - x0) * depth + (bz - z0)])
                        continue;

                    Material type = world.getType(bx, by, bz);
                    if(type.isAir())
                        continue;

                    BlockProfile profile = BlockProfile.of(world.getBlockData(bx, by, bz), world, bx, by, bz);
                    if(!profile.hasCollision())
                        continue;

                    double top = profile.maxTop(bx, by, bz, minX, minZ, maxX, maxZ, low - 1.0E-6, high + 1.0E-6);
                    if(Double.isNaN(top) || (!Double.isNaN(best) && top <= best))
                        continue;

                    Block block = world.getBlockAt(bx, by, bz);
                    if(NpcApi.config.pathfindingPassableOverride().test(block))
                        continue;

                    best = top;
                    hazard = profile.dangerous() ? 10.0 : 0.0;
                }
            }

            if(!Double.isNaN(best))
                return new FootSupport(best, hazard);
        }

        return FootSupport.INVALID;
    }

    /**
     * Returns the sub-grid step size used by the pathfinder.
     *
     * @return the sub-grid step size
     */
    public double getGridStep()
    {
        return gridStep;
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
        GridSearch search = new GridSearch(space, gridStep, true, allowDiagonal, maxIterations);
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

    /**
     * Record representing ground support metadata beneath an entity's feet.
     */
    public record FootSupport(double feetY, boolean valid, double hazardPenalty)
    {
        /**
         * Constant representing an invalid ground support state.
         */
        public static final FootSupport INVALID = new FootSupport(0, false, 0);

        /**
         * Constructs a valid FootSupport instance with the given feet Y coordinate and hazard penalty.
         *
         * @param feetY         the vertical feet position
         * @param hazardPenalty the hazard penalty value
         */
        public FootSupport(double feetY, double hazardPenalty)
        {
            this(feetY, true, hazardPenalty);
        }
    }
}