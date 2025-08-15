package de.eisi05.npc.api.wrapper.packets;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

public class AnimatePacket
{
    public static Packet<?> create(@NotNull ServerPlayer player, @NotNull Animation animation)
    {
        if(animation != Animation.HURT)
            return new ClientboundAnimatePacket(player, animation.ordinal());

        return new ClientboundHurtAnimationPacket(player);
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
