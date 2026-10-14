package de.eisi05.npc.api.pathfinding.movement;

import de.eisi05.npc.api.pathfinding.algorithm.GridSearch;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The deterministic jump trajectory shared by the planner ({@link GridSearch}) and the executor ({@link PathMotion}, {@code PathTask}), so a gap jump that
 * the planner validated is exactly the jump the NPC performs.
 * <p>
 * Vertical motion uses the same constants as {@code PathTask} (gravity {@code -0.08}, drag {@code 0.98}); only the take-off velocity differs per entity
 * ({@link #DEFAULT} uses {@code 0.42}, an apex of about 1.25 blocks). Strong jumpers such as horses use {@link #forHeight(double)}. Horizontal motion is a
 * straight line from take-off to landing, completed in exactly the number of ticks the vertical arc needs to come back down to the landing height. The
 * horizontal speed is therefore {@code distance / ticks}, independent of the NPC's walking speed.
 */
public final class JumpArc
{
    /**
     * Initial vertical velocity of a normal jump (blocks per tick).
     */
    public static final double JUMP_VELOCITY = 0.42;

    /**
     * Gravity per tick.
     */
    public static final double GRAVITY = -0.08;

    /**
     * Vertical drag per tick.
     */
    public static final double DRAG = 0.98;

    /**
     * Longest flight the arc model considers.
     */
    public static final int MAX_TICKS = 80;

    /**
     * The jump of a normal entity (velocity 0.42, apex about 1.25 blocks).
     */
    public static final JumpArc DEFAULT = new JumpArc(JUMP_VELOCITY);

    private static final double[] FALL = new double[MAX_TICKS + 1];

    static
    {
        double v = 0;
        for(int k = 1; k <= MAX_TICKS; k++)
        {
            v = (v + GRAVITY) * DRAG;
            FALL[k] = FALL[k - 1] + v;
        }
    }

    private static final Map<Long, JumpArc> CACHE = new ConcurrentHashMap<>();

    private final double velocity;
    private final double[] height = new double[MAX_TICKS + 1];
    private final double apex;

    private JumpArc(double velocity)
    {
        this.velocity = velocity;

        double v = velocity;
        height[1] = velocity;
        double max = velocity;
        for(int k = 2; k <= MAX_TICKS; k++)
        {
            v = (v + GRAVITY) * DRAG;
            height[k] = height[k - 1] + v;
            max = Math.max(max, height[k]);
        }
        this.apex = max;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------
    // factories
    // ------------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Returns the arc for a given take-off velocity (cached).
     *
     * @param velocity the initial vertical velocity in blocks per tick
     * @return the arc
     */
    public static @NotNull JumpArc forVelocity(double velocity)
    {
        double clamped = Math.clamp(velocity, 0.2, 1.6);
        if(Math.abs(clamped - JUMP_VELOCITY) < 1.0E-4)
            return DEFAULT;

        return CACHE.computeIfAbsent(Math.round(clamped * 10_000), key -> new JumpArc(key / 10_000.0));
    }

    /**
     * Returns the arc of an entity that can jump about {@code apexHeight} blocks high. {@code 1.25} (the default jump) returns {@link #DEFAULT} exactly.
     *
     * @param apexHeight the highest point of the jump above the take-off height, in blocks
     * @return the arc
     */
    public static @NotNull JumpArc forHeight(double apexHeight)
    {
        if(Math.abs(apexHeight - 1.25) < 0.011)
            return DEFAULT;

        double lo = 0.2;
        double hi = 1.6;
        for(int i = 0; i < 40; i++)
        {
            double mid = (lo + hi) / 2.0;
            if(forVelocity(mid).apex() < apexHeight)
                lo = mid;
            else
                hi = mid;
        }
        return forVelocity(hi);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------
    // trajectory
    // ------------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * @return the take-off velocity of this arc in blocks per tick
     */
    public double velocity()
    {
        return velocity;
    }

    /**
     * Height above the take-off height after the given number of ticks.
     *
     * @param tick ticks since take-off (0 .. {@link #MAX_TICKS})
     * @return the height offset
     */
    public double heightOf(int tick)
    {
        return height[Math.clamp(tick, 0, MAX_TICKS)];
    }

    /**
     * Number of ticks until this jump comes back down to a landing surface {@code dy} above (positive) or below (negative) the take-off height.
     *
     * @param dy landing height minus take-off height
     * @return the flight time in ticks, or -1 if the landing is out of reach (above the apex) or too far below
     */
    public int ticksTo(double dy)
    {
        for(int k = 2; k <= MAX_TICKS; k++)
        {
            if(height[k] < height[k - 1] && height[k] <= dy)
                return k;
        }
        return -1;
    }

    /**
     * Highest point of this jump relative to the take-off height.
     *
     * @return the apex height
     */
    public double apex()
    {
        return apex;
    }

    /**
     * Height offset (<= 0) of an entity that walks off a ledge and falls freely, after the given number of ticks. Same integration as {@code PathTask}.
     *
     * @param tick ticks since leaving the ground
     * @return the (negative) height offset
     */
    public static double fallOffset(int tick)
    {
        return FALL[Math.clamp(tick, 0, MAX_TICKS)];
    }
}