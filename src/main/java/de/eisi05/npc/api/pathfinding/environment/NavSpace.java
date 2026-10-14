package de.eisi05.npc.api.pathfinding.environment;

import de.eisi05.npc.api.pathfinding.algorithm.AStarPathfinder;
import de.eisi05.npc.api.pathfinding.algorithm.AbstractPathfinder;
import de.eisi05.npc.api.pathfinding.algorithm.BoundingBoxPathfinder;
import de.eisi05.npc.api.pathfinding.movement.JumpArc;
import de.eisi05.npc.api.pathfinding.movement.NavOptions;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * The single place where "can an entity of this size be here / move there" is answered. Used by both {@link AStarPathfinder} and
 * {@link BoundingBoxPathfinder}, so the two always agree on collision.
 * <ul>
 *     <li>{@link #fits} - exact swept-free AABB test against the real collision shapes (slabs, stairs, fences, walls, trapdoors, ...) plus hazard cells.</li>
 *     <li>{@link #supportTop} - finds the exact surface height an entity can stand on inside a height window (replaces the old "floor block + 1" logic).</li>
 *     <li>{@link #canTravel} - validates a single step / jump / drop between two standing positions including corner cutting and headroom.</li>
 *     <li>{@link #lineWalkable} - validates a long straight walk with continuous ground support (used for any-angle shortcuts and smoothing).</li>
 *     <li>{@link #localCost} - environment awareness: walls, ledges, hazards, water, webs, doors, slippery or sticky floors.</li>
 * </ul>
 * Not thread-safe; one instance per search.
 */
public final class NavSpace
{
    private static final double E = 1.0E-3;
    private static final int[] SIDE_X = {1, -1, 0, 0};
    private static final int[] SIDE_Z = {0, 0, 1, -1};

    final BlockView view;
    final NavOptions options;
    final double width;
    final double height;
    final double radius;
    final double supportRadius;
    final double lineSupportRadius;
    final double sampleStep;

    private final Long2ObjectOpenHashMap<BlockProfile> memo;

    public NavSpace(@NotNull BlockView view, double entityHeight, double entityWidth, @NotNull NavOptions options)
    {
        this.view = view;
        this.options = options;
        this.width = Math.max(0.1, entityWidth);
        this.height = Math.max(0.1, entityHeight);
        this.radius = this.width / 2.0;
        this.supportRadius = Math.max(0.05, radius * 0.85);
        this.lineSupportRadius = Math.max(0.04, radius * 0.5);
        this.sampleStep = Math.clamp(options.getSampleStep(), 0.05, Math.max(0.05, radius));
        this.memo = new Long2ObjectOpenHashMap<>();
    }

    /**
     * A sibling space for the same entity with a different height (the crouched body). It shares the block view and the block memo, so looking at the same
     * blocks for both poses costs nothing extra.
     */
    private NavSpace(@NotNull NavSpace base, double height)
    {
        this.view = base.view;
        this.options = base.options;
        this.width = base.width;
        this.height = Math.max(0.1, height);
        this.radius = base.radius;
        this.supportRadius = base.supportRadius;
        this.lineSupportRadius = base.lineSupportRadius;
        this.sampleStep = base.sampleStep;
        this.memo = base.memo;
    }

    /**
     * @param ratio the new height relative to this space's height (for example the crouched height)
     * @return a space for the lower (or higher) body that shares this space's block cache
     */
    public @NotNull NavSpace withHeightRatio(double ratio)
    {
        return new NavSpace(this, height * ratio);
    }

    /**
     * Memoized, override-aware profile lookup.
     */
    public @NotNull BlockProfile profile(int x, int y, int z)
    {
        long key = AbstractPathfinder.packBlockCoord(x, y, z);
        BlockProfile profile = memo.get(key);
        if(profile == null)
        {
            profile = view.effective(x, y, z);
            memo.put(key, profile);
        }
        return profile;
    }

    /**
     * Tests whether the entity's bounding box (inflated by {@code pad}) can be at the given feet position without touching a solid or dangerous block.
     */
    public boolean fits(double x, double feetY, double z, double pad)
    {
        return fits(x, feetY, z, pad, radius);
    }

    /**
     * Same as {@link #fits(double, double, double, double)} with an explicit half-width, used for wall climbers whose body may touch the wall they climb.
     */
    public boolean fits(double x, double feetY, double z, double pad, double halfWidth)
    {
        double r = halfWidth + pad - E;
        double minX = x - r;
        double maxX = x + r;
        double minY = feetY + E;
        double maxY = feetY + height - E;
        double minZ = z - r;
        double maxZ = z + r;

        int x0 = (int) Math.floor(minX);
        int x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY);
        int y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ);
        int z1 = (int) Math.floor(maxZ);

        for(int bx = x0; bx <= x1; bx++)
        {
            for(int by = y0; by <= y1; by++)
            {
                for(int bz = z0; bz <= z1; bz++)
                {
                    BlockProfile profile = profile(bx, by, bz);
                    if(profile.isTrivial())
                        continue;

                    if(profile.dangerous())
                        return false;

                    if(profile.overlaps(bx, by, bz, minX, minY, minZ, maxX, maxY, maxZ))
                        return false;

                    if(!options.canOpenDoors() && profile.isOpenable() && profile.overlapsDoor(bx, by, bz, minX, minY, minZ, maxX, maxY, maxZ))
                        return false;
                }
            }
        }
        return true;
    }

    /**
     * Finds the highest standable surface below/at a column that lies within {@code [lowY, highY]}.
     *
     * @param strict if true the entity must also fit on top of the surface and the surface must not be a hazard
     * @param pad    extra clearance added around the entity when {@code strict} is checked
     * @return the absolute feet Y, or {@link Double#NaN} if there is no such surface
     */
    public double supportTop(double x, double z, double sr, double lowY, double highY, boolean strict, double pad)
    {
        int x0 = (int) Math.floor(x - sr);
        int x1 = (int) Math.floor(x + sr);
        int z0 = (int) Math.floor(z - sr);
        int z1 = (int) Math.floor(z + sr);

        int yHigh = (int) Math.floor(highY);
        int yLow = (int) Math.ceil(lowY) - 1;
        double hi = highY + 1.0E-6;
        double lo = lowY - 1.0E-6;

        for(int by = yHigh; by >= yLow; by--)
        {
            double best = Double.NaN;
            for(int bx = x0; bx <= x1; bx++)
            {
                for(int bz = z0; bz <= z1; bz++)
                {
                    BlockProfile profile = profile(bx, by, bz);
                    if(!profile.hasSupport())
                        continue;

                    if(strict && profile.dangerous())
                        continue;

                    double top = profile.maxTop(bx, by, bz, x - sr, z - sr, x + sr, z + sr, lo, hi);
                    if(!Double.isNaN(top) && (Double.isNaN(best) || top > best))
                        best = top;
                }
            }

            if(!Double.isNaN(best) && (!strict || fits(x, best, z, pad)))
                return best;
        }
        return Double.NaN;
    }

    /**
     * Validates a single move between two standing positions (adjacent grid cells, a step up, a jump or a drop).
     * <p>
     * Rising moves are checked at the target height (the entity must have headroom to rise), falling moves walk off the ledge at the start height and then
     * drop straight down at the target column. Diagonal moves additionally require both orthogonal neighbours to be free so corners are never cut.
     */
    public boolean canTravel(double fx, double fy, double fz, double tx, double ty, double tz)
    {
        return canTravel(fx, fy, fz, tx, ty, tz, radius);
    }

    /**
     * Same as {@link #canTravel(double, double, double, double, double, double)} with an explicit half-width.
     */
    public boolean canTravel(double fx, double fy, double fz, double tx, double ty, double tz, double halfWidth)
    {
        double dy = ty - fy;
        double hy = dy > E ? ty : fy;
        double dx = tx - fx;
        double dz = tz - fz;
        double len = Math.hypot(dx, dz);

        int n = Math.max(1, (int) Math.ceil(len / sampleStep));
        for(int i = dy > E ? 0 : 1; i <= n; i++)
        {
            double t = (double) i / n;
            if(!fits(fx + dx * t, hy, fz + dz * t, 0, halfWidth))
                return false;
        }

        if(Math.abs(dx) > E && Math.abs(dz) > E && (!fits(tx, hy, fz, 0, halfWidth) || !fits(fx, hy, tz, 0, halfWidth)))
            return false;

        if(dy < -E)
            return rampWalkable(fx, fy, fz, tx, ty, tz) || fallClear(fx, fy, fz, tx, ty, tz, halfWidth);
        return true;
    }

    /**
     * Whether the surface between two points is a staircase/slab ramp that is simply <i>walked</i>: sampled along the segment, the floor never changes by
     * more than the step height between two neighbouring samples and ends at the target height. On a block grid every stair node lies a full block above
     * (or below) its neighbour, but the real floor in between rises in half-block steps, so such a move is neither a jump nor a fall.
     */
    public boolean rampWalkable(double fx, double fy, double fz, double tx, double ty, double tz)
    {
        double dx = tx - fx;
        double dz = tz - fz;
        double len = Math.hypot(dx, dz);
        if(len < 1.0E-6)
            return false;

        double stepH = options.getStepHeight();
        int n = Math.max(1, (int) Math.ceil(len / 0.1));
        double prev = fy;
        for(int i = 1; i <= n; i++)
        {
            double t = (double) i / n;
            double h = supportTop(fx + dx * t, fz + dz * t, supportRadius, prev - stepH - 1.0E-3, prev + stepH + 1.0E-3, false, 0);
            if(Double.isNaN(h))
                return false;
            prev = h;
        }
        return Math.abs(prev - ty) <= 0.05;
    }

    /**
     * Simulates walking off a ledge and falling to a lower surface with the same physics as {@code PathTask} (gravity, drag, constant horizontal walking
     * speed). The entity keeps walking at {@code fy} until its footprint has left the ledge, then falls tick by tick while still moving horizontally towards
     * the target; at every tick (and half tick) the full box must fit, so ledge corners, overhangs and walls below the edge are detected. After landing the
     * remaining horizontal distance must still have ground.
     */
    boolean fallClear(double fx, double fy, double fz, double tx, double ty, double tz, double halfWidth)
    {
        double dx = tx - fx;
        double dz = tz - fz;
        double len = Math.hypot(dx, dz);
        double ux = len > 1.0E-9 ? dx / len : 0;
        double uz = len > 1.0E-9 ? dz / len : 0;
        double stepLen = 0.05;

        double leave = len;
        for(double s = 0; s <= len + 1.0E-9; s += stepLen)
        {
            double sx = fx + ux * s;
            double sz = fz + uz * s;
            if(!fits(sx, fy, sz, 0, halfWidth))
                return false;
            if(Double.isNaN(supportTop(sx, sz, supportRadius, fy - 0.1, fy + 0.1, false, 0)))
            {
                leave = s;
                break;
            }
        }


        double speed = options.getWalkSpeed();
        double s = leave;
        boolean landed = false;
        for(int k = 1; k <= JumpArc.MAX_TICKS * 2 && !landed; k++)
        {
            int whole = k / 2;
            double offset = (k & 1) == 0 ? JumpArc.fallOffset(whole) : (JumpArc.fallOffset(whole) + JumpArc.fallOffset(whole + 1)) / 2.0;
            double y = fy + offset;
            if(y <= ty)
            {
                y = ty;
                landed = true;
            }

            s = Math.min(len, leave + speed * k / 2.0);
            if(!fits(fx + ux * s, y, fz + uz * s, 0, halfWidth))
                return false;
        }
        if(!landed)
            return false;


        for(double r = s; r <= len + 1.0E-9; r += stepLen)
        {
            double sx = fx + ux * r;
            double sz = fz + uz * r;
            if(!fits(sx, ty, sz, 0, halfWidth))
                return false;
            if(Double.isNaN(supportTop(sx, sz, supportRadius, ty - 0.1, ty + 0.1, false, 0)))
                return false;
        }
        return true;
    }

    /**
     * Same as {@link #fallClear(double, double, double, double, double, double, double)} for the entity's own width.
     */
    public boolean fallClear(double fx, double fy, double fz, double tx, double ty, double tz)
    {
        return fallClear(fx, fy, fz, tx, ty, tz, radius);
    }

    /**
     * Validates a long straight walk at (roughly) constant height: every sample needs solid ground directly under the entity, headroom with a small safety
     * margin around walls, and the ground may only deviate by one-step height (slabs, carpets, snow layers) from the start height.
     */
    public boolean lineWalkable(double x1, double y1, double z1, double x2, double z2)
    {
        double dx = x2 - x1;
        double dz = z2 - z1;
        int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / sampleStep));
        double step = options.getStepHeight() + 1.0E-3;

        for(int i = 1; i <= n; i++)
        {
            double t = (double) i / n;
            double x = x1 + dx * t;
            double z = z1 + dz * t;
            double ground = supportTop(x, z, lineSupportRadius, y1 - step, y1 + step, true, 0.08);
            if(Double.isNaN(ground))
                return false;
        }
        return true;
    }


    /**
     * Whether a cell can be climbed: ladders, vines, scaffolding; an open trapdoor with a ladder directly below it (vanilla lets entities climb through
     * it); and, for wall climbers (spiders), any empty cell next to a solid wall.
     */
    boolean isClimbCell(int bx, int by, int bz)
    {
        BlockProfile p = profile(bx, by, bz);
        if(p.isClimbable())
            return true;

        if(isTrapdoorLadder(p, bx, by, bz))
            return true;

        if(!options.canClimbWalls() || p.hasCollision() || p.dangerous() || p.isWater())
            return false;

        for(int s = 0; s < 4; s++)
        {
            BlockProfile n = profile(bx + SIDE_X[s], by, bz + SIDE_Z[s]);
            if(n.hasCollision() && n.topY() >= 0.8)
                return true;
        }
        return false;
    }

    /**
     * @return true if the cell containing the given feet position can be climbed (see {@link #isClimbCell})
     */
    public boolean isClimbableAt(double x, double feetY, double z)
    {
        return isClimbCell((int) Math.floor(x), (int) Math.floor(feetY + E), (int) Math.floor(z));
    }

    /**
     * @return true if the cell is climbable <b>without</b> relying on wall climbing (a real ladder, vine, scaffolding or trapdoor-ladder)
     */
    public boolean isRealClimbCellAt(double x, double feetY, double z)
    {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(feetY + E);
        int bz = (int) Math.floor(z);
        BlockProfile p = profile(bx, by, bz);
        return p.isClimbable() || isTrapdoorLadder(p, bx, by, bz);
    }

    /**
     * A trapdoor directly above a ladder is part of the ladder (vanilla): open ones always, closed ones if the entity can open them, which
     * {@code PathTask} does while climbing through.
     */
    private boolean isTrapdoorLadder(@NotNull BlockProfile p, int bx, int by, int bz)
    {
        return p.isTrapdoor() && (p.isOpenTrapdoor() || (p.isOpenable() && options.canOpenDoors())) && profile(bx, by - 1, bz).isClimbable();
    }

    /**
     * Half-width to use when moving into / along a climbable cell: wall climbers may press their body against the wall they climb, so their half-width is
     * limited to a bit under half a cell on wall cells; everything else uses the real width.
     */
    public double climbRadius(double x, double feetY, double z)
    {
        return options.canClimbWalls() && !isRealClimbCellAt(x, feetY, z) ? Math.min(radius, 0.49) : radius;
    }

    /**
     * @return true if the cell containing the given feet position contains water
     */
    public boolean isWaterAt(double x, double feetY, double z)
    {
        return profile((int) Math.floor(x), (int) Math.floor(feetY + E), (int) Math.floor(z)).isWater();
    }

    /**
     * @return true if the cell at the entity's head contains water as well (the entity is fully submerged)
     */
    public boolean isDeepWaterAt(double x, double feetY, double z)
    {
        return profile((int) Math.floor(x), (int) Math.floor(feetY + height - 0.1), (int) Math.floor(z)).isWater();
    }

    /**
     * Validates a purely vertical move (climbing, swimming up/down): the entity box must fit at every sample between the two heights.
     */
    public boolean canMoveVertically(double x, double fromY, double z, double toY)
    {
        double lo = Math.min(fromY, toY);
        double hi = Math.max(fromY, toY);
        int n = Math.max(1, (int) Math.ceil((hi - lo) / 0.5));
        for(int k = 0; k <= n; k++)
        {
            double y = lo + (hi - lo) * k / n;
            if(!fits(x, y, z, 0, climbRadius(x, y, z)))
                return false;
        }
        return true;
    }

    /**
     * Whether the entity currently stands on a surface (used as precondition for take-offs).
     */
    public boolean isGrounded(double x, double feetY, double z)
    {
        double top = supportTop(x, z, supportRadius, feetY - 0.06, feetY + 0.06, false, 0);
        return !Double.isNaN(top) && Math.abs(top - feetY) < 0.07;
    }

    /**
     * Validates a ballistic jump: at every tick of the {@link JumpArc} (and half-way between ticks) the entity box must fit and must not touch hazards.
     *
     * @param arc   the jump arc of the entity
     * @param ticks flight time from {@link JumpArc#ticksTo(double)}
     */
    public boolean arcClear(@NotNull JumpArc arc, double sx, double sy, double sz, double lx, double ly, double lz, int ticks)
    {
        final int sub = 4;
        for(int k = 1; k <= ticks * sub; k++)
        {
            double f = (double) k / (ticks * sub);
            int whole = k / sub;
            double frac = (k % sub) / (double) sub;
            double y;
            if(k == ticks * sub)
                y = ly;
            else
            {
                double before = arc.heightOf(whole);
                double after = whole + 1 >= ticks ? ly - sy : arc.heightOf(whole + 1);
                y = sy + before + (after - before) * frac;
            }

            if(!fits(sx + (lx - sx) * f, y, sz + (lz - sz) * f, 0))
                return false;
        }
        return true;
    }

    /**
     * Whether there is still ground under every sample of a straight, roughly level walk (a floor block broken somewhere along a long segment makes this
     * false). Samples are 0.5 blocks apart; ladders and water have no floor of their own and are skipped.
     */
    public boolean floorAlong(double x1, double y1, double z1, double x2, double y2, double z2)
    {
        double dx = x2 - x1;
        double dz = z2 - z1;
        int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 0.5));
        for(int i = 1; i < n; i++)
        {
            double t = (double) i / n;
            double x = x1 + dx * t;
            double y = y1 + (y2 - y1) * t;
            double z = z1 + dz * t;
            if(isClimbableAt(x, y, z) || isWaterAt(x, y, z))
                continue;

            if(Double.isNaN(supportTop(x, z, supportRadius, y - 0.6, y + 0.1, false, 0)))
                return false;
        }
        return true;
    }

    /**
     * Validates a long straight swim at constant height: every sample must fit and be inside water.
     */
    public boolean lineSwimmable(double x1, double y, double z1, double x2, double z2)
    {
        double dx = x2 - x1;
        double dz = z2 - z1;
        int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / sampleStep));
        for(int i = 1; i <= n; i++)
        {
            double t = (double) i / n;
            double x = x1 + dx * t;
            double z = z1 + dz * t;
            if(!isWaterAt(x, y, z) || !fits(x, y, z, 0.05))
                return false;
        }
        return true;
    }

    /**
     * @return true if the entity's body centre is inside water (aquatic entities must stay in water)
     */
    public boolean bodyInWater(double x, double feetY, double z)
    {
        return profile((int) Math.floor(x), (int) Math.floor(feetY + height * 0.5), (int) Math.floor(z)).isWater();
    }

    /**
     * Whether the entity can be at this position in free 3D movement: it fits, and aquatic entities must be in water.
     */
    public boolean fits3(double x, double y, double z, boolean aquatic)
    {
        return fits(x, y, z, 0) && (!aquatic || bodyInWater(x, y, z));
    }

    /**
     * Validates a straight 3D flight/swim segment by sampling the entity box along it.
     *
     * @param pad extra clearance around the entity (use a small value for smoothing shortcuts)
     */
    public boolean line3(double fx, double fy, double fz, double tx, double ty, double tz, boolean aquatic, double pad)
    {
        double dx = tx - fx;
        double dy = ty - fy;
        double dz = tz - fz;
        int n = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / sampleStep));
        for(int i = 1; i <= n; i++)
        {
            double t = (double) i / n;
            double x = fx + dx * t;
            double y = fy + dy * t;
            double z = fz + dz * t;
            if(!fits(x, y, z, pad) || (aquatic && !bodyInWater(x, y, z)))
                return false;
        }
        return true;
    }

    /**
     * Environment-aware extra cost of a position in free 3D movement: nearby walls (including floor and ceiling) cost a little so flights keep some
     * distance from geometry, hazards nearby cost a lot.
     */
    public float localCost3(double x, double feetY, double z)
    {
        double d = radius + 0.3;
        double cy = feetY + height * 0.5;
        double[][] probes = {{d, 0, 0}, {-d, 0, 0}, {0, 0, d}, {0, 0, -d}, {0, height * 0.5 + 0.3, 0}, {0, -height * 0.5 - 0.3, 0}};
        double cost = 0;
        for(double[] probe : probes)
        {
            BlockProfile p = profile((int) Math.floor(x + probe[0]), (int) Math.floor(cy + probe[1]), (int) Math.floor(z + probe[2]));
            if(p.dangerous())
                cost += options.getHazardPenalty();
            else if(p.hasCollision())
                cost += options.getWallPenalty();
        }
        return (float) cost;
    }

    /**
     * Environment-aware extra cost of standing at a position. Computed once per node.
     * <p>
     * Walls and ledges next to the node cost a little (paths keep distance from walls and do not hug drops), hazards next to or under the node cost a lot,
     * wading, webs, doors and slippery/sticky floors add their own penalties.
     */
    public float localCost(double x, double feetY, double z)
    {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int yb = (int) Math.floor(feetY + E);
        double cost = 0.0;

        BlockProfile floor = profile(bx, (int) Math.floor(feetY - E), bz);
        cost += floor.floorCost();

        BlockProfile feet = profile(bx, yb, bz);
        BlockProfile head = profile(bx, yb + Math.max(1, (int) Math.ceil(height) - 1), bz);

        if(feet.isWeb() || head.isWeb())
            cost += 3.0;

        if(feet.isOpenable() || head.isOpenable())
            cost += options.getDoorPenalty();

        if(options.getWadePenalty() > 0 || options.getEdgePenalty() > 0 || options.getHazardPenalty() > 0)
        {
            double d = radius + 0.3;
            for(int s = 0; s < 4; s++)
            {
                int nx = (int) Math.floor(x + SIDE_X[s] * d);
                int nz = (int) Math.floor(z + SIDE_Z[s] * d);
                if(nx == bx && nz == bz)
                    continue;

                BlockProfile sideFeet = profile(nx, yb, nz);
                BlockProfile sideHead = profile(nx, yb + 1, nz);
                BlockProfile sideBelow = profile(nx, yb - 1, nz);

                if(sideFeet.dangerous() || sideHead.dangerous() || sideBelow.dangerous())
                    cost += options.getHazardPenalty();
                else if(sideFeet.hasCollision() && yb + sideFeet.topY() > feetY + options.getStepHeight())
                    cost += options.getWallPenalty();
                else if(!sideFeet.hasCollision() && !sideBelow.hasCollision() && !profile(nx, yb - 2, nz).hasCollision())
                    cost += options.getEdgePenalty();
            }
        }

        return (float) cost;
    }

    /**
     * Returns the view of the environment.
     *
     * @return the view of the environment
     */
    public BlockView getView()
    {
        return view;
    }

    /**
     * Returns the options of the environment.
     *
     * @return the options of the environment
     */
    public NavOptions getOptions()
    {
        return options;
    }

    /**
     * Returns the width of the environment.
     *
     * @return the width of the environment
     */
    public double getWidth()
    {
        return width;
    }

    /**
     * Returns the height of the environment.
     *
     * @return the height of the environment
     */
    public double getHeight()
    {
        return height;
    }

    /**
     * Returns the radius of the environment.
     *
     * @return the radius of the environment
     */
    public double getRadius()
    {
        return radius;
    }

    /**
     * Returns the support radius of the environment.
     *
     * @return the support radius of the environment
     */
    public double getSupportRadius()
    {
        return supportRadius;
    }

    /**
     * Returns the line support radius of the environment.
     *
     * @return the line support radius of the environment
     */
    public double getLineSupportRadius()
    {
        return lineSupportRadius;
    }

    /**
     * Returns the sample step of the environment.
     *
     * @return the sample step of the environment
     */
    public double getSampleStep()
    {
        return sampleStep;
    }
}