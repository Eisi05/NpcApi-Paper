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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The {@link Tasks} class manages and starts various recurring tasks related to Non-Player Characters (NPCs) within the Bukkit environment. These tasks often
 * involve NPC behavior such as looking at nearby players.
 */
public class Tasks
{
    private static final Map<UUID, Map<UUID, String>> placeholderCache = new HashMap<>();
    private static BukkitTask lookAtTask;
    private static BukkitTask placeholderTask;

    /**
     * Starts all defined NPC-related tasks. This method should be called when the plugin is enabled to ensure that NPC behaviors are active.
     */
    public static void start()
    {
        lookAtTask();
        placeholderTask();
    }

    /**
     * Stops all defined NPC-related tasks.
     */
    public static void stop()
    {
        if(lookAtTask != null && !lookAtTask.isCancelled())
            lookAtTask.cancel();

        if(placeholderTask != null && !placeholderTask.isCancelled())
            placeholderTask.cancel();
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
                NpcManager.getList().forEach(npc ->
                {
                    double range = npc.getOption(NpcOption.LOOK_AT_PLAYER);

                    if(range <= 0)
                        return;

                    ((ServerPlayer) npc.getServerPlayer()).getBukkitEntity().getNearbyEntities(range, range, range)
                            .stream().filter(entity -> entity instanceof Player)
                            .forEach(entity -> npc.lookAtPlayer((Player) entity));
                });
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
                NpcManager.getList().stream().filter(npc -> !npc.getNpcName().isStatic()).forEach(NPC::updateNameForAll);

                if(!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                    return;

                NpcManager.getList().forEach(npc ->
                {
                    NpcSkin npcSkin = npc.getOption(NpcOption.SKIN);
                    if(npcSkin == null || npcSkin.isStatic() || npcSkin.getPlaceholder() == null || npc.getOption(NpcOption.USE_PLAYER_SKIN))
                        return;

                    for(UUID viewerId : npc.getViewers())
                    {
                        if(viewerId == null)
                            continue;

                        Player player = Bukkit.getPlayer(viewerId);
                        if(player == null)
                            continue;

                        updateSkin(player, npc, npcSkin);
                    }
                });
            }
        }.runTaskTimer(NpcApi.plugin, 10, NpcApi.config.placeholderTimer());
    }

    /**
     * Updates the skin of an NPC for a specific player based on a placeholder value. This method handles both UUID and string-based skin lookups, and updates
     * the NPC's skin asynchronously when the skin is fetched.
     *
     * @param player  The player who will see the updated skin. Must not be null.
     * @param npc     The NPC whose skin will be updated. Must not be null.
     * @param npcSkin The NpcSkin configuration containing the placeholder and skin settings. Must not be null.
     * @throws NullPointerException if any parameter is null.
     */
    public static void updateSkin(@NotNull Player player, @NotNull NPC npc, @NotNull NpcSkin npcSkin)
    {
        String newPlaceholder = (String) Reflections.invokeStaticMethod("me.clip.placeholderapi.PlaceholderAPI", "setPlaceholders", player,
                npcSkin.getPlaceholder()).get();

        Map<UUID, String> playerCache = placeholderCache.getOrDefault(npc.getUUID(), new HashMap<>());
        String oldPlaceholder = playerCache.getOrDefault(player.getUniqueId(), null);
        if(newPlaceholder.equals(oldPlaceholder))
            return;

        playerCache.put(player.getUniqueId(), newPlaceholder);
        placeholderCache.put(npc.getUUID(), playerCache);

        try
        {
            UUID skinUuid = UUID.fromString(newPlaceholder);
            Skin.fetchSkinAsync(skinUuid).thenAccept(skinOpt -> skinOpt.ifPresent(skin -> npc.updateSkin(player)));
        }
        catch(IllegalArgumentException e)
        {
            Skin.fetchSkinAsync(newPlaceholder).thenAccept(skinOpt -> skinOpt.ifPresent(skin -> npc.updateSkin(player)));
        }
    }
}
