package de.eisi05.npc.api.objects;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.interfaces.NpcClickAction;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.manager.TeamManager;
import de.eisi05.npc.api.utils.CustomNameTag;
import de.eisi05.npc.api.utils.ObjectSaver;
import de.eisi05.npc.api.utils.Var;
import de.eisi05.npc.api.wrapper.packets.AnimatePacket;
import de.eisi05.npc.api.wrapper.packets.SetEntityDataPacket;
import de.eisi05.npc.api.wrapper.packets.SetPlayerTeamPacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Represents a Non-Player Character (NPC) with location, appearance, options, and interaction logic.
 */
public class NPC extends NpcHolder
{
    final List<Integer> toDeleteEntities = new ArrayList<>();
    private final ServerPlayer serverPlayer;
    private final List<UUID> viewers = new ArrayList<>();
    private final Map<NpcOption<?, ?>, Object> options;
    private final CustomNameTag nameTag;
    private Component name;
    private Location location;
    private NpcClickAction clickEvent;
    private Instant createdAt = Instant.now();
    private final Path npcPath;

    /**
     * Creates an NPC at the specified location with a random UUID and default name.
     * The default name is an empty component.
     *
     * @param location the location to spawn the NPC. Must not be null.
     */
    public NPC(@NotNull Location location)
    {
        this(location, UUID.randomUUID());
    }

    /**
     * Creates an NPC at the specified location with a random UUID and given name.
     *
     * @param location the location to spawn the NPC. Must not be null.
     * @param name     the display name of the NPC. Must not be null.
     */
    public NPC(@NotNull Location location, @NotNull Component name)
    {
        this(location, UUID.randomUUID(), name);
    }

    /**
     * Creates an NPC at the specified location with the given UUID and default name.
     * The default name is an empty component.
     *
     * @param location the location to spawn the NPC. Must not be null.
     * @param uuid     the UUID of the NPC. Must not be null.
     */
    public NPC(@NotNull Location location, @NotNull UUID uuid)
    {
        this(location, uuid, Component.empty());
    }

    /**
     * Creates an NPC at the specified location with the given UUID and name.
     * This is the primary constructor that initializes the NPC's core properties.
     *
     * @param location the location to spawn the NPC. Must not be null.
     * @param uuid     the UUID of the NPC. Must not be null.
     * @param name     the display name of the NPC. Must not be null.
     */
    public NPC(@NotNull Location location, @NotNull UUID uuid, @NotNull Component name)
    {
        this.name = name;
        this.location = location;

        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        GameProfile profile = new GameProfile(uuid, "NPC" + uuid.toString().substring(0, 13));

        this.serverPlayer = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        serverPlayer.absSnapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());

        npcPath = NpcApi.plugin.getDataFolder().toPath().resolve("NPC").resolve(uuid + ".npc");

        serverPlayer.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), serverPlayer,
                CommonListenerCookie.createInitial(profile, true));

        this.options = new HashMap<>();
        for(NpcOption<?, ?> value : NpcOption.values())
            setOption(value, Var.unsafeCast(value.getDefaultValue()));

        Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, ((CraftWorld) location.getWorld()).getHandle());
        display.absSnapTo(location.getX(), location.getY() + 2, location.getZ());

        nameTag = new CustomNameTag(display);
        serverPlayer.passengers = ImmutableList.of((Display.TextDisplay) nameTag.getDisplay());

        NpcManager.addNPC(this);
    }

    /**
     * Private constructor used for creating a copy of an NPC.
     *
     * @param location   The new location for the NPC. Must not be null.
     * @param name       The name for the NPC. Must not be null.
     * @param options    The options map for the NPC. Must not be null.
     * @param clickEvent The click event for the NPC. Can be null.
     */
    private NPC(@NotNull Location location, @NotNull Component name, @NotNull Map<NpcOption<?, ?>, Object> options,
            @Nullable NpcClickAction clickEvent)
    {
        this(location, UUID.randomUUID(), name);
        this.options.putAll(options);
        this.clickEvent = clickEvent;
    }

    /**
     * Creates a copy of this NPC at a new location.
     * The copied NPC will have a new UUID but will retain the original NPC's name, options, and click event.
     *
     * @param newLocation the location for the copied NPC. Must not be null.
     * @return the new NPC instance. Will not be null.
     */
    public @NotNull NPC copy(@NotNull Location newLocation)
    {
        return new NPC(newLocation, name, new HashMap<>(options), clickEvent == null ? null : clickEvent.copy());
    }

    /**
     * Checks if this NPC has been saved to a file.
     *
     * @return {@code true} if the NPC's data file exists, {@code false} otherwise.
     */
    public boolean isSaved()
    {
        return Files.exists(npcPath);
    }

    /**
     * Saves the NPC's data to a file.
     * This method serializes the NPC's current state and writes it to a .npc file.
     *
     * @throws IOException if an I/O error occurs during saving.
     */
    @Override
    public void save() throws IOException
    {
        npcPath.toFile().getParentFile().mkdirs();
        new ObjectSaver(npcPath.toFile()).write(SerializedNPC.serializedNPC(this), false);
        super.save();
    }

    /**
     * Gets the underlying server player representation for this NPC.
     *
     * @return the {@link ServerPlayer} instance for this NPC. Will not be null.
     */
    public @NotNull Object getServerPlayer()
    {
        return serverPlayer;
    }

    /**
     * Gets the click action associated with this NPC.
     *
     * @return the {@link NpcClickAction} for this NPC, or {@code null} if no action is set.
     */
    public @Nullable NpcClickAction getClickEvent()
    {
        return clickEvent;
    }

    /**
     * Sets the click action for this NPC.
     *
     * @param event the {@link NpcClickAction} to set, or {@code null} to remove the current action.
     * @return this NPC instance for method chaining. Will not be null.
     */
    public @NotNull NPC setClickEvent(@Nullable NpcClickAction event)
    {
        this.clickEvent = event;
        return this;
    }

    /**
     * Checks if the NPC is currently enabled.
     * An enabled NPC is visible and interactable (unless overridden by player permissions).
     *
     * @return {@code true} if the NPC is enabled, {@code false} otherwise.
     */
    public boolean isEnabled()
    {
        return getOption(NpcOption.ENABLED);
    }

    /**
     * Sets the enabled state of the NPC.
     * Changing this state will trigger a reload of the NPC for all viewers.
     *
     * @param enabled {@code true} to enable the NPC, {@code false} to disable it.
     */
    public void setEnabled(boolean enabled)
    {
        setOption(NpcOption.ENABLED, enabled);
        reload();
    }

    /**
     * Sets a specific option for this NPC.
     *
     * @param option the {@link NpcOption} to set. Must not be null.
     * @param value  the value for the option. If {@code null}, the option will be removed (reverting to default).
     * @param <T>    the type of the option's value.
     */
    public <T> void setOption(@NotNull NpcOption<T, ?> option, @Nullable T value)
    {
        if(value == null)
            options.remove(option);
        else
            options.put(option, value);
    }

    /**
     * Gets the value of a specific option for this NPC.
     * If the option has not been explicitly set, its default value will be returned.
     *
     * @param option the {@link NpcOption} to get. Must not be null.
     * @param <T>    the type of the option's value.
     * @return the value of the option. Will not be null (guaranteed by NpcOption default values).
     */
    @SuppressWarnings("unchecked")
    public <T> @NotNull T getOption(@NotNull NpcOption<T, ?> option)
    {
        return (T) options.getOrDefault(option, option.getDefaultValue());
    }

    /**
     * Plays an animation for this NPC, visible to the specified player.
     *
     * @param player    the player who will see the animation. Must not be null.
     * @param animation the {@link AnimatePacket.Animation} to play. Must not be null.
     */
    public void playAnimation(@NotNull Player player, @NotNull AnimatePacket.Animation animation)
    {
        ((CraftPlayer) player).getHandle().connection.send((Packet<?>) AnimatePacket.create(serverPlayer, animation));
    }

    /**
     * Reloads the NPC for all current viewers.
     * This typically involves hiding and then re-showing the NPC to apply any changes.
     */
    public void reload()
    {
        final List<UUID> viewers = new ArrayList<>(this.viewers);
        hideNpcFromAllPlayers();
        TeamManager.clear(serverPlayer.getGameProfile().getName());
        viewers.stream().filter(uuid -> Bukkit.getPlayer(uuid) != null).forEach(uuid -> showNPCToPlayer(Bukkit.getPlayer(uuid)));
    }

    /**
     * Gets the current location of the NPC.
     *
     * @return the {@link Location} of the NPC. Will not be null.
     */
    public @NotNull Location getLocation()
    {
        return location;
    }

    /**
     * Sets the location of the NPC.
     * This will also update the underlying server player's position.
     *
     * @param location the new {@link Location} for the NPC. Must not be null.
     */
    public void setLocation(@NotNull Location location)
    {
        this.location = location;
        this.serverPlayer.snapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    /**
     * Gets the unique identifier (UUID) of this NPC.
     *
     * @return the {@link UUID} of the NPC. Will not be null.
     */
    public @NotNull UUID getUUID()
    {
        return serverPlayer.getUUID();
    }

    /**
     * Gets the display name of this NPC.
     *
     * @return the {@link Component} representing the NPC's name. Will not be null.
     */
    public @NotNull Component getName()
    {
        return name;
    }

    /**
     * Sets the display name of this NPC.
     * This also updates the name for the underlying server player and its list name.
     *
     * @param name the new {@link Component} name for the NPC. Must not be null.
     */
    public void setName(@NotNull Component name)
    {
        this.name = name;
        serverPlayer.listName = CraftChatMessage.fromJSON(JSONComponentSerializer.json().serialize(name));

        viewers.stream().filter(uuid -> Bukkit.getPlayer(uuid) != null).forEach(
                uuid -> ((CraftPlayer) Bukkit.getPlayer(uuid)).getHandle().connection.send(
                        ((Packet<?>) SetEntityDataPacket.create(((Display.TextDisplay) nameTag.getDisplay()).getId(), (SynchedEntityData) nameTag.applyData(name)))));
    }

    /**
     * Gets the timestamp when this NPC was created.
     *
     * @return the {@link Instant} of creation. Will not be null.
     */
    public Instant getCreatedAt()
    {
        return createdAt;
    }

    /**
     * Makes the NPC visible to all currently online players.
     * This respects the NPC's enabled state and player permissions.
     */
    public void showNpcToAllPlayers()
    {
        Bukkit.getOnlinePlayers().forEach(this::showNPCToPlayer);
    }

    /**
     * Makes the NPC visible to a specific player.
     * If the NPC is disabled and the player is not an operator, the NPC will not be shown.
     * This method handles sending all necessary packets to display the NPC correctly.
     *
     * @param player the player to show the NPC to. Must not be null.
     */
    public void showNPCToPlayer(@NotNull Player player)
    {
        if(!getOption(NpcOption.ENABLED) && !player.isOp())
            return;

        if(!player.getWorld().getName().equals(serverPlayer.getBukkitEntity().getWorld().getName()))
        {
            hideNpcFromPlayer(player);
            return;
        }

        if(!viewers.contains(player.getUniqueId()))
            viewers.add(player.getUniqueId());

        List<Packet<?>> packets = new ArrayList<>();

        packets.add(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(serverPlayer, true));
        packets.add(serverPlayer.getAddEntityPacket(new ServerEntity(serverPlayer.level(), serverPlayer, 0, false, packet ->
        {
        }, (packet, uuids) ->
        {
        }, Set.of())));

        boolean modified = TeamManager.exists(player, serverPlayer.getGameProfile().getName());
        PlayerTeam wrappedPlayerTeam = (PlayerTeam) TeamManager.create(player, serverPlayer.getGameProfile().getName());
        wrappedPlayerTeam.setNameTagVisibility(Team.Visibility.NEVER);

        packets.add((Packet<?>) SetPlayerTeamPacket.createAddOrModifyPacket(wrappedPlayerTeam, !modified));
        packets.add((Packet<?>) SetPlayerTeamPacket.createPlayerPacket(wrappedPlayerTeam, serverPlayer.getGameProfile().getName(),
                ClientboundSetPlayerTeamPacket.Action.ADD));

        packets.add(new ClientboundRotateHeadPacket(serverPlayer, (byte) ((location.getYaw() % 360) * 256 / 360)));
        packets.add(new ClientboundMoveEntityPacket.Rot(serverPlayer.getId(), (byte) location.getYaw(), (byte) location.getPitch(),
                serverPlayer.onGround));

        if(!getOption(NpcOption.HIDE_NAMETAG))
        {
            packets.add(((Display.TextDisplay) nameTag.getDisplay()).getAddEntityPacket(
                    new ServerEntity(serverPlayer.level(), (Display.TextDisplay) nameTag.getDisplay(), 0, false, packet ->
                    {
                    }, (packet, uuids) ->
                    {
                    }, Set.of())));

            packets.add((Packet<?>) SetEntityDataPacket.create(((Display.TextDisplay) nameTag.getDisplay()).getId(), (SynchedEntityData) nameTag.applyData(name)));

            packets.add(new ClientboundSetPassengersPacket(serverPlayer));
        }

        Arrays.stream(NpcOption.values()).filter(npcOption -> !npcOption.equals(NpcOption.ENABLED))
                .forEach(npcOption -> npcOption.getPacket(getOption(npcOption), this, player).map(o -> (Packet<?>) o).ifPresent(packets::add));

        NpcOption.ENABLED.getPacket(isEnabled(), this, player).map(o -> (Packet<?>) o).ifPresent(packets::add);

        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
        packets.forEach(connection::send);
    }

    /**
     * Hides the NPC from all currently online players.
     */
    public void hideNpcFromAllPlayers()
    {
        Bukkit.getOnlinePlayers().forEach(this::hideNpcFromPlayer);
    }

    /**
     * Hides the NPC from a specific player.
     * This method sends packets to remove the NPC and its associated entities from the player's view.
     *
     * @param player the player to hide the NPC from. Must not be null.
     */
    public void hideNpcFromPlayer(@NotNull Player player)
    {
        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
        connection.send(new ClientboundRemoveEntitiesPacket(serverPlayer.getId(), ((Display.TextDisplay) nameTag.getDisplay()).getId()));

        if(TeamManager.exists(player, serverPlayer.getGameProfile().getName()))
        {
            PlayerTeam team = (PlayerTeam) TeamManager.create(player, serverPlayer.getGameProfile().getName());
            connection.send((Packet<?>) SetPlayerTeamPacket.createPlayerPacket(team, serverPlayer.getGameProfile().getName(),
                    ClientboundSetPlayerTeamPacket.Action.REMOVE));
            connection.send((Packet<?>) SetPlayerTeamPacket.createRemovePacket(team));
        }

        toDeleteEntities.forEach(integer -> connection.send(new ClientboundRemoveEntitiesPacket(integer)));

        connection.send(new ClientboundPlayerInfoRemovePacket(List.of(getUUID())));

        viewers.remove(player.getUniqueId());
    }

    /**
     * Deletes the NPC.
     * This hides the NPC from all players, removes it from the NPC manager, and deletes its saved data file.
     */
    public void delete() throws IOException
    {
        hideNpcFromAllPlayers();
        NpcManager.removeNPC(this);

        npcPath.toFile().getParentFile().mkdirs();
        Files.deleteIfExists(npcPath);
    }

    /**
     * Makes the NPC look at a specific player.
     * This calculates the required yaw and pitch and sends update packets to the viewing player.
     *
     * @param viewer the player the NPC should look at. Must not be null.
     */
    public void lookAtPlayer(@NotNull Player viewer)
    {
        Location npcLoc = serverPlayer.getBukkitEntity().getLocation();
        Location playerLoc = viewer.getLocation();

        if(npcLoc.getWorld() != playerLoc.getWorld())
            return;

        double dx = playerLoc.getX() - npcLoc.getX();
        double dy = ((playerLoc.getY() + viewer.getEyeHeight())) -
                ((npcLoc.getY() + serverPlayer.getBukkitEntity().getEyeHeight() * getOption(NpcOption.SCALE)));
        double dz = playerLoc.getZ() - npcLoc.getZ();

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, distanceXZ));

        byte yawByte = (byte) (yaw * 256 / 360);
        byte pitchByte = (byte) (pitch * 256 / 360);

        ServerGamePacketListenerImpl connection = ((CraftPlayer) viewer).getHandle().connection;

        connection.send(new ClientboundRotateHeadPacket(serverPlayer, yawByte));
        connection.send(new ClientboundMoveEntityPacket.Rot(serverPlayer.getId(), yawByte, pitchByte, serverPlayer.onGround()));
    }

    public CustomNameTag getNameTag()
    {
        return nameTag;
    }

    /**
     * A serializable representation of an NPC, used for saving and loading NPC data.
     * This record stores all essential properties of an NPC that need to be persisted.
     *
     * @param world      The UUID of the world where the NPC is located.
     * @param x          The x-coordinate of the NPC's location.
     * @param y          The y-coordinate of the NPC's location.
     * @param z          The z-coordinate of the NPC's location.
     * @param yaw        The yaw (horizontal rotation) of the NPC.
     * @param pitch      The pitch (vertical rotation) of the NPC.
     * @param id         The unique identifier (UUID) of the NPC.
     * @param name       The serialized representation of the NPC's display name.
     * @param options    A map of NPC options, where keys are option paths (strings) and values are serializable option values.
     * @param clickEvent The click action associated with the NPC. Can be null.
     * @param createdAt  The timestamp when the NPC was originally created.
     */
    public record SerializedNPC(@NotNull UUID world, double x, double y, double z, float yaw, float pitch, @NotNull UUID id,
                                @NotNull String name, @NotNull Map<String, ? extends Serializable> options,
                                @Nullable NpcClickAction clickEvent, @NotNull Instant createdAt) implements Serializable
    {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Creates a {@link SerializedNPC} instance from an existing {@link NPC} object.
         *
         * @param npc The NPC to serialize. Must not be null.
         * @return A new {@link SerializedNPC} instance representing the given NPC. Will not be null.
         */
        public static @NotNull SerializedNPC serializedNPC(@NotNull NPC npc)
        {
            Map<String, ? extends Serializable> options = new HashMap<>();
            npc.options.forEach((key, value) -> options.put(key.getPath(), Var.unsafeCast(key.serialize(npc.getOption(key)))));

            return new SerializedNPC(npc.getLocation().getWorld().getUID(), npc.getLocation().getX(), npc.getLocation().getY(),
                    npc.getLocation().getZ(), npc.getLocation().getYaw(), npc.getLocation().getPitch(), npc.getUUID(),
                    JSONComponentSerializer.json().serialize(npc.getName()), options, npc.clickEvent, npc.createdAt);
        }

        /**
         * Deserializes this {@link SerializedNPC} object back into a fully functional {@link NPC} instance.
         *
         * @param <T> The type of the NpcOption value.
         * @param <S> The serializable type of the NpcOption value.
         * @return A new {@link NPC} instance reconstructed from the serialized data. Will not be null.
         */
        @SuppressWarnings("unchecked")
        public <T, S extends Serializable> @NotNull NPC deserializedNPC()
        {
            NPC npc = new NPC(new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch), id,
                    JSONComponentSerializer.json().deserialize(name)).setClickEvent(
                    clickEvent == null ? clickEvent : clickEvent.initialize());
            options.forEach((string, serializable) -> NpcOption.getOption(string)
                    .ifPresent(npcOption -> npc.setOption((NpcOption<T, S>) npcOption, (T) npcOption.deserialize(Var.unsafeCast(serializable)))));
            npc.createdAt = createdAt == null ? Instant.now() : createdAt;
            return npc;
        }
    }
}
