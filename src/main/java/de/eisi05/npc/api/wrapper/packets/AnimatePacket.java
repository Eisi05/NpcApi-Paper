package de.eisi05.npc.api.wrapper.packets;

import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

public class AnimatePacket
{
    public static @Nullable Object create(@NotNull Entity entity, @NotNull Animation animation)
    {
        if(animation != Animation.HURT)
            return new ClientboundAnimatePacket(entity, animation.ordinal());

        if(entity instanceof LivingEntity le)
            return new ClientboundHurtAnimationPacket(le);

        return null;
    }

    public enum Animation implements Serializable
    {
        SWING_MAIN_HAND,
        HURT,
        WAKE_UP,
        SWING_OFF_HAND,
        CRITICAL_HIT,
        MAGIC_CRITICAL_HIT
    }
}
