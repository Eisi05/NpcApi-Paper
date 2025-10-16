package de.eisi05.npc.api.pathfinding;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;


/**
 * Represents a path in the world, providing both {@link Location} and {@link Vector} representations
 * of the path's waypoints. The lists returned are unmodifiable to ensure immutability.
 */
public class Path implements ConfigurationSerializable
{
    private final List<Vector> vectors;
    private final List<Location> locations;
    private final List<Location> waypoints;

    private String name;

    /**
     * Constructs a Path from a list of Bukkit {@link Location} objects.
     * Converts each location to a {@link Vector} for easy mathematical manipulation.
     *
     * @param nodes the ordered list of {@link Location} waypoints
     */
    public Path(@NotNull List<Location> nodes, @Nullable List<Location> waypoints)
    {
        this.locations = Collections.unmodifiableList(nodes);
        this.vectors = nodes.stream().map(Location::toVector).toList();
        this.waypoints = waypoints == null ? null : Collections.unmodifiableList(waypoints);
    }

    /**
     * Constructs a Path from a list of {@link Vector} waypoints in the given world.
     * Converts each vector to a {@link Location} for compatibility with Bukkit APIs.
     *
     * @param nodes the ordered list of {@link Vector} waypoints
     * @param world the Bukkit {@link World} where the locations reside
     */
    public Path(@NotNull List<Vector> nodes, @NotNull World world, @Nullable List<Location> waypoints)
    {
        this.vectors = Collections.unmodifiableList(nodes);
        this.locations = nodes.stream().map(vector -> new Location(world, vector.getX(), vector.getY(), vector.getZ())).toList();
        this.waypoints = waypoints == null ? null : Collections.unmodifiableList(waypoints);
    }

    public @NotNull Path setName(@Nullable String name)
    {
        this.name = name;
        return this;
    }

    public @Nullable String getName()
    {
        return name;
    }

    @SuppressWarnings("unchecked")
    public static Path deserialize(Map<String, Object> map)
    {
        return new Path((List<Location>) map.get("locations"), (List<Location>) map.get("waypoints"));
    }

    /**
     * Returns the path as an unmodifiable list of {@link Location} objects.
     *
     * @return an unmodifiable list of Bukkit locations representing the path
     */
    public List<Location> asLocations()
    {
        return locations;
    }

    /**
     * Returns the path as an unmodifiable list of {@link Vector} objects.
     *
     * @return an unmodifiable list of vectors representing the path
     */
    public List<Vector> asVectors()
    {
        return vectors;
    }

    @Override
    public String toString()
    {
        if(locations.isEmpty())
            return "Empty path";

        Vector start = vectors.getFirst();
        Vector end = vectors.getLast();

        return String.format("Start: [%.2f, %.2f, %.2f] -> End: [%.2f, %.2f, %.2f]",
                start.getX(), start.getY(), start.getZ(),
                end.getX(), end.getY(), end.getZ());
    }

    /**
     * Returns th waypoints with which the path was calculated.
     *
     * @return an unmodifiable list of {@link Location} objects
     */
    public @NotNull List<Location> getWaypoints()
    {
        return waypoints == null ? new ArrayList<>() : waypoints;
    }

    @Override
    public boolean equals(Object obj)
    {
        if(this == obj)
            return true;

        if(!(obj instanceof Path other))
            return false;

        return locations.equals(other.locations);
    }

    @Override
    public int hashCode()
    {
        return locations.hashCode();
    }

    @Override
    public @NotNull Map<String, Object> serialize()
    {
        return Map.of("locations", new ArrayList<>(locations), "waypoints", new ArrayList<>(waypoints));
    }

    public SerializablePath toSerializablePath()
    {
        return new SerializablePath(this);
    }

    public static class SerializablePath implements Serializable
    {
        @Serial
        private static final long serialVersionUID = 1L;

        private final List<SerializableLocation> locations;
        private final List<SerializableLocation> waypoints;

        private final String name;

        private SerializablePath(@NotNull Path path)
        {
            locations = new ArrayList<>(path.locations.stream().map(SerializableLocation::new).toList());
            waypoints = path.waypoints == null ? null : new ArrayList<>(path.waypoints.stream().map(SerializableLocation::new).toList());
            name = path.getName();
        }

        public @NotNull Path toPath()
        {
            return new Path(locations.stream().map(SerializableLocation::toLocation).toList(),
                    waypoints == null ? null : waypoints.stream().map(SerializableLocation::toLocation).toList()).setName(name);
        }

        private static class SerializableLocation implements Serializable
        {
            @Serial
            private static final long serialVersionUID = 1L;

            private final double x;
            private final double y;
            private final double z;
            private final float pitch;
            private final float yaw;
            private final UUID world;

            public SerializableLocation(@NotNull Location location)
            {
                this.x = location.getX();
                this.y = location.getY();
                this.z = location.getZ();
                this.pitch = location.getPitch();
                this.yaw = location.getYaw();
                this.world = location.getWorld().getUID();
            }

            public @NotNull Location toLocation()
            {
                return new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch);
            }
        }
    }
}
