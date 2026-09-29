package de.eisi05.npc.api.scheduler;

/**
 * Represents a scheduled task that can be cancelled and queried for its cancellation status.
 */
public interface PluginTask
{
    /**
     * Cancels this task, preventing further executions.
     */
    void cancel();

    /**
     * Checks whether this task has been cancelled.
     *
     * @return {@code true} if this task has been cancelled, {@code false} otherwise
     */
    boolean isCancelled();
}