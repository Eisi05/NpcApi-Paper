package de.eisi05.npc.api.objects;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.enums.WalkingResult;
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
import net.minecraft.world.scores.Team;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Represents a Non-Player Character (NPC) with location, appearance, options, and interaction logic.
 */
public class NPC extends NpcHolder
{
    private transient final Map<UUID, String> nameCache = new HashMap<>();

    ServerPlayer serverPlayer;
    private final List<UUID> viewers = new ArrayList<>();
    private final Map<NpcOption<?, ?>, Object> options;
    private final CustomNameTag nameTag;
    private NpcName name;
    private Location location;
    private NpcClickAction clickEvent;
    private Instant createdAt = Instant.now();
    private Path npcPath;
    private PathTask pathTask;

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
    public NPC(@NotNull Location location, @NotNull NpcName name)
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
        this(location, uuid, NpcName.empty());
    }

    /**
     * Creates an NPC at the specified location with the given UUID and name.
     * This is the primary constructor that initializes the NPC's core properties.
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

        this.serverPlayer = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Var.moveEntity(serverPlayer, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());

        npcPath = NpcApi.plugin.getDataFolder().toPath().resolve("NPC").resolve(uuid + ".npc");

        serverPlayer.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), serverPlayer,
                CommonListenerCookie.createInitial(profile, true));

        this.options = new HashMap<>();
        for(NpcOption<?, ?> value : NpcOption.values())
            setOption(value, Var.unsafeCast(value.getDefaultValue()));

        Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, ((CraftWorld) location.getWorld()).getHandle());
        Var.moveEntity(display, location.getX(), location.getY() + 2, location.getZ(), 0f, 0f);

        nameTag = new CustomNameTag(display);
        serverPlayer.listName = CraftChatMessage.fromJSON(JSONComponentSerializer.json().serialize(name.getName()));
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
    private NPC(@NotNull Location location, @NotNull NpcName name, @NotNull Map<NpcOption<?, ?>, Object> options,
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
     * Checks if this NPC is marked as editable through the {@code NpcPlugin}.
     * <p>
     * The default state is {@code false}.
     *
     * @return {@code true} if the NPC is editable, {@code false} otherwise
     */
    public boolean isEditable()
    {
        return getOption(NpcOption.EDITABLE);
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

        if(NpcApi.config.autoUpdate())
        {
            viewers.forEach(uuid ->
            {
                Player player = Bukkit.getPlayer(uuid);
                if(player == null)
                    return;

                option.getPacket(value, this, player).ifPresent(packetWrapper ->
                        ((CraftPlayer) player).getHandle().connection.send((Packet<?>) packetWrapper));
            });
        }
    }

    /**
     * Gets the value of a specific option for this NPC.
     * If the option has not been explicitly set, its default value will be returned.
     *
     * @param option the {@link NpcOption} to get. Must not be null.
     * @param <T>    the type of the option's value.
     * @return the value of the option.
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getOption(@NotNull NpcOption<T, ?> option)
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
     * Sets the location of the NPC.
     * This will also update the underlying server player's position.
     *
     * @param location the new {@link Location} for the NPC. Must not be null.
     */
    public void setLocation(@NotNull Location location)
    {
        this.location = location;
        if(serverPlayer != null)
            Var.moveEntity(serverPlayer, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
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

    public @NotNull String getGameProfileName()
    {
        if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_9))
            return (String) Reflections.invokeMethod(serverPlayer.getGameProfile(), "getName").get();
        return serverPlayer.getGameProfile().name();
    }

    /**
     * Sets the display name of this NPC.
     * This also updates the name for the underlying server player and its list name.
     *
     * @param name the new {@link Component} name for the NPC. Must not be null.
     */
    public void setName(@NotNull NpcName name)
    {
        this.name = name;
        serverPlayer.listName = CraftChatMessage.fromJSON(JSONComponentSerializer.json().serialize(name.getName()));

        viewers.stream().filter(uuid -> Bukkit.getPlayer(uuid) != null).forEach(uuid -> updateName(Bukkit.getPlayer(uuid)));
    }

    /**
     * Updates the display name of the given player on the server.
     * <p>
     * Sends a packet to the player to modify their name tag, taking into account
     * the server version and whether custom naming is enabled.
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
     * Sends a packet to the player to modify their name tag, taking into account
     * the server version and whether custom naming is enabled.
     */
    public void updateNameForAll()
    {
        for(UUID uuid : viewers)
        {
            Player player = Bukkit.getPlayer(uuid);
            if(player == null)
                continue;

            String name = PlainTextComponentSerializer.plainText().serialize(getName(Bukkit.getPlayer(uuid)));
            if(nameCache.getOrDefault(uuid, "").equals(name))
                continue;

            updateName(player);
            nameCache.put(uuid, name);
        }
    }

    /**
     * Updates the NPC's skin for a subset of players based on a condition.
     * <p>
     * Iterates over all viewers of the NPC and, for each player that satisfies
     * the given {@link Predicate}, hides and then shows the NPC to refresh its skin.
     * </p>
     *
     * @param predicate a {@link java.util.function.Predicate} that determines which players
     *                  should have the skin updated.
     */
    public void updateSkin(@NotNull Predicate<Player> predicate)
    {
        for(UUID uuid : viewers)
        {
            Player player = Bukkit.getPlayer(uuid);
            if(player == null)
                continue;

            if(!predicate.test(player))
                continue;

            hideNpcFromPlayer(player);
            showNPCToPlayer(player);
        }
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
        if(!getOption(NpcOption.ENABLED) && !player.isPermissionSet("npc.admin") && !player.isOp())
            return;

        if(!player.getWorld().getName().equals(serverPlayer.getBukkitEntity().getWorld().getName()))
        {
            hideNpcFromPlayer(player);
            return;
        }

        if(!viewers.contains(player.getUniqueId()))
            viewers.add(player.getUniqueId());

        if(!name.isStatic() && getOption(NpcOption.SHOW_TAB_LIST))
            setOption(NpcOption.SHOW_TAB_LIST, false);

        List<Packet<?>> packets = new ArrayList<>();

        Arrays.stream(NpcOption.values()).filter(NpcOption::loadBefore)
                .forEach(npcOption -> npcOption.getPacket(getOption(npcOption), this, player).ifPresent(o -> packets.add((Packet<?>) o)));

        packets.add(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(serverPlayer, true));
        packets.add(serverPlayer.getAddEntityPacket(Var.getServerEntity(serverPlayer, Var.getServerLevel(serverPlayer))));

        boolean modified = TeamManager.exists(player, getGameProfileName());
        PlayerTeam wrappedPlayerTeam = (PlayerTeam) TeamManager.create(player, getGameProfileName());
        wrappedPlayerTeam.setNameTagVisibility(Team.Visibility.NEVER);

        packets.add((Packet<?>) SetPlayerTeamPacket.createAddOrModifyPacket(wrappedPlayerTeam, !modified));
        packets.add((Packet<?>) SetPlayerTeamPacket.createPlayerPacket(wrappedPlayerTeam, getGameProfileName(),
                ClientboundSetPlayerTeamPacket.Action.ADD));

        packets.add(new ClientboundRotateHeadPacket(serverPlayer, (byte) ((location.getYaw() % 360) * 256 / 360)));
        packets.add(new ClientboundMoveEntityPacket.Rot(serverPlayer.getId(), (byte) location.getYaw(), (byte) location.getPitch(),
                serverPlayer.onGround));

        if(!getOption(NpcOption.HIDE_NAMETAG))
        {
            packets.add(((Display.TextDisplay) nameTag.getDisplay()).getAddEntityPacket(
                    Var.getServerEntity((Display.TextDisplay) nameTag.getDisplay(), Var.getServerLevel(serverPlayer))));

            packets.add((Packet<?>) SetEntityDataPacket.create(((Display.TextDisplay) nameTag.getDisplay()).getId(),
                    (SynchedEntityData) nameTag.applyData(isEnabled() ? name.getName(player) : NpcApi.DISABLED_MESSAGE_PROVIDER.apply(player)
                            .appendNewline().append(name.getName(player)))));

            packets.add(new ClientboundSetPassengersPacket(serverPlayer));
        }

        Arrays.stream(NpcOption.values()).filter(npcOption -> !npcOption.equals(NpcOption.ENABLED))
                .forEach(npcOption -> npcOption.getPacket(getOption(npcOption), this, player).map(o -> (Packet<?>) o)
                        .ifPresent(packets::add));

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

        if(TeamManager.exists(player, getGameProfileName()))
        {
            PlayerTeam team = (PlayerTeam) TeamManager.create(player, getGameProfileName());
            connection.send((Packet<?>) SetPlayerTeamPacket.createPlayerPacket(team, getGameProfileName(),
                    ClientboundSetPlayerTeamPacket.Action.REMOVE));
            connection.send((Packet<?>) SetPlayerTeamPacket.createRemovePacket(team));
        }

        connection.send(new ClientboundPlayerInfoRemovePacket(List.of(getUUID())));

        viewers.remove(player.getUniqueId());
    }

    /**
     * Deletes the NPC.
     * This hides the NPC from all players, removes it from the NPC manager, and deletes its saved data file.
     */
    public void delete() throws IOException
    {
        if(serverPlayer == null)
            return;

        hideNpcFromAllPlayers();
        NpcManager.removeNPC(this);

        serverPlayer.remove(Entity.RemovalReason.DISCARDED);
        serverPlayer = null;

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

    /**
     * Moves the NPC along a precomputed {@link de.eisi05.npc.api.pathfinding.Path}, simulating walking, jumping, and gravity.
     * The NPC's position and rotation are updated each tick and sent to the specified player(s).
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
     * Moves the NPC along a precomputed {@link de.eisi05.npc.api.pathfinding.Path}, simulating walking, jumping, and gravity.
     * The NPC's position and rotation are updated each tick and sent to the specified player(s).
     *
     * @param path               The {@link de.eisi05.npc.api.pathfinding.Path} containing the ordered waypoints the NPC should follow.
     * @param walkSpeed          The walking speed of the NPC (clamped between 0.1 and 1).
     * @param changeRealLocation If true, the NPC's actual server-side location will be updated; otherwise only packets are sent.
     * @param onEnd              A {@link Runnable} to be executed when the NPC reaches the end of the path.
     * @param viewers             The players who should see the NPC move. If null, updates all viewers in the `viewers` set.
     * @return The {@link BukkitTask} representing the movement task.
     */
    public @NotNull BukkitTask walkTo(@NotNull de.eisi05.npc.api.pathfinding.Path path, double walkSpeed,
            boolean changeRealLocation, @Nullable Consumer<WalkingResult> onEnd, @Nullable Player... viewers)
    {
        if(isWalking())
            cancelWalking();

        final double speed = Math.max(Math.min(walkSpeed, 1), 0.1);

        NpcStartWalkingEvent event = new NpcStartWalkingEvent(this, path, speed, changeRealLocation);
        Bukkit.getPluginManager().callEvent(event);
        if(event.isCancelled())
            return null;

        pathTask = new PathTask.Builder(this, path)
                .speed(event.getWalkSpeed())
                .viewers(viewers)
                .updateRealLocation(event.isChangeRealLocation())
                .callback(onEnd).build();

        return pathTask.runTaskTimer(NpcApi.plugin, 1L, 1L);
    }

    /**
     * Checks whether the NPC is currently walking.
     * <p>
     * The NPC is considered walking if a path task exists and has not yet finished.
     *
     * @return true if the NPC is still walking, false otherwise
     */
    public boolean isWalking()
    {
        return pathTask != null && !pathTask.isFinished();
    }

    /**
     * Cancels the NPC's current walking task if one is active.
     * <p>
     * If the NPC is walking, the path task is cancelled and cleared. If no
     * walking task is active, this method has no effect.
     */
    public void cancelWalking()
    {
        if(pathTask != null)
        {
            pathTask.cancel();
            pathTask = null;
        }
    }

    /**
     * Sends movement-related packets for the NPC's body to specific players.
     * <p>
     * If one or more {@link Player} instances are provided, the packet is sent only
     * to those players. If {@code players} is {@code null}, the packet is sent to all
     * currently online players who are registered as viewers of the NPC.
     * <p>
     * If {@code moveEntityPacket} is {@code null}, no packets are sent.
     *
     * @param moveEntityPacket the movement packet to send, or {@code null} to send nothing
     * @param players          the target players to receive the packet, or {@code null}
     *                         to send the packet to all online registered viewers
     */
    public void sendNpcBodyPackets(@Nullable ClientboundMoveEntityPacket moveEntityPacket, @Nullable Player... players) {
        if(players != null)
        {
            for (var player : players)
            {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
                if (moveEntityPacket != null)
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
                if (moveEntityPacket != null)
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
     * Updates the real location of the NPC and refreshes visibility for viewers.
     * * @param location The new target location.
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

        ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(serverPlayer.getId(),
                new PositionMoveRotation(new Vec3(location.getX(), location.getY(), location.getZ()), new Vec3(0, 0, 0), location.getYaw(),
                        location.getPitch()), Set.of(), true);

        for(UUID uuid : viewers)
        {
            Player viewer = Bukkit.getPlayer(uuid);
            if(viewer == null)
                continue;

            if(excluded.contains(viewer.getUniqueId()))
                continue;

            ((CraftPlayer) viewer).getHandle().connection.send(teleport);
        }
    }

    public CustomNameTag getNameTag()
    {
        return nameTag;
    }

    /**
     * Represents a fully serialized NPC, including its location, orientation,
     * unique ID, name, additional options, click behavior, and creation time.
     * <p>
     * The {@code name} field now uses {@link NpcName}, which supports
     * both static and dynamic names. For backward compatibility, a secondary constructor
     * allows creating a {@code SerializedNPC} from a legacy {@link Component}.
     *
     * @param world      the UUID of the world the NPC is in
     * @param x          the X-coordinate of the NPC
     * @param y          the Y-coordinate of the NPC
     * @param z          the Z-coordinate of the NPC
     * @param yaw        the yaw rotation of the NPC
     * @param pitch      the pitch rotation of the NPC
     * @param id         the unique UUID of the NPC
     * @param name       the serializable NPC name (static or dynamic)
     * @param options    additional serializable options associated with the NPC
     * @param clickEvent optional click event behavior for the NPC
     * @param createdAt  the timestamp when the NPC was created
     */
    public record SerializedNPC(@NotNull UUID world, double x, double y, double z, float yaw, float pitch, @NotNull UUID id,
                                @NotNull Serializable name, @NotNull Map<String, ? extends Serializable> options,
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
                    npc.getNpcName(), options, npc.clickEvent, npc.createdAt);
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

            return new SerializedNPC(world, x, y, z, yaw, pitch, id, fixedName, options, clickEvent, createdAt);
        }

        /**
         * Deserializes this {@link SerializedNPC} object back into a fully functional {@link NPC} instance.
         *
         * @param <T> The type of the NpcOption value.
         * @param <S> The serializable type of the NpcOption value.
         * @return an {@code Either} containing the deserialized {@link NPC} on the left,
         *         or the world UUID on the right if the world is not currently loaded
         */
        @SuppressWarnings("unchecked")
        public <T, S extends Serializable> @NotNull Either<NPC, UUID> deserializedNPC()
        {
            World world1 = Bukkit.getWorld(world);
            if(world1 == null)
                return Either.right(world);

            NPC npc = new NPC(new Location(world1, x, y, z, yaw, pitch), id, (NpcName) name).setClickEvent(
                    clickEvent == null ? clickEvent : clickEvent.initialize());
            options.forEach((string, serializable) -> NpcOption.getOption(string)
                    .ifPresent(npcOption -> npc.setOption((NpcOption<T, S>) npcOption, (T) npcOption.deserialize(Var.unsafeCast(serializable)))));
            npc.createdAt = createdAt == null ? Instant.now() : createdAt;
            return Either.left(npc);
        }
    }
}
