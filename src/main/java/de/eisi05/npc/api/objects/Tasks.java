package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * The {@link Tasks} class manages and starts various recurring tasks
 * related to Non-Player Characters (NPCs) within the Bukkit environment.
 * These tasks often involve NPC behavior such as looking at nearby players.
 */
public class Tasks
{
    private static BukkitTask task;

    /**
     * Starts all defined NPC-related tasks.
     * This method should be called when the plugin is enabled to ensure
     * that NPC behaviors are active.
     */
    public static void start()
    {
        lookAtTask();
    }

    public static void stop()
    {
        if(task != null && !task.isCancelled())
            task.cancel();
    }

    /**
     * Implements a recurring task that makes NPCs look at nearby players.
     * The task runs on a timer defined by {@code NpcApi.config.getLookAtTimer()}.
     * NPCs will only look at players within a specified range, which is
     * configured via {@link NpcOption#LOOK_AT_PLAYER}.
     */
    private static void lookAtTask()
    {
        task = new BukkitRunnable()
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
        }.runTaskTimer(NpcApi.plugin, 0, NpcApi.config.getLookAtTimer());
    }
}
