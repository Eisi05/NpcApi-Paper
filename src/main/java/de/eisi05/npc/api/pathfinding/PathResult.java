package de.eisi05.npc.api.pathfinding;

import de.eisi05.npc.api.pathfinding.movement.MoveType;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The result of a pathfinding run: the points and, for each point, how the NPC arrives there from the previous one.
 *
 * @param points the path points
 * @param moves  one {@link MoveType} per point ({@code moves.get(0)} is always {@link MoveType#WALK}); same size as {@code points}
 */
public record PathResult(@NotNull List<Location> points, @NotNull List<MoveType> moves)
{
    /**
     * Validates that both lists have the same size.
     */
    public PathResult
    {
        if(points.size() != moves.size())
            throw new IllegalArgumentException("points and moves must have the same size");
    }
}
