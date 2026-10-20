package de.eisi05.npc.api.pathfinding.util;

import de.eisi05.npc.api.pathfinding.movement.MoveType;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

/**
 * Receives the <b>relevant</b> events of a path search instead of every single node, so a visualization stays readable and cheap.
 * <p>
 * Every method has an empty default, override only what you want to show.
 * <p>
 * <b>Threading:</b> the callbacks run on the thread that runs the search: a pathfinding thread for {@code findPathAsync}, the calling thread for a synchronous
 * search. They are never called concurrently for one search, but not on the thread that owns the player or the world. An implementation must therefore be
 * thread-safe and must not touch the Bukkit API (world, entities, players) in the callback: hand the data over, for example through a concurrent queue, and
 * draw it from the player's own scheduler (the entity scheduler on Folia). {@code PathRenderer.SearchView} does exactly that. The {@link Location}s and lists
 * passed in are private to the callback and not modified afterward.
 */
public interface PathVisualizer
{
    /**
     * Adapter for a plain location consumer: it only receives {@link #visited(Location)}.
     *
     * @param consumer the consumer
     * @return a visualizer that forwards visited locations to the consumer
     */
    static @NotNull PathVisualizer ofVisited(@NotNull Consumer<Location> consumer)
    {
        return new PathVisualizer()
        {
            @Override
            public void visited(@NotNull Location location)
            {
                consumer.accept(location);
            }
        };
    }

    /**
     * A node the search expanded. Reported <b>once per block</b> (a fine 0.25 grid does not report 16 nodes per block) at the centre of that block.
     *
     * @param location the block position (feet level)
     */
    default void visited(@NotNull Location location)
    {
    }

    /**
     * The search front: reported whenever the search gets closer to the goal than ever before. Very few events, shows the direction of the search.
     *
     * @param location the node position
     */
    default void frontier(@NotNull Location location)
    {
    }

    /**
     * A special move the planner considered and found a cheaper way with: jump, fall, gap jump, climb, swim. For a gap jump {@code from} is the take-off point,
     * so the arc can be drawn (see {@code JumpArc}). Plain walking is not reported.
     *
     * @param from the start of the move
     * @param to   the landing / end of the move
     * @param type the move type
     */
    default void move(@NotNull Location from, @NotNull Location to, @NotNull MoveType type)
    {
    }

    /**
     * The final path (after smoothing), with the move type of every point. Reported once when a path was found.
     *
     * @param points the path points
     * @param moves  how each point is reached
     */
    default void path(@NotNull List<Location> points, @NotNull List<MoveType> moves)
    {
    }
}