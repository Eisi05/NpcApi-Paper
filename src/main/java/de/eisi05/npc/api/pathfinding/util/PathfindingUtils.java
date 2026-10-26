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
        return findPathAsync(builder(waypoints)
                .maxIterations(maxIterations)
                .allowDiagonalMovement(allowDiagonalMovement)
                .progressListener(progressListener));
    }

    /**
     * Asynchronously calculates a path through a list of waypoints using a configured builder.
     *
     * @param builder the configured pathfinding builder
     * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
     */
    public static @NotNull CompletableFuture<Path> findPathAsync(@NotNull PathfindingBuilder builder)
    {
        CompletableFuture<Path> future = new CompletableFuture<>();

        List<Location> snapshot = new ArrayList<>(builder.waypoints.size());
        for(Location waypoint : builder.waypoints)
            snapshot.add(waypoint.clone());

        builder.waypoints = snapshot;

        PluginTask task = SchedulerProvider.get().runAsync(() ->
        {
            if(future.isCancelled())
                return;

            try
            {
                future.complete(findPathInternal(builder.factory, builder.waypoints, builder.maxIterations, builder.allowDiagonalMovement,
                        builder.entityHeight, builder.entityWidth, builder.capabilities, builder.progressListener,
                        future::isCancelled, builder.visualizer));
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
        return findPath(builder(waypoints)
                .maxIterations(maxIterations)
                .allowDiagonalMovement(allowDiagonalMovement)
                .progressListener(progressListener));
    }

    /**
     * Synchronously calculates a path through a list of waypoints using a configured builder.
     * <p>
     * Segments are calculated sequentially. This method blocks the calling thread and should be used with caution.
     * <p>
     *
     * @param builder the configured pathfinding builder
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@NotNull PathfindingBuilder builder) throws PathfindingException
    {
        return findPathInternal(builder.factory, builder.waypoints, builder.maxIterations, builder.allowDiagonalMovement,
                builder.entityHeight, builder.entityWidth, builder.capabilities, builder.progressListener,
                builder.cancelled, builder.visualizer);
    }

    /**
     * Internal implementation of pathfinding logic.
     */
    private static @NotNull Path findPathInternal(@Nullable AbstractPathfinder.PathfinderFactory<?> factory, @NotNull List<Location> waypoints, int maxIterations,
                                                  boolean allowDiagonalMovement, double entityHeight, double entityWidth, @Nullable NavCapabilities capabilities,
                                                  @Nullable BiConsumer<Double, Integer> progressListener, @Nullable BooleanSupplier cancelled,
                                                  @Nullable PathVisualizer visualizer)
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
                .usePathVisualizer(visualizer);

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

        if(!fullPathPoints.isEmpty() && !waypoints.isEmpty())
        {
            Location finalWaypoint = waypoints.getLast();
            Location finalPathPoint = fullPathPoints.getLast();

            finalPathPoint.setYaw(finalWaypoint.getYaw());
            finalPathPoint.setPitch(finalWaypoint.getPitch());
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

    /**
     * Creates a new {@link PathfindingBuilder} for configuring and executing pathfinding requests.
     *
     * @param waypoints the waypoints to calculate a path for
     * @return a new builder instance
     */
    public static @NotNull PathfindingBuilder builder(@NotNull List<Location> waypoints)
    {
        return new PathfindingBuilder(waypoints);
    }

    /**
     * Builder class for configuring pathfinding requests with a fluent API.
     */
    public static class PathfindingBuilder
    {
        private AbstractPathfinder.PathfinderFactory<?> factory = NpcApi.config.pathfinderFactory();
        private List<Location> waypoints;
        private int maxIterations = 5000;
        private boolean allowDiagonalMovement = true;
        private double entityHeight = 1.8;
        private double entityWidth = 0.6;
        private @Nullable NavCapabilities capabilities;
        private @Nullable BiConsumer<Double, Integer> progressListener;
        private @Nullable BooleanSupplier cancelled;
        private @Nullable PathVisualizer visualizer;

        private PathfindingBuilder(@NotNull List<Location> waypoints)
        {
            this.waypoints = waypoints;
        }

        /**
         * Sets the pathfinder factory to use. If null, the default factory from {@link NpcConfig#pathfinderFactory()} will be used.
         *
         * @param factory the pathfinder factory
         * @return this builder
         */
        public @NotNull PathfindingBuilder factory(@Nullable AbstractPathfinder.PathfinderFactory<?> factory)
        {
            this.factory = factory;
            return this;
        }

        /**
         * Sets the maximum number of iterations the algorithm will attempt per segment.
         *
         * @param maxIterations the maximum iterations
         * @return this builder
         */
        public @NotNull PathfindingBuilder maxIterations(int maxIterations)
        {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets whether diagonal movement is allowed.
         *
         * @param allowDiagonalMovement true to allow diagonal movement
         * @return this builder
         */
        public @NotNull PathfindingBuilder allowDiagonalMovement(boolean allowDiagonalMovement)
        {
            this.allowDiagonalMovement = allowDiagonalMovement;
            return this;
        }

        /**
         * Sets the height of the entity traversing the path.
         *
         * @param entityHeight the entity height
         * @return this builder
         */
        public @NotNull PathfindingBuilder entityHeight(double entityHeight)
        {
            this.entityHeight = entityHeight;
            return this;
        }

        /**
         * Sets the width of the entity traversing the path.
         *
         * @param entityWidth the entity width
         * @return this builder
         */
        public @NotNull PathfindingBuilder entityWidth(double entityWidth)
        {
            this.entityWidth = entityWidth;
            return this;
        }

        /**
         * Sets the navigation capabilities of the entity (ladders, swimming, gap jumps, doors, etc.).
         * If null, no restrictions are applied.
         *
         * @param capabilities the navigation capabilities
         * @return this builder
         */
        public @NotNull PathfindingBuilder capabilities(@Nullable NavCapabilities capabilities)
        {
            this.capabilities = capabilities;
            return this;
        }

        /**
         * Sets a listener that receives progress updates (completion percentage between 0.0 and 1.0, and current iteration count).
         *
         * @param progressListener the progress listener
         * @return this builder
         */
        public @NotNull PathfindingBuilder progressListener(@Nullable BiConsumer<Double, Integer> progressListener)
        {
            this.progressListener = progressListener;
            return this;
        }

        /**
         * Sets a supplier that returns true when the result is no longer needed, allowing cancellation.
         *
         * @param cancelled the cancellation supplier
         * @return this builder
         */
        public @NotNull PathfindingBuilder cancelled(@Nullable BooleanSupplier cancelled)
        {
            this.cancelled = cancelled;
            return this;
        }

        /**
         * Sets a visualizer that receives search events (visited blocks, search front, special moves, final path).
         *
         * @param visualizer the path visualizer
         * @return this builder
         */
        public @NotNull PathfindingBuilder visualizer(@Nullable PathVisualizer visualizer)
        {
            this.visualizer = visualizer;
            return this;
        }

        /**
         * Builds and executes the pathfinding request synchronously.
         *
         * @return the calculated {@link Path}
         * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
         * @throws IllegalArgumentException if waypoints is null or contains fewer than 2 locations
         */
        public @NotNull Path build() throws PathfindingException
        {
            if(waypoints == null)
                throw new IllegalArgumentException("Waypoints must be set before building.");
            return findPathInternal(factory, waypoints, maxIterations, allowDiagonalMovement, entityHeight, entityWidth, capabilities,
                    progressListener, cancelled, visualizer);
        }

        /**
         * Builds and executes the pathfinding request asynchronously.
         *
         * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
         * @throws IllegalArgumentException if waypoints is null or contains fewer than 2 locations
         */
        public @NotNull CompletableFuture<Path> buildAsync()
        {
            if(waypoints == null)
                throw new IllegalArgumentException("Waypoints must be set before building.");
            return findPathAsync(this);
        }
    }
}