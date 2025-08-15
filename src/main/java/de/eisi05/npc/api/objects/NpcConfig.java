package de.eisi05.npc.api.objects;

import org.jetbrains.annotations.NotNull;

/**
 * Configuration settings for NPC behavior.
 * This class allows for customizing various aspects of an NPC, such as interaction timers.
 */
public class NpcConfig
{
    /**
     * The time in ticks an NPC will look at a player after interaction.
     * The default value is 5 ticks.
     */
    private long lookAtTimer = 5;

    /**
     * If true, command validation will be skipped.
     * Useful for allowing proxy commands like BungeeCord.
     */
    private boolean avoidCommandCheck = false;

    /**
     * Sets the duration an NPC will look at a player after an interaction.
     *
     * @param time The time in ticks. For example, 20 ticks = 1 second.
     * @return This {@link NpcConfig} instance for method chaining. Will not be null.
     */
    public @NotNull NpcConfig lookAtTimer(long time)
    {
        lookAtTimer = time;
        return this;
    }

    /**
     * Sets whether to skip command validation.
     * Useful for allowing BungeeCord or proxy commands.
     *
     * @param avoidCommandCheck True to skip command checks, false to validate.
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig avoidCommandCheck(boolean avoidCommandCheck)
    {
        this.avoidCommandCheck = avoidCommandCheck;
        return this;
    }

    /**
     * Gets the configured duration an NPC will look at a player.
     *
     * @return The time in ticks.
     */
    public long getLookAtTimer()
    {
        return lookAtTimer;
    }

    /**
     * Checks whether command validation is disabled.
     *
     * @return True if validation is skipped; false otherwise.
     */
    public boolean avoidCommandCheck()
    {
        return avoidCommandCheck;
    }
}
