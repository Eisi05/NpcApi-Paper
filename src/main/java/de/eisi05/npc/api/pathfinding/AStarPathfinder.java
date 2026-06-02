package de.eisi05.npc.api.pathfinding;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.utils.Var;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
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
    private final PriorityQueue<Node> openSet = new PriorityQueue<>();
    private final Set<Long> openSetIds = new HashSet<>();
    private final Map<Long, Node> allNodes = new HashMap<>();
    private World world;

    public AStarPathfinder(int maxIterations, boolean allowDiagonal)
    {
        this.maxIterations = maxIterations;
        this.allowDiagonal = allowDiagonal;
    }

    /**
     * Checks if a block is valid to stand ON.
     */
    public static boolean isSafeFloor(Block block)
    {
        if(block == null)
            return false;

        Material type = block.getType();
        if(type.isAir() || block.isLiquid())
            return false;

        if(block.isPassable())
            return false;

        return true;
    }

    /**
     * Checks if a block obstructs movement (is a wall).
     */
    public static boolean isSolid(Block block)
    {
        if(block == null)
            return false;

        Material type = block.getType();
        if(type.isAir())
            return false;

        if(Var.isCarpet(type) && Var.isCarpet(block.getRelative(BlockFace.UP).getType()))
            return true;

        if(Var.isCarpet(type))
            return false;

        if(block.isPassable())
            return false;

        if(block.getBlockData() instanceof Openable)
            return false;

        return true;
    }

    public @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end) throws PathfindingUtils.PathfindingException
    {
        if(!start.getWorld().equals(end.getWorld()))
            return null;

        openSet.clear();
        openSetIds.clear();
        allNodes.clear();
        this.world = start.getWorld();

        int startOffset = Math.abs(start.getY() - start.getBlockY()) > 0 ? 0 : 1;
        Block startFloor = findSafeFloor(start.getBlockX(), start.getBlockY() - startOffset, start.getBlockZ());
        if(NpcApi.config.checkValidPath() && startFloor == null)
            throw new PathfindingUtils.PathfindingException("Start not on a valid floor: " + start);

        int endOffset = Math.abs(end.getY() - end.getBlockY()) > 0 ? 0 : 1;
        Block endFloor = findSafeFloor(end.getBlockX(), end.getBlockY() - endOffset, end.getBlockZ());
        if(NpcApi.config.checkValidPath() && endFloor == null)
            throw new PathfindingUtils.PathfindingException("End not on a valid floor: " + end);

        Node startNode = new Node(start.getBlockX(), start.getBlockY(), start.getBlockZ(), null);
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

            if(distance(current, end) < 4)
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

                        if(!allowDiagonal && (Math.abs(x) + Math.abs(z) > 1.5))
                            continue;

                        int targetX = current.x + x;
                        int targetY = current.y + y;
                        int targetZ = current.z + z;

                        if(!canWalk(current.x, current.y, current.z, targetX, targetY, targetZ))
                            continue;

                        long id = Node.hash(targetX, targetY, targetZ);
                        Node neighbor = allNodes.get(id);

                        if(neighbor == null)
                            neighbor = new Node(targetX, targetY, targetZ, id);

                        if(neighbor.closed)
                            continue;

                        double moveCost = MOVE_COSTS[x + 1][y + 1][z + 1];
                        double newGCost = current.gCost + moveCost;

                        if(newGCost < neighbor.gCost || !openSetIds.contains(id))
                        {
                            neighbor.gCost = newGCost;
                            neighbor.calculateH(end);
                            neighbor.parent = current;

                            if(!openSetIds.contains(id))
                            {
                                openSet.add(neighbor);
                                openSetIds.add(id);
                                allNodes.put(id, neighbor);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Advanced physics check. Checks if we can move from (fx, fy, fz) to (tx, ty, tz).
     */
    private boolean canWalk(int fx, int fy, int fz, int tx, int ty, int tz)
    {
        Block floor = world.getBlockAt(tx, ty, tz);
        Block spaceFeet = world.getBlockAt(tx, ty + 1, tz);
        Block spaceHead = world.getBlockAt(tx, ty + 2, tz);

        if(!isSafeFloor(floor))
            return false;

        if(isSolid(spaceFeet) || isSolid(spaceHead))
            return false;

        if(fx != tx && fz != tz)
        {
            Block checkA = world.getBlockAt(fx, ty + 1, tz);
            Block checkB = world.getBlockAt(tx, ty + 1, fz);
            if(isSolid(checkA) || isSolid(checkB))
                return false;
        }

        return true;
    }

    /**
     * Finds a safe floor block at or below the given coordinates. Searches downward from the starting Y level to find a solid, passable block. Includes edge
     * detection to handle cases where the entity is standing near a block edge.
     *
     * @param x The X coordinate
     * @param y The Y coordinate to start searching from
     * @param z The Z coordinate
     * @return The safe floor block, or null if none found
     */
    private @Nullable Block findSafeFloor(int x, int y, int z)
    {
        Block directBlock = findSafeFloorDirect(x, y, z);
        if(directBlock != null)
            return directBlock;

        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for(int[] offset : offsets)
        {
            Block neighborBlock = findSafeFloorDirect(x + offset[0], y, z + offset[1]);
            if(neighborBlock != null)
                return neighborBlock;
        }

        return null;
    }

    /**
     * Finds a safe floor block directly at the given coordinates by searching downward.
     *
     * @param x The X coordinate
     * @param y The Y coordinate to start searching from
     * @param z The Z coordinate
     * @return The safe floor block, or null if none found
     */
    private @Nullable Block findSafeFloorDirect(int x, int y, int z)
    {
        for(int searchY = y; searchY >= y - 3; searchY--)
        {
            Block block = world.getBlockAt(x, searchY, z);
            if(isSafeFloor(block))
                return block;
        }
        return null;
    }

    private @NotNull List<Location> retracePath(@NotNull Node current)
    {
        List<Location> path = new ArrayList<>();
        while(current != null)
        {
            path.add(new Location(world, current.x + 0.5, current.y, current.z + 0.5));
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private double distance(@NotNull Node n, @NotNull Location l)
    {
        double dx = (n.x + 0.5) - l.getBlockX();
        double dy = n.y - l.getBlockY();
        double dz = (n.z + 0.5) - l.getBlockZ();

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
            double dx = x - end.getBlockX();
            double dy = y - end.getBlockY();
            double dz = z - end.getBlockZ();
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