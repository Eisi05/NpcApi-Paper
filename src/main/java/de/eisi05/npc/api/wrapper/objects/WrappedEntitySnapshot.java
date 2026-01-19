package de.eisi05.npc.api.wrapper.objects;

import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.utils.Reflections;
import de.eisi05.npc.api.utils.SerializableFunction;
import de.eisi05.npc.api.utils.Var;
import de.eisi05.npc.api.utils.Versions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftEntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.function.Function;

/**
 * Represents a snapshot of an entity's state that can be serialized and later used to restore the entity. This is particularly useful for NPCs and other custom
 * entities that need to be saved and loaded.
 */
public class WrappedEntitySnapshot implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    private final String type;
    private final byte[] data;
    private final SerializableFunction<? extends Entity, ? extends Entity> entityFunction;

    /**
     * Creates a new entity snapshot with the specified entity type and NBT data.
     *
     * @param type The type of entity this snapshot represents
     * @param data The NBT data containing the entity's properties (can be null)
     */
    public WrappedEntitySnapshot(@NotNull EntityType type, @Nullable CompoundTag data)
    {
        this.type = type.name();

        if(data != null)
        {
            byte[] tempData;
            try
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                NbtIo.writeCompressed(data, baos);
                tempData = baos.toByteArray();
            }
            catch(IOException e)
            {
                tempData = null;
            }

            this.data = tempData;
        }
        else
            this.data = null;

        this.entityFunction = entity -> entity;
    }

    /**
     * Creates a new entity snapshot with a custom entity transformation function.
     *
     * @param type     The type of entity this snapshot represents
     * @param function A function that transforms the created entity before it's used
     */
    public WrappedEntitySnapshot(@NotNull EntityType type, @NotNull SerializableFunction<? extends Entity, ? extends Entity> function)
    {
        this.type = type.name();
        this.data = null;
        this.entityFunction = function;
    }

    /**
     * Creates a new entity snapshot with default NBT data.
     *
     * @param type The type of entity this snapshot represents
     */
    public WrappedEntitySnapshot(@NotNull EntityType type)
    {
        this(type, (CompoundTag) null);
    }

    /**
     * Gets the type of entity this snapshot represents.
     *
     * @return The entity type
     */
    public @NotNull EntityType getType()
    {
        return EntityType.valueOf(type);
    }

    /**
     * Gets the NBT data containing the entity's properties.
     *
     * @return The entity's NBT data
     */
    public @NotNull CompoundTag getData()
    {
        if(data == null)
            return new CompoundTag();

        try
        {
            return NbtIo.readCompressed(new ByteArrayInputStream(data), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        }
        catch(IOException e)
        {
            return new CompoundTag();
        }
    }

    /**
     * Creates a new entity in the specified world using this snapshot's data.
     *
     * @param world The world to create the entity in
     * @return A wrapped entity instance
     * @throws RuntimeException If the entity could not be created
     */
    @SuppressWarnings("unchecked")
    public @NotNull net.minecraft.world.entity.Entity create(@NotNull World world, @NotNull NPC npc)
    {
        net.minecraft.world.entity.Entity entity;
        if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_2))
        {
            getData().putString("id", type.toLowerCase());

            entity = (net.minecraft.world.entity.Entity) Reflections.invokeStaticMethod(net.minecraft.world.entity.EntityType.class, "loadEntityRecursive",
                    getData(), ((CraftWorld) world).getHandle(), Function.identity()).get();
        }
        else if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_9))
        {
            getData().putString("id", type.toLowerCase());

            entity = (net.minecraft.world.entity.Entity) Reflections.invokeStaticMethod(net.minecraft.world.entity.EntityType.class, "loadEntityRecursive",
                    getData(), ((CraftWorld) world).getHandle(), EntitySpawnReason.LOAD, Function.identity()).get();
        }
        else if(Versions.isCurrentVersionSmallerThan(Versions.V1_21_11))
            entity = (net.minecraft.world.entity.Entity) Reflections.invokeStaticMethod(net.minecraft.world.entity.EntityType.class, "loadEntityRecursive",
                    CraftEntityType.bukkitToMinecraft(getType()), getData(), ((CraftWorld) world).getHandle(), EntitySpawnReason.LOAD,
                    Function.identity()).get();
        else
            entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(CraftEntityType.bukkitToMinecraft(getType()), getData(),
                    ((CraftWorld) world).getHandle(), EntitySpawnReason.LOAD, EntityProcessor.NOP);

        npc.data = getData().toString();
        return entityFunction == null ? entity : ((CraftEntity) entityFunction.apply(Var.unsafeCast(entity.getBukkitEntity()))).getHandle();
    }
}
