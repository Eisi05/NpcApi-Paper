package de.eisi05.npc.api.wrapper.packets;

import de.eisi05.npc.api.utils.Var;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class TeleportEntityPacket
{
    public static Object create(@NotNull Entity entity, Vec3 current, Vec3 movement, float yaw, float pitch, Set<?> relatives, boolean onGround)
    {
        Vec3 original = entity.position();
        float originalYaw = entity.getYRot();
        float originalPitch = entity.getXRot();
        Var.moveEntity(entity, current.x, current.y, current.z, yaw, pitch);
        ClientboundTeleportEntityPacket teleportEntityPacket = new ClientboundTeleportEntityPacket(entity);
        Var.moveEntity(entity, original.x, original.y, original.z, originalYaw, originalPitch);
        return teleportEntityPacket;
    }
}
