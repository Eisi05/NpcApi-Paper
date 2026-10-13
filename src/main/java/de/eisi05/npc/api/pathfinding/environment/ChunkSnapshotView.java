package de.eisi05.npc.api.pathfinding.environment;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link BlockView} backed by {@link ChunkSnapshot}s (the same idea Citizens uses for its async pathfinder).
 * <p>
 * Snapshots are taken on the main thread; the actual search then runs on immutable data. This makes {@code NPC#findPathAsync} safe (no live-world access
 * from async threads) and faster (no {@code CraftBlock} allocations, no main-thread contention).
 * <ul>
 *     <li>{@link #create(World, List)} prefetches the chunks along the waypoint corridor in <b>one</b> main-thread hop.</li>
 *     <li>Chunks outside the corridor are fetched lazily, in batches of 3x3, so a search never pays more than a handful of tick-hops.</li>
 *     <li>When already on the main thread no scheduling happens at all.</li>
 * </ul>
 * Chunks that cannot be fetched (timeout, error) are treated as empty space, which makes them non-walkable.
 * <p>
 * <b>Paper and Folia:</b> every world access is done by the thread that owns the chunk ({@code Bukkit#isOwnedByCurrentRegion}): inline when the caller already
 * owns it (the main thread on Paper), otherwise through {@code SchedulerProvider.get().runSyncAtLocation}, which is the region scheduler on Folia. Each chunk
 * gets its own task because neighbouring chunks may belong to different Folia regions. Unloaded chunks are requested with {@code World#getChunkAtAsync}.
 * The snapshots themselves are immutable, so the search reads them from any thread without synchronisation. A view is still single-threaded (see
 * {@link BlockView}).
 */
public final class ChunkSnapshotView extends BlockView
{
    private static final long FETCH_TIMEOUT_MS = 5_000L;
    private static final int PREFETCH_RADIUS = 2;
    private static final int MAX_PREFETCH_CHUNKS = 192;
    private static final int MAX_BATCH = 64;
    private static final BlockProfile SOLID_FALLBACK = BlockProfile.create(List.of(new BoundingBox(0, 0, 0, 1, 1, 1)), false, false, false, false);

    private final Long2ObjectOpenHashMap<ChunkSnapshot> chunks = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet missing = new LongOpenHashSet();
    private final Set<BlockData> unresolvable = new HashSet<>();
    private final Set<BlockData> overrideUnresolvable = new HashSet<>();

    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private @Nullable ChunkSnapshot lastChunk;

    private ChunkSnapshotView(@NotNull World world)
    {
        super(world);
    }

    /**
     * Creates an empty view; chunks are fetched lazily on first access.
     *
     * @param world the world
     * @return the view
     */
    public static @NotNull ChunkSnapshotView create(@NotNull World world)
    {
        return new ChunkSnapshotView(world);
    }

    /**
     * Creates a view and prefetches the chunks around the straight corridor through all waypoints.
     *
     * @param world     the world
     * @param waypoints the ordered waypoints
     * @return the view
     */
    public static @NotNull ChunkSnapshotView create(@NotNull World world, @NotNull List<Location> waypoints)
    {
        ChunkSnapshotView view = new ChunkSnapshotView(world);
        view.prefetch(waypoints);
        return view;
    }

    private static long key(int chunkX, int chunkZ)
    {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private void prefetch(@NotNull List<Location> waypoints)
    {
        LongOpenHashSet wanted = new LongOpenHashSet();

        if(waypoints.size() == 1)
            addCorridor(wanted, waypoints.getFirst().getX(), waypoints.getFirst().getZ(), waypoints.getFirst().getX(), waypoints.getFirst().getZ());

        for(int i = 0; i < waypoints.size() - 1; i++)
        {
            Location a = waypoints.get(i);
            Location b = waypoints.get(i + 1);
            if(a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(getWorld()) || !b.getWorld().equals(getWorld()))
                continue;

            addCorridor(wanted, a.getX(), a.getZ(), b.getX(), b.getZ());
            if(wanted.size() >= MAX_PREFETCH_CHUNKS)
                break;
        }

        if(wanted.isEmpty())
            return;

        long[] keys = wanted.toLongArray();
        fetch(keys.length > MAX_PREFETCH_CHUNKS ? java.util.Arrays.copyOf(keys, MAX_PREFETCH_CHUNKS) : keys);
    }

    private void addCorridor(@NotNull LongOpenHashSet out, double ax, double az, double bx, double bz)
    {
        double distance = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(distance / 8.0));

        for(int s = 0; s <= steps; s++)
        {
            double t = (double) s / steps;
            int cx = (int) Math.floor(ax + (bx - ax) * t) >> 4;
            int cz = (int) Math.floor(az + (bz - az) * t) >> 4;

            for(int dx = -PREFETCH_RADIUS; dx <= PREFETCH_RADIUS; dx++)
            {
                for(int dz = -PREFETCH_RADIUS; dz <= PREFETCH_RADIUS; dz++)
                    out.add(key(cx + dx, cz + dz));
            }
        }
    }

    private void fetchAround(int chunkX, int chunkZ)
    {
        LongArrayList wanted = new LongArrayList(9);
        for(int dx = -1; dx <= 1; dx++)
        {
            for(int dz = -1; dz <= 1; dz++)
            {
                long k = key(chunkX + dx, chunkZ + dz);
                if(!chunks.containsKey(k) && !missing.contains(k))
                    wanted.add(k);
            }
        }

        if(!wanted.isEmpty())
            fetch(wanted.toLongArray());
    }

    /**
     * Snapshots the given chunks (each on the thread that owns it) and stores them in this view. All chunks are requested first and then awaited together, so
     * the total wait is bounded by {@link #FETCH_TIMEOUT_MS}, not multiplied by the number of chunks.
     */
    private void fetch(long @NotNull [] keys)
    {
        World world = getWorld();

        List<CompletableFuture<ChunkSnapshot>> futures = new ArrayList<>(keys.length);
        for(long k : keys)
            futures.add(snapshotAsync(world, (int) (k >> 32), (int) k));

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FETCH_TIMEOUT_MS);
        boolean interrupted = false;

        for(int i = 0; i < keys.length; i++)
        {
            ChunkSnapshot snapshot = null;
            if(!interrupted)
            {
                try
                {
                    long remaining = Math.max(0L, deadline - System.nanoTime());
                    snapshot = futures.get(i).get(remaining, TimeUnit.NANOSECONDS);
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                }
                catch(ExecutionException | TimeoutException ignored)
                {
                    // chunk stays missing
                }
            }

            if(snapshot != null)
                chunks.put(keys[i], snapshot);
            else
                missing.add(keys[i]);
        }
    }

    private static @NotNull Location centerOf(@NotNull World world, int chunkX, int chunkZ)
    {
        return new Location(world, (chunkX << 4) + 8, world.getMinHeight(), (chunkZ << 4) + 8);
    }

    /**
     * Runs a task on the thread that owns the chunk: inline if the caller owns it (main thread on Paper), otherwise scheduled at the chunk's location. The
     * returned future always completes, also when scheduling fails (for example because the plugin is being disabled).
     */
    private static @NotNull CompletableFuture<Void> runOnChunk(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable task)
    {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable guarded = () ->
        {
            try
            {
                task.run();
            }
            catch(Throwable ignored)
            {
                // the caller treats missing results as "unknown"
            }
            finally
            {
                done.complete(null);
            }
        };

        if(Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ))
        {
            guarded.run();
            return done;
        }

        try
        {
            SchedulerProvider.get().runSyncAtLocation(centerOf(world, chunkX, chunkZ), guarded);
        }
        catch(Throwable throwable)
        {
            done.complete(null);
        }
        return done;
    }

    private @NotNull CompletableFuture<ChunkSnapshot> snapshotAsync(@NotNull World world, int chunkX, int chunkZ)
    {
        CompletableFuture<ChunkSnapshot> result = new CompletableFuture<>();

        if(world.isChunkLoaded(chunkX, chunkZ))
        {
            takeSnapshot(world, chunkX, chunkZ, result);
            return result;
        }

        if(!NpcApi.config.loadChunksOnPath())
        {
            result.complete(null);
            return result;
        }

        if(!SchedulerProvider.isFolia() && Bukkit.isPrimaryThread())
        {
            try
            {
                result.complete(world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false));
            }
            catch(Throwable throwable)
            {
                result.complete(null);
            }
            return result;
        }

        try
        {
            world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) ->
            {
                if(error != null || chunk == null)
                    result.complete(null);
                else
                    takeSnapshot(world, chunkX, chunkZ, result);
            });
        }
        catch(Throwable throwable)
        {
            result.complete(null);
        }
        return result;
    }

    private void takeSnapshot(@NotNull World world, int chunkX, int chunkZ, @NotNull CompletableFuture<ChunkSnapshot> result)
    {
        CompletableFuture<Void> done = runOnChunk(world, chunkX, chunkZ, () ->
        {
            if(world.isChunkLoaded(chunkX, chunkZ))
                result.complete(world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false));
        });
        done.thenRun(() -> result.complete(null));
    }

    private @Nullable ChunkSnapshot chunkAt(int chunkX, int chunkZ)
    {
        if(chunkX == lastChunkX && chunkZ == lastChunkZ)
            return lastChunk;

        long k = key(chunkX, chunkZ);
        ChunkSnapshot snapshot = chunks.get(k);
        if(snapshot == null && !missing.contains(k))
        {
            fetchAround(chunkX, chunkZ);
            snapshot = chunks.get(k);
        }

        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
        lastChunk = snapshot;
        return snapshot;
    }

    @Override
    public @NotNull BlockProfile profileAt(int x, int y, int z)
    {
        if(y < getMinY() || y >= getMaxY())
            return BlockProfile.EMPTY;

        ChunkSnapshot snapshot = chunkAt(x >> 4, z >> 4);
        if(snapshot == null)
            return BlockProfile.EMPTY;

        int lx = x & 15;
        int lz = z & 15;

        Material type = snapshot.getBlockType(lx, y, lz);
        if(type.isAir())
            return BlockProfile.EMPTY;

        BlockData data = snapshot.getBlockData(lx, y, lz);
        BlockProfile profile = BlockProfile.cached(data);
        return profile != null ? profile : resolveUnknown(data, x, y, z);
    }

    @Override
    protected boolean testPassableOverride(int x, int y, int z)
    {
        if(y < getMinY() || y >= getMaxY())
            return false;

        ChunkSnapshot snapshot = chunkAt(x >> 4, z >> 4);
        if(snapshot == null)
            return false;

        BlockData data = snapshot.getBlockData(x & 15, y, z & 15);
        Boolean verdict = PassableOverrides.cached(data);
        if(verdict == null && !overrideUnresolvable.contains(data))
        {
            resolveUnknown(data, x, y, z);
            verdict = PassableOverrides.cached(data);
            if(verdict == null)
                overrideUnresolvable.add(data);
        }
        return verdict != null && verdict;
    }

    /**
     * Collision shapes can only be read from real blocks on the main thread. When the search meets a block state it has never seen, this collects <b>all</b>
     * still-unknown states in the surrounding cube (from the already available snapshots) and resolves them in one main-thread hop, so a cold cache costs a
     * handful of hops instead of one per state (one task per chunk on Folia). States that cannot be resolved (block changed, timeout) fall back to a conservative shape for this view.
     */
    private @NotNull BlockProfile resolveUnknown(@NotNull BlockData data, int x, int y, int z)
    {
        if(unresolvable.contains(data))
            return fallback(data);

        List<Pending> pending = new ArrayList<>();
        Set<BlockData> seen = new HashSet<>();
        pending.add(new Pending(data, x, y, z));
        seen.add(data);

        scan:
        for(int dx = -3; dx <= 3; dx++)
        {
            for(int dz = -3; dz <= 3; dz++)
            {
                int wx = x + dx;
                int wz = z + dz;
                ChunkSnapshot snapshot = chunks.get(key(wx >> 4, wz >> 4));
                if(snapshot == null)
                    continue;

                for(int dy = -3; dy <= 3; dy++)
                {
                    int wy = y + dy;
                    if(wy < getMinY() || wy >= getMaxY())
                        continue;

                    if(snapshot.getBlockType(wx & 15, wy, wz & 15).isAir())
                        continue;

                    BlockData other = snapshot.getBlockData(wx & 15, wy, wz & 15);
                    if((BlockProfile.cached(other) != null && PassableOverrides.cached(other) != null) || !seen.add(other))
                        continue;

                    pending.add(new Pending(other, wx, wy, wz));
                    if(pending.size() >= MAX_BATCH)
                        break scan;
                }
            }
        }

        World world = getWorld();
        Long2ObjectOpenHashMap<List<Pending>> byChunk = new Long2ObjectOpenHashMap<>();
        for(Pending p : pending)
            byChunk.computeIfAbsent(key(p.x >> 4, p.z >> 4), k -> new ArrayList<>()).add(p);

        List<CompletableFuture<Void>> running = new ArrayList<>(byChunk.size());
        for(long chunkKey : byChunk.keySet())
        {
            int cx = (int) (chunkKey >> 32);
            int cz = (int) chunkKey;
            List<Pending> group = byChunk.get(chunkKey);

            running.add(runOnChunk(world, cx, cz, () ->
            {
                if(!world.isChunkLoaded(cx, cz))
                    return;

                for(Pending p : group)
                {
                    try
                    {
                        Block block = world.getBlockAt(p.x, p.y, p.z);
                        BlockProfile.resolve(block, p.data);
                        PassableOverrides.resolve(block, p.data);
                    }
                    catch(Throwable ignored)
                    {
                        // stays unresolved
                    }
                }
            }));
        }

        try
        {
            CompletableFuture.allOf(running.toArray(new CompletableFuture[0])).get(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch(ExecutionException | TimeoutException ignored)
        {
            // timeout or failure: handled below
        }

        BlockProfile profile = BlockProfile.cached(data);
        if(profile != null)
            return profile;

        unresolvable.add(data);
        return fallback(data);
    }

    private static @NotNull BlockProfile fallback(@NotNull BlockData data)
    {
        return data.getMaterial().isSolid() ? SOLID_FALLBACK : BlockProfile.EMPTY;
    }

    private record Pending(BlockData data, int x, int y, int z)
    {
    }
}
