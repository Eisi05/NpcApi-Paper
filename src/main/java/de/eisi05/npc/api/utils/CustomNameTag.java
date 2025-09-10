package de.eisi05.npc.api.utils;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.Optional;

public class CustomNameTag
{
    public static Object applyData(ArmorStand armorStand, Component component)
    {
        SynchedEntityData data = armorStand.getEntityData();
        data.set(EntityDataSerializers.BYTE.createAccessor(0), (byte) 0x20);
        data.set(EntityDataSerializers.OPTIONAL_COMPONENT.createAccessor(2), Optional.of(component));
        data.set(EntityDataSerializers.BOOLEAN.createAccessor(4), true);
        data.set(EntityDataSerializers.BYTE.createAccessor(15), (byte) 0x10);

        return data;
    }
}
