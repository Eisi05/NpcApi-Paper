package de.eisi05.npc.api.objects;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.events.NpcHideEvent;
import de.eisi05.npc.api.events.NpcPostShowEvent;
import de.eisi05.npc.api.events.NpcPreShowEvent;
import de.eisi05.npc.api.events.NpcStartWalkingEvent;
import de.eisi05.npc.api.interfaces.NpcClickAction;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.manager.TeamManager;
import de.eisi05.npc.api.scheduler.PathTask;
import de.eisi05.npc.api.utils.ObjectSaver;
import de.eisi05.npc.api.utils.Reflections;
import de.eisi05.npc.api.utils.Var;
import de.eisi05.npc.api.utils.Versions;
import de.eisi05.npc.api.wrapper.packets.AnimatePacket;
import de.eisi05.npc.api.wrapper.packets.SetEntityDataPacket;
import de.eisi05.npc.api.wrapper.packets.SetPlayerTeamPacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Represents a Non-Player Character (NPC) with location, appearance, options, and interaction logic.
 */
public class NPC extends NpcHolder
{
    public transient final Map<UUID, String> nameCache = new HashMap<>();
    final Map<String, Integer> toDeleteEntities = new HashMap<>();
    private final List<UUID> viewers = new ArrayList<>();
    private final CustomNameTag nameTag;
    private final Path npcPath;
    private final Map<UUID, PathTask> pathTasks = new HashMap<>();
    public transient String data;
    ServerPlayer serverPlayer;
    Entity entity;
    private Location location;
    private NpcClickAction clickEvent;
    private Instant createdAt = Instant.now();

    /**
     * Creates an NPC at the specified location with a random UUID and default name. The default name is an empty component.
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
    public NPC(@NotNull Location location, @NotNull NpcName name)
    {
        this(location, UUID.randomUUID(), name);
    }

    /**
     * Creates an NPC at the specified location with the given UUID and default name. The default name is an empty component.
     *
     * @param location the location to spawn the NPC. Must not be null.
     * @param uuid     the UUID of the NPC. Must not be null.
     */
    public NPC(@NotNull Location location, @NotNull UUID uuid)
    {
        this(location, uuid, NpcName.empty());
    }

    /**
     * Creates an NPC at the specified location with the given UUID and name. This is the primary constructor that initializes the NPC's core properties.
     *
     * @param location the location to spawn the NPC. Must not be null.
     * @param uuid     the UUID of the NPC. Must not be null.
     * @param name     the display name of the NPC. Must not be null.
     */
    public NPC(@NotNull Location location, @NotNull UUID uuid, @NotNull NpcName name)
    {
        this.name = name;
        this.location = location;

        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        GameProfile profile = new GameProfile(uuid, "NPC" + uuid.toString().substring(0, 13));

        this.entity = this.serverPlayer = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Var.moveEntity(serverPlayer, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());

        npcPath = NpcApi.plugin.getDataFolder().toPath().resolve("NPC").resolve(uuid + ".npc");

        serverPlayer.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), serverPlayer,
                CommonListenerCookie.createInitial(profile, true));

        for(NpcOption<?, ?> value : NpcOption.values())
            setOption(value, Var.unsafeCast(value.getDefaultValue()));

        Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, ((CraftWorld) location.getWorld()).getHandle());
        Var.moveEntity(display, location.getX(), location.getY() + 2, location.getZ(), 0f, 0f);

        nameTag = new CustomNameTag(display);
        serverPlayer.listName = CraftChatMessage.fromJSON(JSONComponentSerializer.json().serialize(name.getName()));

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
    private NPC(@NotNull Location location, @NotNull NpcName name, @NotNull Map<UUID, Map<NpcOption<?, ?>, Object>> options,
                @Nullable NpcClickAction clickEvent)
    {
        this(location, UUID.randomUUID(), name);
        this.options.putAll(options);
        this.clickEvent = clickEvent;
    }

    /**
     * Creates a copy of this NPC at a new location. The copied NPC will have a new UUID but will retain the original NPC's name, options, and click event.
     *
     * @param newLocation the location for the copied NPC. Must not be null.
     * @return the new NPC instance. Will not be null.
     */
    public @NotNull NPC copy(@NotNull Location newLocation)
    {
        return new NPC(newLocation, name.copy(), new HashMap<>(options), clickEvent == null ? null : clickEvent.copy());
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
     * Saves the NPC's data to a file. This method serializes the NPC's current state and writes it to a .npc file.
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
     * Gets the underlying entity representation for this NPC.
     *
     * @return the {@link Entity} instance for this NPC. Will not be null.
     */
    public @NotNull Object getEntity()
    {
        return entity;
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
     * <p>
     * ⚠ <b>Serialization notice:</b><br> The provided {@link NpcClickAction} is serialized when the NPC is saved. Avoid using lambdas or method references that
     * capture non-serializable objects (e.g. plugin instances, command classes, or {@code this}), as this will cause a
     * {@link java.io.NotSerializableException}.
     * <p>
     * Recommended approaches are:
     * <ul>
     *   <li>Stateless lambdas that capture nothing</li>
     *   <li>Method references to static methods</li>
     *   <li>Explicit {@link NpcClickAction} implementation classes</li>
     * </ul>
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
     * Checks if the NPC is currently enabled. An enabled NPC is visible and interactable (unless overridden by player permissions).
     *
     * @return {@code true} if the NPC is enabled, {@code false} otherwise.
     */
    public boolean isEnabled()
    {
        return getOption(NpcOption.ENABLED, GLOBAL_UUID);
    }

    /**
     * Sets the enabled state of the NPC. Changing this state will trigger a reload of the NPC for all viewers.
     *
     * @param enabled {@code true} to enable the NPC, {@code false} to disable it.
     */
    public void setEnabled(boolean enabled)
    {
        setOption(NpcOption.ENABLED, enabled);
        reload();
    }

    /**
     * Checks if this NPC is marked as editable through the {@code NpcPlugin}.
     * <p>
     * The default state is {@code false}.
     *
     * @return {@code true} if the NPC is editable, {@code false} otherwise
     */
    public boolean isEditable()
    {
        return getOption(NpcOption.EDITABLE, GLOBAL_UUID);
    }

    /**
     * Sets whether this NPC can be edited through the {@code NpcPlugin}.
     * <p>
     * By default, an NPC is <b>not</b> editable ({@code false}).
     *
     * @param editable {@code true} if the NPC should be editable, {@code false} otherwise
     */
    public void setEditable(boolean editable)
    {
        setOption(NpcOption.EDITABLE, editable);
    }

    /**
     * Plays an animation for this NPC, visible to the specified player.
     *
     * @param player    the player who will see the animation. Must not be null.
     * @param animation the {@link AnimatePacket.Animation} to play. Must not be null.
     */
    public void playAnimation(@NotNull Player player, @NotNull AnimatePacket.Animation animation)
    {
        Packet<?> packet = (Packet<?>) AnimatePacket.create(entity, animation);

        if(packet == null)
            return;

        ((CraftPlayer) player).getHandle().connection.send(packet);
    }

    /**
     * Reloads the NPC for all current viewers. This typically involves hiding and then re-showing the NPC to apply any changes.
     */
    public void reload()
    {
        final List<UUID> viewers = new ArrayList<>(this.viewers);
        hideNpcFromAllPlayers();
        TeamManager.clear(getGameProfileName());
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
     * Sets the location of the NPC. This will also update the underlying server player's position.
     *
     * @param location the new {@link Location} for the NPC. Must not be null.
     */
    public void setLocation(@NotNull Location location)
    {
        this.location = location;

        if(serverPlayer == null)
            return;

        Var.moveEntity(serverPlayer, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        if(!entity.equals(serverPlayer))
            Var.moveEntity(entity, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    /**
     * Gets the unique identifier (UUID) of this NPC.
     *
     * @return the {@link UUID} of the NPC. Can be null if NPC is deleted.
     */
    public @Nullable UUID getUUID()
    {
        if(serverPlayer != null)
            return serverPlayer.getUUID();
        return null;
    }

    /**
     * Gets the display name of this NPC.
     *
     * @return the {@link NpcName} representing the NPC's name. Will not be null.
     */
    public @NotNull NpcName getNpcName()
    {
        return name;
    }

    /**
     * Gets the display name of this NPC.
     *
     * @return the {@link Component} representing the NPC's name. Will not be null.
     */
    public @NotNull Component getName(@NotNull Player player)
    {
        return name.getName(player);
    }

    /**
     * Gets the display name of this NPC.
     *
     * @return the {@link Component} representing the NPC's name. Will not be null.
     */
    public @NotNull Component getName()
    {
        return name.getName();
    }

    /**
     * Sets the display name of this NPC. This also updates the name for the underlying server player and its list name.
     *
     * @param name the new {@link Component} name for the NPC. Must not be null.
     */
    public void setName(@NotNull NpcName name)
    {
        this.name = name;
        serverPlayer.listName = CraftChatMessage.fromJSON(JSONComponentSerializer.json().serialize(name.getName()));

        viewers.stream().filter(uuid -> Bukkit.getPlayer(uuid) != null).forEach(uuid -> updateName(Bukkit.getPlayer(uuid)));
    }

    public @NotNull String getGameProfileName()
    {
        if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_9))
            return (String) Reflections.invokeMethod(serverPlayer.getGameProfile(), "getName").get();
        return serverPlayer.getGameProfile().name();
    }

    /**
     * Adds custom data to this NPC. This data can be retrieved later using the {@link #getCustomData(Serializable)} method.
     *
     * @param key   the key for the custom data. Must not be null.
     * @param value the value for the custom data. Must not be null.
     */
    public <K extends Serializable, V extends Serializable> void addCustomData(@NotNull K key, @NotNull V value)
    {
        HashMap<Serializable, Serializable> map = getOption(NpcOption.CUSTOM_DATA, GLOBAL_UUID);
        map.put(key, value);
        setOption(NpcOption.CUSTOM_DATA, map);
    }

    /**
     * Removes custom data from this NPC. This data can be retrieved later using the {@link #getCustomData(Serializable)} method.
     *
     * @param key the key for the custom data. Must not be null.
     * @return the value of the custom data, or null if the key does not exist.
     */
    @SuppressWarnings("unchecked")
    public <K extends Serializable, T extends Serializable> @Nullable T removeCustomData(@NotNull K key)
    {
        HashMap<Serializable, Serializable> map = getOption(NpcOption.CUSTOM_DATA, GLOBAL_UUID);
        T value = (T) map.remove(key);
        setOption(NpcOption.CUSTOM_DATA, map);
        return value;
    }

    /**
     * Retrieves custom data from this NPC.
     *
     * @param key the key for the custom data. Must not be null.
     * @return the value of the custom data, or null if the key does not exist.
     */
    @SuppressWarnings("unchecked")
    public <K extends Serializable, T extends Serializable> @Nullable T getCustomData(@NotNull K key)
    {
        HashMap<Serializable, Serializable> map = getOption(NpcOption.CUSTOM_DATA, GLOBAL_UUID);
        return (T) map.get(key);
    }

    /**
     * Retrieves the custom data associated with this NPC. The custom data is stored as a map of serializable key-value pairs.
     *
     * @return A {@code HashMap} containing the custom data, or {@code null} if no custom data is set
     * @see NpcOption#CUSTOM_DATA
     */
    public @Nullable HashMap<Serializable, Serializable> getCustomData()
    {
        return getOption(NpcOption.CUSTOM_DATA, GLOBAL_UUID);
    }

    /**
     * Updates the display name of the given player on the server.
     * <p>
     * Sends a packet to the player to modify their name tag, taking into account the server version and whether custom naming is enabled.
     *
     * @param player the player whose name will be updated; must not be null
     */
    public void updateName(@NotNull Player player)
    {
        ((CraftPlayer) player).getHandle().connection.send(
                ((Packet<?>) SetEntityDataPacket.create(((Display.TextDisplay) nameTag.getDisplay()).getId(),
                        (SynchedEntityData) nameTag.applyData(
                                isEnabled() ? name.getName(player) : NpcApi.DISABLED_MESSAGE_PROVIDER.apply(player)
                                        .appendNewline().append(name.getName(player))))));
    }

    /**
     * Updates the display name for all players in the viewer list.
     * <p>
     * Sends a packet to the player to modify their name tag, taking into account the server version and whether custom naming is enabled.
     */
    public void updateNameForAll()
    {
        for(UUID uuid : viewers)
        {
            Player player = Bukkit.getPlayer(uuid);
            if(player == null)
                continue;

            String name = PlainTextComponentSerializer.plainText().serialize(getName(player));
            if(nameCache.getOrDefault(uuid, "").equals(name))
                continue;

            updateName(player);
            nameCache.put(uuid, name);
        }
    }

    /**
     * Updates the NPC's skin for the specified players by hiding and then showing the NPC. This forces a skin refresh for each player in the provided array.
     *
     * @param players the players who should see the updated skin. If no players are provided, no action is taken.
     * @throws IllegalArgumentException if the players array is null
     * @see #hideNpcFromPlayer(Player)
     * @see #showNPCToPlayer(Player)
     */
    public void updateSkin(@NotNull Player... players)
    {
        for(Player player : players)
            hideNpcFromPlayer(player);

        for(Player player : players)
            showNPCToPlayer(player);
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

    @ApiStatus.Internal
    public List<UUID> getViewers()
    {
        return viewers;
    }

    /**
     * Makes the NPC visible to all currently online players. This respects the NPC's enabled state and player permissions.
     */
    public void showNpcToAllPlayers()
    {
        Bukkit.getOnlinePlayers().forEach(this::showNPCToPlayer);
    }

    /**
     * Makes the NPC visible to a specific player. If the NPC is disabled and the player is not an operator, the NPC will not be shown. This method handles
     * sending all necessary packets to display the NPC correctly.
     *
     * @param player the player to show the NPC to. Must not be null.
     */
    public void showNPCToPlayer(@NotNull Player player)
    {
        if(!getOption(NpcOption.ENABLED, GLOBAL_UUID) && !player.isPermissionSet("npc.admin") && !player.isOp())
            return;

        if(!player.getWorld().getUID().equals(serverPlayer.getBukkitEntity().getWorld().getUID()))
        {
            hideNpcFromPlayer(player);
            return;
        }

        NpcPreShowEvent npcPreShowEvent = new NpcPreShowEvent(player, this, viewers.contains(player.getUniqueId()));
        Bukkit.getPluginManager().callEvent(npcPreShowEvent);
        if(npcPreShowEvent.isCancelled())
            return;

        if(!viewers.contains(player.getUniqueId()))
            viewers.add(player.getUniqueId());

        List<Packet<?>> packets = new ArrayList<>();

        Arrays.stream(NpcOption.values()).filter(NpcOption::loadBefore)
                .forEach(npcOption -> npcOption.getPacket(getOption(npcOption), this, player).ifPresent(o -> packets.add((Packet<?>) o)));

        if(!name.isStatic() && getOption(NpcOption.SHOW_TAB_LIST))
            setOption(NpcOption.SHOW_TAB_LIST, false);

        packets.add(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(serverPlayer, true));

        NpcOption.ENABLED.getPacket(isEnabled(), this, player).map(o -> (Packet<?>) o).ifPresent(packets::add);

        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
        packets.forEach(connection::send);

        Bukkit.getPluginManager().callEvent(new NpcPostShowEvent(player, this, npcPreShowEvent.wasViewer()));
    }

    /**
     * Hides the NPC from all currently online players.
     */
    public void hideNpcFromAllPlayers()
    {
        Bukkit.getOnlinePlayers().forEach(this::hideNpcFromPlayer);
    }

    /**
     * Hides the NPC from a specific player. This method sends packets to remove the NPC and its associated entities from the player's view.
     *
     * @param player the player to hide the NPC from. Must not be null.
     */
    public void hideNpcFromPlayer(@NotNull Player player)
    {
        if(!viewers.contains(player.getUniqueId()))
            return;

        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
        connection.send(new ClientboundRemoveEntitiesPacket(serverPlayer.getId(), ((Display.TextDisplay) nameTag.getDisplay()).getId()));

        if(TeamManager.exists(player, getGameProfileName()))
        {
            PlayerTeam team = (PlayerTeam) TeamManager.create(player, getGameProfileName());
            connection.send((Packet<?>) SetPlayerTeamPacket.createPlayerPacket(team, getGameProfileName(), ClientboundSetPlayerTeamPacket.Action.REMOVE));
            connection.send((Packet<?>) SetPlayerTeamPacket.createRemovePacket(team));
            TeamManager.clear(player.getUniqueId(), getGameProfileName());
        }

        toDeleteEntities.values().forEach(integer -> connection.send(new ClientboundRemoveEntitiesPacket(integer)));

        connection.send(new ClientboundPlayerInfoRemovePacket(List.of(getUUID())));

        viewers.remove(player.getUniqueId());

        Bukkit.getPluginManager().callEvent(new NpcHideEvent(player, this));
    }

    /**
     * Deletes the NPC. This hides the NPC from all players, removes it from the NPC manager, and deletes its saved data file.
     */
    public void delete() throws IOException
    {
        if(serverPlayer == null)
            return;

        hideNpcFromAllPlayers();
        NpcManager.removeNPC(this);

        serverPlayer.remove(Entity.RemovalReason.DISCARDED);
        entity = serverPlayer = null;

        npcPath.toFile().getParentFile().mkdirs();
        Files.deleteIfExists(npcPath);
    }

    /**
     * Makes the NPC look at a specific player. This calculates the required yaw and pitch and sends update packets to the viewing player.
     *
     * @param viewer the player the NPC should look at. Must not be null.
     */
    public void lookAtPlayer(@NotNull Player viewer)
    {
        Location npcLoc = entity.getBukkitEntity().getLocation();
        Location playerLoc = viewer.getLocation();

        if(npcLoc.getWorld() != playerLoc.getWorld())
            return;

        double dx = playerLoc.getX() - npcLoc.getX();

        double eyeHeight = (entity.getBukkitEntity() instanceof LivingEntity le ? le.getEyeHeight() :
                entity.getBukkitEntity().getHeight()) - (getOption(NpcOption.POSE, viewer) == Pose.SITTING ? 0.625 : 0);

        double dy = (playerLoc.getY() + viewer.getEyeHeight()) - (npcLoc.getY() + (eyeHeight * getOption(NpcOption.SCALE, viewer)));
        double dz = playerLoc.getZ() - npcLoc.getZ();

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, distanceXZ));

        byte yawByte = (byte) (yaw * 256 / 360);
        byte pitchByte = (byte) (pitch * 256 / 360);

        ServerGamePacketListenerImpl connection = ((CraftPlayer) viewer).getHandle().connection;

        connection.send(new ClientboundRotateHeadPacket(entity, yawByte));
        connection.send(new ClientboundMoveEntityPacket.Rot(entity.getId(), yawByte, pitchByte, serverPlayer.onGround()));
    }

    /**
     * Moves the NPC along a precomputed {@link de.eisi05.npc.api.pathfinding.Path}, simulating walking, jumping, and gravity. The NPC's position and rotation
     * are updated each tick and sent to the specified player(s).
     *
     * @param path               The {@link de.eisi05.npc.api.pathfinding.Path} containing the ordered waypoints the NPC should follow.
     * @param walkSpeed          The walking speed of the NPC (clamped between 0.1 and 1).
     * @param changeRealLocation If true, the NPC's actual server-side location will be updated; otherwise only packets are sent.
     * @return The {@link BukkitTask} representing the movement task.
     */
    public @NotNull BukkitTask walkTo(@NotNull de.eisi05.npc.api.pathfinding.Path path, double walkSpeed, boolean changeRealLocation)
    {
        return walkTo(path, walkSpeed, changeRealLocation, null, (Player[]) null);
    }

    /**
     * Moves the NPC along a precomputed {@link de.eisi05.npc.api.pathfinding.Path}, simulating walking, jumping, and gravity. The NPC's position and rotation
     * are updated each tick and sent to the specified player(s).
     *
     * @param path               The {@link de.eisi05.npc.api.pathfinding.Path} containing the ordered waypoints the NPC should follow.
     * @param walkSpeed          The walking speed of the NPC (clamped between 0.1 and 1).
     * @param changeRealLocation If true, the NPC's actual server-side location will be updated; otherwise only packets are sent.
     * @param onEnd              A {@link Runnable} to be executed when the NPC reaches the end of the path.
     * @param viewers            The players who should see the NPC move. If null, updates all viewers in the `viewers` set.
     * @return The {@link BukkitTask} representing the movement task.
     */
    public @NotNull BukkitTask walkTo(@NotNull de.eisi05.npc.api.pathfinding.Path path, double walkSpeed,
                                      boolean changeRealLocation, @Nullable Consumer<WalkingResult> onEnd, @Nullable Player... viewers)
    {
        if(viewers != null)
        {
            for(Player player : viewers)
            {
                if(isWalking(player))
                    cancelWalking(player);
            }
        }

        final double speed = Math.max(Math.min(walkSpeed, 1), 0.1);

        NpcStartWalkingEvent event = new NpcStartWalkingEvent(this, path, speed, changeRealLocation);
        Bukkit.getPluginManager().callEvent(event);
        if(event.isCancelled())
            return null;

        PathTask pathTask = new PathTask.Builder(this, path)
                .speed(event.getWalkSpeed())
                .viewers(viewers)
                .updateRealLocation(event.isChangeRealLocation())
                .callback(onEnd).build();

        if(viewers != null)
        {
            for(Player player : viewers)
                pathTasks.put(player.getUniqueId(), pathTask);
        }

        return pathTask.runTaskTimer(NpcApi.plugin, 1L, 1L);
    }

    /**
     * Checks whether the NPC is currently walking.
     * <p>
     * The NPC is considered walking if a path task exists and has not yet finished.
     *
     * @return true if the NPC is still walking, false otherwise
     */
    public boolean isWalking(@NotNull Player viewer)
    {
        return pathTasks.containsKey(viewer.getUniqueId()) && !pathTasks.get(viewer.getUniqueId()).isFinished();
    }

    /**
     * Cancels the NPC's current walking task if one is active.
     * <p>
     * If the NPC is walking, the path task is canceled and cleared. If no walking task is active, this method has no effect.
     */
    public void cancelWalking(@NotNull Player viewer)
    {
        if(!pathTasks.containsKey(viewer.getUniqueId()))
            return;

        pathTasks.remove(viewer.getUniqueId()).cancel();
    }

    /**
     * Sends movement-related packets for the NPC's body to specific players.
     * <p>
     * If one or more {@link Player} instances are provided, the packet is sent only to those players. If {@code players} is {@code null}, the packet is sent to
     * all currently online players who are registered as viewers of the NPC.
     * <p>
     * If {@code moveEntityPacket} is {@code null}, no packets are sent.
     *
     * @param moveEntityPacket the movement packet to send, or {@code null} to send nothing
     * @param players          the target players to receive the packet, or {@code null} to send the packet to all online registered viewers
     */
    public void sendNpcBodyPackets(@Nullable ClientboundMoveEntityPacket moveEntityPacket, @Nullable Player... players)
    {
        if(players != null)
        {
            for(var player : players)
            {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
                if(moveEntityPacket != null)
                    serverPlayer.connection.send(moveEntityPacket);
            }
        }
        else
        {
            for(UUID uuid : viewers)
            {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                if(!offlinePlayer.isOnline())
                    continue;

                ServerPlayer serverPlayer = ((CraftPlayer) offlinePlayer.getPlayer()).getHandle();
                if(moveEntityPacket != null)
                    serverPlayer.connection.send(moveEntityPacket);
            }
        }
    }

    /**
     * Sends NPC movement and rotation packets to a specific player or all viewers.
     *
     * @param teleportEntityPacket The packet containing the NPC's teleport/move data. Must not be null.
     * @param rotateHeadPacket     The packet containing the NPC's head rotation data. Must not be null.
     * @param players              Players to send packets to. If null, packets are sent to all viewers.
     */
    public void sendNpcMovePackets(@Nullable ClientboundTeleportEntityPacket teleportEntityPacket,
                                   @Nullable ClientboundRotateHeadPacket rotateHeadPacket, @Nullable Player... players)
    {
        if(players != null)
        {
            for(Player player : players)
            {
                ServerPlayer serverPlayer1 = ((CraftPlayer) player).getHandle();
                if(teleportEntityPacket != null)
                    serverPlayer1.connection.send(teleportEntityPacket);
                if(rotateHeadPacket != null)
                    serverPlayer1.connection.send(rotateHeadPacket);
            }
        }
        else
        {
            for(UUID uuid : viewers)
            {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                if(!offlinePlayer.isOnline())
                    continue;

                ServerPlayer serverPlayer1 = ((CraftPlayer) offlinePlayer.getPlayer()).getHandle();
                if(teleportEntityPacket != null)
                    serverPlayer1.connection.send(teleportEntityPacket);
                if(rotateHeadPacket != null)
                    serverPlayer1.connection.send(rotateHeadPacket);
            }
        }
    }

    /**
     * Updates the real location of the NPC and refreshes visibility for viewers. * @param location The new target location.
     *
     * @param excludedPlayers Players who should NOT see the respawn/refresh.
     */
    public void changeRealLocation(Location location, @Nullable Player... excludedPlayers)
    {
        if(serverPlayer == null)
            return;

        setLocation(location);

        Set<UUID> excluded = excludedPlayers == null ? Collections.emptySet() :
                Arrays.stream(excludedPlayers).filter(Objects::nonNull).map(Player::getUniqueId).collect(Collectors.toSet());

        ClientboundTeleportEntityPacket teleport1 = new ClientboundTeleportEntityPacket(serverPlayer.getId(),
                new PositionMoveRotation(new Vec3(location.getX(), location.getY(), location.getZ()), new Vec3(0, 0, 0), location.getYaw(),
                        location.getPitch()), Set.of(), true);

        ClientboundTeleportEntityPacket teleport2 = entity.equals(serverPlayer) ? null : new ClientboundTeleportEntityPacket(entity.getId(),
                new PositionMoveRotation(new Vec3(location.getX(), location.getY(), location.getZ()), new Vec3(0, 0, 0), location.getYaw(),
                        location.getPitch()), Set.of(), true);

        for(UUID uuid : viewers)
        {
            Player viewer = Bukkit.getPlayer(uuid);
            if(viewer == null)
                continue;

            if(excluded.contains(viewer.getUniqueId()))
                continue;

            ((CraftPlayer) viewer).getHandle().connection.send(teleport1);

            if(teleport2 != null)
                ((CraftPlayer) viewer).getHandle().connection.send(teleport2);
        }
    }

    public CustomNameTag getNameTag()
    {
        return nameTag;
    }

    /**
     * A serializable representation of an NPC that can be stored and later restored. This class handles the conversion between in-memory {@link NPC} objects
     * and their persistent storage format.
     *
     * <p>The serialization includes:
     * <ul>
     *   <li>NPC's world and position (x, y, z, yaw, pitch)</li>
     *   <li>NPC's unique identifier and display name</li>
     *   <li>Click action handler (if any)</li>
     *   <li>All configured options and their values</li>
     *   <li>Creation timestamp</li>
     * </ul>
     *
     * <p>This class supports both legacy (pre-2.0.0) and current serialization formats
     * through the {@link #readResolve()} method for backward compatibility.
     *
     * @see SerializedNPC#serializedNPC(NPC)
     * @see #deserializedNPC()
     */
    public static class SerializedNPC implements Serializable
    {
        @Serial
        private static final long serialVersionUID = 1L;
        private final @NotNull UUID world;
        private final double x, y, z;
        private final float yaw, pitch;
        private final @NotNull UUID id;
        private final @NotNull Serializable name;
        private final @Nullable NpcClickAction clickEvent;
        private final @NotNull Instant createdAt;

        /**
         * Legacy options storage (pre-2.0.0).
         *
         * @deprecated Replaced by {@link #newOptions} in version 2.0.0
         */
        @Deprecated(since = "2.0.0")
        private @NotNull Map<String, ? extends Serializable> options;
        private @NotNull Map<String, HashMap<String, Serializable>> newOptions;

        /**
         * Creates a new serialized NPC instance (legacy constructor).
         *
         * @param world      The UUID of the world the NPC is in
         * @param x          The x-coordinate of the NPC
         * @param y          The y-coordinate of the NPC
         * @param z          The z-coordinate of the NPC
         * @param yaw        The yaw rotation of the NPC
         * @param pitch      The pitch rotation of the NPC
         * @param id         The unique identifier of the NPC
         * @param name       The serialized name of the NPC
         * @param options    The NPC's options in legacy format
         * @param clickEvent The click action handler, if any
         * @param createdAt  When the NPC was created
         * @deprecated Replaced by
         * {@link SerializedNPC#SerializedNPC(UUID, double, double, double, float, float, UUID, Serializable, NpcClickAction, Instant, Map)} in version 2.0.0
         */
        @Deprecated(since = "2.0.0")
        private SerializedNPC(@NotNull UUID world, double x, double y, double z, float yaw, float pitch, @NotNull UUID id, @NotNull Serializable name,
                              @NotNull Map<String, ? extends Serializable> options, @Nullable NpcClickAction clickEvent, @NotNull Instant createdAt)
        {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.id = id;
            this.name = name;
            this.options = options;
            this.clickEvent = clickEvent;
            this.createdAt = createdAt;
        }

        /**
         * Creates a new serialized NPC instance (current format).
         *
         * @param world      The UUID of the world the NPC is in
         * @param x          The x-coordinate of the NPC
         * @param y          The y-coordinate of the NPC
         * @param z          The z-coordinate of the NPC
         * @param yaw        The yaw rotation of the NPC
         * @param pitch      The pitch rotation of the NPC
         * @param id         The unique identifier of the NPC
         * @param name       The serialized name of the NPC
         * @param clickEvent The click action handler, if any
         * @param createdAt  When the NPC was created
         * @param options    The NPC's options in current format
         */
        private SerializedNPC(@NotNull UUID world, double x, double y, double z, float yaw, float pitch, @NotNull UUID id, @NotNull Serializable name,
                              @Nullable NpcClickAction clickEvent, @NotNull Instant createdAt, @NotNull Map<String, HashMap<String, Serializable>> options)
        {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.id = id;
            this.name = name;
            this.newOptions = options;
            this.clickEvent = clickEvent;
            this.createdAt = createdAt;
        }

        /**
         * Creates a {@link SerializedNPC} instance from an existing {@link NPC} object.
         *
         * @param npc The NPC to serialize. Must not be null.
         * @return A new {@link SerializedNPC} instance representing the given NPC. Will not be null.
         */
        public static @NotNull SerializedNPC serializedNPC(@NotNull NPC npc)
        {
            HashMap<String, HashMap<String, Serializable>> map = npc.options.entrySet()
                    .stream()
                    .collect(HashMap::new, (m, e) -> m.put(e.getKey().toString(), e.getValue()
                                    .entrySet()
                                    .stream()
                                    .collect(HashMap::new, (om, oe) -> om.put(oe.getKey().getPath(), oe.getKey().serialize(oe.getValue())), HashMap::putAll)),
                            HashMap::putAll);

            return new SerializedNPC(npc.getLocation().getWorld().getUID(), npc.getLocation().getX(), npc.getLocation().getY(),
                    npc.getLocation().getZ(), npc.getLocation().getYaw(), npc.getLocation().getPitch(), npc.getUUID(),
                    npc.getNpcName(), npc.clickEvent, npc.createdAt, map);
        }

        @Serial
        private Object readResolve() throws ObjectStreamException
        {
            NpcName fixedName;
            if(name instanceof NpcName sn)
                fixedName = sn;
            else if(name instanceof String oldName)
                fixedName = NpcName.of(JSONComponentSerializer.json().deserialize(oldName));
            else
                throw new IllegalStateException("Unexpected type for name field: " + name.getClass());

            Map<String, HashMap<String, Serializable>> fixedOptions = newOptions == null ? new HashMap<>() : newOptions;
            if(fixedOptions.isEmpty() && options != null)
                options.forEach((s, serializable) -> fixedOptions.computeIfAbsent(GLOBAL_UUID.toString(), s1 -> new HashMap<>()).put(s, serializable));

            return new SerializedNPC(world, x, y, z, yaw, pitch, id, fixedName, clickEvent, createdAt, fixedOptions);
        }

        /**
         * Deserializes this {@link SerializedNPC} object back into a fully functional {@link NPC} instance.
         *
         * @param <T> The type of the NpcOption value.
         * @param <S> The serializable type of the NpcOption value.
         * @return an {@code Either} containing the deserialized {@link NPC} on the left, or the world UUID on the right if the world is not currently loaded
         */
        @SuppressWarnings("unchecked")
        public <T, S extends Serializable> @NotNull Either<NPC, UUID> deserializedNPC()
        {
            World world1 = Bukkit.getWorld(world);
            if(world1 == null)
                return Either.right(world);

            NPC npc = new NPC(new Location(world1, x, y, z, yaw, pitch), id, (NpcName) name).setClickEvent(
                    clickEvent == null ? clickEvent : clickEvent.initialize());

            if(options != null)
            {
                options.forEach((s, serializable) ->
                {
                    Map<String, Serializable> map = (Map<String, Serializable>) serializable;
                    map.forEach((s1, serializable1) -> NpcOption.getOption(s1)
                            .ifPresent(npcOption -> npc.setOption((NpcOption<T, S>) npcOption, (T) npcOption.deserialize(Var.unsafeCast(serializable1)),
                                    UUID.fromString(s))));
                });
            }
            if(newOptions != null)
                newOptions.forEach((s, stringSerializableHashMap) -> stringSerializableHashMap.forEach((s1, serializable) ->
                        NpcOption.getOption(s1)
                                .ifPresent(npcOption -> npc.setOption((NpcOption<T, S>) npcOption, (T) npcOption.deserialize(Var.unsafeCast(serializable)),
                                        UUID.fromString(s)))));

            npc.createdAt = createdAt == null ? Instant.now() : createdAt;
            return Either.left(npc);
        }
    }
}
