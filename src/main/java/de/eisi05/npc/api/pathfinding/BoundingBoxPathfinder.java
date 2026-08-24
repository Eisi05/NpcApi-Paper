package de.eisi05.npc.api.pathfinding;

import de.eisi05.npc.api.NpcApi;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Continuous 3D Bounding-Box Theta* Pathfinder with Dynamic Obstacle Clearance Probing.
 * <p>
 * Operates completely independently of integer block centers. Uses entity-footprint scanning, sub-grid step offsets, 3D swept-volume collision detection, and
 * exact collision shape measurement. When obstacles like open trapdoors, fence edges, or wall frames partially block a path, the pathfinder calculates the
 * exact width/depth offset needed to clear the obstacle boundary and tests if the NPC can smoothly navigate around it.
 */
public class BoundingBoxPathfinder extends AbstractPathfinder
{
    private final double gridStep;
    private final PriorityQueue<SubNode> openSet = new PriorityQueue<>();
    private final Long2ObjectOpenHashMap<SubNode> allNodes = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Collection<BoundingBox>> collisionCache = new Long2ObjectOpenHashMap<>();
    private World world;

    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
    {
        this(maxIterations, allowDiagonal, entityHeight, entityWidth, 0.25);
    }

    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth, double gridStep)
    {
        super(maxIterations, allowDiagonal, entityHeight, entityWidth);
        this.gridStep = Math.max(0.1, gridStep);
    }

    private static long packBlockCoord(int x, int y, int z)
    {
        return ((long) (x & 0x3FFFFFF)) | (((long) (z & 0x3FFFFFF)) << 26) | (((long) (y & 0xFFF)) << 52);
    }

    /**
     * Checks if a specific 3D location is valid for an entity bounding box without overlapping solid block collision shapes. Fully spatial and
     * non-block-bound.
     */
    public static boolean isBoxValidAt(@NotNull World world, double x, double y, double z, double entityHeight, double entityWidth)
    {
        double radius = entityWidth / 2.0;
        BoundingBox entityBox = new BoundingBox(
                x - radius + 0.001, y + 0.001, z - radius + 0.001,
                x + radius - 0.001, y + entityHeight - 0.001, z + radius - 0.001
        );

        int minBlockX = (int) Math.floor(entityBox.getMinX());
        int maxBlockX = (int) Math.floor(entityBox.getMaxX());
        int minBlockY = (int) Math.floor(entityBox.getMinY());
        int maxBlockY = (int) Math.floor(entityBox.getMaxY());
        int minBlockZ = (int) Math.floor(entityBox.getMinZ());
        int maxBlockZ = (int) Math.floor(entityBox.getMaxZ());

        for(int bx = minBlockX; bx <= maxBlockX; bx++)
        {
            for(int by = minBlockY; by <= maxBlockY; by++)
            {
                for(int bz = minBlockZ; bz <= maxBlockZ; bz++)
                {
                    Block block = world.getBlockAt(bx, by, bz);
                    if(block.getBlockData() instanceof Openable)
                        continue;

                    if(block.isEmpty() || block.isPassable() || NpcApi.config.pathfindingPassableOverride().test(block))
                        continue;

                    Collection<BoundingBox> blockBoxes = block.getCollisionShape().getBoundingBoxes();
                    if(blockBoxes.isEmpty())
                        continue;

                    for(BoundingBox blockBox : blockBoxes)
                    {
                        double bMinX = blockBox.getMinX() + bx;
                        double bMaxX = blockBox.getMaxX() + bx;
                        double bMinY = blockBox.getMinY() + by;
                        double bMaxY = blockBox.getMaxY() + by;
                        double bMinZ = blockBox.getMinZ() + bz;
                        double bMaxZ = blockBox.getMaxZ() + bz;
                        if(entityBox.getMinX() < bMaxX && entityBox.getMaxX() > bMinX &&
                                entityBox.getMinY() < bMaxY && entityBox.getMaxY() > bMinY &&
                                entityBox.getMinZ() < bMaxZ && entityBox.getMaxZ() > bMinZ)
                            return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Static, world-parameterized swept bounding-box check with no per-search caching. Companion to {@link #resolveGroundSupport} for callers outside an active
     * search.
     */
    public static boolean canSweepMove(@NotNull World world, double x1, double y1, double z1, double x2, double y2, double z2,
                                       double entityHeight, double entityWidth)
    {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if(distance < 0.0001)
            return true;

        double safeStep = Math.max(0.1, entityWidth / 2.0);
        int steps = Math.max(1, (int) Math.ceil(distance / safeStep));

        double stepX = dx / steps;
        double stepY = dy / steps;
        double stepZ = dz / steps;

        for(int i = 0; i <= steps; i++)
        {
            double cx = x1 + (stepX * i);
            double cy = y1 + (stepY * i);
            double cz = z1 + (stepZ * i);

            if(!isBoxValidAt(world, cx, cy, cz, entityHeight, entityWidth))
                return false;
        }

        return true;
    }

    /**
     * Static, world-parameterized ground/footprint support probe with no per-search caching. Intended for callers outside an active search (e.g.
     * {@code PathTask} during path execution) that want the exact same footprint-aware, collision-shape-accurate ground logic the pathfinder itself used to
     * plan the route - so a spot the planner considered walkable (like a gap next to an open trapdoor) is judged the same way at execution time.
     * <p>
     * Deliberately takes no {@code entityHeight}: this method only answers "where is the floor," based on the entity's horizontal footprint
     * ({@code entityWidth}). Whether there's enough headroom above that floor for the entity to actually stand there is a separate question, already answered
     * correctly by the height-aware {@link #isBoxValidAt} / {@link #canSweepMove} checks callers run against the resulting feet position.
     */
    public static @NotNull FootSupport resolveGroundSupport(@NotNull World world, double x, double currentY, double z, double entityWidth)
    {
        double radius = entityWidth / 2.0;
        double minX = x - radius;
        double maxX = x + radius;
        double minZ = z - radius;
        double maxZ = z + radius;

        int minBlockX = (int) Math.floor(minX);
        int maxBlockX = (int) Math.floor(maxX);
        int minBlockZ = (int) Math.floor(minZ);
        int maxBlockZ = (int) Math.floor(maxZ);

        int searchStartY = (int) Math.floor(currentY + 0.6);
        int searchEndY = (int) Math.floor(currentY - 3.0);

        double highestTopY = -Double.MAX_VALUE;
        boolean foundSolid = false;
        double hazardPenalty = 0.0;

        for(int bx = minBlockX; bx <= maxBlockX; bx++)
        {
            for(int bz = minBlockZ; bz <= maxBlockZ; bz++)
            {
                for(int by = searchStartY; by >= searchEndY; by--)
                {
                    Block block = world.getBlockAt(bx, by, bz);
                    Material mat = block.getType();

                    if(mat == Material.LAVA || mat == Material.FIRE || mat == Material.SOUL_FIRE || mat == Material.MAGMA_BLOCK)
                        hazardPenalty += 10.0;

                    if(block.getBlockData() instanceof Openable)
                        continue;

                    if(block.isEmpty() || block.isPassable() || block.isLiquid() || NpcApi.config.pathfindingPassableOverride().test(block))
                        continue;

                    Collection<BoundingBox> boxes = block.getCollisionShape().getBoundingBoxes();
                    if(boxes.isEmpty())
                    {
                        double top = by + 1.0;
                        if(top <= currentY + 0.6 && top > highestTopY)
                        {
                            highestTopY = top;
                            foundSolid = true;
                        }
                    }
                    else
                    {
                        for(BoundingBox bb : boxes)
                        {
                            double bMinX = bb.getMinX() + bx;
                            double bMaxX = bb.getMaxX() + bx;
                            double bMinZ = bb.getMinZ() + bz;
                            double bMaxZ = bb.getMaxZ() + bz;

                            if(bMaxX >= minX && bMinX <= maxX && bMaxZ >= minZ && bMinZ <= maxZ)
                            {
                                double top = bb.getMaxY() + by;
                                if(top <= currentY + 0.6 && top > highestTopY)
                                {
                                    highestTopY = top;
                                    foundSolid = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        if(!foundSolid)
            return FootSupport.INVALID;

        return new FootSupport(highestTopY, hazardPenalty);
    }

    private Collection<BoundingBox> cachedCollisionBoxes(int bx, int by, int bz)
    {
        long key = packBlockCoord(bx, by, bz);
        Collection<BoundingBox> cached = collisionCache.get(key);
        if(cached != null)
            return cached;

        Block block = world.getBlockAt(bx, by, bz);
        Collection<BoundingBox> boxes;
        if(block.getBlockData() instanceof Openable || block.isEmpty() || block.isPassable() || NpcApi.config.pathfindingPassableOverride().test(block))
            boxes = Collections.emptyList();
        else
            boxes = block.getCollisionShape().getBoundingBoxes();

        collisionCache.put(key, boxes);
        return boxes;
    }

    /**
     * Instance-level, cached equivalent of {@link #isBoxValidAt}. Used internally by the search so repeated probes against the same blocks don't re-query the
     * world/collision shapes every time.
     */
    private boolean isBoxValidAtCached(double x, double y, double z)
    {
        double radius = entityWidth / 2.0;
        BoundingBox entityBox = new BoundingBox(
                x - radius + 0.001, y + 0.001, z - radius + 0.001,
                x + radius - 0.001, y + entityHeight - 0.001, z + radius - 0.001
        );

        int minBlockX = (int) Math.floor(entityBox.getMinX());
        int maxBlockX = (int) Math.floor(entityBox.getMaxX());
        int minBlockY = (int) Math.floor(entityBox.getMinY());
        int maxBlockY = (int) Math.floor(entityBox.getMaxY());
        int minBlockZ = (int) Math.floor(entityBox.getMinZ());
        int maxBlockZ = (int) Math.floor(entityBox.getMaxZ());

        for(int bx = minBlockX; bx <= maxBlockX; bx++)
        {
            for(int by = minBlockY; by <= maxBlockY; by++)
            {
                for(int bz = minBlockZ; bz <= maxBlockZ; bz++)
                {
                    for(BoundingBox blockBox : cachedCollisionBoxes(bx, by, bz))
                    {
                        double bMinX = blockBox.getMinX() + bx;
                        double bMaxX = blockBox.getMaxX() + bx;
                        double bMinY = blockBox.getMinY() + by;
                        double bMaxY = blockBox.getMaxY() + by;
                        double bMinZ = blockBox.getMinZ() + bz;
                        double bMaxZ = blockBox.getMaxZ() + bz;
                        if(entityBox.getMinX() < bMaxX && entityBox.getMaxX() > bMinX &&
                                entityBox.getMinY() < bMaxY && entityBox.getMaxY() > bMinY &&
                                entityBox.getMinZ() < bMaxZ && entityBox.getMaxZ() > bMinZ)
                            return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Unified 3D swept bounding-box raycast.
     *
     * @param checkGround If true, ensures solid floor support exists underneath every raycast step.
     */
    private boolean canSweep(double x1, double y1, double z1, double x2, double y2, double z2, boolean checkGround)
    {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if(distance < 0.0001)
            return true;

        double safeStep = Math.max(0.1, entityWidth / 2.0);
        int steps = Math.max(1, (int) Math.ceil(distance / safeStep));
        double stepX = dx / steps;
        double stepY = dy / steps;
        double stepZ = dz / steps;

        for(int i = 0; i <= steps; i++)
        {
            double cx = x1 + (stepX * i);
            double cy = y1 + (stepY * i);
            double cz = z1 + (stepZ * i);

            if(!isBoxValidAtCached(cx, cy, cz))
                return false;

            if(checkGround)
            {
                FootSupport support = resolveFootSupport(cx, cy, cz);
                if(!support.valid() || Math.abs(support.feetY() - cy) > 0.6)
                    return false;
            }
        }

        return true;
    }

    /**
     * Performs a continuous 3D swept bounding-box check between two points for physical collision only.
     */
    public boolean canSweepMove(double x1, double y1, double z1, double x2, double y2, double z2)
    {
        return canSweep(x1, y1, z1, x2, y2, z2, false);
    }

    /**
     * Performs a continuous 3D swept bounding-box check ensuring both physical clearance AND valid floor support.
     */
    private boolean canSweepWalk(double x1, double y1, double z1, double x2, double y2, double z2)
    {
        return canSweep(x1, y1, z1, x2, y2, z2, true);
    }

    /**
     * Inspects colliding block bounding boxes (e.g. open trapdoors, fence edges, wall frames) at a target location and calculates exact coordinate offsets that
     * shift the entity's bounding box outside the collision shape boundary.
     *
     * @param targetX Candidate X target coordinate
     * @param targetY Candidate Y target coordinate
     * @param targetZ Candidate Z target coordinate
     */
    private void processClearanceOffsets(double targetX, double targetY, double targetZ, SubNode current, Location end)
    {
        double radius = entityWidth / 2.0;
        double margin = 0.01;

        double eMinX = targetX - radius;
        double eMaxX = targetX + radius;
        double eMinY = targetY + 0.001;
        double eMaxY = targetY + entityHeight - 0.001;
        double eMinZ = targetZ - radius;
        double eMaxZ = targetZ + radius;

        int minBlockX = (int) Math.floor(eMinX);
        int maxBlockX = (int) Math.floor(eMaxX);
        int minBlockY = (int) Math.floor(eMinY);
        int maxBlockY = (int) Math.floor(eMaxY);
        int minBlockZ = (int) Math.floor(eMinZ);
        int maxBlockZ = (int) Math.floor(eMaxZ);

        for(int bx = minBlockX; bx <= maxBlockX; bx++)
        {
            for(int by = minBlockY; by <= maxBlockY; by++)
            {
                for(int bz = minBlockZ; bz <= maxBlockZ; bz++)
                {
                    for(BoundingBox bb : cachedCollisionBoxes(bx, by, bz))
                    {

                        double bMinX = bb.getMinX() + bx;
                        double bMaxX = bb.getMaxX() + bx;
                        double bMinY = bb.getMinY() + by;
                        double bMaxY = bb.getMaxY() + by;
                        double bMinZ = bb.getMinZ() + bz;
                        double bMaxZ = bb.getMaxZ() + bz;

                        if(eMinX < bMaxX && eMaxX > bMinX && eMinY < bMaxY && eMaxY > bMinY && eMinZ < bMaxZ && eMaxZ > bMinZ)
                        {

                            double shiftPlusX = bMaxX + radius + margin;
                            double shiftMinusX = bMinX - radius - margin;
                            double shiftPlusZ = bMaxZ + radius + margin;
                            double shiftMinusZ = bMinZ - radius - margin;

                            evaluateCandidatePoint(shiftPlusX, targetZ, current, end);
                            evaluateCandidatePoint(shiftMinusX, targetZ, current, end);
                            evaluateCandidatePoint(targetX, shiftPlusZ, current, end);
                            evaluateCandidatePoint(targetX, shiftMinusZ, current, end);
                        }
                    }
                }
            }
        }
    }

    /**
     * Evaluates solid ground support across the entity's full 2D footprint rather than a single block center.
     */
    private @NotNull FootSupport resolveFootSupport(double x, double currentY, double z)
    {
        double safeWidth = Math.max(0.1, entityWidth * 0.4);
        double radius = safeWidth / 2.0;

        double minX = x - radius;
        double maxX = x + radius;
        double minZ = z - radius;
        double maxZ = z + radius;

        int minBlockX = (int) Math.floor(minX);
        int maxBlockX = (int) Math.floor(maxX);
        int minBlockZ = (int) Math.floor(minZ);
        int maxBlockZ = (int) Math.floor(maxZ);

        int searchStartY = (int) Math.floor(currentY + 0.6);
        int searchEndY = (int) Math.floor(currentY - 3.0);

        double highestTopY = -Double.MAX_VALUE;
        boolean foundSolid = false;
        double hazardPenalty = 0.0;

        for(int bx = minBlockX; bx <= maxBlockX; bx++)
        {
            for(int bz = minBlockZ; bz <= maxBlockZ; bz++)
            {
                for(int by = searchStartY; by >= searchEndY; by--)
                {
                    Block block = world.getBlockAt(bx, by, bz);
                    Material mat = block.getType();

                    if(mat == Material.LAVA || mat == Material.FIRE || mat == Material.SOUL_FIRE || mat == Material.MAGMA_BLOCK)
                        hazardPenalty += 10.0;

                    if(block.getBlockData() instanceof Openable)
                        continue;

                    if(block.isEmpty() || block.isPassable() || block.isLiquid() || NpcApi.config.pathfindingPassableOverride().test(block))
                        continue;

                    Collection<BoundingBox> boxes = cachedCollisionBoxes(bx, by, bz);
                    if(boxes.isEmpty())
                    {
                        double top = by + 1.0;
                        if(top <= currentY + 0.6 && top > highestTopY)
                        {
                            highestTopY = top;
                            foundSolid = true;
                        }
                    }
                    else
                    {
                        for(BoundingBox bb : boxes)
                        {
                            double bMinX = bb.getMinX() + bx;
                            double bMaxX = bb.getMaxX() + bx;
                            double bMinZ = bb.getMinZ() + bz;
                            double bMaxZ = bb.getMaxZ() + bz;

                            if(bMaxX >= minX && bMinX <= maxX && bMaxZ >= minZ && bMinZ <= maxZ)
                            {
                                double top = bb.getMaxY() + by;
                                if(top <= currentY + 0.6 && top > highestTopY)
                                {
                                    highestTopY = top;
                                    foundSolid = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        if(!foundSolid)
            return FootSupport.INVALID;

        return new FootSupport(highestTopY, hazardPenalty);
    }

    @Override
    public @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end, @Nullable Consumer<Double> progressListener)
            throws PathfindingUtils.PathfindingException
    {
        if(start.getWorld() == null || end.getWorld() == null || !start.getWorld().equals(end.getWorld()))
            return null;

        this.world = start.getWorld();
        openSet.clear();
        allNodes.clear();
        collisionCache.clear();

        FootSupport startSupport = resolveFootSupport(start.getX(), start.getY(), start.getZ());
        FootSupport endSupport = resolveFootSupport(end.getX(), end.getY(), end.getZ());

        double startFeetY = startSupport.valid ? startSupport.feetY : start.getY();
        double endFeetY = endSupport.valid ? endSupport.feetY : end.getY();

        if(NpcApi.config.checkValidPath())
        {
            if(!startSupport.valid)
                throw new PathfindingUtils.PathfindingException("Start location has no valid floor support: " + start);
            if(!endSupport.valid)
                throw new PathfindingUtils.PathfindingException("End location has no valid floor support: " + end);
        }

        SubNode startNode = createSubNode(start.getX(), startFeetY, start.getZ(), null);
        startNode.gCost = 0;
        startNode.calculateH(end);

        double startH = startNode.hCost;

        openSet.add(startNode);
        allNodes.put(startNode.id, startNode);

        SubNode bestNode = startNode;
        double bestHCost = startNode.hCost;

        int iterations = 0;
        double[][] directions;
        if(allowDiagonal)
        {
            directions = new double[][]{
                    {1, 0}, {0, 1}, {-1, 0}, {0, -1},
                    {0.7071, 0.7071}, {-0.7071, 0.7071}, {0.7071, -0.7071}, {-0.7071, -0.7071},
                    {0.9238, 0.3826}, {0.3826, 0.9238}, {-0.3826, 0.9238}, {-0.9238, 0.3826},
                    {-0.9238, -0.3826}, {-0.3826, -0.9238}, {0.3826, -0.9238}, {0.9238, -0.3826}
            };
        }
        else
            directions = new double[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

        while(!openSet.isEmpty())
        {
            if(iterations > maxIterations)
                break;

            iterations++;

            SubNode current = openSet.poll();
            if(current.closed)
                continue;

            current.closed = true;
            if(startH > 0 && current.hCost < bestHCost)
            {
                bestHCost = current.hCost;
                bestNode = current;
                if(progressListener != null)
                    progressListener.accept(Math.clamp(1.0 - (bestHCost / startH), 0.0, 1.0));
            }

            if(current.distanceSqTo(end) < (gridStep * gridStep * 1.5))
            {
                List<Location> rawPath = retracePath(current);
                return simplifyPath(rawPath, end);
            }

            double endYDiff = endFeetY - current.y;
            if(endYDiff <= 0.6 && endYDiff >= -1.2 && canSweepWalk(current.x, current.y, current.z, end.getX(), endFeetY, end.getZ()))
            {
                SubNode endNode = createSubNode(end.getX(), endFeetY, end.getZ(), null);
                double directCost = current.gCost + current.distanceTo(end.getX(), endFeetY, end.getZ());
                if(directCost < endNode.gCost)
                {
                    endNode.gCost = directCost;
                    endNode.parent = current;
                }
                List<Location> rawPath = retracePath(endNode);
                return simplifyPath(rawPath, end);
            }

            for(double[] dir : directions)
            {
                double targetX = current.x + (dir[0] * gridStep);
                double targetZ = current.z + (dir[1] * gridStep);

                evaluateCandidatePoint(targetX, targetZ, current, end);
                if(!isBoxValidAtCached(targetX, current.y, targetZ))
                    processClearanceOffsets(targetX, current.y, targetZ, current, end);
            }
        }

        if(bestNode == startNode)
            return null;

        List<Location> rawPath = retracePath(bestNode);
        return simplifyPath(rawPath, rawPath.isEmpty() ? end : rawPath.getLast());
    }

    private void evaluateCandidatePoint(double nextX, double nextZ, SubNode current, Location end)
    {
        FootSupport footSupport = resolveFootSupport(nextX, current.y, nextZ);
        if(!footSupport.valid())
            return;

        double nextFeetY = footSupport.feetY();
        double yDiff = nextFeetY - current.y;

        if(yDiff > 0.6 || yDiff < -1.2)
            return;

        if(!canSweepMove(current.x, current.y, current.z, nextX, nextFeetY, nextZ))
            return;

        SubNode neighbor = createSubNode(nextX, nextFeetY, nextZ, null);
        if(neighbor.closed)
            return;

        SubNode parentCandidate = (current.parent != null) ? current.parent : current;
        boolean losFromParent = false;

        if(parentCandidate != current)
        {
            double pYDiff = nextFeetY - parentCandidate.y;
            if(pYDiff <= 0.6 && pYDiff >= -1.2)
                losFromParent = canSweepWalk(parentCandidate.x, parentCandidate.y, parentCandidate.z, nextX, nextFeetY, nextZ);
        }

        SubNode selectedParent = losFromParent ? parentCandidate : current;
        double stepDistance = selectedParent.distanceTo(nextX, nextFeetY, nextZ);
        double newGCost = selectedParent.gCost + stepDistance + footSupport.hazardPenalty();

        if(newGCost < neighbor.gCost)
        {
            neighbor.gCost = newGCost;
            neighbor.calculateH(end);
            neighbor.parent = selectedParent;
            openSet.add(neighbor);
        }
    }

    private SubNode createSubNode(double x, double y, double z, @Nullable Long explicitId)
    {
        long id = (explicitId != null) ? explicitId : SubNode.hash(x, y, z, gridStep);
        SubNode existing = allNodes.get(id);
        if(existing != null)
            return existing;

        SubNode node = new SubNode(x, y, z, id);
        allNodes.put(id, node);
        return node;
    }

    private @NotNull List<Location> retracePath(@NotNull SubNode current)
    {
        List<Location> path = new ArrayList<>();
        while(current != null)
        {
            path.add(new Location(world, current.x, current.y, current.z));
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * Post-processes and simplifies sub-grid path points by pruning unnecessary waypoints via 3D line-of-sight raycasts.
     */
    private List<Location> simplifyPath(List<Location> path, Location targetEnd)
    {
        if(path.isEmpty())
            return path;

        List<Location> smoothPath = new ArrayList<>();
        Location current = path.getFirst();
        smoothPath.add(current);

        int i = 0;
        while(i < path.size() - 1)
        {
            int furthestVisible = i + 1;
            for(int j = i + 2; j < path.size(); j++)
            {
                Location target = path.get(j);
                if(canSweepWalk(current.getX(), current.getY(), current.getZ(), target.getX(), target.getY(), target.getZ()))
                    furthestVisible = j;
                else
                    break;
            }

            current = path.get(furthestVisible);
            smoothPath.add(current);
            i = furthestVisible;
        }

        Location lastPoint = smoothPath.getLast();
        if(canSweepWalk(lastPoint.getX(), lastPoint.getY(), lastPoint.getZ(), targetEnd.getX(), targetEnd.getY(), targetEnd.getZ()))
            smoothPath.add(targetEnd.clone());

        return smoothPath;
    }

    public record FootSupport(double feetY, boolean valid, double hazardPenalty)
    {
        public static final FootSupport INVALID = new FootSupport(0, false, 0);

        public FootSupport(double feetY, double hazardPenalty)
        {
            this(feetY, true, hazardPenalty);
        }
    }

    private static class SubNode implements Comparable<SubNode>
    {
        final double x, y, z;
        final long id;

        double gCost = Double.MAX_VALUE;
        double hCost = 0;
        SubNode parent = null;
        boolean closed = false;

        public SubNode(double x, double y, double z, long id)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
        }

        public static long hash(double x, double y, double z, double gridStep)
        {
            long gx = Math.round(x / gridStep);
            long gy = Math.round(y / gridStep);
            long gz = Math.round(z / gridStep);
            return (gx & 0x1FFFFFFL) | ((gz & 0x1FFFFFFL) << 25) | ((gy & 0x3FFFL) << 50);
        }

        public void calculateH(@NotNull Location end)
        {
            double dx = x - end.getX();
            double dy = y - end.getY();
            double dz = z - end.getZ();
            this.hCost = Math.sqrt(dx * dx + dy * dy + dz * dz) * 1.001;
        }

        public double distanceTo(double targetX, double targetY, double targetZ)
        {
            double dx = x - targetX;
            double dy = y - targetY;
            double dz = z - targetZ;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public double distanceSqTo(@NotNull Location l)
        {
            double dx = x - l.getX();
            double dy = y - l.getY();
            double dz = z - l.getZ();
            return dx * dx + dy * dy + dz * dz;
        }

        public double getFCost()
        {
            return gCost + hCost;
        }

        @Override
        public int compareTo(@NotNull SubNode other)
        {
            return Double.compare(this.getFCost(), other.getFCost());
        }
    }
}