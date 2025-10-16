package de.eisi05.npc.api.pathfinding;

import org.bukkit.Location;

/**
 * Represents a tile (node) used in the A* pathfinding algorithm.
 * Each tile stores its relative coordinates, costs, and parent reference.
 */
public class Tile
{
    private final short x;
    private final short y;
    private final short z;

    private final String uid;
    private double g = -1.0D;
    private double h = -1.0D;
    private Tile parent;

    /**
     * Creates a new tile with given relative coordinates and parent.
     *
     * @param x      relative x-coordinate
     * @param y      relative y-coordinate
     * @param z      relative z-coordinate
     * @param parent the parent tile leading to this tile
     */
    public Tile(short x, short y, short z, Tile parent)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.parent = parent;
        this.uid = String.valueOf(x) + y + z;
    }

    /**
     * Converts this tile to an absolute {@link Location} using a start reference.
     *
     * @param start the starting location
     * @return absolute location of this tile
     */
    public Location getLocation(Location start)
    {
        return new Location(start.getWorld(), (start.getBlockX() + this.x), (start.getBlockY() + this.y), (start.getBlockZ() + this.z));
    }

    /**
     * Returns the parent tile of this tile.
     *
     * @return the parent tile, or null if none
     */
    public Tile getParent()
    {
        return this.parent;
    }

    /**
     * Updates the parent tile reference.
     *
     * @param parent the new parent tile
     */
    public void setParent(Tile parent)
    {
        this.parent = parent;
    }

    /**
     * Returns the relative x-coordinate.
     *
     * @return relative x
     */
    public short getX()
    {
        return this.x;
    }

    /**
     * Returns the absolute x-coordinate relative to a base location.
     *
     * @param i base location
     * @return absolute x
     */
    public int getX(Location i)
    {
        return i.getBlockX() + this.x;
    }

    /**
     * Returns the relative y-coordinate.
     *
     * @return relative y
     */
    public short getY()
    {
        return this.y;
    }

    /**
     * Returns the absolute y-coordinate relative to a base location.
     *
     * @param i base location
     * @return absolute y
     */
    public int getY(Location i)
    {
        return i.getBlockY() + this.y;
    }

    /**
     * Returns the relative z-coordinate.
     *
     * @return relative z
     */
    public short getZ()
    {
        return this.z;
    }

    /**
     * Returns the absolute z-coordinate relative to a base location.
     *
     * @param i base location
     * @return absolute z
     */
    public int getZ(Location i)
    {
        return i.getBlockZ() + this.z;
    }

    /**
     * Returns the unique identifier string for this tile.
     *
     * @return tile UID
     */
    public String getUID()
    {
        return this.uid;
    }

    /**
     * Checks whether this tile is equal to another based on coordinates.
     *
     * @param t the other tile
     * @return true if coordinates match
     */
    public boolean equals(Tile t)
    {
        return (t.getX() == this.x && t.getY() == this.y && t.getZ() == this.z);
    }

    /**
     * Calculates both G and H values for this tile.
     *
     * @param sx     start x
     * @param sy     start y
     * @param sz     start z
     * @param ex     end x
     * @param ey     end y
     * @param ez     end z
     * @param update whether to force recalculation
     */
    public void calculateBoth(int sx, int sy, int sz, int ex, int ey, int ez, boolean update)
    {
        calculateG(sx, sy, sz, update);
        calculateH(sx, sy, sz, ex, ey, ez, update);
    }

    /**
     * Calculates the heuristic (H) cost using Euclidean distance.
     *
     * @param sx     start x
     * @param sy     start y
     * @param sz     start z
     * @param ex     end x
     * @param ey     end y
     * @param ez     end z
     * @param update whether to force recalculation
     */
    public void calculateH(int sx, int sy, int sz, int ex, int ey, int ez, boolean update)
    {
        if(update || this.h == -1.0D)
        {
            int hx = sx + this.x, hy = sy + this.y, hz = sz + this.z;
            this.h = getEuclideanDistance(hx, hy, hz, ex, ey, ez);
        }
    }

    /**
     * Calculates the movement cost (G) from the start to this tile.
     *
     * @param sx     start x
     * @param sy     start y
     * @param sz     start z
     * @param update whether to force recalculation
     */
    public void calculateG(int sx, int sy, int sz, boolean update)
    {
        if(update || this.g == -1.0D)
        {
            Tile currentParent, currentTile = this;
            int gCost = 0;
            while((currentParent = currentTile.getParent()) != null)
            {
                int dx = currentTile.getX() - currentParent.getX(),
                        dy = currentTile.getY() - currentParent.getY(),
                        dz = currentTile.getZ() - currentParent.getZ();

                dx = abs(dx);
                dy = abs(dy);
                dz = abs(dz);

                if(dx == 1 && dy == 1 && dz == 1)
                    gCost = (int) (gCost + 1.7D);

                else if(((dx == 1 || dz == 1) && dy == 1) || ((dx == 1 || dz == 1) && dy == 0))
                    gCost = (int) (gCost + 1.4D);
                else
                    gCost = (int) (gCost + 1.0D);

                currentTile = currentParent;
            }
            this.g = gCost;
        }
    }

    /**
     * Returns the G cost (movement cost from start).
     *
     * @return g cost
     */
    public double getG()
    {
        return this.g;
    }

    /**
     * Returns the H cost (heuristic estimate to end).
     *
     * @return h cost
     */
    public double getH()
    {
        return this.h;
    }

    /**
     * Returns the total F cost (G + H).
     *
     * @return f cost
     */
    public double getF()
    {
        return this.h + this.g;
    }

    /**
     * Computes Euclidean distance between two points.
     *
     * @param sx start x
     * @param sy start y
     * @param sz start z
     * @param ex end x
     * @param ey end y
     * @param ez end z
     * @return Euclidean distance
     */
    private double getEuclideanDistance(int sx, int sy, int sz, int ex, int ey, int ez)
    {
        double dx = (sx - ex), dy = (sy - ey), dz = (sz - ez);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Returns the absolute value of the given integer.
     *
     * @param i input value
     * @return absolute value
     */
    private int abs(int i)
    {
        return (i < 0) ? -i : i;
    }
}
