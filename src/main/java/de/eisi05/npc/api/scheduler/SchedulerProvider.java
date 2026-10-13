package de.eisi05.npc.api.scheduler;

/**
 * Provides access to the appropriate server scheduler based on the server implementation.
 * <p>
 * Automatically detects whether the server is running Folia or a standard Paper-compatible implementation and initializes the corresponding scheduler. Both
 * the detection and the scheduler instance are initialized exactly once, thread-safely (the first call may come from any region or async thread on Folia).
 */
public class SchedulerProvider
{
    private static final boolean FOLIA = detectFolia();

    /**
     * Initialization-on-demand holder: the JVM guarantees the scheduler is created once, lazily, with safe publication.
     */
    private static final class Holder
    {
        private static final ServerScheduler INSTANCE = FOLIA ? new FoliaScheduler() : new PaperScheduler();
    }

    private SchedulerProvider()
    {
    }

    /**
     * Gets the server scheduler.
     *
     * @return the server scheduler for the current server implementation
     */
    public static ServerScheduler get()
    {
        return Holder.INSTANCE;
    }

    /**
     * Checks whether the current server is running Folia.
     * <p>
     * Detection is performed by checking whether the Folia-specific {@code RegionizedServer} class is available.
     *
     * @return {@code true} if Folia is detected, {@code false} otherwise
     */
    public static boolean isFolia()
    {
        return FOLIA;
    }

    private static boolean detectFolia()
    {
        try
        {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        }
        catch(ClassNotFoundException e)
        {
            return false;
        }
    }
}
