package de.eisi05.npc.api.wrapper.packets;

import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import org.jetbrains.annotations.NotNull;

public class SetEntityDataPacket
{
    public static ClientboundSetEntityDataPacket create(int id, @NotNull SynchedEntityData data)
    {
        return new ClientboundSetEntityDataPacket(id, data.packAll());
    }
}
