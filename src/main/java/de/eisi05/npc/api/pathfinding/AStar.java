package de.eisi05.npc.api.pathfinding;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Implementation of the A* pathfinding algorithm for Bukkit worlds.
 * Calculates paths between two locations with optional diagonal movement.
 */
public class AStar
{
    private final int sx;
    private final int sy;
    private final int sz;
    private final int ex;
    private final int ey;
    private final int ez;

    private final World w;
    private final int maxIterations;
    private final String endUID;
    private final HashMap<String, Tile> open = new HashMap<>();
    private final HashMap<String, Tile> closed = new HashMap<>();
    private final Location start;
    private final boolean allowDiagonalMovement;
    private PathingResult result;

    /**
     * Constructs an A* pathfinder between two locations.
     *
     * @param start                 the starting location
     * @param end                   the target location
     * @param maxIterations         maximum number of iterations before aborting
     * @param allowDiagonalMovement whether diagonal movement is allowed
     * @throws InvalidPathException if start or end location is not walkable
     */
    public AStar(Location start, Location end, int maxIterations, boolean allowDiagonalMovement) throws InvalidPathException
    {
        this.start = start;
        this.allowDiagonalMovement = allowDiagonalMovement;

        boolean s, e = true;
        if(!(s = isLocationWalkable(start)) || !(e = isLocationWalkable(end)))
            throw new InvalidPathException(s, e);
        this.w = start.getWorld();
        this.sx = start.getBlockX();
        this.sy = start.getBlockY();
        this.sz = start.getBlockZ();
        this.ex = end.getBlockX();
        this.ey = end.getBlockY();
        this.ez = end.getBlockZ();
        this.maxIterations = maxIterations;
        short sh = 0;
        Tile t = new Tile(sh, sh, sh, null);
        t.calculateBoth(this.sx, this.sy, this.sz, this.ex, this.ey, this.ez, true);
        this.open.put(t.getUID(), t);
        processAdjacentTiles(t);
        this.endUID = String.valueOf(this.ex - this.sx) + (this.ey - this.sy) + (this.ez - this.sz);
    }

    /**
     * Adds a tile to the open list if not already present.
     *
     * @param t the tile to add
     */
    private void addToOpenList(Tile t)
    {
        if(!this.open.containsKey(t.getUID()))
            this.open.put(t.getUID(), t);
    }

    /**
     * Adds a tile to the closed list if not already present.
     *
     * @param t the tile to add
     */
    private void addToClosedList(Tile t)
    {
        if(!this.closed.containsKey(t.getUID()))
            this.closed.put(t.getUID(), t);
    }

    /**
     * Returns the starting location of this pathfinding instance.
     *
     * @return the start location
     */
    public Location getStart()
    {
        return start;
    }

    /**
     * Returns the end location of this pathfinding instance.
     *
     * @return the end location
     */
    public Location getEndLocation()
    {
        return new Location(this.w, this.ex, this.ey, this.ez);
    }

    /**
     * Returns the result of the last pathfinding attempt.
     *
     * @return pathing result status
     */
    public PathingResult getPathingResult()
    {
        return this.result;
    }

    /**
     * Runs the A* iteration until a path is found or terminated.
     *
     * @return list of tiles representing the path, or null if no path exists
     */
    public ArrayList<Tile> iterate()
    {
        Tile current = null;
        int iterations = 0;
        while(canContinue())
        {
            iterations++;
            if(iterations > this.maxIterations)
            {
                this.result = PathingResult.ITERATIONS_EXCEEDED;
                break;
            }
            current = getLowestFTile();
            processAdjacentTiles(current);
        }

        if(this.result != PathingResult.SUCCESS)
            return null;
        LinkedList<Tile> routeTrace = new LinkedList<>();
        routeTrace.add(current);
        Tile parent;

        while((parent = current.getParent()) != null)
        {
            routeTrace.add(parent);
            current = parent;
        }

        Collections.reverse(routeTrace);
        return new ArrayList<>(routeTrace);
    }

    /**
     * Determines if the algorithm can continue.
     * Stops if the open list is empty or the target has been reached.
     *
     * @return true if the search can continue, false otherwise
     */
    private boolean canContinue()
    {
        if(this.open.isEmpty())
        {
            this.result = PathingResult.NO_PATH;
            return false;
        }
        if(this.closed.containsKey(this.endUID))
        {
            this.result = PathingResult.SUCCESS;
            return false;
        }
        return true;
    }

    /**
     * Finds and removes the tile with the lowest f-cost from the open list.
     *
     * @return tile with the lowest f-cost
     */
    private Tile getLowestFTile()
    {
        double f = 0.0D;
        Tile drop = null;
        for(Tile t : this.open.values())
        {
            if(f == 0.0D)
            {
                t.calculateBoth(this.sx, this.sy, this.sz, this.ex, this.ey, this.ez, true);
                f = t.getF();
                drop = t;
                continue;
            }
            t.calculateBoth(this.sx, this.sy, this.sz, this.ex, this.ey, this.ez, true);
            double posF = t.getF();
            if(posF < f)
            {
                f = posF;
                drop = t;
            }
        }
        this.open.remove(drop.getUID());
        addToClosedList(drop);
        return drop;
    }

    /**
     * Checks whether the given tile is already on the closed list.
     *
     * @param t the tile to check
     * @return true if the tile is on the closed list
     */
    private boolean isOnClosedList(Tile t)
    {
        return this.closed.containsKey(t.getUID());
    }

    /**
     * Processes the adjacent tiles around a given tile, adding valid ones to the open list.
     *
     * @param current the current tile to expand from
     */
    private void processAdjacentTiles(Tile current)
    {
        HashSet<Tile> possible = new HashSet<>(allowDiagonalMovement ? 26 : 14);

        if(allowDiagonalMovement)
            for(byte x = -1; x <= 1; x = (byte) (x + 1))
            {
                for(byte y = -1; y <= 1; y = (byte) (y + 1))
                {
                    for(byte z = -1; z <= 1; z = (byte) (z + 1))
                    {
                        if(x != 0 || y != 0 || z != 0)
                        {
                            Tile t = new Tile((short) (current.getX() + x), (short) (current.getY() + y), (short) (current.getZ() + z), current);
                            if(!isOnClosedList(t) && isTileWalkable(t))
                            {
                                t.calculateBoth(this.sx, this.sy, this.sz, this.ex, this.ey, this.ez, true);
                                possible.add(t);
                            }
                        }
                    }
                }
            }
        else
        {
            int[][] directions = {
                    {1, 0, 0}, {-1, 0, 0},
                    {0, 1, 0}, {0, -1, 0},
                    {0, 0, 1}, {0, 0, -1},
                    {0, -1, 1}, {0, -1, -1},
                    {0, 1, 1}, {0, 1, -1},
                    {1, -1, 0}, {-1, -1, 0},
                    {1, 1, 0}, {-1, 1, 0},
            };

            for(int[] d : directions)
            {
                Tile t = new Tile(
                        (short) (current.getX() + d[0]),
                        (short) (current.getY() + d[1]),
                        (short) (current.getZ() + d[2]),
                        current
                );
                if(!isOnClosedList(t) && isTileWalkable(t))
                {
                    t.calculateBoth(this.sx, this.sy, this.sz, this.ex, this.ey, this.ez, true);
                    possible.add(t);
                }
            }
        }

        for(Tile t : possible)
        {
            Tile openRef;
            if((openRef = isOnOpenList(t)) == null)
            {
                addToOpenList(t);
                continue;
            }
            if(t.getG() < openRef.getG())
            {
                openRef.setParent(current);
                openRef.calculateBoth(this.sx, this.sy, this.sz, this.ex, this.ey, this.ez, true);
            }
        }
    }

    /**
     * Returns a tile if it is already on the open list.
     *
     * @param t the tile to check
     * @return the reference from the open list, or null if not present
     */
    private Tile isOnOpenList(Tile t)
    {
        return this.open.getOrDefault(t.getUID(), null);
    }

    /**
     * Determines if the given tile is walkable (i.e., solid ground with space above).
     *
     * @param t the tile to check
     * @return true if walkable, false otherwise
     */
    private boolean isTileWalkable(Tile t)
    {
        Location l = new Location(this.w, (this.sx + t.getX()), (this.sy + t.getY()), (this.sz + t.getZ()));
        Block b = l.getBlock();
        return !b.isLiquid() && b.getType().isSolid() && (b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable());
    }

    /**
     * Checks if the given location is a valid starting or ending point.
     *
     * @param l the location to check
     * @return true if the location is walkable, false otherwise
     */
    private boolean isLocationWalkable(Location l)
    {
        Block b = l.getBlock();

        if(!b.isLiquid() && b.getType().isSolid())
            return ((b.getRelative(0, 1, 0).getType().isAir() || b.getRelative(0, 1, 0).isPassable()) &&
                    (b.getRelative(0, 2, 0).getType().isAir() || b.getRelative(0, 2, 0).isPassable()));
        return false;
    }

    /**
     * Exception thrown when path initialization fails due to invalid start or end.
     */
    public static class InvalidPathException extends Exception
    {
        private final boolean s;
        private final boolean e;

        /**
         * Creates an exception describing invalid start or end tiles.
         *
         * @param s whether the start is valid
         * @param e whether the end is valid
         */
        public InvalidPathException(boolean s, boolean e)
        {
            super(getErrorReason(s, e));
            this.s = s;
            this.e = e;
        }

        /**
         * Returns a textual explanation of why the path is invalid.
         *
         * @return error reason string
         */
        private static @NotNull String getErrorReason(boolean s, boolean e)
        {
            StringBuilder sb = new StringBuilder();
            if(!s)
                sb.append("Start Location was air.");
            if(!e)
                sb.append("End Location was air.");
            return sb.toString();
        }

        /**
         * Checks if the start location was invalid.
         *
         * @return true if the start location is not solid
         */
        public boolean isStartNotSolid()
        {
            return !this.s;
        }

        /**
         * Checks if the end location was invalid.
         *
         * @return true if the end location is not solid
         */
        public boolean isEndNotSolid()
        {
            return !this.e;
        }
    }
}
