package de.eisi05.npc.api.utils;

import de.eisi05.npc.api.wrapper.objects.WrappedEntitySnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Var
{
    /**
     * Performs an unchecked cast of an object to a specified type.
     * This method can be used to bypass Java's type checking at compile time,
     * but it comes with the risk of {@link ClassCastException} at runtime if the
     * object is not an instance of the target type.
     *
     * @param o   The object to cast. Can be {@code null}.
     * @param <T> The target type to which the object will be cast.
     * @return The object cast to the specified type, or {@code null} if the input object was {@code null}.
     */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable T unsafeCast(@Nullable Object o)
    {
        return (T) o;
    }

    public static void moveEntity(Entity entity, double x, double y, double z, float yaw, float pitch)
    {
        if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_5))
            Reflections.invokeMethod(entity, "absMoveTo", x, y, z, yaw, pitch);
        else
            Reflections.invokeMethod(entity, "snapTo", x, y, z, yaw, pitch);
    }

    public static ServerLevel getServerLevel(ServerPlayer player)
    {
        return (ServerLevel) Reflections.invokeMethod(player, "level").get();
    }

    public static ServerEntity getServerEntity(Entity entity, ServerLevel level)
    {
        ServerEntity serverEntity;
        if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_5))
            serverEntity = Reflections.getInstanceFirstConstructor(ServerEntity.class, level, entity, 0, false,
                    new Consumer<Packet<?>>()
                    {
                        @Override
                        public void accept(Packet<?> packet)
                        {

                        }

                        @Override
                        public @NotNull Consumer<Packet<?>> andThen(@NotNull Consumer<? super Packet<?>> after)
                        {
                            return Consumer.super.andThen(after);
                        }
                    },
                    Set.of()).orElseThrow();
        else if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_9))
            serverEntity = Reflections.getInstanceFirstConstructor(ServerEntity.class, level, entity, 0, false,
                    new Consumer<Packet<?>>()
                    {
                        @Override
                        public void accept(Packet<?> packet)
                        {

                        }

                        @Override
                        public @NotNull Consumer<Packet<?>> andThen(@NotNull Consumer<? super Packet<?>> after)
                        {
                            return Consumer.super.andThen(after);
                        }
                    },
                    new BiConsumer<Packet<?>, UUID>()
                    {
                        @Override
                        public void accept(Packet<?> packet, UUID uuid)
                        {

                        }

                        @Override
                        public @NotNull BiConsumer<Packet<?>, UUID> andThen(@NotNull BiConsumer<? super Packet<?>, ? super UUID> after)
                        {
                            return BiConsumer.super.andThen(after);
                        }
                    }, Set.of()).orElseThrow();
        else
            serverEntity = Reflections.getInstanceFirstConstructor(ServerEntity.class, level, entity, 0, false,
                    null, Set.of()).orElseThrow();

        return serverEntity;
    }

    /**
     * Converts a WrappedEntitySnapshot.WrappedCompoundTag containing common entity state booleans into a single byte representing the entity flags for accessor
     * 0.
     * <p>
     * Each bit in the returned byte corresponds to a specific entity state:
     * <ul>
     *     <li>Bit 0 (0x01) - HasVisualFire</li>
     *     <li>Bit 1 (0x02) - IsCrouching</li>
     *     <li>Bit 2 (0x04) - IsRiding</li>
     *     <li>Bit 3 (0x08) - IsSprinting</li>
     *     <li>Bit 4 (0x10) - IsSwimming</li>
     *     <li>Bit 5 (0x20) - IsInvisible</li>
     *     <li>Bit 6 (0x40) - IsGlowing</li>
     *     <li>Bit 7 (0x80) - IsFallFlying</li>
     * </ul>
     *
     * @param nbt The WrappedCompoundTag containing the entity state booleans.
     * @return A byte where each bit represents the corresponding entity state as listed above.
     */
    public static byte nbtToEntityFlags(@NotNull CompoundTag nbt)
    {
        byte flags = 0;

        if(nbt.getBooleanOr("HasVisualFire", false))
            flags |= 0x01;

        if(nbt.getBooleanOr("IsCrouching", false))
            flags |= 0x02;

        if(nbt.getBooleanOr("IsRiding", false))
            flags |= 0x04;

        if(nbt.getBooleanOr("IsSprinting", false))
            flags |= 0x08;

        if(nbt.getBooleanOr("IsSwimming", false))
            flags |= 0x10;

        if(nbt.getBooleanOr("IsInvisible", false))
            flags |= 0x20;

        if(nbt.getBooleanOr("IsGlowing", false))
            flags |= 0x40;

        if(nbt.getBooleanOr("IsFallFlying", false))
            flags |= (byte) 0x80;

        return flags;
    }

    /**
     * Extracts the entity flag byte (accessor 0) from the current state of a Bukkit {@link org.bukkit.entity.Entity}.
     * <p>
     * This method interprets Bukkit-level entity state as user intent and converts it into the corresponding Minecraft entity flags. It is intended to be used
     * as an internal translation layer when NMS classes must not be exposed to API consumers.
     * <p>
     * <b>Important:</b> This is a best-effort mapping. Bukkit state is not a perfect mirror
     * of the underlying NMS entity flags, so some flags (such as visual fire or swimming) are approximated based on available Bukkit APIs.
     *
     * <p>Flag mapping:</p>
     * <ul>
     *     <li>Bit 0 (0x01) - Visual fire (fire ticks or visual fire)</li>
     *     <li>Bit 1 (0x02) - Sneaking / crouching</li>
     *     <li>Bit 2 (0x04) - Riding / inside vehicle</li>
     *     <li>Bit 3 (0x08) - Sprinting</li>
     *     <li>Bit 4 (0x10) - Swimming</li>
     *     <li>Bit 5 (0x20) - Invisible</li>
     *     <li>Bit 6 (0x40) - Glowing</li>
     *     <li>Bit 7 (0x80) - Fall flying (elytra gliding)</li>
     * </ul>
     *
     * @param entity The Bukkit entity whose state should be converted into entity flags.
     * @return A byte representing the combined entity flags for accessor 0.
     */
    public static byte extractFlagsFromBukkit(@NotNull org.bukkit.entity.Entity entity)
    {
        byte flags = 0;

        if(entity.getFireTicks() > 0 || entity.getVisualFire().toBoolean())
            flags |= 0x01;

        if(entity instanceof Player player && player.isSneaking())
            flags |= 0x02;

        if(entity.isInsideVehicle())
            flags |= 0x04;

        if(entity instanceof Player player && player.isSprinting())
            flags |= 0x08;

        if(entity instanceof LivingEntity le && le.isSwimming())
            flags |= 0x10;

        if(entity instanceof LivingEntity le && le.isInvisible())
            flags |= 0x20;

        if(entity.isGlowing())
            flags |= 0x40;

        if(entity instanceof LivingEntity le && le.isGliding())
            flags |= (byte) 0x80;

        return flags;
    }

    public static boolean isCarpet(@NotNull Material material)
    {
        return material.name().contains("CARPET");
    }
}
