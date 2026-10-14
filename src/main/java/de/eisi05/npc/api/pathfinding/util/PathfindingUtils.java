package de.eisi05.npc.api.pathfinding.util;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.objects.NpcConfig;
import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.pathfinding.PathResult;
import de.eisi05.npc.api.pathfinding.PathingResult;
import de.eisi05.npc.api.pathfinding.algorithm.AbstractPathfinder;
import de.eisi05.npc.api.pathfinding.environment.ChunkSnapshotView;
import de.eisi05.npc.api.pathfinding.movement.MoveType;
import de.eisi05.npc.api.pathfinding.movement.NavCapabilities;
import de.eisi05.npc.api.scheduler.PluginTask;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Utility class for calculating paths between locations using the configured pathfinder (grid A* or Theta*). Provides synchronous and asynchronous methods.
 */
public class PathfindingUtils
{
    /**
     * Asynchronously calculates a path through a list of waypoints using the default {@link NpcConfig#pathfinderFactory()}.
     *
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the A* algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
     */
    public static @NotNull CompletableFuture<Path> findPathAsync(@NotNull List<Location> waypoints, int maxIterations, boolean allowDiagonalMovement,
                                                                 @Nullable BiConsumer<Double, Integer> progressListener)
    {
        return findPathAsync(null, waypoints, maxIterations, allowDiagonalMovement, 1.8, 0.6, progressListener);
    }

    /**
     * Asynchronously calculates a path through a list of waypoints using a specified pathfinder factory.
     *
     * @param factory               the factory to create the pathfinder, if null the default factory {@link NpcConfig#pathfinderFactory()} will be used
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param entityHeight          the height of the entity traversing the path
     * @param entityWidth           the width of the entity traversing the path
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
     */
    public static @NotNull CompletableFuture<Path> findPathAsync(@Nullable AbstractPathfinder.PathfinderFactory<?> factory, @NotNull List<Location> waypoints,
                                                                 int maxIterations, boolean allowDiagonalMovement, double entityHeight, double entityWidth,
                                                                 @Nullable BiConsumer<Double, Integer> progressListener)
    {
        return findPathAsync(factory, waypoints, maxIterations, allowDiagonalMovement, entityHeight, entityWidth, null, progressListener, null);
    }

    /**
     * Asynchronously calculates a path through a list of waypoints using a specified pathfinder factory.
     *
     * @param factory               the factory to create the pathfinder, if null the default factory {@link NpcConfig#pathfinderFactory()} will be used
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param entityHeight          the height of the entity traversing the path
     * @param entityWidth           the width of the entity traversing the path
     * @param capabilities          what the entity can do (ladders, swimming, gap jumps, doors, ...), or null for no restriction
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
     */
    public static @NotNull CompletableFuture<Path> findPathAsync(@Nullable AbstractPathfinder.PathfinderFactory<?> factory, @NotNull List<Location> waypoints,
                                                                 int maxIterations, boolean allowDiagonalMovement, double entityHeight, double entityWidth,
                                                                 @Nullable NavCapabilities capabilities, @Nullable BiConsumer<Double, Integer> progressListener)
    {
        return findPathAsync(factory, waypoints, maxIterations, allowDiagonalMovement, entityHeight, entityWidth, capabilities, progressListener, null);
    }

    /**
     * Asynchronously calculates a path through a list of waypoints with visualization support.
     *
     * @param factory               the factory to create the pathfinder, if null the default factory {@link NpcConfig#pathfinderFactory()} will be used
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param entityHeight          the height of the entity traversing the path
     * @param entityWidth           the width of the entity traversing the path
     * @param capabilities          what the entity can do (ladders, swimming, gap jumps, doors, ...), or null for no restriction
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @param visualizer            a consumer that receives the location of every visited node for visualization (e.g., particles), or null
     * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
     */
    public static @NotNull CompletableFuture<Path> findPathAsync(@Nullable AbstractPathfinder.PathfinderFactory<?> factory, @NotNull List<Location> waypoints,
                                                                 int maxIterations, boolean allowDiagonalMovement, double entityHeight, double entityWidth,
                                                                 @Nullable NavCapabilities capabilities,
                                                                 @Nullable BiConsumer<Double, Integer> progressListener,
                                                                 @Nullable Consumer<Location> visualizer)
    {
        CompletableFuture<Path> future = new CompletableFuture<>();

        List<Location> snapshot = new ArrayList<>(waypoints.size());
        for(Location waypoint : waypoints)
            snapshot.add(waypoint.clone());

        PluginTask task = SchedulerProvider.get().runAsync(() ->
        {
            if(future.isCancelled())
                return;

            try
            {
                future.complete(findPath(factory, snapshot, maxIterations, allowDiagonalMovement, entityHeight, entityWidth, capabilities, progressListener,
                        future::isCancelled, visualizer));
            }
            catch(PathfindingException e)
            {
                if(e.getReason() != PathingResult.CANCELLED)
                    future.completeExceptionally(new CompletionException(e));
            }
            catch(Throwable throwable)
            {
                future.completeExceptionally(throwable);
            }
        });

        future.whenComplete((result, error) ->
        {
            if(future.isCancelled())
                task.cancel();
        });
        return future;
    }

    /**
     * Synchronously calculates a path through a list of waypoints using the default {@link NpcConfig#pathfinderFactory()}.
     * <p>
     * Segments are calculated sequentially. This method blocks the main thread and should be used with caution.
     *
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the A* algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@NotNull List<Location> waypoints, int maxIterations, boolean allowDiagonalMovement,
                                         @Nullable BiConsumer<Double, Integer> progressListener) throws PathfindingException
    {
        return findPath(null, waypoints, maxIterations, allowDiagonalMovement, 1.8, 0.6, progressListener);
    }

    /**
     * Synchronously calculates a path through a list of waypoints using a specified pathfinder factory.
     * <p>
     * Segments are calculated sequentially. This method blocks the calling thread and should be used with caution.
     *
     * @param factory               the factory to create the pathfinder, if null the default factory {@link NpcConfig#pathfinderFactory()} will be used
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param entityHeight          the height of the entity traversing the path
     * @param entityWidth           the width of the entity traversing the path
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@Nullable AbstractPathfinder.PathfinderFactory<?> factory, @NotNull List<Location> waypoints, int maxIterations,
                                         boolean allowDiagonalMovement, double entityHeight, double entityWidth,
                                         @Nullable BiConsumer<Double, Integer> progressListener)
            throws PathfindingException
    {
        return findPath(factory, waypoints, maxIterations, allowDiagonalMovement, entityHeight, entityWidth, null, progressListener);
    }

    /**
     * Synchronously calculates a path through a list of waypoints using a specified pathfinder factory.
     * <p>
     * Segments are calculated sequentially. This method blocks the calling thread and should be used with caution.
     *
     * @param factory               the factory to create the pathfinder, if null the default factory {@link NpcConfig#pathfinderFactory()} will be used
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param entityHeight          the height of the entity traversing the path
     * @param entityWidth           the width of the entity traversing the path
     * @param capabilities          what the entity can do (ladders, swimming, gap jumps, doors, ...), or null for no restriction
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@Nullable AbstractPathfinder.PathfinderFactory<?> factory, @NotNull List<Location> waypoints, int maxIterations,
                                         boolean allowDiagonalMovement, double entityHeight, double entityWidth, @Nullable NavCapabilities capabilities,
                                         @Nullable BiConsumer<Double, Integer> progressListener)
            throws PathfindingException
    {
        return findPath(factory, waypoints, maxIterations, allowDiagonalMovement, entityHeight, entityWidth, capabilities, progressListener, null);
    }

    /**
     * Synchronously calculates a path through a list of waypoints, restricted to what the given entity capabilities allow (see {@link NavCapabilities}).
     * <p>
     * Segments are calculated sequentially. This method blocks the calling thread and should be used with caution.
     * <p>
     * <b>Folia:</b> on a region thread this blocks that region while chunks owned by other regions are snapshotted (bounded by a timeout). Prefer
     * {@code findPathAsync} there.
     *
     * @param factory               the factory to create the pathfinder, if null the default factory {@link NpcConfig#pathfinderFactory()} will be used
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param entityHeight          the height of the entity traversing the path
     * @param entityWidth           the width of the entity traversing the path
     * @param capabilities          what the entity can do (ladders, swimming, gap jumps, doors, ...), or null for no restriction
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @param cancelled             returns true when the result is no longer needed, or null
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@Nullable AbstractPathfinder.PathfinderFactory<?> factory, @NotNull List<Location> waypoints, int maxIterations,
                                         boolean allowDiagonalMovement, double entityHeight, double entityWidth, @Nullable NavCapabilities capabilities,
                                         @Nullable BiConsumer<Double, Integer> progressListener, @Nullable BooleanSupplier cancelled)
            throws PathfindingException
    {
        return findPath(factory, waypoints, maxIterations, allowDiagonalMovement, entityHeight, entityWidth, capabilities, progressListener, cancelled, null);
    }

    /**
     * Synchronously calculates a path through a list of waypoints, restricted to what the given entity capabilities allow (see {@link NavCapabilities}).
     * <p>
     * Segments are calculated sequentially. This method blocks the calling thread and should be used with caution.
     * <p>
     * <b>Folia:</b> on a region thread this blocks that region while chunks owned by other regions are snapshotted (bounded by a timeout). Prefer
     * {@code findPathAsync} there.
     *
     * @param factory               the factory to create the pathfinder, if null the default factory {@link NpcConfig#pathfinderFactory()} will be used
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param entityHeight          the height of the entity traversing the path
     * @param entityWidth           the width of the entity traversing the path
     * @param capabilities          what the entity can do (ladders, swimming, gap jumps, doors, ...), or null for no restriction
     * @param progressListener      A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @param cancelled             returns true when the result is no longer needed, or null
     * @param visualizer            a consumer that receives the location of every visited node for visualization (e.g., particles), or null
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@Nullable AbstractPathfinder.PathfinderFactory<?> factory, @NotNull List<Location> waypoints, int maxIterations,
                                         boolean allowDiagonalMovement, double entityHeight, double entityWidth, @Nullable NavCapabilities capabilities,
                                         @Nullable BiConsumer<Double, Integer> progressListener, @Nullable BooleanSupplier cancelled,
                                         @Nullable Consumer<Location> visualizer)
            throws PathfindingException
    {
        if(waypoints.size() < 2)
            throw new IllegalArgumentException("Waypoints list must contain at least 2 locations.");

        List<Location> fullPathPoints = new ArrayList<>();
        List<MoveType> fullMoves = new ArrayList<>();
        int totalSegments = waypoints.size() - 1;

        AbstractPathfinder pathfinder = (factory == null ? NpcApi.config.pathfinderFactory() : factory)
                .create(maxIterations, allowDiagonalMovement, entityHeight, entityWidth)
                .useCapabilities(capabilities)
                .useCancellation(cancelled)
                .useVisualizer(visualizer);

        World world = waypoints.getFirst().getWorld();
        if(world != null)
            pathfinder.useBlockView(ChunkSnapshotView.create(world, waypoints));

        for(int i = 0; i < totalSegments; i++)
        {
            if(cancelled != null && cancelled.getAsBoolean())
                throw new PathfindingException("Pathfinding was cancelled", PathingResult.CANCELLED);

            Location start = waypoints.get(i);
            Location end = waypoints.get(i + 1);

            final int currentSegmentIndex = i;
            PathResult segment = pathfinder.getPathResult(start, end, (segmentProgress, iterations) ->
            {
                if(progressListener != null)
                {
                    double overallProgress = (currentSegmentIndex + segmentProgress) / totalSegments;
                    progressListener.accept(overallProgress, iterations);
                }
            });

            if(segment == null)
            {
                PathingResult reason = pathfinder.getLastResult();
                int used = pathfinder.getLastIterations();
                String prefix = "Could not find path between waypoint " + i + " and " + (i + 1) + ": ";
                if(reason == PathingResult.CANCELLED)
                    throw new PathfindingException("Pathfinding was cancelled", PathingResult.CANCELLED, used);

                String detail = switch(reason)
                {
                    case ITERATIONS_EXCEEDED -> "stopped after " + used + " iterations (limit reached, a path may exist - try a higher iteration limit)";
                    case DIFFERENT_WORLDS -> "the waypoints are in different worlds";
                    case INVALID_START, INVALID_END -> "the location is not usable for this kind of entity";
                    default -> "no route exists (" + used + " positions explored): the target is blocked or needs a move this entity cannot perform";
                };
                throw new PathfindingException(prefix + detail, reason, used);
            }

            List<Location> segmentPoints = new ArrayList<>(segment.points());
            List<MoveType> segmentMoves = new ArrayList<>(segment.moves());

            if(!fullPathPoints.isEmpty() && !segmentPoints.isEmpty())
            {
                segmentPoints.removeFirst();
                segmentMoves.removeFirst();
            }

            for(Location location : segmentPoints)
                fullPathPoints.add(location.clone());
            fullMoves.addAll(segmentMoves);

            if(progressListener != null)
                progressListener.accept((currentSegmentIndex + 1.0) / totalSegments, 0);
        }

        return new Path(fullPathPoints, waypoints, fullMoves);
    }

    /**
     * Thrown when no path could be calculated. {@link #getReason()} tells why (no route, iteration limit, invalid start or end, ...).
     */
    public static class PathfindingException extends Exception
    {
        private final PathingResult reason;
        private final int iterations;

        public PathfindingException(String message)
        {
            this(message, PathingResult.NO_PATH, 0);
        }

        public PathfindingException(String message, @NotNull PathingResult reason)
        {
            this(message, reason, 0);
        }

        public PathfindingException(String message, @NotNull PathingResult reason, int iterations)
        {
            super(message);
            this.reason = reason;
            this.iterations = iterations;
        }

        /**
         * @return why the path could not be calculated, never null
         */
        public @NotNull PathingResult getReason()
        {
            return reason;
        }

        /**
         * @return how many iterations the search used before it stopped (0 if unknown)
         */
        public int getIterations()
        {
            return iterations;
        }

        @Override
        public String toString()
        {
            return getMessage();
        }
    }
}
