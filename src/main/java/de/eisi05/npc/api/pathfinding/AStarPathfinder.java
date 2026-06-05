package de.eisi05.npc.api.pathfinding;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.utils.Var;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class AStarPathfinder
{
    private static final double[][][] MOVE_COSTS = new double[3][3][3];

    static
    {
        for(int x = -1; x <= 1; x++)
        {
            for(int y = -1; y <= 1; y++)
            {
                for(int z = -1; z <= 1; z++)
                {
                    if(x == 0 && y == 0 && z == 0)
                        MOVE_COSTS[x + 1][y + 1][z + 1] = 0;
                    else
                        MOVE_COSTS[x + 1][y + 1][z + 1] = (Math.abs(x) + Math.abs(y) + Math.abs(z)) > 1 ? 1.414 : 1.0;
                }
            }
        }
    }

    private final int maxIterations;
    private final boolean allowDiagonal;
    private final double entityHeight;
    private final double entityWidth;
    private final PriorityQueue<Node> openSet = new PriorityQueue<>();
    private final Set<Long> openSetIds = new HashSet<>();
    private final Map<Long, Node> allNodes = new HashMap<>();
    private World world;

    public AStarPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
    {
        this.maxIterations = maxIterations;
        this.allowDiagonal = allowDiagonal;
        this.entityHeight = entityHeight;
        this.entityWidth = entityWidth;
    }

    /**
     * Checks if a position is valid (not inside a solid block).
     *
     * @param world        The world to check in
     * @param tx           The x coordinate of the position
     * @param ty           The y coordinate of the position
     * @param tz           The z coordinate of the position
     * @param entityHeight The height of the entity
     * @param entityWidth  The width of the entity
     * @return true if the position is valid, false otherwise
     */
    public static boolean isPositionValid(@NotNull World world, double tx, double ty, double tz, double entityHeight, double entityWidth)
    {
        double radius = entityWidth / 2.0;
        double minX = tx - radius;
        double maxX = tx + radius;
        double maxY = ty + entityHeight;
        double minZ = tz - radius;
        double maxZ = tz + radius;

        BoundingBox entityBox = new BoundingBox(minX, ty, minZ, maxX, maxY, maxZ);

        int minBlockX = (int) Math.floor(minX);
        int maxBlockX = (int) Math.floor(maxX);
        int minBlockY = (int) Math.floor(ty);
        int maxBlockY = (int) Math.floor(maxY);
        if(maxY > maxBlockY && maxBlockY == minBlockY)
            maxBlockY++;
        int minBlockZ = (int) Math.floor(minZ);
        int maxBlockZ = (int) Math.floor(maxZ);

        for(int x = minBlockX; x <= maxBlockX; x++)
        {
            for(int y = minBlockY; y <= maxBlockY; y++)
            {
                for(int z = minBlockZ; z <= maxBlockZ; z++)
                {
                    Block block = world.getBlockAt(x, y, z);
                    if(block.getBlockData() instanceof Openable)
                        continue;

                    Collection<BoundingBox> blockBoxes = block.getCollisionShape().getBoundingBoxes();
                    if(blockBoxes.isEmpty())
                        continue;

                    for(BoundingBox blockBox : blockBoxes)
                    {
                        BoundingBox absoluteBox = blockBox.clone().shift(x, y, z);
                        if(entityBox.overlaps(absoluteBox))
                            return false;
                    }
                }
            }
        }

        return true;
    }

    public @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end) throws PathfindingUtils.PathfindingException
    {
        if(start.getWorld() == null || end.getWorld() == null)
            return null;

        if(!start.getWorld().equals(end.getWorld()))
            return null;

        openSet.clear();
        openSetIds.clear();
        allNodes.clear();
        this.world = start.getWorld();

        int startFloorY = resolveFloorY(start);
        int endFloorY = resolveFloorY(end);

        Block startFloor = world.getBlockAt(start.getBlockX(), startFloorY, start.getBlockZ());
        if(NpcApi.config.checkValidPath() && !isSafeFloor(startFloor))
            throw new PathfindingUtils.PathfindingException("Start not on a valid floor: " + start);

        Block endFloor = world.getBlockAt(end.getBlockX(), endFloorY, end.getBlockZ());
        if(NpcApi.config.checkValidPath() && !isSafeFloor(endFloor))
            throw new PathfindingUtils.PathfindingException("End not on a valid floor: " + end);

        Node startNode = new Node(start.getBlockX(), startFloorY, start.getBlockZ(), null);
        startNode.gCost = 0;
        startNode.calculateH(end);

        openSet.add(startNode);
        openSetIds.add(startNode.id);
        allNodes.put(startNode.id, startNode);

        int iterations = 0;

        while(!openSet.isEmpty())
        {
            if(iterations > maxIterations)
                return null;

            iterations++;

            Node current = openSet.poll();
            openSetIds.remove(current.id);

            if(distanceSq(current, end) < 1.0)
                return retracePath(current);

            current.closed = true;

            for(int x = -1; x <= 1; x++)
            {
                for(int y = -1; y <= 1; y++)
                {
                    for(int z = -1; z <= 1; z++)
                    {
                        if(x == 0 && y == 0 && z == 0)
                            continue;

                        if(!allowDiagonal && (Math.abs(x) + Math.abs(z) > 1))
                            continue;

                        int targetX = current.x + x;
                        int targetY = current.y + y;
                        int targetZ = current.z + z;

                        if(!canWalk(current.x, current.y, current.z, targetX, targetY, targetZ))
                            continue;

                        long id = Node.hash(targetX, targetY, targetZ);
                        Node neighbor = allNodes.get(id);

                        if(neighbor == null)
                        {
                            neighbor = new Node(targetX, targetY, targetZ, id);
                            allNodes.put(id, neighbor);
                        }

                        if(neighbor.closed)
                            continue;

                        double newGCost = current.gCost + MOVE_COSTS[x + 1][y + 1][z + 1];

                        if(newGCost < neighbor.gCost || !openSetIds.contains(id))
                        {
                            neighbor.gCost = newGCost;
                            neighbor.calculateH(end);
                            neighbor.parent = current;

                            if(!openSetIds.contains(id))
                            {
                                openSet.add(neighbor);
                                openSetIds.add(id);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Advanced physics check. Checks whether we can move from one floor block to another.
     * <p>
     * The {@code fy} and {@code ty} values are floor-block Y coordinates. Entity feet and headspace are checked at {@code ty + 1} and {@code ty + 2}.
     */
    private boolean canWalk(int fx, int fy, int fz, int tx, int ty, int tz)
    {
        Block floor = world.getBlockAt(tx, ty, tz);
        if(!isSafeFloor(floor))
            return false;

        double absoluteFeetY = feetYAt(tx, ty, tz);

        if(!isPositionValid(world, tx + 0.5, absoluteFeetY, tz + 0.5, entityHeight, entityWidth))
            return false;

        if(fx != tx && fz != tz)
        {
            double currentFeetY = feetYAt(fx, fy, fz);
            double checkY = Math.max(currentFeetY, absoluteFeetY);

            if(!isPositionValid(world, fx + 0.5, checkY, tz + 0.5, entityHeight, entityWidth))
                return false;

            if(!isPositionValid(world, tx + 0.5, checkY, fz + 0.5, entityHeight, entityWidth))
                return false;
        }

        if(fy != ty)
        {
            double currentFeetY = feetYAt(fx, fy, fz);
            double highestFloorY = Math.max(currentFeetY, absoluteFeetY);

            if(!isPositionValid(world, tx + 0.5, highestFloorY, tz + 0.5, entityHeight, entityWidth) ||
                    !isPositionValid(world, fx + 0.5, highestFloorY, fz + 0.5, entityHeight, entityWidth))
                return false;
        }

        return true;
    }

    /**
     * Resolves the block Y coordinate of the floor beneath a feet-based location.
     * This keeps path nodes aligned with partial collision blocks such as slabs and stairs.
     *
     * @param loc the feet-based location to inspect
     * @return the Y coordinate of the floor block
     */
    private int resolveFloorY(@NotNull Location loc)
    {
        World w = loc.getWorld();
        if(w == null)
            return loc.getBlockY() - 1;

        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        int startY = loc.getBlockY();

        double lx = loc.getX() - bx;
        double lz = loc.getZ() - bz;

        for(int y = startY + 1; y >= startY - 6; y--)
        {
            Block block = w.getBlockAt(bx, y, bz);

            if(block.getBlockData() instanceof Openable)
                continue;

            if(block.isLiquid())
                continue;

            if(!block.getType().isSolid() || block.isPassable())
                continue;

            Collection<BoundingBox> boxes = block.getCollisionShape().getBoundingBoxes();
            if(boxes.isEmpty())
                return y;

            for(BoundingBox bb : boxes)
            {
                if(lx >= bb.getMinX() && lx <= bb.getMaxX() && lz >= bb.getMinZ() && lz <= bb.getMaxZ())
                    return y;
            }

            return y;
        }

        return loc.getBlockY() - 1;
    }

    /**
     * Checks if a block is valid to stand on.
     */
    public static boolean isSafeFloor(Block block)
    {
        if(block == null)
            return false;

        Material type = block.getType();
        if(type.isAir() || block.isLiquid())
            return false;

        return !block.isPassable();
    }

    private @NotNull List<Location> retracePath(@NotNull Node current)
    {
        List<Location> path = new ArrayList<>();
        while(current != null)
        {
            double feetY = feetYAt(current.x, current.y, current.z);
            path.add(new Location(world, current.x + 0.5, feetY, current.z + 0.5));
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private double feetYAt(int x, int floorY, int z)
    {
        Block floor = world.getBlockAt(x, floorY, z);
        return floorY + topSurfaceAt(floor, 0.5, 0.5);
    }

    private double topSurfaceAt(@NotNull Block block, double lx, double lz)
    {
        Collection<BoundingBox> boxes = block.getCollisionShape().getBoundingBoxes();
        if(boxes.isEmpty())
            return 1.0;

        double bestTop = -1.0;

        for(BoundingBox bb : boxes)
        {
            if(lx >= bb.getMinX() && lx <= bb.getMaxX() && lz >= bb.getMinZ() && lz <= bb.getMaxZ())
                bestTop = Math.max(bestTop, bb.getMaxY());
        }

        if(bestTop < 0.0)
        {
            for(BoundingBox bb : boxes)
                bestTop = Math.max(bestTop, bb.getMaxY());
        }

        if(bestTop <= 0.0)
            return 1.0;

        return bestTop;
    }

    private double distanceSq(@NotNull Node n, @NotNull Location l)
    {
        double dx = (n.x + 0.5) - l.getX();
        double dy = feetYAt(n.x, n.y, n.z) - l.getY();
        double dz = (n.z + 0.5) - l.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static class Node implements Comparable<Node>
    {
        final int x, y, z;
        final long id;

        double gCost = Double.MAX_VALUE;
        double hCost = 0;
        Node parent = null;
        boolean closed = false;

        public Node(int x, int y, int z, Long id)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = (id != null) ? id : hash(x, y, z);
        }

        public static long hash(int x, int y, int z)
        {
            return ((long) x & 0x3FFFFFF) | (((long) z & 0x3FFFFFF) << 26) | (((long) y & 0xFFF) << 52);
        }

        public void calculateH(@NotNull Location end)
        {
            double dx = (x + 0.5) - end.getX();
            double dy = (y + 1.0) - end.getY();
            double dz = (z + 0.5) - end.getZ();
            this.hCost = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public double getFCost()
        {
            return gCost + hCost;
        }

        @Override
        public int compareTo(@NotNull Node other)
        {
            return Double.compare(this.getFCost(), other.getFCost());
        }
    }
}
