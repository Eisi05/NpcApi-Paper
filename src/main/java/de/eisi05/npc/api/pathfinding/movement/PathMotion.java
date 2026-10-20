package de.eisi05.npc.api.pathfinding.movement;

import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.pathfinding.environment.BlockProfile;
import de.eisi05.npc.api.pathfinding.environment.WorldAccess;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Performs the special movement types of a {@link Path} for {@code PathTask}: ballistic gap jumps ({@link MoveType#GAP_JUMP}), climbing
 * ({@link MoveType#CLIMB}) and swimming ({@link MoveType#SWIM}); plus terrain speed factors (cobwebs, soul sand, honey, wading, ...).
 * <p>
 * {@code PathTask} calls {@link #tick} once per movement tick <i>before</i> its normal walking physics. If a {@link Step} is returned the NPC moves by that
 * delta and normal physics are skipped for this tick; {@code null} means "nothing special, walk as usual". {@link #reachedOverride} lets the special
 * movement types decide when a waypoint counts as reached.
 * <p>
 * Everything here reads the live world and must run on the thread that owns the NPC's region (main thread on Paper). Blocks in chunks that thread may not
 * touch (other Folia region, not loaded) count as empty. One instance per walk; never share it between concurrently running tasks.
 */
public final class PathMotion
{
    /**
     * Climbing speed upwards (blocks per tick), vanilla ladder speed.
     */
    public static final double CLIMB_UP_SPEED = 0.12;

    /**
     * Climbing speed downwards (blocks per tick).
     */
    public static final double CLIMB_DOWN_SPEED = 0.15;

    /**
     * Largest vertical swimming speed (blocks per tick).
     */
    public static final double SWIM_VERTICAL_SPEED = 0.12;

    private static final double WADE_FACTOR = 0.75;
    private static final double STEP_UP = 0.55;

    private static final int SWIM_GRACE_TICKS = 6;

    private final double entityHeight;
    private final NavCapabilities capabilities;
    private final JumpArc arc;
    private int swimTicksLeft;

    private boolean arcActive;
    private int arcIndex = -1;
    private int arcTick;
    private int arcTicks;
    private double startX;
    private double startY;
    private double startZ;
    private double landX;
    private double landY;
    private double landZ;

    /**
     * Result of a special movement tick.
     *
     * @param dx       horizontal X movement to apply
     * @param dy       vertical movement to apply
     * @param dz       horizontal Z movement to apply
     * @param onGround whether the NPC should be reported as standing on the ground after this step
     */
    public record Step(double dx, double dy, double dz, boolean onGround)
    {
    }

    /**
     * Creates the motion helper for an entity.
     *
     * @param entityHeight the height of the walking entity (used for terrain checks)
     */
    public PathMotion(double entityHeight)
    {
        this(entityHeight, NavCapabilities.DEFAULT);
    }

    /**
     * Creates the motion helper for an entity with the given abilities. Special moves the entity cannot perform (climbing, swimming, gap jumps) are ignored
     * and left to the normal walking physics.
     *
     * @param entityHeight the height of the walking entity (used for terrain checks)
     * @param capabilities what the entity can do
     */
    public PathMotion(double entityHeight, @NotNull NavCapabilities capabilities)
    {
        this.entityHeight = entityHeight;
        this.capabilities = capabilities;
        this.arc = capabilities.jumpArc();
    }

    /**
     * Whether the NPC is currently swimming. Stays true for a few ticks after the last swimming tick so the swimming pose does not flicker at the water
     * surface.
     *
     * @return true while swimming
     */
    public boolean isSwimming()
    {
        return swimTicksLeft > 0;
    }

    /**
     * Aborts any running special movement (call when the path or the position changes abruptly).
     */
    public void reset()
    {
        arcActive = false;
        arcIndex = -1;
        arcTick = 0;
    }

    /**
     * @return true while a gap jump is in the air
     */
    public boolean isJumping()
    {
        return arcActive;
    }

    /**
     * Computes the special movement for this tick, if any.
     *
     * @param world  the world
     * @param x      current X
     * @param y      current feet Y
     * @param z      current Z
     * @param index  index of the waypoint the NPC is heading to
     * @param moves  the path's move types ({@link Path#getMoves()})
     * @param points the path's points ({@link Path#asVectors()})
     * @param speed  the NPC's walking speed (blocks per tick)
     * @return the step to apply, or null if normal walking physics should run
     */
    public @Nullable Step tick(@NotNull World world, double x, double y, double z, int index, @NotNull List<MoveType> moves, @NotNull List<Vector> points,
                               double speed)
    {
        if(points.isEmpty() || moves.size() != points.size())
            return null;

        int i = Math.min(index, points.size() - 1);

        if(swimTicksLeft > 0)
            swimTicksLeft--;

        if(arcActive)
        {
            if(arcIndex == i)
                return advanceArc(x, y, z, speed);
            reset();
        }

        MoveType type = moves.get(i);
        MoveType previous = i > 0 ? moves.get(i - 1) : MoveType.WALK;
        Vector target = points.get(i);

        if(type == MoveType.GAP_JUMP && i > 0 && capabilities.canGapJump() && startArc(i, points, x, y, z))
            return advanceArc(x, y, z, speed);

        if(type == MoveType.FLY && capabilities.canFly())
            return flyStep(target, x, y, z, speed);

        boolean climbing = (capabilities.canClimb() || capabilities.canClimbWalls()) && (type == MoveType.CLIMB || previous == MoveType.CLIMB);
        boolean swimming = capabilities.canSwim() && (type == MoveType.SWIM || previous == MoveType.SWIM);
        if(!climbing && !swimming)
            return null;

        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y + 0.01);
        int bz = (int) Math.floor(z);

        if(climbing && isClimbCell(world, bx, by, bz))
            return climbStep(type, target, x, y, z, speed);

        int waterY = capabilities.movement() == MovementMode.AQUATIC ? (int) Math.floor(y + entityHeight * 0.5) : by;
        if(swimming && profileAt(world, bx, waterY, bz).isWater())
        {
            swimTicksLeft = SWIM_GRACE_TICKS;
            return swimStep(target, x, y, z, speed);
        }

        return null;
    }

    /**
     * Live-world version of the planner's climb-cell rule: ladders, vines and scaffolding; an open trapdoor above a ladder; and, for wall climbers, any empty
     * cell next to a solid wall.
     */
    private boolean isClimbCell(@NotNull World world, int bx, int by, int bz)
    {
        BlockProfile p = profileAt(world, bx, by, bz);
        if(capabilities.canClimb())
        {
            if(p.isClimbable())
                return true;
            if(p.isTrapdoor() && (p.isOpenTrapdoor() || (p.isOpenable() && capabilities.canOpenDoors())) && profileAt(world, bx, by - 1, bz).isClimbable())
                return true;
        }

        if(!capabilities.canClimbWalls() || p.hasCollision() || p.dangerous() || p.isWater())
            return false;

        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for(int[] side : sides)
        {
            BlockProfile n = profileAt(world, bx + side[0], by, bz + side[1]);
            if(n.hasCollision() && n.topY() >= 0.8)
                return true;
        }
        return false;
    }

    /**
     * Lets special movement types decide whether a waypoint counts as reached.
     *
     * @param index the waypoint index
     * @param moves the path's move types
     * @param dx    waypoint X minus NPC X
     * @param dy    waypoint Y minus NPC Y
     * @param dz    waypoint Z minus NPC Z
     * @return true/false to force the decision, or null to let {@code PathTask} use its default rule
     */
    public @Nullable Boolean reachedOverride(int index, @NotNull List<MoveType> moves, double dx, double dy, double dz)
    {
        if(index < 0 || index >= moves.size())
            return null;

        if(arcActive && arcIndex == index)
            return false;

        double horizontal = Math.hypot(dx, dz);
        return switch(moves.get(index))
        {
            case CLIMB -> capabilities.canClimb() ? horizontal <= 0.2 && Math.abs(dy) <= 0.12 : null;
            case SWIM -> capabilities.canSwim() || capabilities.movement() == MovementMode.AQUATIC ? horizontal <= 0.25 && Math.abs(dy) <= 0.3 : null;
            case FLY -> capabilities.canFly() ? Math.sqrt(horizontal * horizontal + dy * dy) <= 0.3 : null;
            default -> null;
        };
    }

    /**
     * Movement multiplier for the terrain at the NPC's position: cobwebs, soul sand, honey, powder snow, mud, berry bushes and wading in water.
     *
     * @param world the world
     * @param x     current X
     * @param y     current feet Y
     * @param z     current Z
     * @return a factor in {@code (0, 1]}
     */
    public double speedFactor(@NotNull World world, double x, double y, double z)
    {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        BlockProfile below = profileAt(world, bx, (int) Math.floor(y - 0.01), bz);
        BlockProfile feet = profileAt(world, bx, (int) Math.floor(y + 0.01), bz);
        BlockProfile head = profileAt(world, bx, (int) Math.floor(y + entityHeight - 0.1), bz);

        double factor = Math.min(below.speedFactor(), Math.min(feet.speedFactor(), head.speedFactor()));
        if(feet.isWater())
            factor = Math.min(factor, WADE_FACTOR);
        return factor;
    }

    private boolean startArc(int index, @NotNull List<Vector> points, double x, double y, double z)
    {
        Vector takeoff = points.get(index - 1);
        Vector landing = points.get(index);

        double fromTakeoff = Math.hypot(x - takeoff.getX(), z - takeoff.getZ());
        double toLanding = Math.hypot(landing.getX() - x, landing.getZ() - z);
        int ticks = arc.ticksTo(landing.getY() - y);
        if(fromTakeoff > 0.6 || toLanding > 8.0 || ticks < 2)
            return false;

        arcActive = true;
        arcIndex = index;
        arcTick = 0;
        arcTicks = ticks;
        startX = x;
        startY = y;
        startZ = z;
        landX = landing.getX();
        landY = landing.getY();
        landZ = landing.getZ();
        return true;
    }

    private @NotNull Step advanceArc(double x, double y, double z, double speed)
    {
        double totalDist = Math.hypot(landX - startX, landZ - startZ);
        double f;
        boolean landed;

        if(totalDist < 1.0E-6)
        {
            arcTick++;
            landed = arcTick >= arcTicks;
            f = Math.min(1.0, (double) arcTick / arcTicks);
        }
        else
        {
            double currentDist = Math.hypot(x - startX, z - startZ);
            double nextDist = Math.min(totalDist, currentDist + speed);
            f = nextDist / totalDist;
            landed = f >= 1.0 - 1.0E-6;
        }

        double nx = startX + (landX - startX) * f;
        double nz = startZ + (landZ - startZ) * f;

        int mappedTick = (int) Math.round(f * arcTicks);
        double ny = landed ? landY : startY + arc.heightOf(mappedTick);

        if(landed)
            arcActive = false;

        return new Step(nx - x, ny - y, nz - z, landed);
    }

    private @NotNull Step climbStep(@NotNull MoveType type, @NotNull Vector target, double x, double y, double z, double speed)
    {
        double dx = target.getX() - x;
        double dy = target.getY() - y;
        double dz = target.getZ() - z;
        double horizontal = Math.hypot(dx, dz);

        boolean verticalMove = type == MoveType.CLIMB && horizontal < 0.35;

        double vy;
        if(Math.abs(dy) <= 0.03)
            vy = dy;
        else
            vy = dy > 0 ? Math.min(dy, CLIMB_UP_SPEED) : Math.max(dy, -CLIMB_DOWN_SPEED);

        double hx = 0;
        double hz = 0;
        if(horizontal > 1.0E-6)
        {
            double hs;
            if(verticalMove)
                hs = Math.min(horizontal, 0.08);
            else if(dy <= STEP_UP)
                hs = Math.min(horizontal, speed * 0.8);
            else
                hs = 0;
            hx = dx / horizontal * hs;
            hz = dz / horizontal * hs;
        }

        return new Step(hx, vy, hz, false);
    }

    private @NotNull Step flyStep(@NotNull Vector target, double x, double y, double z, double speed)
    {
        double dx = target.getX() - x;
        double dy = target.getY() - y;
        double dz = target.getZ() - z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if(dist < 1.0E-6)
            return new Step(0, 0, 0, false);

        double scale = Math.min(speed * capabilities.flySpeedFactor(), dist) / dist;
        return new Step(dx * scale, dy * scale, dz * scale, false);
    }

    private @NotNull Step swimStep(@NotNull Vector target, double x, double y, double z, double speed)
    {
        double dx = target.getX() - x;
        double dy = target.getY() - y;
        double dz = target.getZ() - z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if(dist < 1.0E-6)
            return new Step(0, 0, 0, false);

        double scale = Math.min(speed * capabilities.swimSpeedFactor(), dist) / dist;
        double verticalLimit = capabilities.movement() == MovementMode.AQUATIC ? speed * capabilities.swimSpeedFactor() : SWIM_VERTICAL_SPEED;
        double vy = Math.clamp(dy * scale, -verticalLimit, verticalLimit);
        return new Step(dx * scale, vy, dz * scale, false);
    }

    private static @NotNull BlockProfile profileAt(@NotNull World world, int x, int y, int z)
    {
        if(y < world.getMinHeight() || y >= world.getMaxHeight() || !WorldAccess.canAccess(world, x, z))
            return BlockProfile.EMPTY;

        Material type = world.getType(x, y, z);
        if(type.isAir())
            return BlockProfile.EMPTY;

        return BlockProfile.of(world.getBlockData(x, y, z), world, x, y, z);
    }
}
