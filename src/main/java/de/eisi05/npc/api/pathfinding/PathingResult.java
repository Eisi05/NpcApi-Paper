package de.eisi05.npc.api.pathfinding;

/**
 * Represents the result of an A* pathfinding attempt.
 */
public enum PathingResult
{
    /**
     * Pathfinding succeeded and a path was found.
     */
    SUCCESS(0),

    /**
     * No valid path could be found.
     */
    NO_PATH(-1),

    /**
     * Pathfinding stopped because the maximum iteration limit was exceeded.
     */
    ITERATIONS_EXCEEDED(-2);

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
}
