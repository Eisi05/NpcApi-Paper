package de.eisi05.npc.api.objects;

import com.mojang.authlib.properties.Property;
import com.mojang.datafixers.util.Pair;
import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.enums.SkinParts;
import de.eisi05.npc.api.manager.TeamManager;
import de.eisi05.npc.api.utils.ItemSerializer;
import de.eisi05.npc.api.utils.TriFunction;
import de.eisi05.npc.api.wrapper.enums.ChatFormat;
import de.eisi05.npc.api.wrapper.packets.SetEntityDataPacket;
import de.eisi05.npc.api.wrapper.packets.SetPlayerTeamPacket;
import io.papermc.paper.util.KeepAlive;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.scores.PlayerTeam;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
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
                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();

                var textureProperties = serverPlayer.getGameProfile().getProperties().get("textures").iterator();
                npcServerPlayer.getGameProfile().getProperties().removeAll("textures");

                if(!textureProperties.hasNext())
                    return null;

                var textureProperty = textureProperties.next();
                npcServerPlayer.getGameProfile().getProperties().put("textures", textureProperty);
                return null;
            });

    /**
     * NPC option to set a specific skin using a value and signature.
     * This is ignored if {@link #USE_PLAYER_SKIN} is true.
     */
    public static final NpcOption<Skin, Skin> SKIN = new NpcOption<>("skin", null,
            skin -> skin, skin -> skin,
            (skin, npc, player) ->
            {
                if(npc.getOption(USE_PLAYER_SKIN))
                    return null;

                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();

                npcServerPlayer.getGameProfile().getProperties().removeAll("textures");

                if(skin == null)
                    return null;

                npcServerPlayer.getGameProfile().getProperties()
                        .put("textures", new Property("textures", skin.value(), skin.signature()));
                return null;
            });

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

                npcServerPlayer.connection = new ServerGamePacketListenerImpl(npcServerPlayer.getServer(),
                        new Connection(PacketFlow.SERVERBOUND), npcServerPlayer,
                        new CommonListenerCookie(npcServerPlayer.getGameProfile(), latency, ClientInformation.createDefault(), true, null,
                                new HashSet<>(), new KeepAlive()));

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
     * NPC option to set the pose of the NPC (e.g., standing, sleeping, swimming).
     * For a full list look at {@link Pose}.
     */
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

                if(pose == Pose.SPIN_ATTACK)
                    data.set(EntityDataSerializers.BYTE.createAccessor(8), (byte) 0x04);
                else
                    data.set(EntityDataSerializers.BYTE.createAccessor(8), (byte) 0x01);

                return (Packet<?>) SetEntityDataPacket.create(npcServerPlayer.getId(), data);
            });

    /**
     * NPC option to set the equipment worn by the NPC (armor, items in hand).
     * The map uses {@link EquipmentSlot} as keys and {@link ItemStack} as values.
     * Serialized form uses item base64 strings.
     */
    public static final NpcOption<Map<EquipmentSlot, ItemStack>, HashMap<EquipmentSlot, String>> EQUIPMENT = new NpcOption<>("equipment", Map.of(),
            map ->
            {
                HashMap<EquipmentSlot, String> serializedMap = new HashMap<>();
                map.forEach((slot, item) -> serializedMap.put(slot, ItemSerializer.itemStackToBase64(item)));
                return serializedMap;
            },
            serializedMap ->
            {
                HashMap<EquipmentSlot, ItemStack> map = new HashMap<>();
                serializedMap.forEach((slot, string) -> map.put(slot, ItemSerializer.itemStackFromBase64(string)));
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
                data.set(EntityDataSerializers.BYTE.createAccessor(17), (byte) Arrays.stream(skinParts).mapToInt(SkinParts::getValue).sum());
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

                String teamName = npcServerPlayer.getGameProfile().getName();
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
     * NPC option to control if the NPC is enabled (visible and interactable).
     * If false, a "DISABLED" marker may be shown.
     * This is an internal option, typically not directly set by users but controlled by {@link NPC#setEnabled(boolean)}.
     */
    @SuppressWarnings("unchecked")
    static final NpcOption<Boolean, Boolean> ENABLED = new NpcOption<>("enabled", false,
            aBoolean -> aBoolean, aBoolean -> aBoolean,
            (enabled, npc, player) ->
            {
                if(enabled)
                    return null;

                ServerPlayer npcServerPlayer = (ServerPlayer) npc.getServerPlayer();

                ArmorStand armorStand = new ArmorStand(EntityType.ARMOR_STAND, npcServerPlayer.level());
                armorStand.snapTo(npc.getLocation().getX(),
                        npc.getLocation().getY() + (npcServerPlayer.getBoundingBox().getYsize() * npc.getOption(SCALE)) + 0.3,
                        npc.getLocation().getZ());

                Packet<ClientGamePacketListener> addPacket = armorStand.getAddEntityPacket(
                        new ServerEntity(npcServerPlayer.level(), armorStand, 0, false, packet ->
                        {
                        }, (packet, uuids) ->
                        {
                        }, Set.of()));

                SynchedEntityData data = armorStand.getEntityData();

                data.set(EntityDataSerializers.BYTE.createAccessor(0), (byte) 0x20);
                data.set(EntityDataSerializers.OPTIONAL_COMPONENT.createAccessor(2),
                        Optional.of(Component.literal("DISABLED").withStyle(style -> style.withColor(ChatFormatting.RED))));
                data.set(EntityDataSerializers.BOOLEAN.createAccessor(3), true);
                data.set(EntityDataSerializers.BOOLEAN.createAccessor(4), true);
                data.set(EntityDataSerializers.BYTE.createAccessor(15), (byte) 0x10);

                npc.toDeleteEntities.add(armorStand.getId());

                return new ClientboundBundlePacket(List.of(addPacket,
                        (Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>) SetEntityDataPacket.create(armorStand.getId(), data)));
            });

    private final String path;
    private final T defaultValue;
    private final Function<T, S> serializer;
    private final Function<S, T> deserializer;
    private final TriFunction<T, NPC, Player, Packet<?>> packet;

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
    public @NotNull Optional<Object> getPacket(@Nullable Object object, @NotNull NPC npc, @NotNull Player player)
    {
        if(packet == null)
            return Optional.empty();

        return Optional.ofNullable(packet.apply((T) object, npc, player));
    }
}
