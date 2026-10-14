package de.eisi05.npc.api.pathfinding;

import de.eisi05.npc.api.pathfinding.movement.MoveType;
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
    private final List<MoveType> moves;

    private volatile String name;

    /**
     * Constructs a Path from a list of Bukkit {@link Location} objects.
     * Converts each location to a {@link Vector} for easy mathematical manipulation.
     *
     * @param nodes the ordered list of {@link Location} waypoints
     */
    public Path(@NotNull List<Location> nodes, @Nullable List<Location> waypoints)
    {
        this(nodes, waypoints, null);
    }

    /**
     * Constructs a Path from {@link Location} nodes together with the way each node is reached.
     *
     * @param nodes     the ordered list of {@link Location} points
     * @param waypoints the waypoints the path was calculated with, may be null
     * @param moves     one {@link MoveType} per node (how the node is reached from the previous one), or null if unknown (all {@link MoveType#WALK})
     */
    public Path(@NotNull List<Location> nodes, @Nullable List<Location> waypoints, @Nullable List<MoveType> moves)
    {
        if(moves != null && moves.size() != nodes.size())
            throw new IllegalArgumentException("moves must have the same size as nodes");

        // Paths are handed between threads (async search -> region thread), so they own private, immutable copies of everything
        this.locations = copyLocations(nodes);
        this.vectors = locations.stream().map(Location::toVector).toList();
        this.waypoints = waypoints == null ? null : copyLocations(waypoints);
        this.moves = moves == null ? null : List.copyOf(moves);
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
        this.vectors = nodes.stream().map(Vector::clone).toList();
        this.locations = nodes.stream().map(vector -> new Location(world, vector.getX(), vector.getY(), vector.getZ())).toList();
        this.waypoints = waypoints == null ? null : copyLocations(waypoints);
        this.moves = null;
    }

    private static @NotNull List<Location> copyLocations(@NotNull List<Location> source)
    {
        List<Location> copy = new ArrayList<>(source.size());
        for(Location location : source)
            copy.add(location.clone());
        return Collections.unmodifiableList(copy);
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
        List<Location> locations = (List<Location>) map.get("locations");
        List<MoveType> moves = null;

        if(map.get("moves") instanceof List<?> raw && raw.size() == locations.size())
            moves = raw.stream().map(entry -> parseMove(String.valueOf(entry))).toList();

        return new Path(locations, (List<Location>) map.get("waypoints"), moves);
    }

    private static @NotNull MoveType parseMove(@Nullable String name)
    {
        try
        {
            return name == null ? MoveType.WALK : MoveType.valueOf(name);
        }
        catch(IllegalArgumentException e)
        {
            return MoveType.WALK;
        }
    }

    /**
     * Returns how each point of this path is reached from the previous one (walking, jumping, climbing, swimming, ...). Paths without this information
     * (e.g. paths saved by older versions) report {@link MoveType#WALK} for every point.
     *
     * @return an unmodifiable list with one entry per point
     */
    public @NotNull List<MoveType> getMoves()
    {
        return moves != null ? moves : Collections.nCopies(locations.size(), MoveType.WALK);
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
     * Returns a new path that is the reverse of this path.
     *
     * @return a new path that is the reverse of this path
     */
    public @NotNull Path reverse()
    {
        List<MoveType> reversedMoves = null;
        if(moves != null)
        {
            int n = moves.size();
            reversedMoves = new ArrayList<>(n);
            for(int j = 0; j < n; j++)
                reversedMoves.add(j == 0 ? MoveType.WALK : moves.get(n - j).reversed());
        }

        return new Path(locations.reversed(), waypoints == null ? null : waypoints.reversed(), reversedMoves)
                .setName(name);
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
        return waypoints == null ? List.of() : waypoints;
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
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("locations", new ArrayList<>(locations));
        map.put("waypoints", waypoints == null ? new ArrayList<>() : new ArrayList<>(waypoints));
        if(moves != null)
            map.put("moves", moves.stream().map(Enum::name).toList());
        return map;
    }

    /**
     * Returns a serializable version of this path.
     *
     * @return a serializable version of this path
     */
    public SerializablePath toSerializablePath()
    {
        return new SerializablePath(this);
    }

    /**
     * A serializable version of a path.
     */
    public static class SerializablePath implements Serializable
    {
        @Serial
        private static final long serialVersionUID = 1L;

        private final List<SerializableLocation> locations;
        private final List<SerializableLocation> waypoints;
        private final List<String> moves;

        private final String name;

        /**
         * Constructs a new serializable path.
         *
         * @param path the path to serialize
         */
        private SerializablePath(@NotNull Path path)
        {
            locations = new ArrayList<>(path.locations.stream().map(SerializableLocation::new).toList());
            waypoints = path.waypoints == null ? null : new ArrayList<>(path.waypoints.stream().map(SerializableLocation::new).toList());
            moves = path.moves == null ? null : new ArrayList<>(path.moves.stream().map(Enum::name).toList());
            name = path.getName();
        }

        /**
         * Returns the path represented by this serializable path.
         *
         * @param fallbackWorld the world to use if the path's world is null
         * @return the path
         */
        public @NotNull Path toPath(World fallbackWorld)
        {
            List<Location> nodes = new ArrayList<>();
            List<MoveType> nodeMoves = moves != null && moves.size() == locations.size() ? new ArrayList<>() : null;

            for(int i = 0; i < locations.size(); i++)
            {
                Location location = locations.get(i).toLocation(fallbackWorld);
                if(location.getWorld() == null)
                    continue;

                nodes.add(location);
                if(nodeMoves != null)
                    nodeMoves.add(parseMove(moves.get(i)));
            }

            List<Location> wp = waypoints == null ? null : waypoints.stream().map(serializableLocation -> serializableLocation.toLocation(fallbackWorld))
                    .filter(location -> location.getWorld() != null).toList();

            return new Path(nodes, wp, nodeMoves).setName(name);
        }

        /**
         * Returns the name of the path.
         *
         * @return the name of the path
         */
        public String getName()
        {
            return name;
        }

        /**
         * A serializable version of a location.
         */
        public static class SerializableLocation implements Serializable
        {
            @Serial
            private static final long serialVersionUID = 1L;

            private final double x;
            private final double y;
            private final double z;
            private final float pitch;
            private final float yaw;
            private final UUID world;

            /**
             * Constructs a new serializable location.
             *
             * @param location the location to serialize
             */
            public SerializableLocation(@NotNull Location location)
            {
                this.x = location.getX();
                this.y = location.getY();
                this.z = location.getZ();
                this.pitch = location.getPitch();
                this.yaw = location.getYaw();
                this.world = location.getWorld().getUID();
            }

            /**
             * Returns the location represented by this serializable location.
             *
             * @return the location
             */
            public @NotNull Location toLocation(World fallbackWorld)
            {
                World w = Bukkit.getWorld(world);
                return new Location(w == null ? fallbackWorld : w, x, y, z, yaw, pitch);
            }
        }
    }
}
