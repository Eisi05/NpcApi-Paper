package de.eisi05.npc.api.pathfinding.environment;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.pathfinding.algorithm.AbstractPathfinder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Thread-safe cache for {@code NpcConfig#pathfindingPassableOverride()}.
 * <p>
 * The configured predicate takes a live {@link Block}, which may only be touched on the main thread. Asynchronous searches therefore never call it
 * themselves: the result is evaluated <b>once per block state, on the main thread</b> (batched together with the collision-shape resolution in
 * {@link ChunkSnapshotView}) and cached here. Searches then only perform a hash lookup.
 * <p>
 * Consequence: for asynchronous searches the predicate must depend only on the block's type/state, not on its position. The default configuration (a list of
 * material names) satisfies this.
 * <p>
 * The cache is dropped automatically whenever the configured predicate instance changes (e.g. after a config reload) and by
 * {@link AbstractPathfinder#clearBoxCache()}.
 */
public final class PassableOverrides
{
    private static final Map<BlockData, Boolean> CACHE = new ConcurrentHashMap<>();
    private static volatile @Nullable Predicate<Block> cachedFor;

    private PassableOverrides()
    {
    }

    /**
     * Returns the cached verdict for a block state, or null if it has not been evaluated yet. Safe on any thread.
     */
    static @Nullable Boolean cached(@NotNull BlockData data)
    {
        sync();
        return CACHE.get(data);
    }

    /**
     * Evaluates the predicate for the given block and caches the result, but only if the block still has the expected state. <b>Main thread only.</b>
     */
    static void resolve(@NotNull Block block, @NotNull BlockData expected)
    {
        sync();
        if(CACHE.containsKey(expected) || !block.getBlockData().equals(expected))
            return;

        boolean result;
        try
        {
            result = NpcApi.config.pathfindingPassableOverride().test(block);
        }
        catch(Throwable throwable)
        {
            result = false;
        }
        CACHE.put(expected, result);
    }

    /**
     * Drops all cached verdicts.
     */
    public static void clear()
    {
        CACHE.clear();
    }

    private static void sync()
    {
        Predicate<Block> current = NpcApi.config.pathfindingPassableOverride();
        if(current != cachedFor)
        {
            synchronized(PassableOverrides.class)
            {
                if(current != cachedFor)
                {
                    CACHE.clear();
                    cachedFor = current;
                }
            }
        }
    }
}
