package de.eisi05.npc.api.wrapper.packets;

import de.eisi05.npc.api.utils.Reflections;
import de.eisi05.npc.api.utils.Versions;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Arrays;

public class AnimatePacket
{
    private static final int DEFAULT_DURATION_WHACK = 6;
    private static final int DEFAULT_DURATION_STAB = 12;

    public static @Nullable Object create(@NotNull Entity entity, @NotNull Animation animation)
    {
        if(animation != Animation.HURT)
        {
            if(!Versions.isCurrentVersionSmallerThan(Versions.V26_3))
            {
                return switch(animation)
                {
                    case WAKE_UP -> new ClientboundAnimatePacket(entity, 0);
                    case CRITICAL_HIT -> new ClientboundAnimatePacket(entity, 1);
                    case MAGIC_CRITICAL_HIT -> new ClientboundAnimatePacket(entity, 2);
                    case SWING_MAIN_HAND -> Reflections.getInstance("net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket", entity.getId(),
                            InteractionHand.MAIN_HAND, Reflections.getInstance("net.minecraft.world.item.component.SwingAnimation",
                                    Reflections.getStaticField("net.minecraft.world.item.SwingAnimationType", "WHACK"),
                                    DEFAULT_DURATION_WHACK).orElseThrow()).orElse(null);
                    case SWING_OFF_HAND -> Reflections.getInstance("net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket", entity.getId(),
                            InteractionHand.OFF_HAND, Reflections.getInstance("net.minecraft.world.item.component.SwingAnimation",
                                    Reflections.getStaticField("net.minecraft.world.item.SwingAnimationType", "WHACK"),
                                    DEFAULT_DURATION_WHACK).orElseThrow()).orElse(null);
                    case STAB_MAIN_HAND -> Reflections.getInstance("net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket", entity.getId(),
                            InteractionHand.MAIN_HAND, Reflections.getInstance("net.minecraft.world.item.component.SwingAnimation",
                                    Reflections.getStaticField("net.minecraft.world.item.SwingAnimationType", "STAB"),
                                    DEFAULT_DURATION_STAB).orElseThrow()).orElse(null);
                    case STAB_OFF_HAND -> Reflections.getInstance("net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket", entity.getId(),
                            InteractionHand.OFF_HAND, Reflections.getInstance("net.minecraft.world.item.component.SwingAnimation",
                                    Reflections.getStaticField("net.minecraft.world.item.SwingAnimationType", "STAB"),
                                    DEFAULT_DURATION_STAB).orElseThrow()).orElse(null);
                    default -> null;
                };
            }

            return new ClientboundAnimatePacket(entity, animation.ordinal());
        }

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
        MAGIC_CRITICAL_HIT,
        /**
         * @since 26.3
         */
        STAB_MAIN_HAND,
        /**
         * @since 26.3
         */
        STAB_OFF_HAND;

        public static Animation[] getCompatibleAnimations()
        {
            if(Versions.isCurrentVersionSmallerThan(Versions.V26_3))
                return Arrays.stream(values()).filter(animation -> animation != STAB_MAIN_HAND && animation != STAB_OFF_HAND).toArray(Animation[]::new);
            return values();
        }
    }
}
