package de.eisi05.npc.api.movement;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * Represents a single movement data point containing position, rotation, and timing information.
 * This class is used to store individual frames of player movement for later replay.
 */
public class MovementData implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    private final long timestamp;
    private final double x, y, z;
    private final float yaw, pitch;
    private final UUID worldUUID;

    /**
     * Creates a new MovementData instance from a location and timestamp.
     *
     * @param location The location to record
     * @param timestamp The timestamp in milliseconds when this movement occurred
     */
    public MovementData(@NotNull Location location, long timestamp)
    {
        this.timestamp = timestamp;
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
        this.worldUUID = location.getWorld().getUID();
    }

    /**
     * Gets the timestamp when this movement occurred.
     *
     * @return The timestamp in milliseconds
     */
    public long getTimestamp()
    {
        return timestamp;
    }

    /**
     * Gets the X coordinate.
     *
     * @return The X coordinate
     */
    public double getX()
    {
        return x;
    }

    /**
     * Gets the Y coordinate.
     *
     * @return The Y coordinate
     */
    public double getY()
    {
        return y;
    }

    /**
     * Gets the Z coordinate.
     *
     * @return The Z coordinate
     */
    public double getZ()
    {
        return z;
    }

    /**
     * Gets the yaw rotation.
     *
     * @return The yaw in degrees
     */
    public float getYaw()
    {
        return yaw;
    }

    /**
     * Gets the pitch rotation.
     *
     * @return The pitch in degrees
     */
    public float getPitch()
    {
        return pitch;
    }

    /**
     * Gets the world uuid where this movement occurred.
     *
     * @return The world uuid
     */
    public @NotNull UUID getWorldUUID()
    {
        return worldUUID;
    }

    /**
     * Creates a Location object from this movement data.
     *
     * @param world The world to create the location in
     * @return A Location object representing this movement data
     */
    public @NotNull Location toLocation(@NotNull World world)
    {
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Calculates the time difference between this movement and another.
     *
     * @param other The other movement data
     * @return The time difference in milliseconds
     */
    public long getTimeDifference(@NotNull MovementData other)
    {
        return Math.abs(this.timestamp - other.timestamp);
    }

    @Override
    public @NotNull String toString()
    {
        return String.format("MovementData{world='%s', x=%.2f, y=%.2f, z=%.2f, yaw=%.1f, pitch=%.1f, time=%d}", worldUUID, x, y, z, yaw, pitch, timestamp);
    }
}
