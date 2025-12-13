package de.eisi05.npc.api.objects;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimaps;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.enums.SkinParts;
import de.eisi05.npc.api.manager.TeamManager;
import de.eisi05.npc.api.scheduler.Tasks;
import de.eisi05.npc.api.utils.*;
import de.eisi05.npc.api.wrapper.enums.ChatFormat;
import de.eisi05.npc.api.wrapper.packets.SetEntityDataPacket;
import de.eisi05.npc.api.wrapper.packets.SetPlayerTeamPacket;
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.EntityDataSerializers;
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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.scores.PlayerTeam;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;

/**
 * Represents a configurable option for an NPC.
 * Each option has a path, a default value, serialization/deserialization logic,
 * and a function to generate a network packet for applying the option.
 *
 * @param <T> The type of the option's value in its usable form.
 * @param <S> The type of the option's value in its serialized form.
 */
public class NpcOption<T, S extends Serializable>
{
    /**
     * NPC option to determine if the NPC should use the skin of the viewing player.
     * If true, the NPC's skin will be dynamically set to the skin of the player looking at it.
     */
    public static final NpcOption<Boolean, Boolean> USE_PLAYER_SKIN = new NpcOption<>("use-player-skin", false,
            aBoolean -> aBoolean, aBoolean -> aBoolean,
            (skin, npc, player) ->
            {
                if(!skin)
                    return null;

                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
                ServerPlayer npcServerPlayer = npc.serverPlayer;

                if(!Versions.isCurrentVersionSmallerThan(Versions.V1_21_9))
                {
                    var textureProperties = ((PropertyMap) Reflections.getField(serverPlayer.getGameProfile(), "properties")
                            .get()).get("textures").iterator();

                    var npcTextureProperties = ((PropertyMap) Reflections.getField(npcServerPlayer.getGameProfile(), "properties")
                            .get()).get("textures").iterator();

                    Property property = textureProperties.hasNext() ? textureProperties.next() : null;
                    Property npcProperty = npcTextureProperties.hasNext() ? npcTextureProperties.next() : null;

                    if((property == null && npcProperty == null) || (property != null && npcProperty != null &&
                            Reflections.getField(property, "value").get().equals(Reflections.getField(npcProperty, "value").get())))
                        return null;

                    PropertyMap propertyMap = new PropertyMap(Multimaps.forMap(skin == null ? Map.of() : Map.of("textures", property)));
                    GameProfile profile = new GameProfile(npc.getUUID(), "NPC" + npc.getUUID().toString().substring(0, 13), propertyMap);

                    Location location = npc.getLocation();
                    MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
                    ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
                    npc.serverPlayer = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
                    Var.moveEntity(npc.serverPlayer, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                    npc.serverPlayer.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), npc.serverPlayer,
                            CommonListenerCookie.createInitial(profile, true));
                    npc.serverPlayer.listName = CraftChatMessage.fromJSON(JSONComponentSerializer.json().serialize(npc.getName()));
                    npc.serverPlayer.passengers = ImmutableList.of((Display.TextDisplay) npc.getNameTag().getDisplay());
                    return null;
                }

                PropertyMap playerProperty = (PropertyMap) Reflections.invokeMethod(serverPlayer.getGameProfile(), "getProperties").get();
                PropertyMap npcProperty = (PropertyMap) Reflections.invokeMethod(npcServerPlayer.getGameProfile(), "getProperties").get();

                var textureProperties = playerProperty.get("textures").iterator();
                npcProperty.removeAll("textures");

                if(!textureProperties.hasNext())
                    return null;

                var textureProperty = textureProperties.next();
                npcProperty.put("textures", textureProperty);
                return null;
            }).loadBefore(!Versions.isCurrentVersionSmallerThan(Versions.V1_21_9));

    /**
     * NPC option to set a specific skin using a value and signature.
     * This is ignored if {@link #USE_PLAYER_SKIN} is true.
     */
    public static final NpcOption<NpcSkin, SkinData> SKIN = new NpcOption<NpcSkin, SkinData>("skin", null,
            skin -> skin, skin -> skin instanceof Skin skin1 ? NpcSkin.of(skin1) : (NpcSkin) skin,
            (skinData, npc, player) ->
            {
                if(npc.getOption(USE_PLAYER_SKIN) || skinData == null)
                    return null;

                Skin skin = skinData.getSkin(player, npc);
                if(skin == null)
                    return null;

                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();
                if(!Versions.isCurrentVersionSmallerThan(Versions.V1_21_9))
                {
                    var npcTextureProperties = ((PropertyMap) Reflections.getField(npcServerPlayer.getGameProfile(), "properties")
                            .get()).get("textures").iterator();

                    Property npcProperty = npcTextureProperties.hasNext() ? npcTextureProperties.next() : null;
                    if((skin == null && npcProperty == null) ||
                            (npcProperty != null && skin.value().equals(Reflections.getField(npcProperty, "value").get())))
                        return null;

                    var textures = new Property("textures", skin.value(), skin.signature());

                    PropertyMap propertyMap = new PropertyMap(Multimaps.forMap(skin == null ? Map.of() : Map.of("textures", textures)));
                    GameProfile profile = new GameProfile(npc.getUUID(), "NPC" + npc.getUUID().toString().substring(0, 13), propertyMap);

                    Location location = npc.getLocation();
                    MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
                    ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
                    npc.serverPlayer = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
                    Var.moveEntity(npc.serverPlayer, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                    npc.serverPlayer.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), npc.serverPlayer,
                            CommonListenerCookie.createInitial(profile, true));
                    npc.serverPlayer.listName = CraftChatMessage.fromJSON(JSONComponentSerializer.json().serialize(npc.getName()));
                    npc.serverPlayer.passengers = ImmutableList.of((Display.TextDisplay) npc.getNameTag().getDisplay());
                    return null;
                }

                PropertyMap properties = (PropertyMap) Reflections.invokeMethod(npcServerPlayer.getGameProfile(), "getProperties").get();

                properties.removeAll("textures");

                if(skin == null)
                    return null;

                var textures = new Property("textures", skin.value(), skin.signature());

                properties.put("textures", textures);
                return null;
            }).loadBefore(!Versions.isCurrentVersionSmallerThan(Versions.V1_21_9));

    /**
     * NPC option to control whether the NPC is shown in the player tab list.
     * If false, the NPC will be removed from the tab list for the viewing player after a short delay.
     */
    public static final NpcOption<Boolean, Boolean> SHOW_TAB_LIST = new NpcOption<>("show-tab-list", true,
            aBoolean -> aBoolean, aBoolean -> aBoolean,
            (show, npc, player) ->
            {
                if(show)
                    return null;

                new BukkitRunnable()
                {
                    @Override
                    public void run()
                    {
                        ((CraftPlayer) player).getHandle().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(npc.getUUID())));
                    }
                }.runTaskLater(NpcApi.plugin, 50);
                return null;
            });

    /**
     * NPC option to set the simulated latency (ping) of the NPC in the tab list.
     */
    public static final NpcOption<Integer, Integer> LATENCY = new NpcOption<>("latency", 0,
            aInteger -> aInteger, aInteger -> aInteger,
            (latency, npc, player) ->
            {
                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();

                CommonListenerCookie commonListenerCookie;
                if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_7))
                    commonListenerCookie = Reflections.getInstanceFirstConstructor(CommonListenerCookie.class,
                            npcServerPlayer.getGameProfile(), latency, ClientInformation.createDefault(), true).orElseThrow();
                else
                    commonListenerCookie = Reflections.getInstanceFirstConstructor(CommonListenerCookie.class,
                            npcServerPlayer.getGameProfile(), latency, ClientInformation.createDefault(), true, null,
                            new HashSet<>(), Reflections.getInstance("io.papermc.paper.util.KeepAlive").orElseThrow()).orElseThrow();

                if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_9))
                    npcServerPlayer.connection = new ServerGamePacketListenerImpl(
                            (MinecraftServer) Reflections.invokeMethod(npcServerPlayer, "getServer").get(),
                            new Connection(PacketFlow.SERVERBOUND), npcServerPlayer, commonListenerCookie);
                else
                    npcServerPlayer.connection = new ServerGamePacketListenerImpl(npcServerPlayer.level().getServer(),
                            new Connection(PacketFlow.SERVERBOUND), npcServerPlayer, commonListenerCookie);

                return new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, npcServerPlayer);
            });

    /**
     * NPC option to control the visibility of the NPC's nametag.
     */
    public static final NpcOption<Boolean, Boolean> HIDE_NAMETAG = new NpcOption<>("hide-nametag", false,
            aBoolean -> aBoolean, aBoolean -> aBoolean,
            (hide, npc, player) ->
            {
                if(!hide)
                    return null;

                return new ClientboundRemoveEntitiesPacket(((Entity) npc.getNameTag().getDisplay()).getId());
            });
    /**
     * NPC option to set the equipment worn by the NPC (armor, items in hand).
     * The map uses {@link EquipmentSlot} as keys and {@link ItemStack} as values.
     * Serialized form uses item base64 strings.
     */
    public static final NpcOption<Map<EquipmentSlot, ItemStack>, HashMap<EquipmentSlot, String>> EQUIPMENT = new NpcOption<>("equipment",
            new HashMap<>(),
            map ->
            {
                HashMap<EquipmentSlot, String> serializedMap = new HashMap<>();
                map.forEach((slot, item) ->
                {
                    if(item == null)
                        return;

                    String serialized = ItemSerializer.itemStackToBase64(item);
                    if(serialized == null)
                        return;

                    serializedMap.put(slot, serialized);
                });
                return serializedMap;
            },
            serializedMap ->
            {
                HashMap<EquipmentSlot, ItemStack> map = new HashMap<>();
                serializedMap.forEach((slot, string) ->
                {
                    if(string == null)
                        return;

                    ItemStack item = ItemSerializer.itemStackFromBase64(string);
                    if(item == null)
                        return;

                    map.put(slot, item);
                });
                return map;
            },
            (map, npc, player) ->
            {
                if(map.isEmpty())
                    return null;

                List<Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> list = new ArrayList<>();

                map.forEach((slot, item) -> list.add(
                        new Pair<>(net.minecraft.world.entity.EquipmentSlot.values()[slot.ordinal()], CraftItemStack.asNMSCopy(item))));

                return new ClientboundSetEquipmentPacket(((ServerPlayer) npc.getServerPlayer()).getId(), list);
            });
    /**
     * NPC option to control which parts of the NPC's skin are visible (e.g., hat, jacket).
     * For a full list look at {@link SkinParts}.
     */
    public static final NpcOption<SkinParts[], SkinParts[]> SKIN_PARTS = new NpcOption<>("skin-parts", SkinParts.values(),
            skinParts -> skinParts, skinParts -> skinParts,
            (skinParts, npc, player) ->
            {
                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();
                SynchedEntityData data = npcServerPlayer.getEntityData();
                data.set(EntityDataSerializers.BYTE.createAccessor(Versions.isCurrentVersionSmallerThan(Versions.V1_21_9) ? 17 : 16),
                        (byte) Arrays.stream(skinParts).mapToInt(SkinParts::getValue).sum());
                return (Packet<?>) SetEntityDataPacket.create(npcServerPlayer.getId(), data);
            });
    /**
     * NPC option to make the NPC look at the player if they are within a certain distance.
     * The value is the maximum distance in blocks. A value of 0 or less disables this.
     * The actual looking logic is handled by {@link Tasks}.
     */
    public static final NpcOption<Double, Double> LOOK_AT_PLAYER = new NpcOption<>("look-at-player", 0.0,
            distance -> distance, distance -> distance,
            (distance, npc, player) -> null);
    /**
     * NPC option to make the NPC glow with a specific color.
     * If null, the glowing effect is removed.
     */
    @SuppressWarnings("unchecked")
    public static final NpcOption<ChatFormat, ChatFormat> GLOWING = new NpcOption<>("glowing", null,
            color -> color, color -> color,
            (color, npc, player) ->
            {
                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();

                if(color == null)
                {
                    SynchedEntityData entityData = npcServerPlayer.getEntityData();
                    entityData.set(EntityDataSerializers.BYTE.createAccessor(0), (byte) 0);
                    return (Packet<?>) SetEntityDataPacket.create(npcServerPlayer.getId(), entityData);
                }

                String teamName = npc.getGameProfileName();
                boolean modified = TeamManager.exists(player, teamName);
                PlayerTeam team = (PlayerTeam) TeamManager.create(player, teamName);

                team.setColor(ChatFormatting.getByCode(color.getColorCode()));

                var teamPacket = SetPlayerTeamPacket.createAddOrModifyPacket(team, !modified);

                SynchedEntityData entityData = npcServerPlayer.getEntityData();
                entityData.set(EntityDataSerializers.BYTE.createAccessor(0), (byte) 0x40);

                return new ClientboundBundlePacket(List.of((Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>) teamPacket,
                        (Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>) SetEntityDataPacket.create(
                                npcServerPlayer.getId(), entityData)));
            });
    /**
     * NPC option to set the pose of the NPC (e.g., standing, sleeping, swimming).
     * For a full list look at {@link Pose}.
     */
    @SuppressWarnings("unchecked")
    public static final NpcOption<Pose, Pose> POSE = new NpcOption<>("pose", Pose.STANDING,
            pose -> pose, pose -> pose,
            (pose, npc, player) ->
            {
                net.minecraft.world.entity.Pose nmsPose = net.minecraft.world.entity.Pose.values()[pose.ordinal()];

                if(nmsPose == null)
                    throw new RuntimeException("Pose (" + pose.name() + ") not found");

                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();

                npcServerPlayer.setPose(nmsPose);

                SynchedEntityData data = npcServerPlayer.getEntityData();
                data.set(EntityDataSerializers.POSE.createAccessor(6), nmsPose);

                Packet<? super ClientGamePacketListener> packet = null;
                if(pose == Pose.FALL_FLYING)
                {
                    data.set(EntityDataSerializers.BYTE.createAccessor(0), (byte) (npc.getOption(NpcOption.GLOWING) != null ? 0xC0 : 0x80));
                    packet = new ClientboundMoveEntityPacket.Rot(npcServerPlayer.getId(), (byte) (npc.getLocation().getYaw() * 256 / 360),
                            (byte) 0, npcServerPlayer.onGround());
                }
                else if(pose == Pose.SWIMMING)
                    data.set(EntityDataSerializers.BYTE.createAccessor(0), (byte) (npc.getOption(NpcOption.GLOWING) != null ? 0x50 : 0x10));
                else
                    data.set(EntityDataSerializers.BYTE.createAccessor(0), (byte) 0);

                if(pose == Pose.SPIN_ATTACK)
                {
                    data.set(EntityDataSerializers.BYTE.createAccessor(8), (byte) 0x04);
                    packet = new ClientboundMoveEntityPacket.Rot(npcServerPlayer.getId(), (byte) (npc.getLocation().getYaw() * 256 / 360),
                            (byte) -90, npcServerPlayer.onGround());
                }
                else
                    data.set(EntityDataSerializers.BYTE.createAccessor(8), (byte) 0x01);

                if(pose == Pose.SITTING)
                {
                    Display.TextDisplay textDisplay = new Display.TextDisplay(EntityType.TEXT_DISPLAY, npc.serverPlayer.level());
                    textDisplay.absSnapTo(npc.getLocation().getX(), npc.getLocation().getY(), npc.getLocation().getZ());
                    npc.toDeleteEntities.add(textDisplay.getId());

                    Packet<? super ClientGamePacketListener> addEntityPacket = textDisplay.getAddEntityPacket(
                            Var.getServerEntity(textDisplay, npc.serverPlayer.level()));

                    SynchedEntityData entityData = textDisplay.getEntityData();
                    entityData.set(EntityDataSerializers.BYTE.createAccessor(0), (byte) 0x20);
                    Packet<? super ClientGamePacketListener> entityDataPacket = (Packet<? super ClientGamePacketListener>) SetEntityDataPacket.create(
                            textDisplay.getId(), entityData);

                    textDisplay.passengers = ImmutableList.of((ServerPlayer) npc.getServerPlayer());

                    ClientboundSetPassengersPacket passengerPacket = new ClientboundSetPassengersPacket(textDisplay);
                    ClientboundRotateHeadPacket rotateHeadPacket = new ClientboundRotateHeadPacket((ServerPlayer) npc.getServerPlayer(),
                            (byte) (npc.getLocation().getYaw() * 256 / 360));

                    return new ClientboundBundlePacket(List.of(addEntityPacket, entityDataPacket, passengerPacket, rotateHeadPacket));
                }

                return packet == null ? (Packet<?>) SetEntityDataPacket.create(npcServerPlayer.getId(), data) : new ClientboundBundlePacket(
                        List.of(packet, (Packet<? super ClientGamePacketListener>) SetEntityDataPacket.create(npcServerPlayer.getId(), data)));
            });
    /**
     * NPC option to set the scale (size) of the NPC.
     * A value of 1.0 is normal size. Requires Minecraft 1.20.6 or newer.
     */
    public static final NpcOption<Double, Double> SCALE = new NpcOption<>("scale", 1.0,
            scale -> scale, scale -> scale,
            (scale, npc, player) ->
            {
                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();

                AttributeInstance instance = npcServerPlayer.getAttribute(Attributes.SCALE);
                instance.setBaseValue(scale);

                return new ClientboundUpdateAttributesPacket(npcServerPlayer.getId(), List.of(instance));
            });

    /**
     * NPC option to control the position of the NPC in the TAB list.
     * <p>
     * Only works on versions older than 1.21.2.
     * On 1.21.2 and newer, this option has no effect.
     * </p>
     */
    public static final NpcOption<Integer, Integer> LIST_ORDER = new NpcOption<>("list-order", 0,
            aInt -> aInt, aInt -> aInt,
            (order, npc, player) ->
            {
                if(!Versions.isCurrentVersionSmallerThan(Versions.V1_21_2))
                    return null;

                ((ServerPlayer) npc.getServerPlayer()).listOrder = order;

                return new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER,
                        (ServerPlayer) npc.getServerPlayer());
            }).since(Versions.V1_21_2);

    /**
     * NPC option to control if the NPC is enabled (visible and interactable).
     * If false, a "DISABLED" marker may be shown.
     * This is an internal option, typically not directly set by users but controlled by {@link NPC#setEnabled(boolean)}.
     */
    static final NpcOption<Boolean, Boolean> ENABLED = new NpcOption<>("enabled", false,
            aBoolean -> aBoolean, aBoolean -> aBoolean,
            (enabled, npc, player) -> null);

    /**
     * NPC option to control if the NPC is enabled (visible and interactable).
     * If false, a "DISABLED" marker may be shown.
     * This is an internal option, typically not directly set by users but controlled by {@link NPC#setEnabled(boolean)}.
     */
    static final NpcOption<Boolean, Boolean> EDITABLE = new NpcOption<>("editable", false,
            aBoolean -> aBoolean, aBoolean -> aBoolean,
            (enabled, npc, player) -> null);

    private final String path;
    private final T defaultValue;
    private final Function<T, S> serializer;
    private final Function<S, T> deserializer;
    private final TriFunction<T, NPC, Player, Packet<?>> packet;
    private Versions since = Versions.V1_17;
    private boolean loadBefore = false;

    /**
     * Private constructor to create a new NpcOption.
     *
     * @param path         The configuration path string. Must not be null.
     * @param defaultValue The default value for the option. Can be null.
     * @param serializer   The serialization function. Must not be null.
     * @param deserializer The deserialization function. Must not be null.
     * @param packet       The packet generation function. Must not be null.
     */
    private NpcOption(@NotNull String path, @Nullable T defaultValue, @NotNull Function<T, S> serializer, @NotNull Function<S, T> deserializer,
            @NotNull TriFunction<T, NPC, Player, Packet<?>> packet)
    {
        this.path = path;
        this.defaultValue = defaultValue;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.packet = packet;
    }

    /**
     * Retrieves all declared {@link NpcOption} constants within this class using reflection.
     *
     * @return An array of {@link NpcOption} instances. Will not be null.
     */
    public static @NotNull NpcOption<?, ?>[] values()
    {
        List<Field> fields = Arrays.stream(NpcOption.class.getDeclaredFields()).filter(field -> field.getType().equals(NpcOption.class)).toList();

        NpcOption<?, ?>[] values = new NpcOption[fields.size()];

        for(int i = 0; i < fields.size(); i++)
        {
            try
            {
                values[i] = (NpcOption<?, ?>) fields.get(i).get(null);
            } catch(IllegalAccessException e)
            {
            }
        }

        return values;
    }

    /**
     * Retrieves an {@link NpcOption} instance by its configuration path.
     *
     * @param path The configuration path string to search for. Must not be null.
     * @return An {@link Optional} containing the found {@link NpcOption}, or an empty Optional if no option matches the path.
     */
    public static @NotNull Optional<NpcOption<?, ?>> getOption(@NotNull String path)
    {
        return Arrays.stream(values()).filter(npcOption -> npcOption.getPath().equals(path)).findFirst();
    }

    /**
     * Sets the minimum Minecraft version required for this option.
     * Used for options that are only available in newer versions of the game.
     *
     * @param since The minimum {@link Versions} required. Must not be null.
     * @return This {@link NpcOption} instance for method chaining.
     */
    public @NotNull NpcOption<T, S> since(@NotNull Versions since)
    {
        this.since = since;
        return this;
    }

    public @NotNull NpcOption<T, S> loadBefore(boolean loadBefore)
    {
        this.loadBefore = loadBefore;
        return this;
    }

    public boolean loadBefore()
    {
        return loadBefore;
    }

    /**
     * Checks if this NPC option is compatible with the current server version.
     * An option is compatible if the current server version is greater than or equal to
     * the version specified by {@link #since()}.
     *
     * @return {@code true} if the option is compatible, {@code false} otherwise.
     */
    public boolean isCompatible()
    {
        return !Versions.isCurrentVersionSmallerThan(since);
    }

    /**
     * Gets the minimum Minecraft version required for this option.
     *
     * @return The {@link Versions} instance.
     */
    public Versions since()
    {
        return since;
    }

    /**
     * Gets the default value for this option.
     *
     * @return The default value, which can be null if defined as such.
     */
    public @Nullable T getDefaultValue()
    {
        return defaultValue;
    }

    /**
     * Gets the configuration path string for this option.
     *
     * @return The path string. Will not be null.
     */
    public @NotNull String getPath()
    {
        return path;
    }

    /**
     * Serializes the given value (of type T) into its serializable form (type S).
     *
     * @param var1 The value to serialize. Can be null.
     * @return The serialized value. Can be null if the input or serializer result is null.
     * @throws RuntimeException if a {@link ClassCastException} occurs during serialization,
     *                          indicating an incorrect type was passed.
     */
    @SuppressWarnings("unchecked")
    public @Nullable S serialize(@Nullable Object var1)
    {
        try
        {
            return serializer.apply((T) var1);
        } catch(ClassCastException e)
        {
            throw new RuntimeException(path + " -> " + var1);
        }
    }

    /**
     * Deserializes the given value (of type S) back into its usable form (type T).
     *
     * @param var1 The serialized value to deserialize. Can be null.
     * @return The deserialized value. Can be null if the input or deserializer result is null.
     */
    public @Nullable T deserialize(@Nullable S var1)
    {
        return deserializer.apply(var1);
    }

    /**
     * Generates the network packet(s) needed to apply this option's value to an NPC for a specific player.
     * The method checks for version compatibility before generating the packet.
     *
     * @param object The value of the option to apply. Can be null.
     * @param npc    The {@link NPC} to apply the option to. Must not be null.
     * @param player The {@link Player} who will receive the update. Must not be null.
     * @return An {@link Optional} containing the {@link Packet} if one is generated and the option is compatible,
     * otherwise an empty Optional.
     */
    @SuppressWarnings("unchecked")
    public @NotNull Optional<Object> getPacket(@Nullable Object object, @NotNull NPC npc, Player player)
    {
        if(packet == null || !isCompatible())
            return Optional.empty();

        return Optional.ofNullable(packet.apply((T) object, npc, player));
    }
}
