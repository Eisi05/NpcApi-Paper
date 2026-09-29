package de.eisi05.npc.api.scheduler;

/**
 * Provides access to the appropriate server scheduler based on the server implementation.
 * <p>
 * Automatically detects whether the server is running Folia or a standard Paper-compatible implementation and initializes the corresponding scheduler.
 */
public class SchedulerProvider
{
    private static ServerScheduler scheduler;
    private static Boolean isFolia = null;

    /**
     * Gets the server scheduler, initializing it if necessary.
     *
     * @return the server scheduler for the current server implementation
     */
    public static ServerScheduler get()
    {
        if(scheduler == null)
        {
            if(isFolia())
                scheduler = new FoliaScheduler();
            else
                scheduler = new PaperScheduler();
        }
        return scheduler;
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
        if(isFolia == null)
        {
            try
            {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                isFolia = true;
            }
            catch(ClassNotFoundException e)
            {
                isFolia = false;
            }
        }
        return isFolia;
    }
}