package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.NpcApi;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.Packet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Abstract class representing an entity that can hold NPC-related data
 * and has a concept of unsaved changes.
 * It implements {@link InventoryHolder} but onlay as placeholder.
 */
public abstract class NpcHolder implements InventoryHolder
{
    protected static final UUID GLOBAL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    protected final Map<UUID, Map<NpcOption<?, ?>, Object>> options = new HashMap<>();

    protected NpcName name;

    /**
     * Flag indicating whether there are unsaved changes to this holder.
     * Defaults to {@code false}.
     */
    private boolean unsavedChanges = false;

    /**
     * Checks if there are any unsaved changes for this NPC holder.
     *
     * @return {@code true} if there are unsaved changes, {@code false} otherwise.
     */

    public boolean hasUnsavedChanges()
    {
        return unsavedChanges;
    }

    /**
     * Marks that there are unsaved changes to this NPC holder.
     * This should be called whenever a modifiable property of the holder is changed.
     */
    public void markChange()
    {
        unsavedChanges = true;
    }

    /**
     * Saves the current state of the NPC holder.
     * This method is intended to persist any changes. After successful execution,
     * the {@code unsavedChanges} flag is reset to {@code false}.
     *
     * @throws IOException if an error occurs during the saving process.
     */
    public void save() throws IOException
    {
        unsavedChanges = false;
    }

    /**
     * Gets the unique identifier of this NPC holder.
     *
     * @return The UUID of this NPC holder, or {@code null}
     */
    public abstract @Nullable UUID getUUID();

    /**
     * Gets the name of this NPC holder.
     *
     * @return The Name of this NPC holder, or {@code null}
     */
    public abstract @NotNull Component getName();

    /**
     * Sets the name of this NPC holder.
     *
     * @param name The name of this NPC holder
     */
    public abstract void setName(@NotNull NpcName name);

    /**
     * Sets an option value for this NPC holder with the global UUID.
     *
     * @param <T>    the type of the option value
     * @param option the option to set
     * @param value  the value to set for the option, or {@code null} to remove the option
     * @throws NullPointerException if the option is null
     */
    public <T> void setOption(@NotNull NpcOption<T, ?> option, @Nullable T value)
    {
        setOption(option, value, GLOBAL_UUID);
    }

    /**
     * Sets an option value for this NPC holder with the specified UUID.
     *
     * @param <T>    the type of the option value
     * @param option the option to set
     * @param value  the value to set for the option, or {@code null} to remove the option
     * @param uuid   the UUID to associate with this option setting
     * @throws NullPointerException if either option or uuid is null
     */
    <T> void setOption(@NotNull NpcOption<T, ?> option, @Nullable T value, @NotNull UUID uuid)
    {
        Map<NpcOption<?, ?>, Object> playerOptions = options.computeIfAbsent(uuid, k -> new HashMap<>());
        if(value == null)
        {
            playerOptions.remove(option);
            if(playerOptions.isEmpty() && !uuid.equals(GLOBAL_UUID))
                options.remove(uuid);
            else
                options.put(uuid, playerOptions);
        }
        else
        {
            playerOptions.put(option, value);
            options.put(uuid, playerOptions);
        }

        if(NpcApi.config.autoUpdate() && this instanceof NPC npc)
        {
            if(option.equals(NpcOption.SKIN) || option.equals(NpcOption.USE_PLAYER_SKIN))
            {
                npc.updateSkin(npc.getViewers().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toArray(Player[]::new));
                return;
            }

            if(option.equals(NpcOption.ENTITY))
            {
                npc.reload();
                return;
            }

            npc.getViewers().forEach(uuid1 ->
            {
                Player player = Bukkit.getPlayer(uuid1);
                if(player == null)
                    return;

                option.getPacket(value, npc, player).ifPresent(packetWrapper -> ((CraftPlayer) player).getHandle().connection.send((Packet<?>) packetWrapper));
            });
        }
    }

    /**
     * Gets the value of an option for a specific UUID.
     *
     * @param <T>    the type of the option value
     * @param option the option to get
     * @param uuid   the UUID to get the option for
     * @return the option value, or the default value if not set
     * @throws NullPointerException if either option or uuid is null
     */
    @SuppressWarnings("unchecked")
    <T> @Nullable T getOption(@NotNull NpcOption<T, ?> option, @NotNull UUID uuid)
    {
        Map<NpcOption<?, ?>, Object> playerOptions = options.get(uuid);

        if(playerOptions != null && playerOptions.containsKey(option))
            return (T) playerOptions.get(option);

        return option.getDefaultValue();
    }

    /**
     * Gets the value of an option using the global UUID.
     *
     * @param <T>    the type of the option value
     * @param option the option to get
     * @return the option value, or the default value if not set
     * @throws NullPointerException if option is null
     */
    public <T> @Nullable T getOption(@NotNull NpcOption<T, ?> option)
    {
        return getOption(option, GLOBAL_UUID);
    }

    /**
     * Gets the value of an option for a specific player.
     *
     * @param <T>    the type of the option value
     * @param option the option to get
     * @param player the player to get the option for
     * @return the option value, or the default value if not set
     * @throws NullPointerException if either option or player is null
     */
    public <T> @Nullable T getOption(@NotNull NpcOption<T, ?> option, @NotNull Player player)
    {
        if(getUUID() == null)
            return getOption(option);

        return getOption(option, UUID.fromString(player.getPersistentDataContainer().getOrDefault(getKey(player), PersistentDataType.STRING,
                GLOBAL_UUID.toString())));
    }

    /**
     * Generates a unique {@link NamespacedKey} for storing player-specific NPC data. The key is constructed using the NPC's UUID and the player's UUID to
     * ensure uniqueness.
     *
     * @param player The player for whom to generate the key
     * @return A new {@link NamespacedKey} in the format: "npcplugin:[npcUuid]-[playerUuid]"
     * @throws NullPointerException  if the player parameter is null
     * @throws IllegalStateException if the NPC's UUID is null
     */
    protected @NotNull NamespacedKey getKey(@NotNull Player player)
    {
        return new NamespacedKey(NpcApi.plugin, getUUID() + "-" + player.getUniqueId());
    }

    /**
     * Applies multiple options to this NPC holder for a specific UUID.
     *
     * @param options the map of options to apply
     * @param uuid    the UUID to associate with these options
     * @throws NullPointerException if either options or uuid is null
     */
    public void applyOptions(@NotNull Map<NpcOption<?, ?>, Object> options, @NotNull UUID uuid)
    {
        Map<NpcOption<?, ?>, Object> playerOptions = this.options.computeIfAbsent(uuid, k -> new HashMap<>());
        playerOptions.putAll(options);
        if(NpcApi.config.autoUpdate() && this instanceof NPC npc)
            npc.reload();
    }

    /**
     * Applies multiple options to this NPC holder using the global UUID.
     *
     * @param options the map of options to apply
     * @throws NullPointerException if options is null
     */
    public void applyOptions(@NotNull Map<NpcOption<?, ?>, Object> options)
    {
        applyOptions(options, GLOBAL_UUID);
    }

    /**
     * Gets all options for a specific UUID.
     *
     * @param uuid the UUID to get options for
     * @return a map of options for the specified UUID, or an empty map if none exist
     * @throws NullPointerException if uuid is null
     */
    public @NotNull Map<NpcOption<?, ?>, Object> getOptions(@NotNull UUID uuid)
    {
        return options.getOrDefault(uuid, new HashMap<>());
    }

    /**
     * Gets all options associated with the global UUID.
     *
     * @return a map of global options, or an empty map if none exist
     */
    public @NotNull Map<NpcOption<?, ?>, Object> getGlobalOptions()
    {
        return getOptions(GLOBAL_UUID);
    }

    /**
     * Gets all options for all UUIDs.
     *
     * @return a map of UUIDs to their respective option maps
     */
    public @NotNull Map<UUID, Map<NpcOption<?, ?>, Object>> getOptions()
    {
        return options;
    }

    /**
     * @throws UnsupportedOperationException always, as this inventory is not used.
     */
    @Override
    public @NotNull Inventory getInventory()
    {
        throw new UnsupportedOperationException("This inventory is not used!");
    }
}
