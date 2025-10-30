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
     * If true, debug mode is enabled.
     * Can be used for logging or diagnostic purposes.
     */
    private boolean debug = false;

    /**
     * Time allowed for input, measured in seconds.
     * Default is 60 seconds.
     */
    private int inputTime = 60;

    /**
     * If true, NPCs are automatically updated when changed.
     */
    private boolean autoUpdate = false;

    /**
     * The time in ticks a placeholder (Name or Skin) will update.
     * The default value is 1 minute.
     */
    private long placeholderTimer = 20 * 60;

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
     * Enables or disables debug mode.
     *
     * @param debug True to enable debug mode, false to disable it.
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig debug(boolean debug)
    {
        this.debug = debug;
        return this;
    }

    /**
     * Sets the input time limit.
     *
     * @param inputTime The time in seconds.
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig inputTime(int inputTime)
    {
        this.inputTime = inputTime;
        return this;
    }

    /**
     * Sets whether automatic updates should be enabled.
     *
     * @param autoUpdate True to enable auto updates, false otherwise.
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig autoUpdate(boolean autoUpdate)
    {
        this.autoUpdate = autoUpdate;
        return this;
    }

    /**
     * Sets the duration a placeholder will be updated.
     *
     * @param time The time in ticks. For example, 20 ticks = 1 second.
     * @return This {@link NpcConfig} instance for method chaining. Will not be null.
     */
    public @NotNull NpcConfig placeholderTimer(long time)
    {
        placeholderTimer = time;
        return this;
    }

    /**
     * Gets the configured duration an NPC will look at a player.
     *
     * @return The time in ticks.
     */
    public long lookAtTimer()
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

    /**
     * Checks whether debug mode is enabled.
     *
     * @return True if debug is enabled; false otherwise.
     */
    public boolean debug()
    {
        return debug;
    }

    /**
     * Gets the configured input time limit.
     *
     * @return The time in seconds.
     */
    public int inputTime()
    {
        return inputTime;
    }

    /**
     * Checks whether automatic updates are enabled.
     *
     * @return True if auto updates are enabled; false otherwise.
     */
    public boolean autoUpdate()
    {
        return autoUpdate;
    }

    /**
     * Gets the configured duration a placeholder will update.
     *
     * @return The time in ticks.
     */
    public long placeholderTimer()
    {
        return placeholderTimer;
    }
}
