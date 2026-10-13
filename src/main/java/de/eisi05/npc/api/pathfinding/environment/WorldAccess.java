package de.eisi05.npc.api.pathfinding.environment;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * Region-safe world access checks that behave identically on Paper and Folia.
 * <p>
 * On Folia, blocks may only be read or written by the thread that owns the region containing them, and a chunk that is not loaded must never be loaded
 * synchronously from a tick thread. On Paper, {@code Bukkit#isOwnedByCurrentRegion} simply means "this is the main thread", so the same code path works on
 * both platforms.
 */
public final class WorldAccess
{
    private WorldAccess()
    {
    }

    /**
     * @return true if the current thread may read/write the block column at {@code blockX, blockZ} right now: the chunk is owned by the current region (main
     * thread on Paper) and loaded
     */
    public static boolean canAccess(@NotNull World world, int blockX, int blockZ)
    {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        return Bukkit.isOwnedByCurrentRegion(world, cx, cz) && world.isChunkLoaded(cx, cz);
    }

    /**
     * @return true if every chunk touched by the block area (inclusive) is owned by the current region and loaded
     */
    public static boolean canAccessArea(@NotNull World world, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ)
    {
        int minCx = minBlockX >> 4;
        int minCz = minBlockZ >> 4;
        int maxCx = maxBlockX >> 4;
        int maxCz = maxBlockZ >> 4;

        if(!Bukkit.isOwnedByCurrentRegion(world, minCx, minCz, maxCx, maxCz))
            return false;

        for(int cx = minCx; cx <= maxCx; cx++)
        {
            for(int cz = minCz; cz <= maxCz; cz++)
            {
                if(!world.isChunkLoaded(cx, cz))
                    return false;
            }
        }
        return true;
    }

    /**
     * How far a block or entity search around a location may reach with the current thread: the wanted range, reduced to what lies inside the chunks this
     * thread owns (on Paper that is always the wanted range).
     *
     * @param location the centre of the search
     * @param wanted   the wanted range in blocks
     * @return the usable range, possibly 0
     */
    public static double ownedRange(@NotNull Location location, double wanted)
    {
        if(location.getWorld() == null)
            return 0;

        int radius = (int) Math.ceil(wanted / 16.0) + 1;
        while(radius > 0 && !Bukkit.isOwnedByCurrentRegion(location, radius))
            radius--;

        return Math.min(wanted, radius * 16.0);
    }

    /**
     * @return true if the current thread owns the region at the given location (always the main thread on Paper)
     */
    public static boolean isOwned(@NotNull Location location)
    {
        return location.getWorld() != null && Bukkit.isOwnedByCurrentRegion(location);
    }
}