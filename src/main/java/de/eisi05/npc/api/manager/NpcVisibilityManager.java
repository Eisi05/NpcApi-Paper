package de.eisi05.npc.api.manager;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Manages visibility settings for an NPC, controlling whether it should be shown to all players or only to specific players.
 */
public class NpcVisibilityManager implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    private final Set<UUID> specificPlayers = new HashSet<>();
    private boolean showToAllPlayers = false;

    /**
     * Sets whether this NPC should be shown to all players (including new ones joining).
     *
     * @param showToAllPlayers true if the NPC should be shown to all players, false if only to specific players
     */
    public void setShowToAllPlayers(boolean showToAllPlayers)
    {
        this.showToAllPlayers = showToAllPlayers;
    }

    /**
     * Checks if this NPC should be shown to all players.
     *
     * @return true if the NPC should be shown to all players, false if only to specific players
     */
    public boolean shouldShowToAllPlayers()
    {
        return showToAllPlayers;
    }

    /**
     * Adds a player to the list of specific players who should see this NPC. This has no effect if showToAllPlayers is true.
     *
     * @param playerUuid the UUID of the player to add
     */
    public void addSpecificPlayer(@NotNull UUID playerUuid)
    {
        specificPlayers.add(playerUuid);
    }

    /**
     * Removes a player from the list of specific players who should see this NPC.
     *
     * @param playerUuid the UUID of the player to remove
     */
    public void removeSpecificPlayer(@NotNull UUID playerUuid)
    {
        specificPlayers.remove(playerUuid);
    }

    /**
     * Checks if a specific player should see this NPC.
     *
     * @param playerUuid the UUID of the player to check
     * @return true if the player should see this NPC, false otherwise
     */
    public boolean shouldShowToPlayer(@NotNull UUID playerUuid)
    {
        return showToAllPlayers || specificPlayers.contains(playerUuid);
    }

    /**
     * Gets the set of specific players who should see this NPC.
     *
     * @return an immutable set of player UUIDs
     */
    public @NotNull Set<UUID> getSpecificPlayers()
    {
        return Collections.unmodifiableSet(specificPlayers);
    }

    /**
     * Clears all specific players from the list.
     */
    public void clearSpecificPlayers()
    {
        specificPlayers.clear();
    }
}
