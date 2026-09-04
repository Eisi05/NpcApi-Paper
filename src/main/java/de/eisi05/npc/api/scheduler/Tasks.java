package de.eisi05.npc.api.scheduler;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.objects.NpcSkin;
import de.eisi05.npc.api.objects.Skin;
import de.eisi05.npc.api.utils.Reflections;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The {@link Tasks} class manages and starts various recurring tasks related to Non-Player Characters (NPCs) within the Bukkit environment. These tasks often
 * involve NPC behavior such as looking at nearby players and updating skins via placeholders.
 */
public class Tasks
{
    public static final Map<UUID, Map<UUID, String>> placeholderCache = new ConcurrentHashMap<>();
    private static final List<CompletableFuture<?>> activeFutures = new ArrayList<>();

    // --- Skin Queue & Rate Limiting Constants ---
    private static final Map<String, Long> negativeCache = new ConcurrentHashMap<>();
    private static final long NEGATIVE_CACHE_DURATION = 300_000L; // 5 minutes
    private static final PriorityBlockingQueue<SkinRequest> fetchQueue = new PriorityBlockingQueue<>();
    private static final Map<String, SkinRequest> pendingRequests = new ConcurrentHashMap<>();
    private static final int MAX_TOKENS = 180;
    private static final AtomicInteger tokens = new AtomicInteger(MAX_TOKENS);

    private static BukkitTask lookAtTask;
    private static BukkitTask placeholderTask;
    private static BukkitTask queueProcessorTask;

    /**
     * Starts all defined NPC-related tasks, including the skin fetch queue processor. This method should be called when the plugin is enabled to ensure that
     * NPC behaviors are active.
     */
    public static void start()
    {
        lookAtTask();
        placeholderTask();
        startQueueProcessor();
    }

    /**
     * Stops all defined NPC-related tasks and cancels active skin requests.
     */
    public static void stop()
    {
        if(lookAtTask != null && !lookAtTask.isCancelled())
            lookAtTask.cancel();

        if(placeholderTask != null && !placeholderTask.isCancelled())
            placeholderTask.cancel();

        if(queueProcessorTask != null && !queueProcessorTask.isCancelled())
            queueProcessorTask.cancel();

        lookAtTask = null;
        placeholderTask = null;
        queueProcessorTask = null;

        List<CompletableFuture<?>> futuresToCancel;
        synchronized(activeFutures)
        {
            futuresToCancel = new ArrayList<>(activeFutures);
            activeFutures.clear();
        }

        for(CompletableFuture<?> future : futuresToCancel)
            future.cancel(true);
    }

    /**
     * Starts the asynchronous token bucket rate limiter and queue consumer for skin fetches.
     */
    private static void startQueueProcessor()
    {
        Bukkit.getScheduler().runTaskTimerAsynchronously(NpcApi.plugin, () ->
                tokens.updateAndGet(current -> current < MAX_TOKENS ? Math.min(MAX_TOKENS, current + 3) : current), 20L, 20L);

        queueProcessorTask = Bukkit.getScheduler().runTaskTimerAsynchronously(NpcApi.plugin, () ->
        {
            SkinRequest request = fetchQueue.peek();
            if(request == null)
                return;

            int current;
            do
            {
                current = tokens.get();
                if(current <= 0)
                    return;
            }
            while(!tokens.compareAndSet(current, current - 1));

            fetchQueue.poll();
            if(request.cacheKey() != null && isNegativeCached(request.cacheKey()))
            {
                request.future().complete(Optional.empty());
                return;
            }

            Optional<Skin> result = request.task().get();
            if(result.isEmpty() && request.cacheKey() != null)
                addNegativeCache(request.cacheKey());
            request.future().complete(result);
        }, 0L, 2L);
    }

    private static boolean isNegativeCached(String identifier)
    {
        if(negativeCache.containsKey(identifier))
        {
            if(negativeCache.get(identifier) > System.currentTimeMillis())
                return true;
            negativeCache.remove(identifier);
        }
        return false;
    }

    private static void addNegativeCache(String identifier)
    {
        if(identifier != null && !identifier.isBlank())
            negativeCache.put(identifier, System.currentTimeMillis() + NEGATIVE_CACHE_DURATION);
    }

    /**
     * Enqueues a skin fetch task, combining futures if a request for the same key is already pending.
     */
    private static CompletableFuture<Optional<Skin>> enqueueFetch(@NotNull String cacheKey, @NotNull Supplier<Optional<Skin>> task)
    {
        SkinRequest existing = pendingRequests.get(cacheKey);
        if(existing != null)
            return existing.future();

        CompletableFuture<Optional<Skin>> future = new CompletableFuture<>();
        future.whenComplete((result, error) -> pendingRequests.remove(cacheKey));

        SkinRequest request = new SkinRequest(cacheKey, task, future);
        pendingRequests.put(cacheKey, request);
        fetchQueue.add(request);

        trackFuture(future);
        return future;
    }

    /**
     * Asynchronously fetches a skin by UUID using the rate-limited queue.
     */
    public static CompletableFuture<Optional<Skin>> fetchSkinAsync(@NotNull UUID uuid)
    {
        return enqueueFetch(uuid.toString(), () -> Skin.fetchSkin(uuid));
    }

    /**
     * Asynchronously fetches a skin by name or URL using the rate-limited queue.
     */
    public static CompletableFuture<Optional<Skin>> fetchSkinAsync(@NotNull String nameOrUrl)
    {
        return enqueueFetch(nameOrUrl, () -> Skin.fetchSkin(nameOrUrl));
    }

    /**
     * Implements a recurring task that makes NPCs look at nearby players. The task runs on a timer defined by {@code NpcApi.config.getLookAtTimer()}. NPCs will
     * only look at players within a specified range, which is configured via {@link NpcOption#LOOK_AT_PLAYER}.
     */
    private static void lookAtTask()
    {
        lookAtTask = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                for(NPC npc : NpcManager.getList())
                {
                    double range = npc.getOption(NpcOption.LOOK_AT_PLAYER);

                    if(range <= 0)
                        continue;

                    ((ServerPlayer) npc.getServerPlayer()).getBukkitEntity().getNearbyEntities(range, range, range)
                            .stream().filter(entity -> entity instanceof Player)
                            .forEach(entity -> npc.lookAtPlayer((Player) entity));
                }
            }
        }.runTaskTimer(NpcApi.plugin, 0, NpcApi.config.lookAtTimer());
    }

    /**
     * Implements a recurring task that updates placeholders. The task runs on a timer defined by {@code NpcApi.config.placeholderTimer()}.
     */
    private static void placeholderTask()
    {
        placeholderTask = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                Collection<NPC> npcs = NpcManager.getList();
                for(NPC npc : npcs)
                {
                    if(!npc.getNpcName().isStatic())
                        npc.updateNameForAll();
                }

                if(!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                    return;

                for(NPC npc : npcs)
                {
                    for(UUID viewerId : npc.getViewers())
                    {
                        if(viewerId == null)
                            continue;

                        Player player = Bukkit.getPlayer(viewerId);
                        if(player == null)
                            continue;

                        NpcSkin npcSkin = npc.getOption(NpcOption.SKIN, player);
                        if(npcSkin == null || npcSkin.isStatic() || npcSkin.getPlaceholder() == null || npc.getOption(NpcOption.USE_PLAYER_SKIN, player))
                            continue;

                        updateSkin(player, npc, npcSkin);
                    }
                }
            }
        }.runTaskTimer(NpcApi.plugin, 10, NpcApi.config.placeholderTimer());
    }

    /**
     * Updates the skin of an NPC for a specific player based on a placeholder value. This method handles both UUID and string-based skin lookups, and updates
     * the NPC's skin asynchronously when the skin is fetched via the queue.
     *
     * @param player  The player who will see the updated skin. Must not be null.
     * @param npc     The NPC whose skin will be updated. Must not be null.
     * @param npcSkin The NpcSkin configuration containing the placeholder and skin settings. Must not be null.
     * @throws NullPointerException if any parameter is null.
     */
    public static void updateSkin(@NotNull Player player, @NotNull NPC npc, @NotNull NpcSkin npcSkin)
    {
        if(!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
            return;

        String newPlaceholder = (String) Reflections.invokeStaticMethod("me.clip.placeholderapi.PlaceholderAPI", "setPlaceholders", player,
                npcSkin.getPlaceholder()).get();

        Map<UUID, String> playerCache = placeholderCache.computeIfAbsent(npc.getUUID(), k -> new ConcurrentHashMap<>());
        String oldPlaceholder = playerCache.getOrDefault(player.getUniqueId(), null);
        if(newPlaceholder.equals(oldPlaceholder))
            return;

        playerCache.put(player.getUniqueId(), newPlaceholder);

        try
        {
            UUID skinUuid = UUID.fromString(newPlaceholder);
            fetchSkinAsync(skinUuid).thenAccept(skinOpt -> skinOpt.ifPresent(skin ->
                    Bukkit.getScheduler().runTaskLater(NpcApi.plugin, () -> npc.updateSkin(player), 1)));
        }
        catch(IllegalArgumentException e)
        {
            fetchSkinAsync(newPlaceholder).thenAccept(skinOpt -> skinOpt.ifPresent(skin ->
                    Bukkit.getScheduler().runTaskLater(NpcApi.plugin, () -> npc.updateSkin(player), 1)));
        }
    }

    /**
     * Tracks a CompletableFuture for cancellation on plugin disable.
     *
     * @param future the future to track
     */
    public static void trackFuture(@NotNull CompletableFuture<?> future)
    {
        if(lookAtTask == null && placeholderTask == null)
        {
            future.cancel(true);
            return;
        }

        synchronized(activeFutures)
        {
            activeFutures.add(future);
        }
        future.whenComplete((result, error) ->
        {
            if(lookAtTask == null && placeholderTask == null)
                return;

            synchronized(activeFutures)
            {
                activeFutures.remove(future);
            }
        });
    }

    private record SkinRequest(String cacheKey, Supplier<Optional<Skin>> task, CompletableFuture<Optional<Skin>> future) implements Comparable<SkinRequest>
    {
        @Override
        public int compareTo(@NotNull SkinRequest o)
        {
            return 0;
        }
    }
}