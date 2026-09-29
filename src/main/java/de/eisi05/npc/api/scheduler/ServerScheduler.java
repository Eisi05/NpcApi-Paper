package de.eisi05.npc.api.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Provides platform-independent methods for scheduling tasks on the server.
 * <p>
 * Supports synchronous, asynchronous, delayed, repeating, location-based, and entity-based task execution.
 */
public interface ServerScheduler
{
    /**
     * Cancels all scheduled tasks.
     */
    void cancelAllTasks();

    /**
     * Schedules a task to run synchronously on the server's main thread.
     *
     * @param runnable the task to execute
     * @return a handle for managing the scheduled task
     */
    PluginTask runSync(Runnable runnable);

    /**
     * Schedules a task to run synchronously at the specified location.
     *
     * @param location the location at which the task should execute
     * @param runnable the task to execute
     * @return a handle for managing the scheduled task
     */
    PluginTask runSyncAtLocation(Location location, Runnable runnable);

    /**
     * Schedules a task to run synchronously on the thread responsible for the specified entity.
     *
     * @param entity   the entity associated with the task
     * @param runnable the task to execute
     * @return a handle for managing the scheduled task
     */
    PluginTask runSyncForEntity(Entity entity, Runnable runnable);

    /**
     * Schedules a task to run for the specified entity after a delay.
     *
     * @param entity     the entity associated with the task
     * @param runnable   the task to execute
     * @param delayTicks the delay before execution, in server ticks
     * @return a handle for managing the scheduled task
     */
    PluginTask runLaterForEntity(Entity entity, Runnable runnable, long delayTicks);

    /**
     * Schedules a repeating task for the specified entity.
     *
     * @param entity      the entity associated with the task
     * @param runnable    the task to execute
     * @param delayTicks  the delay before the first execution, in server ticks
     * @param periodTicks the interval between executions, in server ticks
     * @return a handle for managing the scheduled task
     */
    PluginTask runTimerForEntity(Entity entity, Runnable runnable, long delayTicks, long periodTicks);

    /**
     * Schedules a task to run asynchronously.
     *
     * @param runnable the task to execute
     * @return a handle for managing the scheduled task
     */
    PluginTask runAsync(Runnable runnable);

    /**
     * Schedules a task using the scheduler's default execution context.
     *
     * @param runnable the task to execute
     * @return a handle for managing the scheduled task
     */
    PluginTask run(Runnable runnable);

    /**
     * Schedules a task to run after a specified delay.
     *
     * @param runnable   the task to execute
     * @param delayTicks the delay before execution, in server ticks
     * @return a handle for managing the scheduled task
     */
    PluginTask runDelayed(Runnable runnable, long delayTicks);

    /**
     * Schedules a repeating synchronous task.
     *
     * @param runnable    the task to execute
     * @param delayTicks  the delay before the first execution, in server ticks
     * @param periodTicks the interval between executions, in server ticks
     * @return a handle for managing the scheduled task
     */
    PluginTask runTimer(Runnable runnable, long delayTicks, long periodTicks);

    /**
     * Schedules a repeating asynchronous task.
     *
     * @param runnable    the task to execute
     * @param delayTicks  the delay before the first execution, in server ticks
     * @param periodTicks the interval between executions, in server ticks
     * @return a handle for managing the scheduled task
     */
    PluginTask runTimerAsync(Runnable runnable, long delayTicks, long periodTicks);
}