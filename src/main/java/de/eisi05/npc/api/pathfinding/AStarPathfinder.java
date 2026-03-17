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
    private final int maxIterations;
    private final boolean allowDiagonal;
    private World world;

    private final PriorityQueue<Node> openSet = new PriorityQueue<>();
    private final Map<Long, Node> allNodes = new HashMap<>();

    public AStarPathfinder(int maxIterations, boolean allowDiagonal)
    {
        this.maxIterations = maxIterations;
        this.allowDiagonal = allowDiagonal;
    }

    public @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end) throws PathfindingUtils.PathfindingException
    {
        if(!start.getWorld().equals(end.getWorld()))
            return null;

        openSet.clear();
        allNodes.clear();
        this.world = start.getWorld();

        int startOffset = Math.abs(start.getY() - start.getBlockY()) > 0 ? 0 : 1;
        Block startFloor = world.getBlockAt(start.getBlockX(), start.getBlockY() - startOffset, start.getBlockZ());
        if(NpcApi.config.checkValidPath() && !isSafeFloor(startFloor))
            throw new PathfindingUtils.PathfindingException("Start not on a valid floor: " + start);

        int endOffset = Math.abs(end.getY() - end.getBlockY()) > 0 ? 0 : 1;
        Block endFloor = world.getBlockAt(end.getBlockX(), end.getBlockY() - endOffset, end.getBlockZ());
        if(NpcApi.config.checkValidPath() && !isSafeFloor(endFloor))
            throw new PathfindingUtils.PathfindingException("End not on a valid floor: " + end);

        Node startNode = new Node(start.getBlockX(), start.getBlockY(), start.getBlockZ(), null);
        startNode.gCost = 0;
        startNode.calculateH(end);

        openSet.add(startNode);
        allNodes.put(startNode.id, startNode);

        int iterations = 0;

        while(!openSet.isEmpty())
        {
            if(iterations > maxIterations)
                return null;

            iterations++;

            Node current = openSet.poll();

            if(distance(current, end) < 2)
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
                        Node neighbor = allNodes.getOrDefault(id, new Node(targetX, targetY, targetZ, id));

                        if(neighbor.closed)
                            continue;

                        double moveCost = (Math.abs(x) + Math.abs(y) + Math.abs(z)) > 1 ? 1.414 : 1.0;
                        double newGCost = current.gCost + moveCost;

                        if(newGCost < neighbor.gCost || !openSet.contains(neighbor))
                        {
                            neighbor.gCost = newGCost;
                            neighbor.calculateH(end);
                            neighbor.parent = current;

                            if(!openSet.contains(neighbor))
                            {
                                openSet.add(neighbor);
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
     * Advanced physics check.
     * Checks if we can move from (fx, fy, fz) to (tx, ty, tz).
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
     * Checks if a block is valid to stand ON.
     */
    private boolean isSafeFloor(Block block)
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
    private boolean isSolid(Block block)
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
            this.hCost = Math.hypot(Math.hypot(x - end.getBlockX(), y - end.getBlockY()), z - end.getBlockZ());
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