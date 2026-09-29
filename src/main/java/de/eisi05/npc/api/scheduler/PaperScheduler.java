package de.eisi05.npc.api.scheduler;

import de.eisi05.npc.api.NpcApi;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

/**
 * Implementation of {@link ServerScheduler} using the Bukkit scheduler.
 * <p>
 * Provides task scheduling for Paper and other Bukkit-compatible servers, supporting synchronous, asynchronous, delayed, repeating, and entity- or
 * location-based task execution.
 */
public class PaperScheduler implements ServerScheduler
{
    /**
     * Wraps a Bukkit task in a {@link PluginTask} to provide a platform-independent task management interface.
     *
     * @param task the Bukkit task to wrap
     * @return a {@link PluginTask} delegating cancellation and cancellation status to the Bukkit task
     */
    private PluginTask wrapTask(BukkitTask task)
    {
        return new PluginTask()
        {
            @Override
            public void cancel()
            {
                task.cancel();
            }

            @Override
            public boolean isCancelled()
            {
                return task.isCancelled();
            }
        };
    }

    @Override
    public void cancelAllTasks()
    {
        Bukkit.getScheduler().cancelTasks(NpcApi.plugin);
    }

    @Override
    public PluginTask runSync(Runnable runnable)
    {
        return wrapTask(Bukkit.getScheduler().runTask(NpcApi.plugin, runnable));
    }

    @Override
    public PluginTask runSyncAtLocation(Location location, Runnable runnable)
    {
        return runSync(runnable);
    }

    @Override
    public PluginTask runLaterAtLocation(Location location, Runnable runnable, long delayTicks)
    {
        return runDelayed(runnable, delayTicks);
    }

    @Override
    public PluginTask runSyncForEntity(Entity entity, Runnable runnable)
    {
        return runSync(runnable);
    }

    @Override
    public PluginTask runLaterForEntity(Entity entity, Runnable runnable, long delayTicks)
    {
        return wrapTask(Bukkit.getScheduler().runTaskLater(NpcApi.plugin, runnable, delayTicks));
    }

    @Override
    public PluginTask runTimerForEntity(Entity entity, Runnable runnable, long delayTicks, long periodTicks)
    {
        return wrapTask(Bukkit.getScheduler().runTaskTimer(NpcApi.plugin, runnable, delayTicks, periodTicks));
    }

    @Override
    public PluginTask runAsync(Runnable runnable)
    {
        return wrapTask(Bukkit.getScheduler().runTaskAsynchronously(NpcApi.plugin, runnable));
    }

    @Override
    public PluginTask run(Runnable runnable)
    {
        return wrapTask(Bukkit.getScheduler().runTask(NpcApi.plugin, runnable));
    }

    @Override
    public PluginTask runDelayed(Runnable runnable, long delayTicks)
    {
        return wrapTask(Bukkit.getScheduler().runTaskLater(NpcApi.plugin, runnable, delayTicks));
    }

    @Override
    public PluginTask runTimer(Runnable runnable, long delayTicks, long periodTicks)
    {
        return wrapTask(Bukkit.getScheduler().runTaskTimer(NpcApi.plugin, runnable, delayTicks, periodTicks));
    }

    @Override
    public PluginTask runTimerAsync(Runnable runnable, long delayTicks, long periodTicks)
    {
        return wrapTask(Bukkit.getScheduler().runTaskTimerAsynchronously(NpcApi.plugin, runnable, delayTicks, periodTicks));
    }
}