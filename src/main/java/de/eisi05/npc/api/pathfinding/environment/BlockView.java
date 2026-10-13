package de.eisi05.npc.api.pathfinding.environment;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.pathfinding.algorithm.AbstractPathfinder;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * Read-only view of the blocks a pathfinder searches through. Replaces direct {@code world.getBlockAt(...)} access so that a search never touches the live
 * world from an async thread (see {@link ChunkSnapshotView}).
 * <p>
 * A view is <b>not</b> thread-safe and must only be used by one search thread at a time.
 */
public abstract class BlockView
{
    private final World world;
    private final int minY;
    private final int maxY;
    private final Long2ByteOpenHashMap overrideMemo = new Long2ByteOpenHashMap();

    /**
     * Creates a view for the given world.
     *
     * @param world the world
     */
    protected BlockView(@NotNull World world)
    {
        this.world = world;
        this.minY = world.getMinHeight();
        this.maxY = world.getMaxHeight();
    }

    /**
     * @return the world this view reads from
     */
    public final @NotNull World getWorld()
    {
        return world;
    }

    /**
     * @return the lowest valid block Y (inclusive)
     */
    public final int getMinY()
    {
        return minY;
    }

    /**
     * @return the highest valid block Y (exclusive)
     */
    public final int getMaxY()
    {
        return maxY;
    }

    /**
     * Returns the raw profile at a position. Out-of-range or unknown positions must return {@link BlockProfile#EMPTY}.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return the profile, never null
     */
    public abstract @NotNull BlockProfile profileAt(int x, int y, int z);

    /**
     * Returns the profile at a position with the user-configured {@code pathfindingPassableOverride} applied. The override is only evaluated for blocks that
     * actually have collision and the result is memoized per position for the lifetime of this view.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return the effective profile, never null
     */
    public final @NotNull BlockProfile effective(int x, int y, int z)
    {
        BlockProfile profile = profileAt(x, y, z);
        if(!profile.hasCollision() && !profile.hasSupport())
            return profile;

        return isOverridden(x, y, z) ? profile.passable() : profile;
    }

    /**
     * Evaluates {@code NpcConfig#pathfindingPassableOverride()} for a position. The default implementation reads the live world and therefore only works on
     * the main thread; views that are used from other threads (see {@link ChunkSnapshotView}) must override it and must not touch the live world.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return true if the block should be treated as passable
     */
    protected boolean testPassableOverride(int x, int y, int z)
    {
        try
        {
            return NpcApi.config.pathfindingPassableOverride().test(world.getBlockAt(x, y, z));
        }
        catch(Throwable throwable)
        {
            return false;
        }
    }

    private boolean isOverridden(int x, int y, int z)
    {
        long key = AbstractPathfinder.packBlockCoord(x, y, z);
        byte state = overrideMemo.get(key);
        if(state != 0)
            return state == 2;

        boolean result = testPassableOverride(x, y, z);
        overrideMemo.put(key, (byte) (result ? 2 : 1));
        return result;
    }
}
