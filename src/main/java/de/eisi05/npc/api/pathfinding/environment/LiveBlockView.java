package de.eisi05.npc.api.pathfinding.environment;

import de.eisi05.npc.api.pathfinding.algorithm.AStarPathfinder;
import de.eisi05.npc.api.pathfinding.algorithm.BoundingBoxPathfinder;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * {@link BlockView} that reads straight from the live world; used by the small static helpers that run during walking
 * ({@link BoundingBoxPathfinder#resolveGroundSupport}, {@link AStarPathfinder#isPositionValid}).
 * <p>
 * <b>Region thread only</b> (the main thread on Paper, the owning region thread on Folia). Positions the current thread may not touch (chunks owned by another
 * Folia region, or not loaded) are reported as {@link BlockProfile#EMPTY} instead of throwing or loading chunks, so callers simply find "no support / nothing
 * there" at region borders. Never share an instance between threads.
 */
public final class LiveBlockView extends BlockView
{
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private boolean lastReadable;

    public LiveBlockView(@NotNull World world)
    {
        super(world);
    }

    private boolean readable(int x, int z)
    {
        int cx = x >> 4;
        int cz = z >> 4;
        if(cx == lastChunkX && cz == lastChunkZ)
            return lastReadable;

        lastReadable = WorldAccess.canAccess(getWorld(), x, z);
        lastChunkX = cx;
        lastChunkZ = cz;
        return lastReadable;
    }

    @Override
    public @NotNull BlockProfile profileAt(int x, int y, int z)
    {
        if(y < getMinY() || y >= getMaxY() || !readable(x, z))
            return BlockProfile.EMPTY;

        World world = getWorld();
        Material type = world.getType(x, y, z);
        if(type.isAir())
            return BlockProfile.EMPTY;

        return BlockProfile.of(world.getBlockData(x, y, z), world, x, y, z);
    }
}
