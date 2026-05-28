package de.eisi05.npc.api.utils;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;

/**
 * Utility class for location-related operations.
 */
public class LocationUtils
{
    /**
     * Finds a safe Y level on the ground for the given location. Searches up to 5 blocks above and below the starting Y level.
     *
     * @param loc The location to find a safe Y level for
     * @return An OptionalInt containing the safe Y level, or empty if none found
     */
    public static OptionalInt findSafeY(@NotNull Location loc)
    {
        World world = loc.getWorld();
        if(world == null)
            return OptionalInt.empty();

        int startX = loc.getBlockX();
        int startZ = loc.getBlockZ();
        int startY = loc.getBlockY();

        for(int y = startY; y <= startY + 5; y++)
        {
            Block block = world.getBlockAt(startX, y, startZ);
            if(block.getType().isSolid() && !block.getRelative(BlockFace.UP).getType().isSolid() &&
                    !block.getRelative(BlockFace.UP).getRelative(BlockFace.UP).getType().isSolid())
                return OptionalInt.of(y + 1);
        }

        for(int y = startY; y >= startY - 5; y--)
        {
            Block block = world.getBlockAt(startX, y, startZ);
            if(block.getType().isSolid() && !block.getRelative(BlockFace.UP).getType().isSolid() &&
                    !block.getRelative(BlockFace.UP).getRelative(BlockFace.UP).getType().isSolid())
                return OptionalInt.of(y + 1);
        }

        return OptionalInt.empty();
    }
}
