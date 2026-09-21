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
import java.util.function.BiConsumer;

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
    private final double maxJumpHeight;
    private final double maxFallDistance;
    private final double supportWidth;

    private final PriorityQueue<SubNode> openSet = new PriorityQueue<>();
    private final Long2ObjectOpenHashMap<SubNode> allNodes = new Long2ObjectOpenHashMap<>();

    private final Long2ObjectOpenHashMap<BlockData> blockCache = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<FootSupport> supportCache = new Long2ObjectOpenHashMap<>();

    private World world;

    /**
     * Constructs a new BoundingBoxPathfinder with default step, jump height, and fall distance settings.
     *
     * @param maxIterations the maximum number of iterations allowed
     * @param allowDiagonal whether diagonal movement is permitted
     * @param entityHeight  the height of the entity
     * @param entityWidth   the width of the entity
     */
    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth)
    {
        this(maxIterations, allowDiagonal, entityHeight, entityWidth, 0.25, 1.25, 3.0);
    }

    /**
     * Constructs a new BoundingBoxPathfinder with a custom grid step and default movement limits.
     *
     * @param maxIterations the maximum number of iterations allowed
     * @param allowDiagonal whether diagonal movement is permitted
     * @param entityHeight  the height of the entity
     * @param entityWidth   the width of the entity
     * @param gridStep      the sub-grid step size
     */
    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth, double gridStep)
    {
        this(maxIterations, allowDiagonal, entityHeight, entityWidth, gridStep, 1.25, 3.0);
    }

    /**
     * Constructs a new BoundingBoxPathfinder with fully customized parameters.
     *
     * @param maxIterations   the maximum number of iterations allowed
     * @param allowDiagonal   whether diagonal movement is permitted
     * @param entityHeight    the height of the entity
     * @param entityWidth     the width of the entity
     * @param gridStep        the sub-grid step size
     * @param maxJumpHeight   the maximum height the entity can jump
     * @param maxFallDistance the maximum safe fall distance for the entity
     */
    public BoundingBoxPathfinder(int maxIterations, boolean allowDiagonal, double entityHeight, double entityWidth, double gridStep,
                                 double maxJumpHeight, double maxFallDistance)
    {
        super(maxIterations, allowDiagonal, entityHeight, entityWidth);
        this.gridStep = Math.clamp(0.01, gridStep, 1);
        this.maxJumpHeight = maxJumpHeight;
        this.maxFallDistance = maxFallDistance;
        this.supportWidth = Math.max(0.1, entityWidth * 0.85);
    }

    /**
     * Resolves ground support for an entity at the specified coordinates using default jump and fall constraints.
     *
     * @param world       the world to check in
     * @param x           the target X coordinate
     * @param currentY    the current Y coordinate
     * @param z           the target Z coordinate
     * @param entityWidth the width of the entity
     * @return a {@link FootSupport} instance describing the ground details
     */
    public static @NotNull FootSupport resolveGroundSupport(@NotNull World world, double x, double currentY, double z, double entityWidth)
    {
        return resolveGroundSupport(world, x, currentY, z, entityWidth, 1.25, 3.0);
    }

    /**
     * Resolves ground support for an entity at the specified coordinates with custom movement limitations.
     *
     * @param world           the world to check in
     * @param x               the target X coordinate
     * @param currentY        the current Y coordinate
     * @param z               the target Z coordinate
     * @param entityWidth     the width of the entity
     * @param maxJumpHeight   the maximum jump height allowed
     * @param maxFallDistance the maximum fall distance allowed
     * @return a {@link FootSupport} instance describing the ground details
     */
    public static @NotNull FootSupport resolveGroundSupport(@NotNull World world, double x, double currentY, double z, double entityWidth, double maxJumpHeight,
                                                            double maxFallDistance)
    {
        double supportWidth = Math.max(0.1, entityWidth * 0.85);
        double radius = supportWidth / 2.0;
        double minX = x - radius;
        double maxX = x + radius;
        double minZ = z - radius;
        double maxZ = z + radius;

        int minBlockX = (int) Math.floor(minX);
        int maxBlockX = (int) Math.floor(maxX);
        int minBlockZ = (int) Math.floor(minZ);
        int maxBlockZ = (int) Math.floor(maxZ);

        int searchStartY = (int) Math.floor(currentY + maxJumpHeight);
        int searchEndY = (int) Math.floor(currentY - maxFallDistance);

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

                    if(block.getBlockData() instanceof Openable || block.isEmpty() || block.isPassable() || block.isLiquid() ||
                            NpcApi.config.pathfindingPassableOverride().test(block))
                        continue;

                    if(mat == Material.LAVA || mat == Material.FIRE || mat == Material.SOUL_FIRE || mat == Material.MAGMA_BLOCK)
                        hazardPenalty += 10.0;

                    Collection<BoundingBox> boxes = getBlockBoxes(block);
                    if(boxes.isEmpty())
                    {
                        double top = by + 1.0;
                        if(top <= currentY + maxJumpHeight && top > highestTopY)
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
                                if(top <= currentY + maxJumpHeight && top > highestTopY)
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

        return foundSolid ? new FootSupport(highestTopY, hazardPenalty) : FootSupport.INVALID;
    }

    /**
     * Returns the sub-grid step size used by the pathfinder.
     *
     * @return the sub-grid step size
     */
    public double getGridStep()
    {
        return gridStep;
    }

    /**
     * Retrieves cached block collision and hazard metadata for a specific block coordinate.
     *
     * @param bx the block X coordinate
     * @param by the block Y coordinate
     * @param bz the block Z coordinate
     * @return the corresponding {@link BlockData}
     */
    private BlockData getCachedBlock(int bx, int by, int bz)
    {
        long key = packBlockCoord(bx, by, bz);
        BlockData cached = blockCache.get(key);
        if(cached != null)
            return cached;

        Block block = world.getBlockAt(bx, by, bz);
        Material mat = block.getType();
        double hazard = (mat == Material.LAVA || mat == Material.FIRE || mat == Material.SOUL_FIRE || mat == Material.MAGMA_BLOCK) ? 10.0 : 0.0;

        boolean bodyPassable = block.getBlockData() instanceof Openable || block.isEmpty() || block.isPassable() ||
                NpcApi.config.pathfindingPassableOverride().test(block);
        Collection<BoundingBox> boxes = bodyPassable ? Collections.emptyList() : getBlockBoxes(block);

        boolean isFootingSolid = !bodyPassable && !block.isLiquid();

        BlockData data = new BlockData(boxes, isFootingSolid, hazard);
        blockCache.put(key, data);
        return data;
    }

    /**
     * Checks if the entity's bounding box is entirely valid and free of obstacles at the given position using cached blocks.
     *
     * @param x the target center X coordinate
     * @param y the target feet Y coordinate
     * @param z the target center Z coordinate
     * @return true if the box position is valid, false otherwise
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
                    for(BoundingBox blockBox : getCachedBlock(bx, by, bz).collisionBoxes())
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
     * Performs a swept-volume collision check along a movement path from one point to another.
     *
     * @param x1          start X coordinate
     * @param y1          start Y coordinate
     * @param z1          start Z coordinate
     * @param x2          target X coordinate
     * @param y2          target Y coordinate
     * @param z2          target Z coordinate
     * @param checkGround whether to validate ground support along the sweep
     * @return true if the movement path is clear, false otherwise
     */
    private boolean canSweep(double x1, double y1, double z1, double x2, double y2, double z2, boolean checkGround)
    {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        if(hDist < 0.0001 && Math.abs(dy) < 0.0001)
            return true;

        double safeStep = Math.max(0.05, entityWidth / 2.0);

        if(dy > 0.0001)
        {
            int vSteps = Math.max(1, (int) Math.ceil(dy / safeStep));
            double vStepY = dy / vSteps;
            for(int i = 0; i <= vSteps; i++)
            {
                if(!isBoxValidAtCached(x1, y1 + (vStepY * i), z1))
                    return false;
            }
        }

        if(hDist > 0.0001)
        {
            int hSteps = Math.max(1, (int) Math.ceil(hDist / safeStep));
            double stepX = dx / hSteps;
            double stepZ = dz / hSteps;
            double lastFeetY = y1;

            for(int i = 0; i <= hSteps; i++)
            {
                double cx = x1 + (stepX * i);
                double cz = z1 + (stepZ * i);
                double testY = y1 + (dy * (i / (double) hSteps));

                if(checkGround)
                {
                    FootSupport support = resolveFootSupport(cx, testY, cz);
                    if(!support.valid())
                        return false;

                    testY = support.feetY();

                    if(i > 0)
                    {
                        double stepYDiff = testY - lastFeetY;
                        if(stepYDiff > maxJumpHeight || stepYDiff < -maxFallDistance)
                            return false;
                    }
                    lastFeetY = testY;
                }

                if(!isBoxValidAtCached(cx, testY, cz))
                    return false;
            }
        }

        if(dy < -0.0001)
        {
            int vSteps = Math.max(1, (int) Math.ceil(Math.abs(dy) / safeStep));
            double vStepY = Math.abs(dy) / vSteps;
            for(int i = 0; i <= vSteps; i++)
            {
                if(!isBoxValidAtCached(x2, y1 - (vStepY * i), z2))
                    return false;
            }
        }

        return true;
    }

    /**
     * Checks if a general movement sweep is valid between two points without ground validation.
     *
     * @param x1 start X
     * @param y1 start Y
     * @param z1 start Z
     * @param x2 target X
     * @param y2 target Y
     * @param z2 target Z
     * @return true if the movement is valid
     */
    public boolean canSweepMove(double x1, double y1, double z1, double x2, double y2, double z2)
    {
        return canSweep(x1, y1, z1, x2, y2, z2, false);
    }

    /**
     * Checks if a walking sweep movement is valid between two points, including ground support verification.
     *
     * @param x1 start X
     * @param y1 start Y
     * @param z1 start Z
     * @param x2 target X
     * @param y2 target Y
     * @param z2 target Z
     * @return true if the walking move is valid
     */
    private boolean canSweepWalk(double x1, double y1, double z1, double x2, double y2, double z2)
    {
        return canSweep(x1, y1, z1, x2, y2, z2, true);
    }

    /**
     * Probes and processes obstacle clearance offsets around a target point, evaluating alternative candidate points.
     *
     * @param targetX target X coordinate
     * @param targetY target Y coordinate
     * @param targetZ target Z coordinate
     * @param current current sub-node being expanded
     * @param end     the ultimate destination location
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
                    for(BoundingBox bb : getCachedBlock(bx, by, bz).collisionBoxes())
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
     * Resolves foot support using cached block data and support cache lookups.
     *
     * @param x        the target X coordinate
     * @param currentY the current Y coordinate
     * @param z        the target Z coordinate
     * @return the resolved {@link FootSupport}
     */
    private @NotNull FootSupport resolveFootSupport(double x, double currentY, double z)
    {
        long cacheKey = SubNode.hash(x, currentY, z, gridStep / 2.0);
        FootSupport cached = supportCache.get(cacheKey);
        if(cached != null)
            return cached;

        double radius = supportWidth / 2.0;
        double minX = x - radius;
        double maxX = x + radius;
        double minZ = z - radius;
        double maxZ = z + radius;

        int minBlockX = (int) Math.floor(minX);
        int maxBlockX = (int) Math.floor(maxX);
        int minBlockZ = (int) Math.floor(minZ);
        int maxBlockZ = (int) Math.floor(maxZ);

        int searchStartY = (int) Math.floor(currentY + maxJumpHeight);
        int searchEndY = (int) Math.floor(currentY - maxFallDistance);

        double highestTopY = -Double.MAX_VALUE;
        boolean foundSolid = false;
        double hazardPenalty = 0.0;

        for(int bx = minBlockX; bx <= maxBlockX; bx++)
        {
            for(int bz = minBlockZ; bz <= maxBlockZ; bz++)
            {
                for(int by = searchStartY; by >= searchEndY; by--)
                {
                    BlockData blockData = getCachedBlock(bx, by, bz);
                    hazardPenalty += blockData.hazardPenalty();

                    if(!blockData.isFootingSolid())
                        continue;

                    Collection<BoundingBox> boxes = blockData.collisionBoxes();
                    if(boxes.isEmpty())
                    {
                        double top = by + 1.0;
                        if(top <= currentY + maxJumpHeight && top > highestTopY)
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
                                if(top <= currentY + maxJumpHeight && top > highestTopY)
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

        FootSupport result = foundSolid ? new FootSupport(highestTopY, hazardPenalty) : FootSupport.INVALID;
        supportCache.put(cacheKey, result);
        return result;
    }

    /**
     * Calculates an optimized continuous 3D path from start to end using the Bounding-Box Theta* algorithm.
     *
     * @param start            the starting location
     * @param end              the target destination location
     * @param progressListener A listener that receives a completion percentage between 0.0 and 1.0 and the current iteration count
     * @return a list of locations representing the smoothed path, or null if unreachable
     * @throws PathfindingUtils.PathfindingException if start or end locations lack valid floor support
     */
    @Override
    public @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end, @Nullable BiConsumer<Double, Integer> progressListener)
            throws PathfindingUtils.PathfindingException
    {
        if(start.getWorld() == null || end.getWorld() == null || !start.getWorld().equals(end.getWorld()))
            return null;

        this.world = start.getWorld();
        openSet.clear();
        allNodes.clear();
        blockCache.clear();
        supportCache.clear();

        FootSupport startSupport = resolveFootSupport(start.getX(), start.getY(), start.getZ());
        FootSupport endSupport = resolveFootSupport(end.getX(), end.getY(), end.getZ());

        if(NpcApi.config.checkValidPath())
        {
            if(!startSupport.valid())
                throw new PathfindingUtils.PathfindingException("Start location has no valid ground support: " + start);

            if(!endSupport.valid())
                throw new PathfindingUtils.PathfindingException("End location has no valid ground support: " + end);
        }

        double startFeetY = startSupport.valid() ? startSupport.feetY() : start.getY();
        double endFeetY = endSupport.valid() ? endSupport.feetY() : end.getY();

        SubNode startNode = new SubNode(start.getX(), startFeetY, start.getZ(), gridStep);
        startNode.gCost = 0;
        startNode.calculateH(end);

        double startH = startNode.hCost;
        double minH = startH;

        openSet.add(startNode);
        allNodes.put(startNode.id, startNode);

        int iterations = 0;

        while(!openSet.isEmpty())
        {
            if(iterations >= maxIterations)
                return null;

            iterations++;

            SubNode current = openSet.poll();

            if(startH > 0 && current.hCost < minH)
                minH = current.hCost;

            if(progressListener != null)
                progressListener.accept(Math.clamp(1.0 - (minH / startH), 0.0, 1.0), iterations);


            if(current.distanceSqTo(end.getX(), endFeetY, end.getZ()) <= (gridStep * 1.5) * (gridStep * 1.5))
            {
                List<Location> rawPath = retracePath(current, end);
                return postProcessAndGroundPath(rawPath);
            }

            current.closed = true;

            for(double dx = -gridStep; dx <= gridStep; dx += gridStep)
            {
                for(double dz = -gridStep; dz <= gridStep; dz += gridStep)
                {
                    if(Math.abs(dx) < 0.001 && Math.abs(dz) < 0.001)
                        continue;

                    if(!allowDiagonal && Math.abs(dx) > 0.001 && Math.abs(dz) > 0.001)
                        continue;

                    double targetX = current.x + dx;
                    double targetZ = current.z + dz;

                    FootSupport supp = resolveFootSupport(targetX, current.y, targetZ);
                    if(!supp.valid())
                        continue;

                    double targetY = supp.feetY();
                    double dy = targetY - current.y;

                    if(dy > maxJumpHeight || dy < -maxFallDistance)
                        continue;

                    if(!canSweepWalk(current.x, current.y, current.z, targetX, targetY, targetZ))
                        continue;

                    long id = SubNode.hash(targetX, targetY, targetZ, gridStep);
                    SubNode neighbor = allNodes.get(id);

                    if(neighbor == null)
                    {
                        neighbor = new SubNode(targetX, targetY, targetZ, gridStep);
                        allNodes.put(id, neighbor);
                    }

                    if(neighbor.closed)
                        continue;

                    boolean shortcutUsed = false;
                    // Prevent Theta* shortcuts across vertical height differences to keep jumps grounded
                    if(current.parent != null && Math.abs(current.parent.y - targetY) < 0.01)
                    {
                        if(canSweepWalk(current.parent.x, current.parent.y, current.parent.z, targetX, targetY, targetZ))
                        {
                            double dist = distance(current.parent.x, current.parent.y, current.parent.z, targetX, targetY, targetZ);
                            double newGCost = current.parent.gCost + dist + supp.hazardPenalty();
                            if(newGCost < neighbor.gCost)
                            {
                                neighbor.gCost = newGCost;
                                neighbor.parent = current.parent;
                                neighbor.calculateH(end);
                                shortcutUsed = true;

                                if(!openSet.contains(neighbor))
                                    openSet.add(neighbor);
                            }
                        }
                    }

                    if(!shortcutUsed)
                    {
                        double dist = distance(current.x, current.y, current.z, targetX, targetY, targetZ);
                        double newGCost = current.gCost + dist + supp.hazardPenalty();
                        if(newGCost < neighbor.gCost)
                        {
                            neighbor.gCost = newGCost;
                            neighbor.parent = current;
                            neighbor.calculateH(end);

                            if(!openSet.contains(neighbor))
                                openSet.add(neighbor);
                        }
                    }

                    processClearanceOffsets(targetX, targetY, targetZ, current, end);
                }
            }
        }

        return null;
    }

    /**
     * Evaluates a candidate point during path exploration, updating costs and parent nodes if optimal.
     *
     * @param candX   the target candidate X coordinate
     * @param candZ   the target candidate Z coordinate
     * @param current the current sub-node
     * @param end     the target end location
     */
    private void evaluateCandidatePoint(double candX, double candZ, SubNode current, Location end)
    {
        FootSupport supp = resolveFootSupport(candX, current.y, candZ);
        if(!supp.valid())
            return;

        double targetY = supp.feetY();
        double dy = targetY - current.y;
        if(dy > maxJumpHeight || dy < -maxFallDistance)
            return;

        if(!canSweepWalk(current.x, current.y, current.z, candX, targetY, candZ))
            return;

        long id = SubNode.hash(candX, targetY, candZ, gridStep);
        SubNode neighbor = allNodes.get(id);

        if(neighbor == null)
        {
            neighbor = new SubNode(candX, targetY, candZ, gridStep);
            allNodes.put(id, neighbor);
        }

        if(neighbor.closed)
            return;

        double dist = distance(current.x, current.y, current.z, candX, targetY, candZ);
        double newGCost = current.gCost + dist + supp.hazardPenalty();

        if(newGCost < neighbor.gCost)
        {
            neighbor.gCost = newGCost;
            neighbor.parent = current;
            neighbor.calculateH(end);

            if(!openSet.contains(neighbor))
                openSet.add(neighbor);
        }
    }

    /**
     * Retraces the path backwards from the given sub-node to construct a raw location list.
     *
     * @param endNode the target sub-node
     * @param destination the final target destination
     * @return a list of locations representing the raw path
     */
    private List<Location> retracePath(SubNode endNode, Location destination)
    {
        List<Location> path = new ArrayList<>();
        SubNode current = endNode;

        while(current != null)
        {
            path.add(new Location(world, current.x, current.y, current.z));
            current = current.parent;
        }

        Collections.reverse(path);

        if(!path.isEmpty())
        {
            if(path.getLast().distanceSquared(destination) > 0.001)
            {
                FootSupport endSupp = resolveFootSupport(destination.getX(), destination.getY(), destination.getZ());
                double endFeetY = endSupp.valid() ? endSupp.feetY() : destination.getY();
                path.add(new Location(world, destination.getX(), endFeetY, destination.getZ()));
            }
        }

        return path;
    }

    /**
     * Post-processes a raw path by grounding every segment to the actual block floor surface,
     * inserting explicit step/jump transition waypoints where elevation changes occur, and simplifying
     * redundant collinear points while maintaining off-grid precision.
     *
     * @param rawPath the initial path from graph search
     * @return a clean, fully grounded list of locations with no floating segments
     */
    private List<Location> postProcessAndGroundPath(List<Location> rawPath)
    {
        if(rawPath == null || rawPath.size() < 2)
            return rawPath;

        List<Location> groundedDense = new ArrayList<>();

        Location first = rawPath.getFirst();
        FootSupport firstSupp = resolveFootSupport(first.getX(), first.getY(), first.getZ());
        double currentFeetY = firstSupp.valid() ? firstSupp.feetY() : first.getY();

        groundedDense.add(new Location(world, first.getX(), currentFeetY, first.getZ()));

        double stepSize = Math.max(0.05, gridStep);

        for(int i = 0; i < rawPath.size() - 1; i++)
        {
            Location p1 = rawPath.get(i);
            Location p2 = rawPath.get(i + 1);

            double dx = p2.getX() - p1.getX();
            double dz = p2.getZ() - p1.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);

            int steps = Math.max(1, (int) Math.ceil(hDist / stepSize));
            double stepX = dx / steps;
            double stepZ = dz / steps;

            for(int s = 1; s <= steps; s++)
            {
                double cx = p1.getX() + (stepX * s);
                double cz = p1.getZ() + (stepZ * s);

                FootSupport supp = resolveFootSupport(cx, currentFeetY, cz);
                double targetFeetY = supp.valid() ? supp.feetY() : currentFeetY;

                if(Math.abs(targetFeetY - currentFeetY) > 0.01)
                {
                    if(targetFeetY < currentFeetY)
                    {
                        groundedDense.add(new Location(world, cx, currentFeetY, cz));
                        groundedDense.add(new Location(world, cx, targetFeetY, cz));
                    }
                    else
                    {
                        double edgeX = p1.getX() + (stepX * (s - 0.5));
                        double edgeZ = p1.getZ() + (stepZ * (s - 0.5));
                        groundedDense.add(new Location(world, edgeX, currentFeetY, edgeZ));
                        groundedDense.add(new Location(world, edgeX, targetFeetY, edgeZ));
                    }

                    currentFeetY = targetFeetY;
                }

                groundedDense.add(new Location(world, cx, currentFeetY, cz));
            }
        }

        return simplifyGroundedPath(groundedDense);
    }

    /**
     * Simplifies a dense grounded path by removing redundant intermediate points on the same ground plane
     * and combining straight-line horizontal moves that have clear line-of-sight and ground support.
     */
    private List<Location> simplifyGroundedPath(List<Location> densePath)
    {
        if(densePath.size() <= 2)
            return densePath;

        List<Location> simplified = new ArrayList<>();
        simplified.add(densePath.getFirst());

        int currentIdx = 0;

        while(currentIdx < densePath.size() - 1)
        {
            int furthestIdx = currentIdx + 1;

            for(int nextIdx = currentIdx + 2; nextIdx < densePath.size(); nextIdx++)
            {
                Location pStart = densePath.get(currentIdx);
                Location pCandidate = densePath.get(nextIdx);

                if(Math.abs(pCandidate.getY() - pStart.getY()) > 0.01)
                    break;

                if(canSweepWalk(pStart.getX(), pStart.getY(), pStart.getZ(),
                        pCandidate.getX(), pCandidate.getY(), pCandidate.getZ()))
                {
                    boolean validGroundContinuity = true;
                    for(int k = currentIdx + 1; k < nextIdx; k++)
                    {
                        Location pMid = densePath.get(k);
                        FootSupport supp = resolveFootSupport(pMid.getX(), pStart.getY(), pMid.getZ());
                        if(!supp.valid() || Math.abs(supp.feetY() - pStart.getY()) > 0.1)
                        {
                            validGroundContinuity = false;
                            break;
                        }
                    }

                    if(validGroundContinuity)
                        furthestIdx = nextIdx;
                    else
                        break;
                }
                else
                    break;
            }

            simplified.add(densePath.get(furthestIdx));
            currentIdx = furthestIdx;
        }

        return simplified;
    }

    /**
     * Calculates the Euclidean distance between two points.
     *
     * @param x1 the start x coordinate.
     * @param y1 the start y coordinate.
     * @param z1 the start z coordinate.
     * @param x2 the target x coordinate.
     * @param y2 the target y coordinate.
     * @param z2 the target z coordinate.
     * @return the distance
     */
    private static double distance(double x1, double y1, double z1, double x2, double y2, double z2)
    {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Record holding cached collision metadata, footing solidity, and hazard penalties for a block.
     */
    private record BlockData(Collection<BoundingBox> collisionBoxes, boolean isFootingSolid, double hazardPenalty) {}

    /**
     * Record representing ground support metadata beneath an entity's feet.
     */
    public record FootSupport(double feetY, boolean valid, double hazardPenalty)
    {
        /**
         * Constant representing an invalid ground support state.
         */
        public static final FootSupport INVALID = new FootSupport(0, false, 0);

        /**
         * Constructs a valid FootSupport instance with the given feet Y coordinate and hazard penalty.
         *
         * @param feetY         the vertical feet position
         * @param hazardPenalty the hazard penalty value
         */
        public FootSupport(double feetY, double hazardPenalty)
        {
            this(feetY, true, hazardPenalty);
        }
    }

    /**
     * Represents a continuous 3D coordinate node within the BoundingBox pathfinding grid.
     */
    private static class SubNode implements Comparable<SubNode>
    {
        final double x, y, z;
        final long id;
        double gCost = Double.MAX_VALUE;
        double hCost;
        boolean closed;
        SubNode parent;

        /**
         * Constructs a new SubNode.
         *
         * @param x  X coordinate
         * @param y  Y coordinate
         * @param z  Z coordinate
         */
        SubNode(double x, double y, double z, double step)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = hash(x, y, z, step);
        }

        /**
         * Generates a packed long hash for continuous 3D coordinates based on a grid step size.
         *
         * @param x        X coordinate
         * @param y        Y coordinate
         * @param z        Z coordinate
         * @param step the grid step size
         * @return the packed coordinate hash
         */
        static long hash(double x, double y, double z, double step)
        {
            int ix = (int) Math.floor(x / step);
            int iy = (int) Math.floor(y / step);
            int iz = (int) Math.floor(z / step);
            return packBlockCoord(ix, iy, iz);
        }

        /**
         * Calculates the heuristic cost (H-cost) to the destination location.
         *
         * @param target the target destination location
         */
        void calculateH(Location target)
        {
            double dx = Math.abs(x - target.getX());
            double dy = Math.abs(y - target.getY());
            double dz = Math.abs(z - target.getZ());
            this.hCost = dx + dy + dz;
        }

        /**
         * Calculates the squared distance to a target coordinates.
         *
         * @param targetX target X
         * @param targetY target Y
         * @param targetZ target Z
         * @return the distance
         */
        public double distanceSqTo(double targetX, double targetY, double targetZ)
        {
            double dx = x - targetX;
            double dy = y - targetY;
            double dz = z - targetZ;
            return dx * dx + dy * dy + dz * dz;
        }

        /**
         * Gets the total estimated F-cost (G-cost + H-cost).
         *
         * @return the F-cost
         */
        double fCost()
        {
            return gCost + hCost;
        }

        /**
         * Compares this node with another sub-node based on their F-costs.
         *
         * @param other the other sub-node
         * @return comparison result (-1, 0, or 1)
         */
        @Override
        public int compareTo(@NotNull SubNode other)
        {
            int cmp = Double.compare(this.fCost(), other.fCost());
            if(cmp == 0)
                return Double.compare(this.hCost, other.hCost);
            return cmp;
        }
    }
}