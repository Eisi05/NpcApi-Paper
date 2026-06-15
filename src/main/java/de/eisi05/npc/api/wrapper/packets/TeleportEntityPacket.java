package de.eisi05.npc.api.wrapper.packets;

import de.eisi05.npc.api.utils.Reflections;
import de.eisi05.npc.api.utils.Var;
import de.eisi05.npc.api.utils.Versions;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class TeleportEntityPacket
{
    public static Object create(@NotNull Entity entity, Vec3 current, Vec3 movement, float yaw, float pitch, Set<?> relatives, boolean onGround)
    {
        if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_2))
        {
            Vec3 original = entity.position();
            float originalYaw = entity.getYRot();
            float originalPitch = entity.getXRot();
            Var.moveEntity(entity, current.x, current.y, current.z, yaw, pitch);
            Object instance = Reflections.getInstance(ClientboundTeleportEntityPacket.class, entity).orElse(null);
            Var.moveEntity(entity, original.x, original.y, original.z, originalYaw, originalPitch);
            return instance;
        }

        return new ClientboundTeleportEntityPacket(entity.getId(),
                new net.minecraft.world.entity.PositionMoveRotation(current, movement, yaw, pitch), Var.unsafeCast(relatives), onGround);
    }
}
