package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.utils.SerializableFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents the name of an NPC, which can be either a fixed {@link String}
 * or dynamically generated based on a {@link Player}.
 */
public class NpcName implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    private final String nameComponentSerialized;
    private final SerializableFunction<Player, String> nameFunctionSerialized;

    private transient final Component nameComponent;
    private transient final SerializableFunction<Player, Component> nameFunction;

    /**
     * Creates a static NPC name.
     *
     * @param nameComponent the fixed name component
     */
    private NpcName(@NotNull Component nameComponent)
    {
        this.nameComponent = nameComponent;
        this.nameFunction = null;

        this.nameComponentSerialized = JSONComponentSerializer.json().serialize(nameComponent);
        this.nameFunctionSerialized = null;
    }

    /**
     * Creates a dynamic NPC name with a fallback static component.
     * <p>
     * The {@code nameFunction} generates the name for a player, but if needed,
     * {@code fallback} will be used as a default static name.
     *
     * @param nameFunction the function producing the name for a given player
     * @param fallback     the static fallback name component
     */
    private NpcName(@NotNull SerializableFunction<Player, Component> nameFunction, @NotNull Component fallback)
    {
        this.nameComponent = fallback;
        this.nameFunction = nameFunction;

        this.nameComponentSerialized = JSONComponentSerializer.json().serialize(fallback);
        this.nameFunctionSerialized = player -> JSONComponentSerializer.json().serialize(nameFunction.apply(player));
    }

    /**
     * Creates a new {@link NpcName} with a static name.
     *
     * @param name the fixed name component
     * @return a new NpcName instance
     */
    public static @NotNull NpcName of(@NotNull Component name)
    {
        return new NpcName(name);
    }

    /**
     * Creates a new {@link NpcName} with a dynamic function and a fallback name.
     *
     * @param nameFunction the function producing the name for a given player
     * @param fallback     the static fallback name component
     * @return a new NpcName instance
     */
    public static @NotNull NpcName of(@NotNull SerializableFunction<Player, Component> nameFunction, @NotNull Component fallback)
    {
        return new NpcName(nameFunction, fallback);
    }

    /**
     * Creates an empty NPC name.
     * <p>
     * This returns an {@link NpcName} with a {@link Component} containing no content.
     *
     * @return a new NpcName representing an empty name
     */
    public static @NotNull NpcName empty()
    {
        return NpcName.of(Component.empty());
    }

    @Serial
    private Object readResolve() throws ObjectStreamException
    {
        if(nameFunctionSerialized == null)
            return new NpcName(JSONComponentSerializer.json().deserialize(nameComponentSerialized));

        return new NpcName(player -> JSONComponentSerializer.json().deserialize(nameFunctionSerialized.apply(player)),
                JSONComponentSerializer.json().deserialize(nameComponentSerialized));
    }

    /**
     * Checks if this NPC name is static (fixed) or dynamic.
     *
     * @return true if the name is static, false if dynamic
     */
    public boolean isStatic()
    {
        return nameFunction == null;
    }

    /**
     * Gets the static name component, if present.
     *
     * @return the static name component, or null if the name is dynamic
     */
    public @Nullable Component getName()
    {
        return nameComponent;
    }

    /**
     * Gets the NPC name for a specific player.
     *
     * @param player the player to generate the name for
     * @return the name component for the player, or null if this is a static name and no function is defined
     */
    public @Nullable Component getName(@Nullable Player player)
    {
        if(nameFunction == null || player == null)
            return nameComponent;

        return nameFunction.apply(player);
    }

    /**
     * Creates a copy of this NpcName instance.
     *
     * @return a new NpcName with the same name component or name function
     */
    public @NotNull NpcName copy()
    {
        return isStatic() ? new NpcName(nameComponent) : new NpcName(nameFunction, nameComponent);
    }

    @Override
    public String toString()
    {
        return "{" + (isStatic() ? "static" : "dynamic") + " -> " + getName().toString() + "}";
    }
}
