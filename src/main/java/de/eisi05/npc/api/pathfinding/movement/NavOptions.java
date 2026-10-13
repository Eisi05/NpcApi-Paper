package de.eisi05.npc.api.pathfinding.movement;

import de.eisi05.npc.api.pathfinding.algorithm.AStarPathfinder;
import de.eisi05.npc.api.pathfinding.algorithm.BoundingBoxPathfinder;
import org.jetbrains.annotations.NotNull;

/**
 * Movement capabilities and cost weights of the grid pathfinders ({@link AStarPathfinder}, {@link BoundingBoxPathfinder}).
 * <p>
 * The defaults are tuned for a normal player-sized NPC driven by {@code PathTask}. All setters are fluent; pathfinders copy the options on construction, so
 * later changes to an instance do not affect running searches.
 */
public final class NavOptions
{
    double stepHeight = 0.55;
    double maxJumpHeight = 1.25;
    boolean jumpHeightExplicit = false;
    double arcHeight = 1.25;
    double maxFallDistance = 3.0;

    double jumpCost = 1.2;
    double fallCost = 0.8;
    double wallPenalty = 0.12;
    double edgePenalty = 0.35;
    double hazardPenalty = 4.0;
    double wadePenalty = 1.0;
    double doorPenalty = 1.5;
    double turnPenalty = 0.1;
    float heuristicWeight = 1.1f;

    boolean canClimb = true;
    boolean canClimbWalls = false;
    boolean allowCrouch = true;
    double crouchRatio = 0.0;
    double crouchPenalty = 2.0;
    MovementMode mode = MovementMode.GROUND;
    boolean canSwim = true;
    boolean canGapJump = true;
    boolean avoidWater = false;
    boolean canOpenDoors = true;
    double climbCost = 1.8;
    double swimCost = 1.6;
    double gapJumpCost = 2.0;
    double maxGapJumpDistance = 4.0;
    double gapJumpSpeed = 0.27;
    double walkSpeed = 0.25;

    boolean smooth = false;
    double sampleStep = 0.25;
    int smoothLookahead = 96;

    private NavOptions()
    {
    }

    /**
     * Options for plain grid-aligned paths (no smoothing).
     *
     * @return new options
     */
    public static @NotNull NavOptions defaults()
    {
        return new NavOptions();
    }

    /**
     * Options for natural-looking paths: like {@link #defaults()} but with line-of-sight smoothing enabled.
     *
     * @return new options
     */
    public static @NotNull NavOptions natural()
    {
        return new NavOptions().smooth(true);
    }

    /**
     * @return an independent copy
     */
    public @NotNull NavOptions copy()
    {
        NavOptions copy = new NavOptions();
        copy.stepHeight = stepHeight;
        copy.maxJumpHeight = maxJumpHeight;
        copy.jumpHeightExplicit = jumpHeightExplicit;
        copy.arcHeight = arcHeight;
        copy.maxFallDistance = maxFallDistance;
        copy.jumpCost = jumpCost;
        copy.fallCost = fallCost;
        copy.wallPenalty = wallPenalty;
        copy.edgePenalty = edgePenalty;
        copy.hazardPenalty = hazardPenalty;
        copy.wadePenalty = wadePenalty;
        copy.doorPenalty = doorPenalty;
        copy.turnPenalty = turnPenalty;
        copy.heuristicWeight = heuristicWeight;
        copy.canClimb = canClimb;
        copy.canClimbWalls = canClimbWalls;
        copy.allowCrouch = allowCrouch;
        copy.crouchRatio = crouchRatio;
        copy.crouchPenalty = crouchPenalty;
        copy.mode = mode;
        copy.canSwim = canSwim;
        copy.canGapJump = canGapJump;
        copy.avoidWater = avoidWater;
        copy.canOpenDoors = canOpenDoors;
        copy.climbCost = climbCost;
        copy.swimCost = swimCost;
        copy.gapJumpCost = gapJumpCost;
        copy.maxGapJumpDistance = maxGapJumpDistance;
        copy.gapJumpSpeed = gapJumpSpeed;
        copy.walkSpeed = walkSpeed;
        copy.smooth = smooth;
        copy.sampleStep = sampleStep;
        copy.smoothLookahead = smoothLookahead;
        return copy;
    }

    /**
     * @param stepHeight highest ledge the NPC walks up without jumping (default 0.55, matches {@code PathTask})
     * @return this
     */
    public @NotNull NavOptions stepHeight(double stepHeight)
    {
        this.stepHeight = Math.max(0.0, stepHeight);
        return this;
    }

    /**
     * @param maxJumpHeight highest ledge the NPC can jump onto (default 1.25)
     * @return this
     */
    public @NotNull NavOptions maxJumpHeight(double maxJumpHeight)
    {
        this.maxJumpHeight = Math.max(0.0, maxJumpHeight);
        this.jumpHeightExplicit = true;
        return this;
    }

    /**
     * @param maxFallDistance largest drop the NPC may take (default 3.0, no fall damage)
     * @return this
     */
    public @NotNull NavOptions maxFallDistance(double maxFallDistance)
    {
        this.maxFallDistance = Math.max(0.0, maxFallDistance);
        return this;
    }

    /**
     * @param jumpCost extra cost of a jump, makes the NPC prefer walking around small obstacles (default 1.2)
     * @return this
     */
    public @NotNull NavOptions jumpCost(double jumpCost)
    {
        this.jumpCost = Math.max(0.0, jumpCost);
        return this;
    }

    /**
     * @param fallCost extra cost per block of fall distance (default 0.8)
     * @return this
     */
    public @NotNull NavOptions fallCost(double fallCost)
    {
        this.fallCost = Math.max(0.0, fallCost);
        return this;
    }

    /**
     * @param wallPenalty cost per side that touches a wall; makes paths keep a little distance from walls (default 0.12, 0 disables)
     * @return this
     */
    public @NotNull NavOptions wallPenalty(double wallPenalty)
    {
        this.wallPenalty = Math.max(0.0, wallPenalty);
        return this;
    }

    /**
     * @param edgePenalty cost per side that borders a drop of two or more blocks (default 0.35, 0 disables)
     * @return this
     */
    public @NotNull NavOptions edgePenalty(double edgePenalty)
    {
        this.edgePenalty = Math.max(0.0, edgePenalty);
        return this;
    }

    /**
     * @param hazardPenalty cost per side that borders lava, fire, cactus, magma, ... (default 4.0)
     * @return this
     */
    public @NotNull NavOptions hazardPenalty(double hazardPenalty)
    {
        this.hazardPenalty = Math.max(0.0, hazardPenalty);
        return this;
    }

    /**
     * @param wadePenalty cost of walking through shallow water (default 1.0)
     * @return this
     */
    public @NotNull NavOptions wadePenalty(double wadePenalty)
    {
        this.wadePenalty = Math.max(0.0, wadePenalty);
        return this;
    }

    /**
     * @param doorPenalty cost of passing a door or gate (default 1.5)
     * @return this
     */
    public @NotNull NavOptions doorPenalty(double doorPenalty)
    {
        this.doorPenalty = Math.max(0.0, doorPenalty);
        return this;
    }

    /**
     * @param turnPenalty cost per full direction reversal, scales with the turn angle; reduces zig-zagging (default 0.1)
     * @return this
     */
    public @NotNull NavOptions turnPenalty(double turnPenalty)
    {
        this.turnPenalty = Math.max(0.0, turnPenalty);
        return this;
    }

    /**
     * @param heuristicWeight A* heuristic weight; 1.0 is optimal, larger values search faster but may be slightly longer (default 1.1)
     * @return this
     */
    public @NotNull NavOptions heuristicWeight(double heuristicWeight)
    {
        this.heuristicWeight = (float) Math.max(1.0, heuristicWeight);
        return this;
    }

    /**
     * @param smooth whether the raw grid path is straightened using swept line-of-sight checks (ignored when diagonal movement is disabled)
     * @return this
     */
    public @NotNull NavOptions smooth(boolean smooth)
    {
        this.smooth = smooth;
        return this;
    }

    /**
     * @param sampleStep distance between collision samples when checking straight segments (default 0.25, clamped to half the entity width)
     * @return this
     */
    public @NotNull NavOptions sampleStep(double sampleStep)
    {
        this.sampleStep = sampleStep;
        return this;
    }

    /**
     * @param smoothLookahead how many path nodes the smoother looks ahead from each anchor (default 96)
     * @return this
     */
    public @NotNull NavOptions smoothLookahead(int smoothLookahead)
    {
        this.smoothLookahead = Math.max(2, smoothLookahead);
        return this;
    }

    /**
     * @param canClimb whether ladders, vines, scaffolding and similar may be climbed (default true)
     * @return this
     */
    public @NotNull NavOptions canClimb(boolean canClimb)
    {
        this.canClimb = canClimb;
        return this;
    }

    /**
     * Enables spider-style climbing of any solid wall (default false). Usually set through
     * {@link NavCapabilities#canClimbWalls(boolean)} rather than directly.
     *
     * @param canClimbWalls whether the entity can climb plain walls
     * @return this
     */
    public @NotNull NavOptions canClimbWalls(boolean canClimbWalls)
    {
        this.canClimbWalls = canClimbWalls;
        return this;
    }

    /**
     * Selects how the entity moves: walking ({@link MovementMode#GROUND}, default), swimming only ({@link MovementMode#AQUATIC}) or flying
     * ({@link MovementMode#FLYING}). Usually set through {@link NavCapabilities#movement(MovementMode)}.
     *
     * @param mode the movement mode
     * @return this
     */
    public @NotNull NavOptions movement(@NotNull MovementMode mode)
    {
        this.mode = mode;
        return this;
    }

    /**
     * Allows or forbids crouching under low ceilings. Crouching additionally needs an entity that can crouch ({@link NavCapabilities#canCrouch()}, players);
     * without capabilities nobody crouches.
     *
     * @param canCrouch whether crouching may be used (default true)
     * @return this
     */
    public @NotNull NavOptions canCrouch(boolean canCrouch)
    {
        this.allowCrouch = canCrouch;
        return this;
    }

    /**
     * Extra cost of every block walked crouched, on top of the block itself (default 2.0, i.e. crouching costs three times as much as walking). Crouching is
     * slow, so a detour through standing-height passages is preferred unless it is much longer.
     *
     * @param crouchPenalty the additional cost per block
     * @return this
     */
    public @NotNull NavOptions crouchPenalty(double crouchPenalty)
    {
        this.crouchPenalty = Math.max(0.0, crouchPenalty);
        return this;
    }

    /**
     * @param canSwim whether the path may go through deep water (default true)
     * @return this
     */
    public @NotNull NavOptions canSwim(boolean canSwim)
    {
        this.canSwim = canSwim;
        return this;
    }

    /**
     * @param canGapJump whether the NPC may jump over gaps (default true)
     * @return this
     */
    public @NotNull NavOptions canGapJump(boolean canGapJump)
    {
        this.canGapJump = canGapJump;
        return this;
    }

    /**
     * Makes the NPC avoid water: no swimming and a large penalty for wading. Water is still used if there is no other way (wading) unless
     * {@link #canSwim(boolean)} is false and the water is too deep.
     *
     * @param avoidWater whether to avoid water (default false)
     * @return this
     */
    public @NotNull NavOptions avoidWater(boolean avoidWater)
    {
        this.avoidWater = avoidWater;
        return this;
    }

    /**
     * @param canOpenDoors whether the NPC can open doors and fence gates; if false, closed ones block the path (default true)
     * @return this
     */
    public @NotNull NavOptions canOpenDoors(boolean canOpenDoors)
    {
        this.canOpenDoors = canOpenDoors;
        return this;
    }

    /**
     * @param climbCost cost per block climbed (default 1.8, climbing is slow)
     * @return this
     */
    public @NotNull NavOptions climbCost(double climbCost)
    {
        this.climbCost = Math.max(0.0, climbCost);
        return this;
    }

    /**
     * @param swimCost cost multiplier per block swum (default 1.6, swimming is slow)
     * @return this
     */
    public @NotNull NavOptions swimCost(double swimCost)
    {
        this.swimCost = Math.max(0.0, swimCost);
        return this;
    }

    /**
     * @param gapJumpCost extra cost of a gap jump, makes the NPC prefer walking around small gaps (default 2.0)
     * @return this
     */
    public @NotNull NavOptions gapJumpCost(double gapJumpCost)
    {
        this.gapJumpCost = Math.max(0.0, gapJumpCost);
        return this;
    }

    /**
     * @param maxGapJumpDistance maximum horizontal take-off to landing distance of a gap jump (default 4.0, still limited by {@link #gapJumpSpeed(double)})
     * @return this
     */
    public @NotNull NavOptions maxGapJumpDistance(double maxGapJumpDistance)
    {
        this.maxGapJumpDistance = Math.max(0.0, maxGapJumpDistance);
        return this;
    }

    /**
     * The fastest horizontal speed (blocks per tick) a gap jump may assume. A flat jump lasts 12 ticks, so the default of 0.27 allows gaps of two blocks;
     * about 0.36 (sprint jump) allows three.
     *
     * @param gapJumpSpeed the maximum horizontal jump speed
     * @return this
     */
    public @NotNull NavOptions gapJumpSpeed(double gapJumpSpeed)
    {
        this.gapJumpSpeed = Math.max(0.05, gapJumpSpeed);
        return this;
    }

    /**
     * Horizontal walking speed (blocks per tick) the NPC has while it walks off a ledge. The planner uses it to simulate the fall: a slow NPC drops almost
     * straight down, a fast one carries more horizontal momentum. Set it to the speed of the {@code PathTask} (default 0.25).
     *
     * @param walkSpeed blocks per tick
     * @return this
     */
    public @NotNull NavOptions walkSpeed(double walkSpeed)
    {
        this.walkSpeed = Math.max(0.02, walkSpeed);
        return this;
    }

    /**
     * Returns the step height of the NPC.
     *
     * @return the step height
     */
    public double getStepHeight()
    {
        return stepHeight;
    }

    /**
     * Returns the maximum height the NPC can jump.
     *
     * @return the maximum jump height
     */
    public double getMaxJumpHeight()
    {
        return maxJumpHeight;
    }

    /**
     * Returns whether the jump height was explicitly set.
     *
     * @return true if the jump height was explicitly set, false otherwise
     */
    public boolean isJumpHeightExplicit()
    {
        return jumpHeightExplicit;
    }

    /**
     * Returns the arc height of the NPC.
     *
     * @return the arc height
     */
    public double getArcHeight()
    {
        return arcHeight;
    }


    public double getMaxFallDistance()
    {
        return maxFallDistance;
    }

    /**
     * Returns the cost of a jump.
     *
     * @return the jump cost
     */
    public double getJumpCost()
    {
        return jumpCost;
    }

    /**
     * Returns the cost of falling.
     *
     * @return the fall cost
     */
    public double getFallCost()
    {
        return fallCost;
    }

    /**
     * Returns the penalty for moving against a wall.
     *
     * @return the wall penalty
     */
    public double getWallPenalty()
    {
        return wallPenalty;
    }

    /**
     * Returns the penalty for moving against an edge.
     *
     * @return the edge penalty
     */
    public double getEdgePenalty()
    {
        return edgePenalty;
    }

    /**
     * Returns the penalty for moving into a hazard.
     *
     * @return the hazard penalty
     */
    public double getHazardPenalty()
    {
        return hazardPenalty;
    }

    /**
     * Returns the penalty for moving into shallow water.
     *
     * @return the wade penalty
     */
    public double getWadePenalty()
    {
        return wadePenalty;
    }

    /**
     * Returns the penalty for moving into a door or fence gate.
     *
     * @return the door penalty
     */
    public double getDoorPenalty()
    {
        return doorPenalty;
    }

    /**
     * Returns the penalty for moving into a door or fence gate.
     *
     * @return the door penalty
     */
    public double getTurnPenalty()
    {
        return turnPenalty;
    }

    /**
     * Returns the weight of the heuristic function.
     *
     * @return the heuristic weight
     */
    public float getHeuristicWeight()
    {
        return heuristicWeight;
    }

    /**
     * Returns whether the NPC can climb.
     *
     * @return true if the NPC can climb, false otherwise
     */
    public boolean canClimb()
    {
        return canClimb;
    }

    /**
     * Returns whether the NPC can climb walls.
     *
     * @return true if the NPC can climb walls, false otherwise
     */
    public boolean canClimbWalls()
    {
        return canClimbWalls;
    }

    /**
     * Returns whether the NPC can crouch.
     *
     * @return true if the NPC can crouch, false otherwise
     */
    public boolean isAllowCrouch()
    {
        return allowCrouch;
    }

    /**
     * Returns the ratio of the NPC's height that can be crouched.
     *
     * @return the crouch ratio
     */
    public double getCrouchRatio()
    {
        return crouchRatio;
    }

    /**
     * Returns the penalty for moving into a crouch.
     *
     * @return the crouch penalty
     */
    public double getCrouchPenalty()
    {
        return crouchPenalty;
    }

    /**
     * Returns the movement mode of the NPC.
     *
     * @return the movement mode
     */
    public MovementMode getMode()
    {
        return mode;
    }

    /**
     * Returns whether the NPC can swim.
     *
     * @return true if the NPC can swim, false otherwise
     */
    public boolean canSwim()
    {
        return canSwim;
    }

    /**
     * Returns whether the NPC can gap jump.
     *
     * @return true if the NPC can gap jump, false otherwise
     */
    public boolean canGapJump()
    {
        return canGapJump;
    }

    /**
     * Returns whether the NPC should avoid water.
     *
     * @return true if the NPC should avoid water, false otherwise
     */
    public boolean avoidWater()
    {
        return avoidWater;
    }

    /**
     * Returns whether the NPC can open doors.
     *
     * @return true if the NPC can open doors, false otherwise
     */
    public boolean canOpenDoors()
    {
        return canOpenDoors;
    }

    /**
     * Returns the cost of climbing.
     *
     * @return the climb cost
     */
    public double getClimbCost()
    {
        return climbCost;
    }

    /**
     * Returns the cost of swimming.
     *
     * @return the swim cost
     */
    public double getSwimCost()
    {
        return swimCost;
    }

    /**
     * Returns the cost of gap jumping.
     *
     * @return the gap jump cost
     */
    public double getGapJumpCost()
    {
        return gapJumpCost;
    }

    /**
     * Returns the maximum distance the NPC can gap jump.
     *
     * @return the maximum gap jump distance
     */
    public double getMaxGapJumpDistance()
    {
        return maxGapJumpDistance;
    }

    /**
     * Returns the speed of gap jumping.
     *
     * @return the gap jump speed
     */
    public double getGapJumpSpeed()
    {
        return gapJumpSpeed;
    }

    /**
     * Returns the speed of walking.
     *
     * @return the walk speed
     */
    public double getWalkSpeed()
    {
        return walkSpeed;
    }

    /**
     * Returns whether the path should be smoothed.
     *
     * @return true if the path should be smoothed, false otherwise
     */
    public boolean isSmooth()
    {
        return smooth;
    }

    /**
     * Returns the step size for path smoothing.
     *
     * @return the step size
     */
    public double getSampleStep()
    {
        return sampleStep;
    }

    /**
     * Returns the number of steps to look ahead for path smoothing.
     *
     * @return the number of steps
     */
    public int getSmoothLookahead()
    {
        return smoothLookahead;
    }
}