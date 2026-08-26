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
        this.gridStep = Math.max(0.1, gridStep);
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

                    Collection<BoundingBox> boxes = block.getCollisionShape().getBoundingBoxes();
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
        Collection<BoundingBox> boxes = bodyPassable ? Collections.emptyList() : block.getCollisionShape().getBoundingBoxes();

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

        double safeStep = Math.max(0.1, entityWidth / 2.0);

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

        double sweepY = Math.max(y1, y2);
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

                if(!isBoxValidAtCached(cx, sweepY, cz))
                    return false;

                if(checkGround)
                {
                    FootSupport support = resolveFootSupport(cx, sweepY, cz);
                    if(!support.valid())
                        return false;

                    if(i > 0)
                    {
                        double stepYDiff = support.feetY() - lastFeetY;
                        if(stepYDiff > maxJumpHeight || stepYDiff < -maxFallDistance)
                            return false;
                    }
                    lastFeetY = support.feetY();
                }
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
     * @param progressListener an optional consumer tracking calculation progress (0.0 to 1.0)
     * @return a list of locations representing the smoothed path, or null if unreachable
     * @throws PathfindingUtils.PathfindingException if start or end locations lack valid floor support
     */
    @Override
    public @Nullable List<Location> getPath(@NotNull Location start, @NotNull Location end, @Nullable Consumer<Double> progressListener)
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
            if(endYDiff <= maxJumpHeight && endYDiff >= -maxFallDistance && canSweepWalk(current.x, current.y, current.z, end.getX(), endFeetY, end.getZ()))
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

    /**
     * Evaluates a candidate point during path exploration, updating costs and parent nodes if optimal.
     *
     * @param nextX   the target candidate X coordinate
     * @param nextZ   the target candidate Z coordinate
     * @param current the current sub-node
     * @param end     the target end location
     */
    private void evaluateCandidatePoint(double nextX, double nextZ, SubNode current, Location end)
    {
        FootSupport footSupport = resolveFootSupport(nextX, current.y, nextZ);
        if(!footSupport.valid())
            return;

        double nextFeetY = footSupport.feetY();
        double yDiff = nextFeetY - current.y;

        if(yDiff > maxJumpHeight || yDiff < -maxFallDistance)
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
            if(Math.abs(pYDiff) <= 0.1)
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

    /**
     * Creates or retrieves a sub-node for the given coordinates from the node registry.
     *
     * @param x          X coordinate
     * @param y          Y coordinate
     * @param z          Z coordinate
     * @param explicitId explicit node identifier, or null to auto-generate
     * @return the created or cached sub-node
     */
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

    /**
     * Retraces the path backwards from the given sub-node to construct a raw location list.
     *
     * @param current the target sub-node
     * @return a list of locations representing the raw path
     */
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
     * Simplifies and smooths a raw path using line-of-sight sweep checks.
     *
     * @param path      the raw path locations
     * @param targetEnd the final target destination
     * @return a smoothed list of path locations
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
                if(Math.abs(target.getY() - current.getY()) > 0.1)
                    break;

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

        if(Math.abs(targetEnd.getY() - lastPoint.getY()) <= 0.1)
        {
            if(canSweepWalk(lastPoint.getX(), lastPoint.getY(), lastPoint.getZ(), targetEnd.getX(), targetEnd.getY(), targetEnd.getZ()))
                smoothPath.add(targetEnd.clone());
        }
        else
            smoothPath.add(targetEnd.clone());

        return smoothPath;
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
        double hCost = 0;
        SubNode parent = null;
        boolean closed = false;

        /**
         * Constructs a new SubNode.
         *
         * @param x  X coordinate
         * @param y  Y coordinate
         * @param z  Z coordinate
         * @param id unique long identifier
         */
        public SubNode(double x, double y, double z, long id)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
        }

        /**
         * Generates a packed long hash for continuous 3D coordinates based on a grid step size.
         *
         * @param x        X coordinate
         * @param y        Y coordinate
         * @param z        Z coordinate
         * @param gridStep the grid step size
         * @return the packed coordinate hash
         */
        public static long hash(double x, double y, double z, double gridStep)
        {
            long gx = Math.round(x / gridStep);
            long gy = Math.round(y / gridStep);
            long gz = Math.round(z / gridStep);
            return (gx & 0x1FFFFFFL) | ((gz & 0x1FFFFFFL) << 25) | ((gy & 0x3FFFL) << 50);
        }

        /**
         * Calculates the heuristic cost (H-cost) to the destination location.
         *
         * @param end the target destination location
         */
        public void calculateH(@NotNull Location end)
        {
            double dx = x - end.getX();
            double dy = y - end.getY();
            double dz = z - end.getZ();
            this.hCost = Math.sqrt(dx * dx + dy * dy + dz * dz) * 1.001;
        }

        /**
         * Calculates the Euclidean distance to specific target coordinates.
         *
         * @param targetX target X
         * @param targetY target Y
         * @param targetZ target Z
         * @return the distance
         */
        public double distanceTo(double targetX, double targetY, double targetZ)
        {
            double dx = x - targetX;
            double dy = y - targetY;
            double dz = z - targetZ;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /**
         * Calculates the squared distance to a target location.
         *
         * @param l the target location
         * @return the squared distance
         */
        public double distanceSqTo(@NotNull Location l)
        {
            double dx = x - l.getX();
            double dy = y - l.getY();
            double dz = z - l.getZ();
            return dx * dx + dy * dy + dz * dz;
        }

        /**
         * Gets the total estimated F-cost (G-cost + H-cost).
         *
         * @return the F-cost
         */
        public double getFCost()
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
            return Double.compare(this.getFCost(), other.getFCost());
        }
    }
}