package de.eisi05.npc.api.scheduler;

import org.bukkit.Bukkit;

/**
 * A server tick counter that works on Paper and Folia.
 * <p>
 * {@code Bukkit#getCurrentTick} has no single meaning on Folia, where every region ticks on its own. Code that only needs "how many ticks passed since X"
 * (cooldowns, invulnerability windows, expiry) can use this counter instead: on Paper it is the real server tick, on Folia it is derived from the clock at 20
 * ticks per second, which is what every region aims for.
 */
public final class GameTick
{
    private static final long START_NANOS = System.nanoTime();

    private GameTick()
    {
    }

    /**
     * @return the current tick; only differences between two values are meaningful
     */
    public static int current()
    {
        if(!SchedulerProvider.isFolia())
            return Bukkit.getCurrentTick();

        return (int) ((System.nanoTime() - START_NANOS) / 50_000_000L);
    }
}