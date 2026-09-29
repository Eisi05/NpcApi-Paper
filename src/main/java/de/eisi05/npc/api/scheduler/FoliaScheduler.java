package de.eisi05.npc.api.scheduler;

import de.eisi05.npc.api.NpcApi;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link ServerScheduler} using the Folia scheduling API.
 * <p>
 * Supports Folia's region-based, entity-based, global, and asynchronous scheduling mechanisms to ensure tasks are executed in the appropriate execution
 * context.
 */
public class FoliaScheduler implements ServerScheduler
{
    /**
     * Wraps a Folia scheduled task in a {@link PluginTask} to provide a platform-independent task management interface.
     * <p>
     * If the provided task is {@code null}, returns a no-op task that is always considered cancelled.
     *
     * @param task the Folia scheduled task to wrap, or {@code null}
     * @return a {@link PluginTask} delegating to the scheduled task, or an already-cancelled no-op task if the provided task is {@code null}
     */
    private PluginTask wrapTask(ScheduledTask task)
    {
        if(task == null)
        {
            return new PluginTask()
            {
                @Override
                public void cancel() {}

                @Override
                public boolean isCancelled()
                {
                    return true;
                }
            };
        }
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
        Bukkit.getGlobalRegionScheduler().cancelTasks(NpcApi.plugin);
        Bukkit.getAsyncScheduler().cancelTasks(NpcApi.plugin);
    }

    @Override
    public PluginTask runSync(Runnable runnable)
    {
        return wrapTask(Bukkit.getGlobalRegionScheduler().run(NpcApi.plugin, t -> runnable.run()));
    }

    @Override
    public PluginTask runSyncAtLocation(Location location, Runnable runnable)
    {
        return wrapTask(Bukkit.getRegionScheduler().run(NpcApi.plugin, location, t -> runnable.run()));
    }

    @Override
    public PluginTask runSyncForEntity(Entity entity, Runnable runnable)
    {
        return wrapTask(entity.getScheduler().run(NpcApi.plugin, t -> runnable.run(), null));
    }

    @Override
    public PluginTask runLaterForEntity(Entity entity, Runnable runnable, long delayTicks)
    {
        return wrapTask(entity.getScheduler().runDelayed(NpcApi.plugin, t -> runnable.run(), null, delayTicks));
    }

    @Override
    public PluginTask runTimerForEntity(Entity entity, Runnable runnable, long delayTicks, long periodTicks)
    {
        return wrapTask(entity.getScheduler().runAtFixedRate(NpcApi.plugin, t -> runnable.run(), null, delayTicks, periodTicks));
    }

    @Override
    public PluginTask runAsync(Runnable runnable)
    {
        return wrapTask(Bukkit.getAsyncScheduler().runNow(NpcApi.plugin, t -> runnable.run()));
    }

    @Override
    public PluginTask run(Runnable runnable)
    {
        return wrapTask(Bukkit.getGlobalRegionScheduler().run(NpcApi.plugin, t -> runnable.run()));
    }

    @Override
    public PluginTask runDelayed(Runnable runnable, long delayTicks)
    {
        return wrapTask(Bukkit.getGlobalRegionScheduler().runDelayed(NpcApi.plugin, t -> runnable.run(), delayTicks));
    }

    @Override
    public PluginTask runTimer(Runnable runnable, long delayTicks, long periodTicks)
    {
        return wrapTask(Bukkit.getGlobalRegionScheduler().runAtFixedRate(NpcApi.plugin, t -> runnable.run(), delayTicks, periodTicks));
    }

    @Override
    public PluginTask runTimerAsync(Runnable runnable, long delayTicks, long periodTicks)
    {
        return wrapTask(
                Bukkit.getAsyncScheduler().runAtFixedRate(NpcApi.plugin, t -> runnable.run(), delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS));
    }
}