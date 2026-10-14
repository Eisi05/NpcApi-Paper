package de.eisi05.npc.api.pathfinding.environment;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable, pre-digested description of a block state for navigation purposes.
 * <p>
 * Everything the pathfinders need to know about a block (collision boxes, hazards, liquids, doors, climbables, movement penalties) is computed <b>once per
 * block state</b> and cached globally. Afterward a lookup is a single hash access and all collision tests run on flat {@code double[]} arrays without any
 * Bukkit object allocation.
 * <p>
 * <b>Threading:</b> the collision shape can only be read from a real {@link Block}, which must happen on the main thread. {@link #of(Block)},
 * {@link #of(BlockData, World, int, int, int)} and {@link #resolve(Block, BlockData)} are therefore main-thread only; asynchronous searches obtain unknown
 * states through {@link ChunkSnapshotView}, which resolves them in batches on the main thread. {@link #cached(BlockData)} and all instance methods are safe on
 * any thread.
 * <p>
 * Two box sets are kept: the <i>body</i> boxes block the entity, the <i>support</i> boxes can be stood on. They are identical for normal blocks. Ladders and
 * vines have neither (the entity enters their cell), scaffolding has no body boxes but a standable top.
 */
public final class BlockProfile
{
    /**
     * Profile of "nothing there" (air and similar). Has no collision and no flags.
     */
    public static final BlockProfile EMPTY = new BlockProfile(List.of(), new double[0], new double[0], new double[0], false, false, false, false, false, false, false, false, 0f, 1f);

    private static final int MAX_CACHE_SIZE = 40_000;
    private static final Map<BlockData, BlockProfile> CACHE = new ConcurrentHashMap<>();

    private static final Set<Material> HAZARDS = materials("FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE", "SWEET_BERRY_BUSH",
            "WITHER_ROSE", "POWDER_SNOW", "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY");
    private static final Set<Material> WATER_PLANTS = materials("BUBBLE_COLUMN", "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT");
    private static final Set<Material> CLIMBABLES = materials("LADDER", "VINE", "SCAFFOLDING", "TWISTING_VINES", "TWISTING_VINES_PLANT", "WEEPING_VINES",
            "WEEPING_VINES_PLANT", "CAVE_VINES", "CAVE_VINES_PLANT");
    private static final Set<Material> POSITION_OFFSET = materials("BAMBOO", "POINTED_DRIPSTONE");
    private static final Map<Material, Float> FLOOR_COSTS = new EnumMap<>(Material.class);
    private static final Map<Material, Float> SPEED_FACTORS = new EnumMap<>(Material.class);

    static
    {
        floorCost(1.0f, "SOUL_SAND");
        floorCost(1.5f, "HONEY_BLOCK");
        floorCost(0.5f, "MUD", "ICE", "PACKED_ICE", "BLUE_ICE", "FROSTED_ICE");
        floorCost(2.0f, "SLIME_BLOCK");

        // approximate vanilla movement multipliers
        speed(0.25f, "COBWEB");
        speed(0.5f, "SOUL_SAND");
        speed(0.4f, "HONEY_BLOCK");
        speed(0.85f, "MUD");
        speed(0.3f, "POWDER_SNOW");
        speed(0.8f, "SWEET_BERRY_BUSH");
    }

    private final List<BoundingBox> shape;
    private final double[] boxes;
    private final double[] support;
    private final double[] doorBoxes;
    private final int boxCount;
    private final boolean hazard;
    private final boolean lava;
    private final boolean water;
    private final boolean openable;
    private final boolean web;
    private final boolean climbable;
    private final boolean trapdoor;
    private final boolean openTrapdoor;
    private final float floorCost;
    private final float speedFactor;
    private final double topY;

    private volatile BlockProfile passableVariant;

    private BlockProfile(@NotNull List<BoundingBox> shape, double @NotNull [] boxes, double @NotNull [] support, double @NotNull [] doorBoxes, boolean hazard, boolean lava, boolean water,
                         boolean openable, boolean web, boolean climbable, boolean trapdoor, boolean openTrapdoor, float floorCost, float speedFactor)
    {
        this.shape = shape;
        this.boxes = boxes;
        this.support = support;
        this.doorBoxes = doorBoxes;
        this.boxCount = boxes.length / 6;
        this.hazard = hazard;
        this.lava = lava;
        this.water = water;
        this.openable = openable;
        this.web = web;
        this.climbable = climbable;
        this.trapdoor = trapdoor;
        this.openTrapdoor = openTrapdoor;
        this.floorCost = floorCost;
        this.speedFactor = speedFactor;

        double top = 0.0;
        for(BoundingBox box : shape)
            top = Math.max(top, box.getMaxY());
        this.topY = top;
    }

    /**
     * Creates a profile from explicit boxes. Intended for custom {@link BlockView} implementations (and tests) that do not originate from Bukkit blocks.
     *
     * @param shape    the raw collision boxes in block-local coordinates (0..1)
     * @param hazard   whether touching/standing on the block hurts
     * @param lava     whether the block is lava
     * @param water    whether the block contains water
     * @param openable whether the block is a door/gate the NPC can open (its boxes are then ignored for navigation)
     * @return the profile
     */
    public static @NotNull BlockProfile create(@NotNull List<BoundingBox> shape, boolean hazard, boolean lava, boolean water, boolean openable)
    {
        return create(shape, hazard, lava, water, openable, false);
    }

    /**
     * Like {@link #create(List, boolean, boolean, boolean, boolean)} with an additional climbable flag (ladder-like blocks: no collision, can be climbed).
     *
     * @param climbable whether the block can be climbed (the given shape is then ignored for navigation)
     * @return the profile
     */
    public static @NotNull BlockProfile create(@NotNull List<BoundingBox> shape, boolean hazard, boolean lava, boolean water, boolean openable,
                                               boolean climbable)
    {
        double[] flat = openable || climbable ? new double[0] : flatten(shape);
        return new BlockProfile(List.copyOf(shape), flat, flat, openable ? flatten(shape) : new double[0], hazard, lava, water,
                openable, false, climbable, false, false, 0f, 1f);
    }

    /**
     * Whether an entity with hands can open this block: doors, fence gates and trapdoors, <b>except</b> iron doors and iron trapdoors, which only react to
     * redstone. Used by the planner and by {@code PathTask} so iron doors are never forced open.
     *
     * @param data the block state
     * @return true if the block can be opened by hand
     */
    public static boolean isHandOperable(@NotNull BlockData data)
    {
        if(!(data instanceof Door || data instanceof Gate || data instanceof TrapDoor))
            return false;

        String name = data.getMaterial().name();
        return !name.equals("IRON_DOOR") && !name.equals("IRON_TRAPDOOR");
    }

    /**
     * Returns the cached profile for a block state or null if it has not been resolved yet. Safe on any thread.
     *
     * @param data the block state
     * @return the profile or null
     */
    public static @Nullable BlockProfile cached(@NotNull BlockData data)
    {
        return CACHE.get(data);
    }

    /**
     * Returns the profile of the block's current state, computing it from the block's collision shape if needed. <b>Main thread only.</b>
     *
     * @param block the block
     * @return the profile
     */
    public static @NotNull BlockProfile of(@NotNull Block block)
    {
        BlockData data = block.getBlockData();
        BlockProfile profile = CACHE.get(data);
        return profile != null ? profile : store(data, build(block, data));
    }

    /**
     * Returns the profile for a block state that was just read at the given position. <b>Main thread only.</b>
     *
     * @param data  the block state read at {@code x, y, z}
     * @param world the world
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     * @return the profile
     */
    public static @NotNull BlockProfile of(@NotNull BlockData data, @NotNull World world, int x, int y, int z)
    {
        BlockProfile profile = CACHE.get(data);
        if(profile != null)
            return profile;

        Block block = world.getBlockAt(x, y, z);
        return store(data, build(block, data));
    }

    /**
     * Resolves and caches the profile of {@code expected} using the given block, but only if the block still has exactly that state (it may have changed
     * since a snapshot was taken). <b>Main thread only.</b>
     *
     * @param block    the block to read the collision shape from
     * @param expected the state that should be resolved
     * @return the profile, or null if the block no longer has the expected state
     */
    public static @Nullable BlockProfile resolve(@NotNull Block block, @NotNull BlockData expected)
    {
        BlockProfile profile = CACHE.get(expected);
        if(profile != null)
            return profile;

        if(!block.getBlockData().equals(expected))
            return null;

        return store(expected, build(block, expected));
    }

    /**
     * Clears the global profile cache, e.g. after a reload that changed block shapes.
     */
    public static void clearCache()
    {
        CACHE.clear();
    }

    private static @NotNull BlockProfile store(@NotNull BlockData data, @NotNull BlockProfile profile)
    {
        if(CACHE.size() < MAX_CACHE_SIZE)
            CACHE.put(data, profile);
        return profile;
    }

    private static @NotNull BlockProfile build(@NotNull Block block, @NotNull BlockData data)
    {
        Material material = data.getMaterial();
        if(material.isAir())
            return EMPTY;

        boolean trapdoor = data instanceof TrapDoor;
        boolean open = data instanceof Openable openableData && openableData.isOpen();
        boolean openable = isHandOperable(data);
        boolean openTrapdoor = trapdoor && open;
        boolean lava = material == Material.LAVA;
        boolean water = material == Material.WATER || WATER_PLANTS.contains(material) || (data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged());
        boolean hazard = HAZARDS.contains(material);
        boolean web = material.name().equals("COBWEB");
        boolean climbable = CLIMBABLES.contains(material);
        float floorCost = FLOOR_COSTS.getOrDefault(material, 0f);
        float speedFactor = SPEED_FACTORS.getOrDefault(material, 1f);

        List<BoundingBox> shape = readShape(block, material);

        double[] body;
        double[] support;
        double[] door = new double[0];
        if(climbable)
        {
            body = new double[0];
            support = material.name().equals("SCAFFOLDING") ? flatten(List.of(new BoundingBox(0, 0.875, 0, 1, 1, 1))) : new double[0];
        }
        else if(openable)
        {
            body = new double[0];
            support = trapdoor && !open ? flatten(shape) : new double[0];
            door = flatten(shape);
        }
        else
        {
            body = flatten(shape);
            support = body;
        }

        return new BlockProfile(shape, body, support, door, hazard, lava, water, openable, web, climbable, trapdoor, openTrapdoor, floorCost, speedFactor);
    }

    private static @NotNull List<BoundingBox> readShape(@NotNull Block block, @NotNull Material material)
    {
        try
        {
            Collection<BoundingBox> raw = block.getCollisionShape().getBoundingBoxes();
            List<BoundingBox> list = new ArrayList<>(raw.size());
            for(BoundingBox box : raw)
                list.add(box.clone());

            if(POSITION_OFFSET.contains(material) && !list.isEmpty())
            {
                double minY = Double.MAX_VALUE;
                double maxY = -Double.MAX_VALUE;
                for(BoundingBox box : list)
                {
                    minY = Math.min(minY, box.getMinY());
                    maxY = Math.max(maxY, box.getMaxY());
                }
                list = new ArrayList<>(List.of(new BoundingBox(0, minY, 0, 1, maxY, 1)));
            }

            return Collections.unmodifiableList(list);
        }
        catch(Throwable throwable)
        {
            return material.isSolid() ? List.of(new BoundingBox(0, 0, 0, 1, 1, 1)) : List.of();
        }
    }

    private static double @NotNull [] flatten(@NotNull List<BoundingBox> shape)
    {
        double[] flat = new double[shape.size() * 6];
        int i = 0;
        for(BoundingBox box : shape)
        {
            flat[i++] = box.getMinX();
            flat[i++] = box.getMinY();
            flat[i++] = box.getMinZ();
            flat[i++] = box.getMaxX();
            flat[i++] = box.getMaxY();
            flat[i++] = box.getMaxZ();
        }
        return flat;
    }

    private static @NotNull Set<Material> materials(String @NotNull ... names)
    {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for(String name : names)
        {
            Material material = Material.getMaterial(name);
            if(material != null)
                set.add(material);
        }
        return set;
    }

    private static void floorCost(float cost, String @NotNull ... names)
    {
        for(Material material : materials(names))
            FLOOR_COSTS.put(material, cost);
    }

    private static void speed(float factor, String @NotNull ... names)
    {
        for(Material material : materials(names))
            SPEED_FACTORS.put(material, factor);
    }

    /**
     * Returns the raw collision shape of the block (in block-local coordinates), regardless of doors/climbables being ignored for navigation.
     *
     * @return an unmodifiable list of boxes
     */
    public @NotNull List<BoundingBox> shape()
    {
        return shape;
    }

    /**
     * Highest point of the raw collision shape in block-local coordinates ({@code 0} if there is none).
     *
     * @return the top Y
     */
    public double topY()
    {
        return topY;
    }

    /**
     * Whether this block physically blocks movement for navigation purposes. Doors/gates the NPC can open and climbables return {@code false}.
     *
     * @return true if the block has navigation-relevant collision
     */
    public boolean hasCollision()
    {
        return boxCount > 0;
    }

    /**
     * Whether an entity can stand on top of this block.
     *
     * @return true if the block has a standable surface
     */
    public boolean hasSupport()
    {
        return support.length > 0;
    }

    /**
     * @return true if this is a door, fence gate or trapdoor that can be opened by hand (see {@link #isHandOperable(BlockData)})
     */
    public boolean isOpenable()
    {
        return openable;
    }

    /**
     * @return true for any trapdoor (open or closed, wood or iron)
     */
    public boolean isTrapdoor()
    {
        return trapdoor;
    }

    /**
     * @return true for an open trapdoor; if a ladder is directly below it, entities can use it as part of the ladder
     */
    public boolean isOpenTrapdoor()
    {
        return openTrapdoor;
    }

    /**
     * @return true for lava
     */
    public boolean isLava()
    {
        return lava;
    }

    /**
     * @return true if the block contains water (water, waterlogged blocks, kelp, seagrass, bubble columns)
     */
    public boolean isWater()
    {
        return water;
    }

    /**
     * @return true for blocks that hurt when touched or stood on (fire, magma, cactus, campfire, berry bush, ...)
     */
    public boolean isHazard()
    {
        return hazard;
    }

    /**
     * @return true for lava and every hazard
     */
    public boolean dangerous()
    {
        return hazard || lava;
    }

    /**
     * @return true for cobwebs
     */
    public boolean isWeb()
    {
        return web;
    }

    /**
     * @return true for ladders, vines, scaffolding and similar
     */
    public boolean isClimbable()
    {
        return climbable;
    }

    /**
     * Additional path cost when standing on this block (soul sand, honey, ice, ...).
     *
     * @return the extra cost
     */
    public float floorCost()
    {
        return floorCost;
    }

    /**
     * Movement speed multiplier while in contact with this block (cobweb, soul sand, honey, ...); {@code 1} if the block does not slow movement.
     *
     * @return the multiplier
     */
    public float speedFactor()
    {
        return speedFactor;
    }

    /**
     * @return true if the block can be skipped in every navigation check (no collision and no flags)
     */
    public boolean isTrivial()
    {
        return boxCount == 0 && !hazard && !lava && !water && !openable && !web;
    }

    /**
     * Returns a variant of this profile without any collision, used when {@code NpcConfig#pathfindingPassableOverride()} marks a position as passable.
     *
     * @return the passable variant
     */
    public @NotNull BlockProfile passable()
    {
        if(boxCount == 0 && support.length == 0)
            return this;

        BlockProfile variant = passableVariant;
        if(variant == null)
            passableVariant = variant = new BlockProfile(shape, new double[0], new double[0], new double[0], hazard, lava, water, openable, web, climbable,
                    trapdoor, openTrapdoor, floorCost, speedFactor);
        return variant;
    }

    /**
     * Tests whether the given world-space box overlaps any body box of this block (touching is not overlapping).
     *
     * @return true on overlap
     */
    public boolean overlaps(int bx, int by, int bz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
    {
        for(int i = 0; i < boxes.length; i += 6)
        {
            if(minX < bx + boxes[i + 3] && maxX > bx + boxes[i] && minY < by + boxes[i + 4] && maxY > by + boxes[i + 1] && minZ < bz + boxes[i + 5] &&
                    maxZ > bz + boxes[i + 2])
                return true;
        }
        return false;
    }

    /**
     * Tests the box against the door/gate shape of this block. Only relevant for entities that <b>cannot</b> open doors: a closed door is a solid wall for
     * them, an open one only its thin panel. Always false for blocks that are not doors or gates.
     *
     * @return true on overlap
     */
    public boolean overlapsDoor(int bx, int by, int bz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
    {
        for(int i = 0; i < doorBoxes.length; i += 6)
        {
            if(minX < bx + doorBoxes[i + 3] && maxX > bx + doorBoxes[i] && minY < by + doorBoxes[i + 4] && maxY > by + doorBoxes[i + 1] &&
                    minZ < bz + doorBoxes[i + 5] && maxZ > bz + doorBoxes[i + 2])
                return true;
        }
        return false;
    }

    /**
     * Finds the highest standable top surface of this block that lies below an XZ rectangle and inside {@code [lowTop, highTop]}.
     *
     * @return the absolute Y of the highest matching top, or {@link Double#NaN} if there is none
     */
    public double maxTop(int bx, int by, int bz, double minX, double minZ, double maxX, double maxZ, double lowTop, double highTop)
    {
        double best = Double.NaN;
        for(int i = 0; i < support.length; i += 6)
        {
            if(maxX > bx + support[i] && minX < bx + support[i + 3] && maxZ > bz + support[i + 2] && minZ < bz + support[i + 5])
            {
                double top = by + support[i + 4];
                if(top >= lowTop && top <= highTop && (Double.isNaN(best) || top > best))
                    best = top;
            }
        }
        return best;
    }
}
