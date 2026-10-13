package de.eisi05.npc.api.pathfinding;

import de.eisi05.npc.api.pathfinding.algorithm.AbstractPathfinder;
import de.eisi05.npc.api.pathfinding.movement.NavCapabilities;
import de.eisi05.npc.api.pathfinding.util.PathfindingUtils;

/**
 * Represents the outcome of a pathfinding attempt. Every {@link AbstractPathfinder} reports it through {@link AbstractPathfinder#getLastResult()}, and
 * {@link PathfindingUtils.PathfindingException#getReason()} carries it when a path could not be calculated.
 */
public enum PathingResult
{
    /**
     * Pathfinding succeeded and a path was found.
     */
    SUCCESS(0),

    /**
     * No valid path could be found: the search explored every reachable position without reaching the target. The target is blocked, lies behind something
     * this entity cannot cross (see {@link NavCapabilities}), or is simply unreachable.
     */
    NO_PATH(-1),

    /**
     * Pathfinding stopped because the maximum iteration limit was exceeded. A path may exist; try a higher limit.
     */
    ITERATIONS_EXCEEDED(-2),

    /**
     * The end location cannot be reached by this kind of entity at all (no ground to stand on, inside solid blocks for flying entities, not in water for
     * aquatic entities).
     */
    INVALID_END(-3),

    /**
     * The start location is unusable for this kind of entity (for example an aquatic entity that is not in water).
     */
    INVALID_START(-4),

    /**
     * Start and end are in different worlds.
     */
    DIFFERENT_WORLDS(-5),

    /**
     * The search was cancelled by its caller (for example because the goal that requested it ended or needed a newer path) and stopped early.
     */
    CANCELLED(-6);

    private final int ec;

    /**
     * Creates a new pathing result with the given end code.
     *
     * @param ec numerical code representing the result
     */
    PathingResult(int ec)
    {
        this.ec = ec;
    }

    /**
     * Returns the numerical code associated with this pathing result.
     *
     * @return the end code
     */
    public int getEndCode()
    {
        return this.ec;
    }

    /**
     * @return true only for {@link #SUCCESS}
     */
    public boolean isSuccess()
    {
        return this == SUCCESS;
    }
}
