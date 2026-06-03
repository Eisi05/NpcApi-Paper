package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.utils.ApiOnly;
import org.jetbrains.annotations.NotNull;

/**
 * Configuration settings for NPC behavior. This class allows for customizing various aspects of an NPC, such as interaction timers.
 */
public class NpcConfig
{
    /**
     * The time in ticks an NPC will look at a player after interaction. The default value is 5 ticks.
     */
    private long lookAtTimer = 5;

    /**
     * If true, command validation will be skipped. Useful for allowing proxy commands like BungeeCord.
     */
    private boolean avoidCommandCheck = false;

    /**
     * If true, debug mode is enabled. Can be used for logging or diagnostic purposes.
     */
    private boolean debug = false;

    /**
     * Time allowed for input, measured in seconds. Default is 60 seconds.
     */
    private int inputTime = 60;

    /**
     * If true, NPCs are automatically updated when changed.
     */
    private boolean autoUpdate = false;

    /**
     * The time in ticks a placeholder (Name or Skin) will update. The default value is 1 minute.
     */
    private long placeholderTimer = 20 * 60;

    /**
     * If enabled, the system will validate that NPC paths have solid blocks beneath both the starting and ending points of the path.
     * <p>
     * When true, the pathfinding will verify that there are solid blocks underneath both the start and end positions of any movement path. If either position
     * lacks a solid block beneath it, the path will be considered invalid.
     * <p>
     * Default: true
     */
    private boolean checkValidPath = true;

    /**
     * If true, the API will automatically manage NPC visibility for players across various events. This includes showing NPCs when players join, change worlds,
     * or load chunks, and hiding NPCs when players quit.
     */
    @ApiOnly
    private boolean autoManageVisibility = true;

    /**
     * If true, walking NPCs automatically manage viewers while a path task is active.
     * <p>
     * When enabled, players who join, change worlds, respawn, or load nearby chunks can be added to the active walking task and synced to the NPC's current
     * walking position.
     * <p>
     * This only applies to walking tasks created without an explicit viewer list.
     * <p>
     * Default: true
     */
    @ApiOnly
    private boolean autoManageWalkingViewers = true;

    /**
     * Maximum distance in blocks at which walking NPC movement is shown to players.
     * <p>
     * Players farther than this distance from the NPC's current walking position will not receive walking movement packets. Players in another world are never
     * shown walking movement.
     * <p>
     * Set to {@code -1} to disable the distance check.
     * <p>
     * Default: 64 blocks.
     */
    private double walkingViewerDistance = 64.0;

    /**
     * If true, precise hitbox detection is used for sleeping NPCs.
     * <p>
     * When enabled, the {@link de.eisi05.npc.api.listeners.NpcSleepListener} will use accurate ray intersection calculations to detect interactions with
     * sleeping NPCs. This provides more precise click detection but may be more computationally expensive.
     * <p>
     * Default: false
     */
    private boolean preciseSleepingHitbox = false;

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
     * Sets whether to skip command validation. Useful for allowing BungeeCord or proxy commands.
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
     * Sets whether the plugin should validate if a path is valid before an NPC starts moving. When enabled, the plugin will check if there are solid blocks
     * beneath both the starting and destination locations of the NPC's path.
     *
     * @param checkValidPath True to enable path validation, false to disable it.
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig checkValidPath(boolean checkValidPath)
    {
        this.checkValidPath = checkValidPath;
        return this;
    }

    /**
     * Sets whether the API should automatically manage NPC visibility for players.
     *
     * @param autoManageVisibility True to enable automatic visibility management, false to disable it.
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig autoManageVisibility(boolean autoManageVisibility)
    {
        this.autoManageVisibility = autoManageVisibility;
        return this;
    }

    /**
     * Sets whether walking NPCs should automatically manage viewers during active path tasks.
     * <p>
     * This only applies when {@link #autoManageVisibility()} is enabled and the walking task was created without an explicit viewer list.
     *
     * @param autoManageWalkingViewers true to automatically manage walking viewers, false otherwise
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig autoManageWalkingViewers(boolean autoManageWalkingViewers)
    {
        this.autoManageWalkingViewers = autoManageWalkingViewers;
        return this;
    }

    /**
     * Sets the maximum distance in blocks at which walking NPC movement is shown to players.
     * <p>
     * Players farther than this distance from the NPC's current walking position will not receive walking packets. Players in another world are never shown
     * walking movement.
     * <p>
     * Use {@code -1} to disable the distance check.
     *
     * @param walkingViewerDistance the maximum distance in blocks, or -1 to disable
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig walkingViewerDistance(double walkingViewerDistance)
    {
        this.walkingViewerDistance = walkingViewerDistance;
        return this;
    }

    /**
     * Sets whether precise hitbox detection is used for sleeping NPCs.
     * <p>
     * When enabled, the {@link de.eisi05.npc.api.listeners.NpcSleepListener} will use accurate ray intersection calculations to detect interactions with
     * sleeping NPCs.
     *
     * @param preciseSleepingHitbox true to enable precise hitbox detection, false otherwise
     * @return This {@link NpcConfig} instance for method chaining. Never null.
     */
    public @NotNull NpcConfig preciseSleepingHitbox(boolean preciseSleepingHitbox)
    {
        this.preciseSleepingHitbox = preciseSleepingHitbox;
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

    /**
     * Checks whether path validation is enabled for NPC movement.
     *
     * @return True if path validation is enabled, false otherwise.
     */
    public boolean checkValidPath()
    {
        return checkValidPath;
    }

    /**
     * Checks whether the API automatically manages NPC visibility for players.
     *
     * @return True if automatic visibility management is enabled; false otherwise.
     */
    public boolean autoManageVisibility()
    {
        return autoManageVisibility;
    }

    /**
     * Checks whether walking NPCs automatically manage viewers during active path tasks.
     *
     * @return true if automatic walking viewer management is enabled; false otherwise
     */
    public boolean autoManageWalkingViewers()
    {
        return autoManageWalkingViewers;
    }

    /**
     * Gets the maximum distance in blocks at which walking NPC movement is shown to players.
     *
     * @return the maximum walking viewer distance in blocks, or -1 if disabled
     */
    public double walkingViewerDistance()
    {
        return walkingViewerDistance;
    }

    /**
     * Checks whether precise hitbox detection is enabled for sleeping NPCs.
     *
     * @return true if precise hitbox detection is enabled, false otherwise
     */
    public boolean preciseSleepingHitbox()
    {
        return preciseSleepingHitbox;
    }
}
