package de.eisi05.npc.api.utils;

import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Utility class for location-related operations.
 */
public class LocationUtils
{
    /**
     * Finds a safe Y level on the ground for the given location using standard player dimensions (1.8m height, 0.6m width).
     * Searches up to 5 blocks above and below the starting Y level.
     *
     * @param loc The location to find a safe Y level for
     * @param npc The NPC to use for dimensions
     * @return An OptionalInt containing the safe block Y level, or empty if none found
     */
    public static OptionalInt findSafeY(@NotNull Location loc, @NotNull NPC npc)
    {
        AABB boundingBox = ((Entity) npc.getEntity()).getBoundingBox();
        double scale = npc.getOption(NpcOption.SCALE);
        OptionalDouble safeY = findSafeY(loc, boundingBox.getYsize() * scale, boundingBox.getXsize());
        return safeY.isPresent() ? OptionalInt.of((int) Math.floor(safeY.getAsDouble())) : OptionalInt.empty();
    }

    /**
     * Finds a safe surface Y coordinate on the ground for the given location, accounting for entity height and footprint width.
     * Searches up to 5 blocks above and below the starting Y level.
     *
     * @param loc          The location to find a safe Y level for
     * @param entityHeight The height of the entity
     * @param entityWidth  The width of the entity
     * @return An OptionalDouble containing the exact safe Y surface coordinate, or empty if none found
     */
    public static OptionalDouble findSafeY(@NotNull Location loc, double entityHeight, double entityWidth)
    {
        World world = loc.getWorld();
        if(world == null)
            return OptionalDouble.empty();

        double x = loc.getX();
        double z = loc.getZ();
        int startY = loc.getBlockY();

        for(int y = startY; y <= startY + 5 && y < world.getMaxHeight(); y++)
        {
            OptionalDouble safeY = testYLevel(world, x, y, z, entityHeight, entityWidth);
            if(safeY.isPresent())
                return safeY;
        }

        for(int y = startY - 1; y >= startY - 5 && y >= world.getMinHeight(); y--)
        {
            OptionalDouble safeY = testYLevel(world, x, y, z, entityHeight, entityWidth);
            if(safeY.isPresent())
                return safeY;
        }

        return OptionalDouble.empty();
    }

    /**
     * Tests whether a given floor block Y level provides solid ground support and complete 3D bounding box clearance.
     */
    private static OptionalDouble testYLevel(World world, double x, int blockY, double z, double entityHeight, double entityWidth)
    {
        double radius = entityWidth / 2.0;
        int minBlockX = (int) Math.floor(x - radius);
        int maxBlockX = (int) Math.floor(x + radius);
        int minBlockZ = (int) Math.floor(z - radius);
        int maxBlockZ = (int) Math.floor(z + radius);

        double maxSurfaceY = -Double.MAX_VALUE;
        boolean hasSolidSupport = false;

        for(int bx = minBlockX; bx <= maxBlockX; bx++)
        {
            for(int bz = minBlockZ; bz <= maxBlockZ; bz++)
            {
                Block floorBlock = world.getBlockAt(bx, blockY, bz);
                if(floorBlock.isPassable() || !floorBlock.getType().isSolid())
                    continue;

                for(BoundingBox bb : floorBlock.getCollisionShape().getBoundingBoxes())
                {
                    double topY = bb.getMaxY() + blockY;
                    if(topY > maxSurfaceY)
                    {
                        maxSurfaceY = topY;
                        hasSolidSupport = true;
                    }
                }
            }
        }

        if(!hasSolidSupport)
            return OptionalDouble.empty();

        BoundingBox entityBox = new BoundingBox(
                x - radius + 0.001, maxSurfaceY + 0.001, z - radius + 0.001,
                x + radius - 0.001, maxSurfaceY + entityHeight - 0.001, z + radius - 0.001
        );

        int minCheckY = (int) Math.floor(entityBox.getMinY());
        int maxCheckY = (int) Math.floor(entityBox.getMaxY());

        for(int bx = minBlockX; bx <= maxBlockX; bx++)
        {
            for(int by = minCheckY; by <= maxCheckY; by++)
            {
                for(int bz = minBlockZ; bz <= maxBlockZ; bz++)
                {
                    Block block = world.getBlockAt(bx, by, bz);
                    if(block.isPassable() || !block.getType().isSolid())
                        continue;

                    for(BoundingBox bb :  block.getCollisionShape().getBoundingBoxes())
                    {
                        BoundingBox worldBB = bb.clone().shift(bx, by, bz);
                        if(entityBox.overlaps(worldBB))
                            return OptionalDouble.empty();
                    }
                }
            }
        }

        return OptionalDouble.of(maxSurfaceY);
    }
}
