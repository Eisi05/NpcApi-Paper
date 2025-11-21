package de.eisi05.npc.api.pathfinding;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Utility class for calculating paths between locations using A* pathfinding.
 * Provides synchronous and asynchronous methods.
 */
public class PathfindingUtils
{
    /**
     * Asynchronously calculates a path through a list of waypoints.
     * <p>
     * Each segment between consecutive waypoints is calculated in parallel using {@link CompletableFuture}.
     * The returned future completes with a {@link Path} containing the full path, or completes exceptionally
     * if an {@link PathfindingException} occurs.
     *
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the A* algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param progressListener      a progress listener with the signature (segmentIndex, totalSegments)
     * @return a {@link CompletableFuture} that completes with the calculated {@link Path}
     */
    public static @NotNull CompletableFuture<Path> findPathAsync(@NotNull List<Location> waypoints, int maxIterations, boolean allowDiagonalMovement,
            @Nullable BiConsumer<Integer, Integer> progressListener)
    {
        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return findPath(waypoints, maxIterations, allowDiagonalMovement, progressListener);
            } catch(PathfindingException e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Synchronously calculates a path through a list of waypoints.
     * <p>
     * Each segment between consecutive waypoints is calculated in parallel internally using {@link CompletableFuture},
     * but this method blocks until all segments are calculated and combined into a single {@link Path}.
     *
     * @param waypoints             the ordered list of locations to traverse
     * @param maxIterations         the maximum number of iterations the A* algorithm will attempt per segment
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @param progressListener      a progress listener with the signature (segmentIndex, totalSegments)
     * @return the calculated {@link Path} containing all intermediate locations
     * @throws PathfindingException if any segment's start or end location is invalid/unwalkable
     */
    public static @NotNull Path findPath(@NotNull List<Location> waypoints, int maxIterations, boolean allowDiagonalMovement,
            @Nullable BiConsumer<Integer, Integer> progressListener) throws PathfindingException
    {
        if(waypoints.size() < 2)
            throw new IllegalArgumentException("Waypoints list must contain at least 2 locations.");

        List<Location> fullPathPoints = new ArrayList<>();

        AStarPathfinder aStar = new AStarPathfinder(maxIterations, allowDiagonalMovement);
        for(int i = 0; i < waypoints.size() - 1; i++)
        {
            Location start = waypoints.get(i);
            Location end = waypoints.get(i + 1);

            List<Location> segment = aStar.getPath(start, end);

            if(segment == null)
                throw new PathfindingException("Could not find path between waypoint " + i + " and " + (i + 1));

            if(!fullPathPoints.isEmpty() && !segment.isEmpty() && isAbove(segment.getFirst(), fullPathPoints.getLast()))
                segment.removeFirst();
            else if(!segment.isEmpty())
                segment.set(0, segment.getFirst().subtract(0, 1, 0));

            fullPathPoints.addAll(segment);

            if(progressListener != null)
                progressListener.accept(i + 1, waypoints.size() - 1);
        }

        return new Path(fullPathPoints, waypoints);
    }

    private static boolean isAbove(@NotNull Location l1, @NotNull Location l2)
    {
        return l1.getX() == l2.getX() && l1.getY() - 1 == l2.getY() && l1.getZ() == l2.getZ();
    }

    public static class PathfindingException extends Exception
    {
        public PathfindingException(String message)
        {
            super(message);
        }
    }
}
