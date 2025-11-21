package de.eisi05.npc.api.utils;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.Material;
import org.bukkit.Tag;
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

    public static boolean isCarpet(@NotNull Material material)
    {
        return Tag.WOOL_CARPETS.isTagged(material) || material == Material.MOSS_CARPET || material == Material.PALE_MOSS_CARPET;
    }
}
