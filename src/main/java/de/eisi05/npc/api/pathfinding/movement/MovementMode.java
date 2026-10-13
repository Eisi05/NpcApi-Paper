package de.eisi05.npc.api.pathfinding.movement;

/**
 * How an entity moves through the world; selects the kind of search the pathfinders run.
 */
public enum MovementMode
{
    /**
     * Walks on surfaces: steps, jumps, falls, climbs ladders, swims through water. Used by players and most mobs.
     */
    GROUND,

    /**
     * Lives in water: free 3D movement, but only through water. Used by fish, squids and dolphins. Start and end must be in water.
     */
    AQUATIC,

    /**
     * Flies: free 3D movement through any open space, no gravity and no ground needed. Used by bats, bees, parrots, ghasts and phantoms.
     */
    FLYING
}
