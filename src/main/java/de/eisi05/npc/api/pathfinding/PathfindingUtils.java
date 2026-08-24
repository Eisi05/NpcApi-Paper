package de.eisi05.npc.api.pathfinding;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.objects.NpcConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Utility class for calculating paths between locations using A* pathfinding. Provides synchronous and asynchronous methods.
 */
public class PathfindingUtils
{
    /**
     * Asynchronously calculates a path through a list of waypoints using the default {@link NpcConfig#pathfinderFactory()}.
     *
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the A* algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param progressListener      a progress listener receiving an overall completion percentage between 0.0 and 1.0
     * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
     */
    public static @NotNull CompletableFuture<Path> findPathAsync(@NotNull List<Location> waypoints, int maxIterations, boolean allowDiagonalMovement,
                                                                 @Nullable Consumer<Double> progressListener)
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
     * @param progressListener      a progress listener receiving an overall completion percentage between 0.0 and 1.0
     * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
     */
    public static @NotNull CompletableFuture<Path> findPathAsync(@Nullable AbstractPathfinder.PathfinderFactory factory, @NotNull List<Location> waypoints,
                                                                 int maxIterations, boolean allowDiagonalMovement, double entityHeight, double entityWidth,
                                                                 @Nullable Consumer<Double> progressListener)
    {
        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return findPath(factory, waypoints, maxIterations, allowDiagonalMovement, entityHeight, entityWidth, progressListener);
            }
            catch(PathfindingException e)
            {
                throw new CompletionException(e);
            }
        }, runnable -> Bukkit.getScheduler().runTaskAsynchronously(NpcApi.plugin, runnable));
    }

    /**
     * Synchronously calculates a path through a list of waypoints using the default {@link NpcConfig#pathfinderFactory()}.
     * <p>
     * Segments are calculated sequentially. This method blocks the main thread and should be used with caution.
     *
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the A* algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param progressListener      a progress listener receiving an overall completion percentage between 0.0 and 1.0
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@NotNull List<Location> waypoints, int maxIterations, boolean allowDiagonalMovement,
                                         @Nullable Consumer<Double> progressListener) throws PathfindingException
    {
        return findPath(null, waypoints, maxIterations, allowDiagonalMovement, 1.8, 0.6, progressListener);
    }

    /**
     * Synchronously calculates a path through a list of waypoints using a specified pathfinder factory.
     * <p>
     * Segments are calculated sequentially. This method blocks the main thread and should be used with caution.
     *
     * @param factory               the factory to create the pathfinder, if null the default factory {@link NpcConfig#pathfinderFactory()} will be used
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param entityHeight          the height of the entity traversing the path
     * @param entityWidth           the width of the entity traversing the path
     * @param progressListener      a progress listener receiving an overall completion percentage between 0.0 and 1.0
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@Nullable AbstractPathfinder.PathfinderFactory factory, @NotNull List<Location> waypoints, int maxIterations,
                                         boolean allowDiagonalMovement, double entityHeight, double entityWidth, @Nullable Consumer<Double> progressListener)
            throws PathfindingException
    {
        if(waypoints.size() < 2)
            throw new IllegalArgumentException("Waypoints list must contain at least 2 locations.");

        List<Location> fullPathPoints = new ArrayList<>();
        int totalSegments = waypoints.size() - 1;

        AbstractPathfinder pathfinder = (factory == null ? NpcApi.config.pathfinderFactory() : factory)
                .create(maxIterations, allowDiagonalMovement, entityHeight, entityWidth);

        for(int i = 0; i < totalSegments; i++)
        {
            Location start = waypoints.get(i);
            Location end = waypoints.get(i + 1);

            final int currentSegmentIndex = i;
            List<Location> segment = pathfinder.getPath(start, end, segmentProgress ->
            {
                if(progressListener != null)
                {
                    double overallProgress = (currentSegmentIndex + segmentProgress) / totalSegments;
                    progressListener.accept(overallProgress);
                }
            });

            if(segment == null)
                throw new PathfindingException("Could not find path between waypoint " + i + " and " + (i + 1));

            if(!fullPathPoints.isEmpty() && !segment.isEmpty())
                segment.removeFirst();

            fullPathPoints.addAll(segment.stream().map(Location::clone).toList());

            if(progressListener != null)
                progressListener.accept((currentSegmentIndex + 1.0) / totalSegments);
        }

        return new Path(fullPathPoints, waypoints);
    }

    public static class PathfindingException extends Exception
    {
        public PathfindingException(String message)
        {
            super(message);
        }

        @Override
        public String toString()
        {
            return getMessage();
        }
    }
}