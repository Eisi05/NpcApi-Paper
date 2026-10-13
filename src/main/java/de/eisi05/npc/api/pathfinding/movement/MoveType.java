package de.eisi05.npc.api.pathfinding.movement;

import de.eisi05.npc.api.pathfinding.Path;

/**
 * Describes <b>how</b> an NPC gets to a path point from the previous one. The pathfinders tag every point of a {@link Path} with one of these so that
 * {@code PathTask} can perform the movement properly instead of guessing from the terrain.
 */
public enum MoveType
{
    /**
     * Normal walking (including small steps up/down, wading and walking through doors).
     */
    WALK,

    /**
     * Walking <b>crouched</b> (sneaking): the entity ducks to fit under a low ceiling that it could not pass standing. Only produced for entities that can
     * crouch (players). {@code PathTask} shows the sneaking pose for such a segment and walks it slower ({@link #paceFactor()}).
     */
    CROUCH,

    /**
     * Walking onto a ledge that is higher than a step; the NPC jumps.
     */
    JUMP,

    /**
     * Walking off a ledge and dropping to a lower surface.
     */
    FALL,

    /**
     * Moving along a ladder, vines, scaffolding or similar. Direction follows from the height difference of the two points.
     */
    CLIMB,

    /**
     * Swimming through water (free 3D movement, no gravity). Also used for {@link MovementMode#AQUATIC} entities.
     */
    SWIM,

    /**
     * Flying through open air (free 3D movement, no gravity). Only produced for {@link MovementMode#FLYING} entities.
     */
    FLY,

    /**
     * A ballistic jump over a gap. The previous point is the take-off point, this point is the landing spot. The arc is defined by {@link JumpArc}.
     */
    GAP_JUMP;

    /**
     * How fast this kind of move is walked relative to the normal walking speed: crouching is slow (30 %, like sneaking in the game), everything else runs at
     * the normal pace.
     *
     * @return the speed factor
     */
    public double paceFactor()
    {
        return this == CROUCH ? 0.3 : 1.0;
    }

    /**
     * Returns the move type that describes travelling the same segment in the opposite direction (used by {@code Path#reverse()}).
     *
     * @return the type for the reversed segment
     */
    public MoveType reversed()
    {
        return switch(this)
        {
            case JUMP -> FALL;
            case FALL -> JUMP;
            default -> this;
        };
    }
}
