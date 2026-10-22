package de.eisi05.npc.api.pathfinding.algorithm;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.pathfinding.PathResult;
import de.eisi05.npc.api.pathfinding.PathingResult;
import de.eisi05.npc.api.pathfinding.environment.BlockProfile;
import de.eisi05.npc.api.pathfinding.environment.NavSpace;
import de.eisi05.npc.api.pathfinding.movement.JumpArc;
import de.eisi05.npc.api.pathfinding.movement.MoveType;
import de.eisi05.npc.api.pathfinding.movement.MovementMode;
import de.eisi05.npc.api.pathfinding.movement.NavOptions;
import de.eisi05.npc.api.pathfinding.util.PathVisualizer;
import de.eisi05.npc.api.pathfinding.util.PathfindingUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Shared search engine behind {@link AStarPathfinder} (cell-sized steps) and {@link BoundingBoxPathfinder} (sub-cell steps + Theta* shortcuts).
 * <p>
 * Moves generated from every node:
 * <ul>
 *     <li><b>Walk / step / jump / fall</b>: onto any surface between {@code -maxFallDistance} and {@code +maxJumpHeight} in the neighbouring column.</li>
 *     <li><b>Climb</b>: up and down ladders, vines, scaffolding and similar, plus stepping onto/off them.</li>
 *     <li><b>Swim</b>: free movement through water (8 horizontal directions and up/down) and entering/leaving it.</li>
 *     <li><b>Gap jump</b>: ballistic jumps over gaps (see {@link JumpArc}) in every direction where walking does not work, validated tick by tick against
 *     the entity's bounding box.</li>
 * </ul>
 * Costs are environment aware (walls, ledges, hazards, water, doors, ...) and include a turn penalty. The open list is an indexed binary heap with decrease-key.
 * Every produced point is tagged with the {@link MoveType} used to reach it.
 * <p>
 * One instance per search; not thread-safe.
 */
public final class GridSearch
{
    private static final double SQRT2 = Math.sqrt(2.0);
    private static final double EPS = 1.0E-3;
    private static final double TAKEOFF_OFFSET = 0.3;
    private static final double MIN_JUMP_TRAVEL = 0.9;
    private static final double LANDING_MARGIN = 0.4;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private final NavSpace space;
    private final NavOptions options;
    private final double step;
    private final boolean theta;
    private final boolean allowDiagonal;
    private final int maxIterations;
    private final JumpArc arc;
    private final @Nullable NavSpace crouchSpace;
    private @Nullable BooleanSupplier cancelled;
    private @Nullable PathVisualizer visualizer;
    private final LongOpenHashSet shownBlocks = new LongOpenHashSet();

    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final NodeHeap heap = new NodeHeap();

    private final LongOpenHashSet shown = new LongOpenHashSet();

    private double originX;
    private double originZ;
    private double goalX;
    private double goalZ;
    private double goalY;
    private double originY;
    private int minWorldY;
    private PathingResult result = PathingResult.NO_PATH;
    private int iterationsUsed;

    GridSearch(@NotNull NavSpace space, double step, boolean theta, boolean allowDiagonal, int maxIterations)
    {
        this.space = space;
        this.options = space.getOptions();
        this.step = step;
        this.theta = theta;
        this.allowDiagonal = allowDiagonal;
        this.maxIterations = maxIterations;
        this.arc = JumpArc.forHeight(options.getArcHeight());
        this.crouchSpace = options.getCrouchRatio() > 0 ? space.withHeightRatio(options.getCrouchRatio()) : null;
    }

    /**
     * Makes the search poll the supplier every few dozen iterations and stop with {@link PathingResult#CANCELLED} as soon as it returns true.
     */
    void setCancellation(@Nullable BooleanSupplier cancelled)
    {
        this.cancelled = cancelled;
    }

    /**
     * Reports an expanded node: once per block as {@code visited}, and as {@code frontier} whenever the search got closer to the goal than ever before.
     */
    private void visualize(@NotNull Node n, boolean front)
    {
        PathVisualizer v = visualizer;
        if(v == null)
            return;

        var world = space.getView().getWorld();
        if(front)
            v.frontier(new Location(world, n.x, n.y, n.z));

        int bx = (int) Math.floor(n.x);
        int by = (int) Math.floor(n.y);
        int bz = (int) Math.floor(n.z);
        if(shownBlocks.add(AbstractPathfinder.packBlockCoord(bx, by, bz)))
            v.visited(new Location(world, bx + 0.5, n.y, bz + 0.5));
    }

    /**
     * Reports a special move (everything but walking) that has just become the cheapest way to {@code to}.
     */
    private void visualizeMove(@NotNull Node from, @NotNull Node to, @NotNull MoveType type, double takeX, double takeZ)
    {
        PathVisualizer v = visualizer;
        if(v == null || type == MoveType.WALK || type == MoveType.CROUCH)
            return;

        var world = space.getView().getWorld();
        double fx = Double.isNaN(takeX) ? from.x : takeX;
        double fz = Double.isNaN(takeZ) ? from.z : takeZ;
        v.move(new Location(world, fx, from.y, fz), new Location(world, to.x, to.y, to.z), type);
    }

    /**
     * Sets the visualizer that receives the relevant events of the search (visited blocks, search front, special moves, final path).
     *
     * @param visualizer the visualizer, or null to disable visualization
     */
    void setVisualizer(@Nullable PathVisualizer visualizer)
    {
        this.visualizer = visualizer;
    }

    private boolean pollCancelled(int iterations)
    {
        if((iterations & 63) != 0 || cancelled == null || !cancelled.getAsBoolean())
            return false;

        result = PathingResult.CANCELLED;
        iterationsUsed = iterations;
        return true;
    }

    @Nullable PathResult search(@NotNull Location start, @NotNull Location end, @Nullable BiConsumer<Double, Integer> listener)
            throws PathfindingUtils.PathfindingException
    {
        minWorldY = space.getView().getMinY();

        if(options.getMode() != MovementMode.GROUND)
            return search3D(start, end, listener);

        boolean exactGrid = step < 1.0 - EPS;
        originX = exactGrid ? start.getX() : Math.floor(start.getX()) + 0.5;
        originZ = exactGrid ? start.getZ() : Math.floor(start.getZ()) + 0.5;

        double startY = space.supportTop(originX, originZ, space.getSupportRadius(), start.getY() - options.getMaxFallDistance() - 0.5,
                start.getY() + options.getMaxJumpHeight(), false, 0);
        if(Double.isNaN(startY))
            startY = start.getY();

        goalX = end.getX();
        goalZ = end.getZ();
        double resolvedGoal = space.supportTop(goalX, goalZ, space.getSupportRadius(), end.getY() - 3.0, end.getY() + 1.0, false, 0);
        boolean goalInWater = options.canSwim() && !options.avoidWater() && space.isWaterAt(goalX, end.getY(), goalZ);
        if(goalInWater && (Double.isNaN(resolvedGoal) || end.getY() - resolvedGoal > 1.0))
        {
            // the requested point is swimmable water well above any floor: stay where the caller asked instead of snapping to the bottom
            resolvedGoal = end.getY();
        }
        else if(Double.isNaN(resolvedGoal))
        {
            boolean swimOrClimb = (options.canSwim() && space.isWaterAt(goalX, end.getY(), goalZ)) ||
                    (options.canClimb() && space.isClimbableAt(goalX, end.getY(), goalZ));

            if(!swimOrClimb && NpcApi.config.checkValidPath())
            {
                result = PathingResult.INVALID_END;
                throw new PathfindingUtils.PathfindingException("End location has no valid ground support: " + end, PathingResult.INVALID_END);
            }
            resolvedGoal = end.getY();
        }
        goalY = resolvedGoal;

        Node startNode = newNode(0, 0, startY);
        startNode.g = 0;
        startNode.f = startNode.h * options.getHeuristicWeight();
        heap.push(startNode);

        double startH = Math.max(1.0E-6, startNode.h);
        double minH = startNode.h;
        int iterations = 0;

        Node current;
        while((current = heap.pop()) != null)
        {
            if(iterations >= maxIterations)
            {
                result = PathingResult.ITERATIONS_EXCEEDED;
                iterationsUsed = iterations;
                return null;
            }
            iterations++;
            iterationsUsed = iterations;

            if(pollCancelled(iterations))
                return null;

            boolean front = current.h < minH;
            if(front)
                minH = current.h;
            if(listener != null)
                listener.accept(Math.clamp(1.0 - (minH / startH), 0.0, 1.0), iterations);

            visualize(current, front);

            if(isGoal(current))
            {
                result = PathingResult.SUCCESS;
                return buildPath(current, start);
            }

            current.closed = true;
            expand(current);
        }

        return null;
    }

    /**
     * @return the outcome of the last {@link #search}
     */
    @NotNull PathingResult result()
    {
        return result;
    }

    /**
     * @return the iterations the last {@link #search} used
     */
    int iterations()
    {
        return iterationsUsed;
    }

    private boolean isGoal(@NotNull Node n)
    {
        if(Math.abs(n.y - goalY) >= 1.0)
            return false;

        if(step >= 1.0 - EPS)
            return (int) Math.floor(n.x) == (int) Math.floor(goalX) && (int) Math.floor(n.z) == (int) Math.floor(goalZ);

        return Math.hypot(n.x - goalX, n.z - goalZ) <= step * 0.75 + EPS;
    }

    private void expand(@NotNull Node cur)
    {
        int dirs = allowDiagonal ? 8 : 4;
        boolean swimming = options.canSwim() && !options.avoidWater() && cur.water;
        int groundedState = 0; // 0 = unknown, 1 = yes, 2 = no
        boolean curStands = crouchSpace == null || space.fits(cur.x, cur.y, cur.z, 0);

        boolean needsGapJump = false;
        for(int d = 0; d < dirs; d++)
        {
            double walkY = tryWalk(cur, d, curStands);

            if(options.canClimb())
                tryClimbEnter(cur, d);

            if(options.canSwim() && !options.avoidWater())
                trySwimHorizontal(cur, d, swimming);

            if(options.canGapJump() && (Double.isNaN(walkY) || walkY < cur.y - options.getStepHeight() - EPS))
                needsGapJump = true;
        }

        if(options.canGapJump())
        {
            if(groundedState == 0)
                groundedState = space.isGrounded(cur.x, cur.y, cur.z) ? 1 : 2;
            if(groundedState == 1)
                tryGapJumps(cur, !needsGapJump);
        }

        if(options.canClimb() && cur.climb)
            climbVertical(cur);

        if(swimming)
            swimVertical(cur);
    }

    /**
     * Walk / step / jump / fall into the neighbouring column. Tries to do it standing first; only if that is impossible and the entity can crouch, the same
     * move is tried crouched (lower bounding box). A crouched move never jumps or falls, it only steps.
     *
     * @param curStands whether the entity fits standing at {@code cur}; if not, only the crouched move is possible
     * @return the height of the surface reached, or NaN if there is none
     */
    private double tryWalk(@NotNull Node cur, int d, boolean curStands)
    {
        if(curStands)
        {
            double ty = walkAttempt(cur, d, space, false);
            if(!Double.isNaN(ty))
                return ty;
        }

        return crouchSpace == null ? Double.NaN : walkAttempt(cur, d, crouchSpace, true);
    }

    private double walkAttempt(@NotNull Node cur, int d, @NotNull NavSpace sp, boolean crouched)
    {
        int nix = cur.ix + DX[d];
        int niz = cur.iz + DZ[d];
        double nx = originX + nix * step;
        double nz = originZ + niz * step;

        double top = cur.y + (crouched ? options.getStepHeight() : options.getMaxJumpHeight());
        double low = cur.y - (crouched ? options.getStepHeight() : options.getMaxFallDistance());

        for(int attempt = 0; attempt < 3; attempt++)
        {
            double ty = sp.supportTop(nx, nz, sp.getSupportRadius(), low, top, true, 0);
            if(Double.isNaN(ty))
                return Double.NaN;

            if(sp.canTravel(cur.x, cur.y, cur.z, nx, ty, nz))
            {
                Node nb = nodeAt(nix, niz, ty);
                if(nb != null)
                {
                    double dy = ty - cur.y;
                    boolean ramp = !crouched && Math.abs(dy) > options.getStepHeight() + EPS && sp.rampWalkable(cur.x, cur.y, cur.z, nx, ty, nz);
                    MoveType type = crouched ? MoveType.CROUCH
                            : ramp ? MoveType.WALK
                            : dy > options.getStepHeight() + EPS ? MoveType.JUMP : dy < -options.getStepHeight() - EPS ? MoveType.FALL : MoveType.WALK;
                    relax(cur, nb, cur, type, Double.NaN, Double.NaN);

                    if(theta && cur.parent != null && Math.abs(nb.y - cur.parent.y) <= options.getStepHeight() + EPS)
                    {
                        Node p = cur.parent;
                        boolean startOk = crouched || crouchSpace == null || space.fits(p.x, p.y, p.z, 0);
                        if(startOk && sp.lineWalkable(p.x, p.y, p.z, nb.x, nb.z))
                            relax(p, nb, p, crouched ? MoveType.CROUCH : MoveType.WALK, Double.NaN, Double.NaN);
                    }
                }
                return ty;
            }

            top = ty - 0.02;
            if(top < low)
                return Double.NaN;
        }
        return Double.NaN;
    }

    /**
     * Stepping onto (or off the edge onto) a ladder-like block next to the current position.
     */
    private void tryClimbEnter(@NotNull Node cur, int d)
    {
        int nix = cur.ix + DX[d];
        int niz = cur.iz + DZ[d];
        double nx = originX + nix * step;
        double nz = originZ + niz * step;
        int cy = (int) Math.floor(cur.y + EPS);

        for(int k = 0; k >= -1; k--)
        {
            double ty = cy + k;
            if(!space.isClimbableAt(nx, ty, nz) || !space.canTravel(cur.x, cur.y, cur.z, nx, ty, nz, space.climbRadius(nx, ty, nz)))
                continue;

            boolean walkOn = Math.abs(ty - cur.y) <= options.getStepHeight() + EPS && space.isRealClimbCellAt(nx, ty, nz);
            Node nb = nodeAt(nix, niz, ty);
            if(nb != null)
                relax(cur, nb, cur, walkOn ? MoveType.WALK : MoveType.CLIMB, Double.NaN, Double.NaN);
            return;
        }
    }

    /**
     * Moving up/down along a climbable column.
     */
    private void climbVertical(@NotNull Node cur)
    {
        int cy = (int) Math.floor(cur.y + EPS);

        double up = cy + 1;
        if(space.isClimbableAt(cur.x, up, cur.z) && space.canMoveVertically(cur.x, cur.y, cur.z, up))
        {
            Node nb = nodeAt(cur.ix, cur.iz, up);
            if(nb != null)
                relax(cur, nb, cur, MoveType.CLIMB, Double.NaN, Double.NaN);
        }

        double down = cur.y - cy > EPS ? cy : cy - 1;
        if(space.isClimbableAt(cur.x, down, cur.z) && space.canMoveVertically(cur.x, cur.y, cur.z, down))
        {
            Node nb = nodeAt(cur.ix, cur.iz, down);
            if(nb != null)
                relax(cur, nb, cur, MoveType.CLIMB, Double.NaN, Double.NaN);
        }
    }

    /**
     * Horizontal swim move, or entering water from land (same height or one block lower, i.e. stepping into a pool).
     */
    private void trySwimHorizontal(@NotNull Node cur, int d, boolean swimming)
    {
        int nix = cur.ix + DX[d];
        int niz = cur.iz + DZ[d];
        double nx = originX + nix * step;
        double nz = originZ + niz * step;
        int cy = (int) Math.floor(cur.y + EPS);

        for(int k = 0; k >= (swimming ? 0 : -1); k--)
        {
            double ty = cy + k;
            if(!space.isWaterAt(nx, ty, nz) || !space.canTravel(cur.x, cur.y, cur.z, nx, ty, nz))
                continue;

            Node nb = nodeAt(nix, niz, ty);
            if(nb != null)
                relax(cur, nb, cur, MoveType.SWIM, Double.NaN, Double.NaN);
            return;
        }
    }

    private void swimVertical(@NotNull Node cur)
    {
        int cy = (int) Math.floor(cur.y + EPS);

        double up = cy + 1;
        if(space.isWaterAt(cur.x, up, cur.z) && space.canMoveVertically(cur.x, cur.y, cur.z, up))
        {
            Node nb = nodeAt(cur.ix, cur.iz, up);
            if(nb != null)
                relax(cur, nb, cur, MoveType.SWIM, Double.NaN, Double.NaN);
        }

        double down = cur.y - cy > EPS ? cy : cy - 1;
        if(space.isWaterAt(cur.x, down, cur.z) && space.canMoveVertically(cur.x, cur.y, cur.z, down))
        {
            Node nb = nodeAt(cur.ix, cur.iz, down);
            if(nb != null)
                relax(cur, nb, cur, MoveType.SWIM, Double.NaN, Double.NaN);
        }
    }

    private void tryGapJumps(@NotNull Node cur, boolean upOnly)
    {
        double perCell = Math.max(1, Math.round(1.0 / step));

        double effectiveMaxDist = Math.max(options.getMaxGapJumpDistance(), options.getMaxFallDistance() * 2.0 + 4.0);
        if(upOnly)
            effectiveMaxDist = Math.min(effectiveMaxDist, 3.0);
        int stride = upOnly ? Math.max(1, (int) Math.round(perCell / 2.0)) : 1;
        int maxDist = (int) Math.ceil((effectiveMaxDist + TAKEOFF_OFFSET + 1.5) * perCell);
        int minCells = step < 1.0 - EPS ? 1 : 2;

        for(int cx = -maxDist; cx <= maxDist; cx += stride)
        {
            for(int cz = -maxDist; cz <= maxDist; cz += stride)
            {
                if(Math.abs(cx) < minCells && Math.abs(cz) < minCells)
                    continue;

                double h = Math.hypot(cx, cz);
                double cellDist = h / perCell;
                if(cellDist > effectiveMaxDist + TAKEOFF_OFFSET + 1.5)
                    continue;

                int nix = cur.ix + cx;
                int niz = cur.iz + cz;
                double lx = originX + nix * step;
                double lz = originZ + niz * step;

                double ux = cx / h;
                double uz = cz / h;

                double tx = cur.x;
                double tz = cur.z;
                double maxTakeOff = (step < 1.0 - EPS) ? TAKEOFF_OFFSET : 0.6;
                for(double d = maxTakeOff; d >= 0; d -= 0.1) {
                    double ptx = cur.x + ux * d;
                    double ptz = cur.z + uz * d;
                    if(space.isGrounded(ptx, cur.y, ptz) && space.fits(ptx, cur.y, ptz, 0)) {
                        tx = ptx;
                        tz = ptz;
                        break;
                    }
                }

                double ly = Double.NaN;
                double landingX = lx;
                double landingZ = lz;
                double maxLandOffset = (step < 1.0 - EPS) ? 0.0 : 0.6;
                for(double d = maxLandOffset; d >= 0; d -= 0.1) {
                    double plx = lx - ux * d;
                    double plz = lz - uz * d;
                    double lowest = upOnly ? cur.y + options.getStepHeight() + 0.05 : cur.y - options.getMaxFallDistance();
                    ly = space.supportTop(plx, plz, space.getSupportRadius(), lowest, cur.y + Math.min(arc.apex() - 0.05,
                            options.getMaxJumpHeight()), true, 0);
                    if(!Double.isNaN(ly)) {
                        landingX = plx;
                        landingZ = plz;
                        break;
                    }
                }

                if(Double.isNaN(ly))
                    continue;

                int ticks = arc.ticksTo(ly - cur.y);
                if(ticks < 0)
                    continue;

                if(ly < cur.y - options.getStepHeight() - EPS && cur.y - ly <= options.getMaxFallDistance() && space.fallClear(cur.x, cur.y, cur.z, lx, ly, lz))
                {
                    Node fall = nodeAt(nix, niz, ly);
                    if(fall != null)
                        relax(cur, fall, cur, MoveType.FALL, Double.NaN, Double.NaN);
                    continue;
                }

                boolean walkable = Math.abs(ly - cur.y) <= options.getStepHeight() + EPS
                        ? space.canTravel(cur.x, cur.y, cur.z, landingX, ly, landingZ) && space.floorAlong(cur.x, cur.y, cur.z, landingX, ly, landingZ)
                        : space.rampWalkable(cur.x, cur.y, cur.z, landingX, ly, landingZ) && space.canTravel(cur.x, cur.y, cur.z, landingX, ly, landingZ);
                if(walkable)
                    continue;

                double dist = Math.hypot(landingX - tx, landingZ - tz);
                double maxAllowedDist = Math.max(options.getMaxGapJumpDistance(), ticks * options.getGapJumpSpeed());
                if(dist > maxAllowedDist)
                    continue;

                if(dist / ticks > options.getGapJumpSpeed())
                    continue;

                if(!space.arcClear(arc, tx, cur.y, tz, lx, ly, lz, ticks))
                    continue;

                Node nb = nodeAt(nix, niz, ly);
                if(nb != null)
                    relax(cur, nb, cur, MoveType.GAP_JUMP, tx, tz);
            }
        }
    }

    /**
     * Returns the node for a standing position, creating it if necessary; null if it is already closed.
     */
    private @Nullable Node nodeAt(int ix, int iz, double y)
    {
        long key = key(ix, iz, y);
        Node nb = nodes.get(key);
        if(nb == null)
            return newNode(ix, iz, y);
        return nb.closed ? null : nb;
    }

    /**
     * Tries to reach {@code nb} from {@code from}; {@code dirFrom} supplies the previous heading for the turn penalty.
     */
    private void relax(@NotNull Node from, @NotNull Node nb, @NotNull Node dirFrom, @NotNull MoveType type, double takeX, double takeZ)
    {
        double dx = nb.x - from.x;
        double dz = nb.z - from.z;
        double dy = nb.y - from.y;
        double travel = Math.hypot(dx, dz);
        if(travel < 1.0E-9 && Math.abs(dy) < 1.0E-9)
            return;

        double base;
        switch(type)
        {
            case CLIMB -> base = travel + (dy >= 0 ? dy * options.getClimbCost() : -dy * options.getClimbCost() * 0.6);
            case CROUCH -> base = travel * (1.0 + options.getCrouchPenalty()) + Math.abs(dy) * 0.1;
            case SWIM -> base = Math.sqrt(travel * travel + dy * dy) * options.getSwimCost();
            case GAP_JUMP -> base = Math.hypot(nb.x - takeX, nb.z - takeZ) * 1.5 + options.getGapJumpCost();
            default ->
            {
                double vertical;
                if(type == MoveType.WALK)
                    vertical = Math.abs(dy) * 0.2;
                else if(dy > options.getStepHeight() + EPS)
                    vertical = options.getJumpCost() + (dy - options.getStepHeight()) * 0.5;
                else if(dy < -options.getStepHeight() - EPS)
                    vertical = options.getFallCost() * -dy;
                else
                    vertical = Math.abs(dy) * 0.1;
                base = travel + vertical;
            }
        }

        if(type != MoveType.SWIM && nb.water)
        {
            base += options.getWadePenalty() * (nb.deep ? 2.0 : 1.0) * Math.max(step, 0.5);
            if(options.avoidWater())
                base += 6.0 * Math.max(step, 0.5);
        }

        double dirX = dirFrom.dirX;
        double dirZ = dirFrom.dirZ;
        double turn = 0;
        if(travel > 1.0E-6)
        {
            dirX = dx / travel;
            dirZ = dz / travel;
            if(dirFrom.dirX != 0 || dirFrom.dirZ != 0)
                turn = options.getTurnPenalty() * (1.0 - (dirX * dirFrom.dirX + dirZ * dirFrom.dirZ));
        }

        float g = (float) (from.g + base + nb.local * (travel > 1.0E-6 ? step : 0.5) + turn);
        if(g < nb.g)
        {
            nb.g = g;
            nb.f = g + nb.h * options.getHeuristicWeight();
            nb.parent = from;
            nb.type = type;
            nb.takeX = takeX;
            nb.takeZ = takeZ;
            nb.dirX = dirX;
            nb.dirZ = dirZ;
            heap.pushOrUpdate(nb);
            visualizeMove(from, nb, type, takeX, takeZ);
        }
    }

    private @NotNull Node newNode(int ix, int iz, double y)
    {
        Node n = new Node(ix, iz, originX + ix * step, y, originZ + iz * step);
        n.local = space.localCost(n.x, n.y, n.z);
        n.water = space.isWaterAt(n.x, n.y, n.z);
        n.deep = n.water && space.isDeepWaterAt(n.x, n.y, n.z);
        n.climb = space.isClimbableAt(n.x, n.y, n.z);

        double dx = Math.abs(n.x - goalX);
        double dz = Math.abs(n.z - goalZ);
        double min = Math.min(dx, dz);
        double max = Math.max(dx, dz);
        n.h = (float) (max + (SQRT2 - 1.0) * min + Math.abs(n.y - goalY) * 0.5);

        nodes.put(key(ix, iz, y), n);
        return n;
    }

    private long key(int ix, int iz, double y)
    {
        return AbstractPathfinder.packBlockCoord(ix, (int) Math.round((y - minWorldY) * 8.0), iz);
    }

    /**
     * A* (with Theta* shortcuts) in free 3D space for {@link MovementMode#FLYING} (any open space) and {@link MovementMode#AQUATIC} (only through water) entities. The grid is
     * anchored at the exact start position, there is no ground, gravity or stepping; moves are validated by sweeping the entity box along the segment.
     */
    private @Nullable PathResult search3D(@NotNull Location start, @NotNull Location end, @Nullable BiConsumer<Double, Integer> listener)
            throws PathfindingUtils.PathfindingException
    {
        boolean aquatic = options.getMode() == MovementMode.AQUATIC;

        if(step < 1.0 - EPS)
        {
            originX = start.getX();
            originY = start.getY();
            originZ = start.getZ();
        }
        else
        {
            originX = Math.floor(start.getX()) + 0.5;
            originZ = Math.floor(start.getZ()) + 0.5;
            originY = Math.floor(start.getY() + space.getHeight() * 0.5) + 0.5 - space.getHeight() * 0.5;
        }
        goalX = end.getX();
        goalY = end.getY();
        goalZ = end.getZ();

        if(NpcApi.config.checkValidPath())
        {
            if(aquatic && !space.bodyInWater(originX, originY, originZ))
            {
                result = PathingResult.INVALID_START;
                throw new PathfindingUtils.PathfindingException("Start location is not in water (aquatic entities must start in water): " + start,
                        PathingResult.INVALID_START);
            }

            if(!space.fits3(goalX, goalY, goalZ, aquatic))
            {
                result = PathingResult.INVALID_END;
                throw new PathfindingUtils.PathfindingException(aquatic ? "End location is not in water (aquatic entities can only go to water): " + end
                        : "End location is inside blocks (flying entities need open space): " + end, PathingResult.INVALID_END);
            }
        }

        Node startNode = newNode3(0, 0, 0);
        startNode.g = 0;
        startNode.f = startNode.h * options.getHeuristicWeight();
        heap.push(startNode);

        double startH = Math.max(1.0E-6, startNode.h);
        double minH = startNode.h;
        int iterations = 0;

        Node current;
        while((current = heap.pop()) != null)
        {
            if(iterations >= maxIterations)
            {
                result = PathingResult.ITERATIONS_EXCEEDED;
                iterationsUsed = iterations;
                return null;
            }
            iterations++;
            iterationsUsed = iterations;

            if(pollCancelled(iterations))
                return null;

            boolean front = current.h < minH;
            if(front)
                minH = current.h;
            if(listener != null)
                listener.accept(Math.clamp(1.0 - (minH / startH), 0.0, 1.0), iterations);

            visualize(current, front);

            if(Math.sqrt(sq(current.x - goalX) + sq(current.y - goalY) + sq(current.z - goalZ)) <= step * 0.9 + EPS)
            {
                result = PathingResult.SUCCESS;
                return buildPath3(current, start, aquatic);
            }

            current.closed = true;
            expand3(current, aquatic);
        }

        return null;
    }

    private static double sq(double v)
    {
        return v * v;
    }

    private void expand3(@NotNull Node cur, boolean aquatic)
    {
        for(int dx = -1; dx <= 1; dx++)
        {
            for(int dy = -1; dy <= 1; dy++)
            {
                for(int dz = -1; dz <= 1; dz++)
                {
                    if(dx == 0 && dy == 0 && dz == 0)
                        continue;

                    if(!allowDiagonal && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1)
                        continue;

                    int nix = cur.ix + dx;
                    int niy = cur.iy + dy;
                    int niz = cur.iz + dz;
                    double nx = originX + nix * step;
                    double ny = originY + niy * step;
                    double nz = originZ + niz * step;

                    if(!space.fits3(nx, ny, nz, aquatic) || !space.line3(cur.x, cur.y, cur.z, nx, ny, nz, aquatic, 0))
                        continue;

                    Node nb = nodeAt3(nix, niy, niz);
                    if(nb == null)
                        continue;

                    MoveType type = aquatic ? MoveType.SWIM : MoveType.FLY;
                    relax3(cur, nb, cur, type);

                    if(theta && cur.parent != null && space.line3(cur.parent.x, cur.parent.y, cur.parent.z, nb.x, nb.y, nb.z, aquatic, 0.05))
                        relax3(cur.parent, nb, cur.parent, type);
                }
            }
        }
    }

    private void relax3(@NotNull Node from, @NotNull Node nb, @NotNull Node dirFrom, @NotNull MoveType type)
    {
        double dx = nb.x - from.x;
        double dy = nb.y - from.y;
        double dz = nb.z - from.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if(dist < 1.0E-9)
            return;

        double dirX = dx / dist;
        double dirY = dy / dist;
        double dirZ = dz / dist;

        double turn = 0;
        if(dirFrom.dirX != 0 || dirFrom.dirY != 0 || dirFrom.dirZ != 0)
            turn = options.getTurnPenalty() * (1.0 - (dirX * dirFrom.dirX + dirY * dirFrom.dirY + dirZ * dirFrom.dirZ));

        float g = (float) (from.g + dist + nb.local * step + turn);
        if(g < nb.g)
        {
            nb.g = g;
            nb.f = g + nb.h * options.getHeuristicWeight();
            nb.parent = from;
            nb.type = type;
            nb.dirX = dirX;
            nb.dirY = dirY;
            nb.dirZ = dirZ;
            heap.pushOrUpdate(nb);
            visualizeMove(from, nb, type, Double.NaN, Double.NaN);
        }
    }

    private @NotNull Node newNode3(int ix, int iy, int iz)
    {
        Node n = new Node(ix, iz, originX + ix * step, originY + iy * step, originZ + iz * step);
        n.iy = iy;
        n.local = space.localCost3(n.x, n.y, n.z);
        n.h = (float) Math.sqrt(sq(n.x - goalX) + sq(n.y - goalY) + sq(n.z - goalZ));
        nodes.put(AbstractPathfinder.packBlockCoord(ix, iy, iz), n);
        return n;
    }

    private @Nullable Node nodeAt3(int ix, int iy, int iz)
    {
        Node nb = nodes.get(AbstractPathfinder.packBlockCoord(ix, iy, iz));
        if(nb == null)
            return newNode3(ix, iy, iz);
        return nb.closed ? null : nb;
    }

    private @NotNull PathResult buildPath3(@NotNull Node goalNode, @NotNull Location start, boolean aquatic)
    {
        MoveType type = aquatic ? MoveType.SWIM : MoveType.FLY;

        List<Pt> pts = new ArrayList<>();
        for(Node n = goalNode; n != null; n = n.parent)
            pts.add(new Pt(n.x, n.y, n.z, n.parent == null ? MoveType.WALK : type, Double.NaN, Double.NaN));
        Collections.reverse(pts);

        if(pts.size() >= 2)
        {
            Pt first = pts.getFirst();
            Pt second = pts.get(1);
            if(sq(first.x - start.getX()) + sq(first.y - start.getY()) + sq(first.z - start.getZ()) > 1.0E-6 &&
                    space.line3(start.getX(), start.getY(), start.getZ(), second.x, second.y, second.z, aquatic, 0))
            {
                first.x = start.getX();
                first.y = start.getY();
                first.z = start.getZ();
            }
        }

        if(pts.size() >= 2)
        {
            Pt last = pts.getLast();
            Pt prev = pts.get(pts.size() - 2);
            if(space.fits3(goalX, goalY, goalZ, aquatic) && space.line3(prev.x, prev.y, prev.z, goalX, goalY, goalZ, aquatic, 0))
            {
                last.x = goalX;
                last.y = goalY;
                last.z = goalZ;
            }
        }
        else
            pts.add(new Pt(goalX, goalY, goalZ, type, Double.NaN, Double.NaN));

        if(options.isSmooth() && pts.size() > 2)
        {
            List<Pt> out = new ArrayList<>();
            out.add(pts.getFirst());
            int i = 0;
            int n = pts.size();
            while(i < n - 1)
            {
                int best = i + 1;
                Pt a = pts.get(i);
                for(int j = Math.min(n - 1, i + options.getSmoothLookahead()); j > i + 1; j--)
                {
                    Pt b = pts.get(j);
                    if(space.line3(a.x, a.y, a.z, b.x, b.y, b.z, aquatic, 0.05))
                    {
                        best = j;
                        break;
                    }
                }
                out.add(pts.get(best));
                i = best;
            }
            pts = out;
        }

        if(pts.size() > 2)
        {
            List<Pt> out = new ArrayList<>();
            out.add(pts.getFirst());
            for(int i = 1; i < pts.size() - 1; i++)
            {
                Pt a = out.getLast();
                Pt b = pts.get(i);
                Pt c = pts.get(i + 1);
                double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
                double bcx = c.x - b.x, bcy = c.y - b.y, bcz = c.z - b.z;
                double cx = aby * bcz - abz * bcy;
                double cy = abz * bcx - abx * bcz;
                double cz = abx * bcy - aby * bcx;
                double dot = abx * bcx + aby * bcy + abz * bcz;
                if(cx * cx + cy * cy + cz * cz < 1.0E-10 && dot > 0)
                    continue;
                out.add(b);
            }
            out.add(pts.getLast());
            pts = out;
        }

        List<Location> points = new ArrayList<>(pts.size());
        List<MoveType> moves = new ArrayList<>(pts.size());
        for(Pt p : pts)
        {
            points.add(new Location(space.getView().getWorld(), p.x, p.y, p.z));
            moves.add(p.type);
        }
        if(visualizer != null)
            visualizer.path(points, moves);
        return new PathResult(points, moves);
    }

    /**
     * A path point under construction: position, how it is reached, and (for gap jumps) the take-off position.
     */
    private static final class Pt
    {
        double x;
        double y;
        double z;
        final MoveType type;
        final double takeX;
        final double takeZ;

        Pt(double x, double y, double z, MoveType type, double takeX, double takeZ)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.takeX = takeX;
            this.takeZ = takeZ;
        }
    }

    private @NotNull PathResult buildPath(@NotNull Node goalNode, @NotNull Location start)
    {
        List<Pt> pts = new ArrayList<>();
        for(Node n = goalNode; n != null; n = n.parent)
            pts.add(new Pt(n.x, n.y, n.z, n.parent == null ? MoveType.WALK : n.type, n.takeX, n.takeZ));
        Collections.reverse(pts);

        snapEndpoints(pts, start);

        if(options.isSmooth() && allowDiagonal)
            pts = smooth(pts);

        pts = extendShortJumps(pts);
        pts = addTransitions(pts);
        pts = removeRedundant(pts);
        offsetWallClimbPoints(pts);

        List<Location> points = new ArrayList<>(pts.size());
        List<MoveType> moves = new ArrayList<>(pts.size());
        for(Pt p : pts)
        {
            points.add(new Location(space.getView().getWorld(), p.x, p.y, p.z));
            moves.add(p.type);
        }
        if(visualizer != null)
            visualizer.path(points, moves);
        return new PathResult(points, moves);
    }

    /**
     * A jump up onto a ledge needs forward travel: the NPC rises about a block while it moves forward, and it has to end up <b>on</b> the ledge, not hovering in
     * front of its face. The cell-sized grid gets this for free (the landing is a full block ahead, in the middle of the next cell), but on a fine grid the
     * landing node can be a quarter block from the take-off and still short of the edge.
     * <p>
     * Moves such a landing point forward so that it lies at least {@value #MIN_JUMP_TRAVEL} blocks from the take-off and {@value #LANDING_MARGIN} blocks
     * inside the surface, and drops level points that this leaves behind it (never the goal).
     */
    private @NotNull List<Pt> extendShortJumps(@NotNull List<Pt> p)
    {
        for(int i = 1; i < p.size(); i++)
        {
            Pt b = p.get(i);
            Pt a = p.get(i - 1);

            if (b.type == MoveType.GAP_JUMP)
            {
                double startX = Double.isNaN(b.takeX) ? a.x : b.takeX;
                double startZ = Double.isNaN(b.takeZ) ? a.z : b.takeZ;
                double h = Math.hypot(b.x - startX, b.z - startZ);

                if (h > 1.0E-6)
                {
                    double ux = (b.x - startX) / h;
                    double uz = (b.z - startZ) / h;

                    double bestPush = 0.0;
                    for (double d = 0.1; d <= 0.5; d += 0.1)
                    {
                        double nx = b.x + ux * d;
                        double nz = b.z + uz * d;
                        if (space.fits(nx, b.y, nz, 0) && !Double.isNaN(space.supportTop(nx, nz, space.getSupportRadius(), b.y - 0.1, b.y + 0.1, true, 0)))
                            bestPush = d;
                        else
                            break;
                    }
                    if (bestPush > 0)
                    {
                        b.x += ux * bestPush;
                        b.z += uz * bestPush;
                    }
                }
                continue;
            }

            if(b.type != MoveType.JUMP || b.y - a.y <= options.getStepHeight() + 0.01)
                continue;

            double h = Math.hypot(b.x - a.x, b.z - a.z);
            double ux;
            double uz;
            if(h > 1.0E-6)
            {
                ux = (b.x - a.x) / h;
                uz = (b.z - a.z) / h;
            }
            else if(i + 1 < p.size())
            {
                Pt c = p.get(i + 1);
                double ch = Math.hypot(c.x - b.x, c.z - b.z);
                if(ch < 1.0E-6)
                    continue;
                ux = (c.x - b.x) / ch;
                uz = (c.z - b.z) / ch;
            }
            else
                continue;

            double edge = Double.NaN;
            for(double d = 0; d <= 3.0; d += 0.05)
            {
                if(!Double.isNaN(space.supportTop(a.x + ux * d, a.z + uz * d, 0.05, b.y - 0.05, b.y + 0.05, false, 0)))
                {
                    edge = d;
                    break;
                }
            }
            if(Double.isNaN(edge))
                continue;

            double wanted = Math.max(MIN_JUMP_TRAVEL, edge + LANDING_MARGIN);
            if(h >= wanted - 0.05)
                continue;

            double nx = a.x + ux * wanted;
            double nz = a.z + uz * wanted;
            if(!space.fits(nx, b.y, nz, 0) || Double.isNaN(space.supportTop(nx, nz, space.getSupportRadius(), b.y - 0.05, b.y + 0.05, true, 0)))
                continue;

            b.x = nx;
            b.z = nz;

            while(i + 1 < p.size() - 1)
            {
                Pt c = p.get(i + 1);
                if(c.type != MoveType.WALK || Math.abs(c.y - b.y) > 0.01 || (c.x - a.x) * ux + (c.z - a.z) * uz >= wanted - 1.0E-6)
                    break;
                p.remove(i + 1);
            }
        }
        return p;
    }

    /**
     * Wall climbers wider than a block (spiders) are planned with a body that may touch the wall, so the grid positions on the wall are cell centres. Move the
     * climb points out so the real body sits flush against the wall face instead of sinking into it.
     */
    private void offsetWallClimbPoints(@NotNull List<Pt> pts)
    {
        if(!options.canClimbWalls() || space.getRadius() <= 0.5 + 1.0E-6)
            return;

        for(Pt p : pts)
        {
            if(p.type != MoveType.CLIMB || space.isRealClimbCellAt(p.x, p.y, p.z))
                continue;

            int bx = (int) Math.floor(p.x);
            int by = (int) Math.floor(p.y + EPS);
            int bz = (int) Math.floor(p.z);
            for(int side = 0; side < 4; side++)
            {
                BlockProfile n = space.profile(bx + DX[side], by, bz + DZ[side]);
                if(!n.hasCollision() || n.topY() < 0.8)
                    continue;

                if(DX[side] == 1)
                    p.x = bx + 1 - space.getRadius();
                else if(DX[side] == -1)
                    p.x = bx + space.getRadius();
                else if(DZ[side] == 1)
                    p.z = bz + 1 - space.getRadius();
                else
                    p.z = bz + space.getRadius();
            }
        }
    }

    /**
     * Replaces the cell-centre first/last node by the exact start/goal coordinates whenever the entity can actually move there. This is what makes the NPC
     * end exactly where it was told to instead of at the middle of the block.
     */
    private void snapEndpoints(@NotNull List<Pt> pts, @NotNull Location start)
    {
        if(pts.size() >= 2)
        {
            Pt last = pts.getLast();
            Pt prev = pts.get(pts.size() - 2);
            if(last.type != MoveType.CLIMB && last.type != MoveType.GAP_JUMP && Math.hypot(last.x - goalX, last.z - goalZ) > 0.01 &&
                    spaceFor(last.type).canTravel(prev.x, prev.y, prev.z, goalX, last.y, goalZ))
            {
                last.x = goalX;
                last.z = goalZ;
            }

            Pt first = pts.getFirst();
            Pt second = pts.get(1);
            if(second.type != MoveType.CLIMB && Math.hypot(first.x - start.getX(), first.z - start.getZ()) > 0.01 &&
                    spaceFor(second.type).canTravel(start.getX(), first.y, start.getZ(), second.x, second.y, second.z))
            {
                first.x = start.getX();
                first.z = start.getZ();
            }
        }
        else if(pts.size() == 1)
        {
            Pt only = pts.getFirst();
            pts.addFirst(new Pt(start.getX(), only.y, start.getZ(), MoveType.WALK, Double.NaN, Double.NaN));
            only.x = goalX;
            only.z = goalZ;
        }
    }

    /**
     * @return the collision space whose body size matches the move (the crouched body for crouched moves)
     */
    private @NotNull NavSpace spaceFor(@NotNull MoveType type)
    {
        return type == MoveType.CROUCH && crouchSpace != null ? crouchSpace : space;
    }

    /**
     * Movement class used by smoothing: 0 = ground walking, 1 = swimming, -1 = anything that must stay exactly as planned.
     */
    private static int smoothClass(@NotNull MoveType type)
    {
        return switch(type)
        {
            case WALK -> 0;
            case SWIM -> 1;
            case CROUCH -> 2;
            default -> -1;
        };
    }

    /**
     * Greedy string pulling: from every anchor jump to the furthest node that is still reachable by a clean straight walk (or swim) at the same level.
     * Jumps, falls, climbs and gap jumps are never merged away.
     */
    private @NotNull List<Pt> smooth(@NotNull List<Pt> p)
    {
        int n = p.size();
        if(n <= 2)
            return p;

        List<Pt> out = new ArrayList<>();
        out.add(p.getFirst());

        int i = 0;
        while(i < n - 1)
        {
            int best = i + 1;
            int cls = smoothClass(p.get(i + 1).type);

            if(cls >= 0)
            {
                int limit = i + 1;
                while(limit + 1 < n && limit + 1 - i <= options.getSmoothLookahead() && smoothClass(p.get(limit + 1).type) == cls)
                    limit++;

                Pt a = p.get(i);
                if(cls == 0 && crouchSpace != null && !space.fits(a.x, a.y, a.z, 0))
                    limit = i + 1;

                for(int j = limit; j > i + 1; j--)
                {
                    Pt b = p.get(j);
                    if(Math.abs(b.y - a.y) > 0.01)
                        continue;

                    boolean clear = switch(cls)
                    {
                        case 0 -> space.lineWalkable(a.x, a.y, a.z, b.x, b.z);
                        case 2 -> crouchSpace != null && crouchSpace.lineWalkable(a.x, a.y, a.z, b.x, b.z);
                        default -> space.lineSwimmable(a.x, a.y, a.z, b.x, b.z);
                    };
                    if(clear)
                    {
                        best = j;
                        break;
                    }
                }
            }

            out.add(p.get(best));
            i = best;
        }
        return out;
    }

    /**
     * Adds explicit take-off points in front of ledges that need a jump (and in front of gap jumps) and drop points at ledges the NPC walks off, so
     * {@code PathTask} jumps at the edge and falls straight down instead of cutting through the corner.
     */
    private @NotNull List<Pt> addTransitions(@NotNull List<Pt> p)
    {
        List<Pt> out = new ArrayList<>(p.size() + 4);
        for(int i = 0; i < p.size(); i++)
        {
            Pt b = p.get(i);
            if(i > 0)
            {
                Pt a = p.get(i - 1);
                double dy = b.y - a.y;
                double dx = b.x - a.x;
                double dz = b.z - a.z;
                double h = Math.hypot(dx, dz);

                if(b.type == MoveType.GAP_JUMP)
                {
                    if(!Double.isNaN(b.takeX) && Math.hypot(b.takeX - a.x, b.takeZ - a.z) > 0.05)
                        out.add(new Pt(b.takeX, a.y, b.takeZ, MoveType.WALK, Double.NaN, Double.NaN));
                }
                else if(!(b.type == MoveType.WALK && Math.abs(dy) > options.getStepHeight() + 0.01)
                        && (b.type == MoveType.JUMP || b.type == MoveType.WALK || b.type == MoveType.FALL))
                {
                    if(dy > options.getStepHeight() + 0.01 && h > 0.01)
                    {
                        double sample = Math.max(0.05, space.getSampleStep() / 2.0);
                        int n = Math.max(1, (int) Math.ceil(h / sample));
                        double bestT = 0.0;
                        for(int k = 1; k <= n; k++)
                        {
                            double t = (double) k / n;
                            if(!space.fits(a.x + dx * t, a.y, a.z + dz * t, 0.1))
                                break;
                            bestT = t;
                        }

                        if(bestT * h > 0.15 && bestT < 1.0)
                            out.add(new Pt(a.x + dx * bestT, a.y, a.z + dz * bestT, MoveType.WALK, Double.NaN, Double.NaN));
                    }
                    else if(dy < -options.getStepHeight() - 0.01 && h > 0.01)
                        out.add(new Pt(b.x, a.y, b.z, MoveType.WALK, Double.NaN, Double.NaN));
                }
            }
            out.add(b);
        }
        return out;
    }

    /**
     * Removes points that carry no information: collinear same-level points of the same movement class and intermediate points of vertical climbs.
     */
    private @NotNull List<Pt> removeRedundant(@NotNull List<Pt> p)
    {
        if(p.size() <= 2)
            return p;

        List<Pt> out = new ArrayList<>(p.size());
        out.add(p.getFirst());

        for(int i = 1; i < p.size() - 1; i++)
        {
            Pt a = out.getLast();
            Pt b = p.get(i);
            Pt c = p.get(i + 1);

            double abx = b.x - a.x;
            double abz = b.z - a.z;
            double bcx = c.x - b.x;
            double bcz = c.z - b.z;

            if(b.type == MoveType.CLIMB && c.type == MoveType.CLIMB && Math.hypot(abx, abz) < 0.01 && Math.hypot(bcx, bcz) < 0.01 &&
                    (b.y - a.y) * (c.y - b.y) > 0)
                continue;

            boolean mergeable = (b.type == MoveType.WALK && c.type == MoveType.WALK) || (b.type == MoveType.SWIM && c.type == MoveType.SWIM) ||
                    (b.type == MoveType.CROUCH && c.type == MoveType.CROUCH);
            if(mergeable && Math.abs(a.y - b.y) < 0.01 && Math.abs(b.y - c.y) < 0.01)
            {
                double cross = abx * bcz - abz * bcx;
                double dot = abx * bcx + abz * bcz;
                if(Math.abs(cross) < 1.0E-6 && dot > 0)
                    continue;
                if(Math.hypot(abx, abz) < 0.01)
                    continue;
            }
            out.add(b);
        }

        out.add(p.getLast());
        return out;
    }

    private static final class Node
    {
        final int ix;
        final int iz;
        int iy;
        double dirY;
        final double x;
        final double y;
        final double z;
        float g = Float.MAX_VALUE;
        float h;
        float f;
        float local;
        double dirX;
        double dirZ;
        double takeX = Double.NaN;
        double takeZ = Double.NaN;
        MoveType type = MoveType.WALK;
        boolean water;
        boolean deep;
        boolean climb;
        boolean closed;
        int heapIndex = -1;
        Node parent;

        Node(int ix, int iz, double x, double y, double z)
        {
            this.ix = ix;
            this.iz = iz;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Binary min-heap on {@code f} (ties broken towards the node closer to the goal) with O(log n) decrease-key.
     */
    private static final class NodeHeap
    {
        private Node[] items = new Node[256];
        private int size;

        void push(@NotNull Node n)
        {
            if(size == items.length)
                items = Arrays.copyOf(items, size * 2);
            items[size] = n;
            n.heapIndex = size;
            siftUp(size++);
        }

        void pushOrUpdate(@NotNull Node n)
        {
            if(n.heapIndex < 0)
                push(n);
            else
                siftUp(n.heapIndex);
        }

        @Nullable Node pop()
        {
            if(size == 0)
                return null;

            Node top = items[0];
            Node last = items[--size];
            items[size] = null;
            top.heapIndex = -1;

            if(size > 0)
            {
                items[0] = last;
                last.heapIndex = 0;
                siftDown(0);
            }
            return top;
        }

        private boolean less(@NotNull Node a, @NotNull Node b)
        {
            return a.f < b.f || (a.f == b.f && a.h < b.h);
        }

        private void siftUp(int i)
        {
            Node n = items[i];
            while(i > 0)
            {
                int parent = (i - 1) >>> 1;
                Node p = items[parent];
                if(!less(n, p))
                    break;
                items[i] = p;
                p.heapIndex = i;
                i = parent;
            }
            items[i] = n;
            n.heapIndex = i;
        }

        private void siftDown(int i)
        {
            Node n = items[i];
            int half = size >>> 1;
            while(i < half)
            {
                int child = 2 * i + 1;
                Node c = items[child];
                int right = child + 1;
                if(right < size && less(items[right], c))
                    c = items[child = right];
                if(!less(c, n))
                    break;
                items[i] = c;
                c.heapIndex = i;
                i = child;
            }
            items[i] = n;
            n.heapIndex = i;
        }
    }
}