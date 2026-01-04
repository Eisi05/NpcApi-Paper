package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.utils.Reflections;
import de.eisi05.npc.api.utils.SerializableBiFunction;
import de.eisi05.npc.api.utils.TriFunction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serial;
import java.util.UUID;

/**
 * Represents the skin data used by an NPC.
 * <p>
 * An {@code NpcSkin} can be:
 * <ul>
 *     <li><b>Static</b> — using a fixed {@link Skin}.</li>
 *     <li><b>Dynamic</b> — using a {@link TriFunction} that calculates
 *     a {@link Skin} based on a {@link Player}, {@link NPC}, and a placeholder string.</li>
 *     <li><b>Placeholder-based</b> — using PlaceholderAPI to resolve skins from placeholders.</li>
 * </ul>
 * This class is serializable, allowing NPCs to persist their skin information.
 * </p>
 */
public class NpcSkin implements SkinData
{
    @Serial
    private static final long serialVersionUID = 1L;

    private final Skin skin;

    @Deprecated(since = "1.21.x-20")
    private final SerializableBiFunction<Player, NPC, Skin> skinFunction;

    private final String placeholder;
    private TriFunction<Player, NPC, String, Skin> newSkinFunction;

    /**
     * Creates a dynamic NPC skin that uses a function to determine the skin.
     *
     * @param skinFunction the function that generates a skin based on player, NPC, and placeholder string.
     * @param placeholder  the placeholder string to be used for skin resolution.
     * @param fallback     the fallback {@link Skin} to use if the function returns {@code null}.
     */
    private NpcSkin(@NotNull TriFunction<Player, NPC, String, Skin> skinFunction, @NotNull String placeholder, @Nullable Skin fallback)
    {
        this.skin = fallback;
        this.newSkinFunction = skinFunction;
        this.placeholder = placeholder;
        this.skinFunction = null;
    }


    /**
     * Creates a static NPC skin that always uses the given {@link Skin}.
     *
     * @param skin the static skin to apply.
     */
    private NpcSkin(@NotNull Skin skin)
    {
        this.skin = skin;
        this.skinFunction = null;
        this.newSkinFunction = null;
        this.placeholder = null;
    }

    /**
     * Creates a new static {@link NpcSkin}.
     *
     * @param skin the fixed {@link Skin} to apply.
     * @return a new static {@code NpcSkin}.
     */
    public static @NotNull NpcSkin of(@NotNull Skin skin)
    {
        return new NpcSkin(skin);
    }

    /**
     * Creates a new dynamic {@link NpcSkin} that determines the skin at runtime.
     *
     * @param skinFunction the function used to calculate the {@link Skin}.
     * @param fallback     the fallback skin if the function fails or returns {@code null}.
     * @return a new dynamic {@code NpcSkin}.
     */
    public static @NotNull NpcSkin of(@NotNull TriFunction<Player, NPC, String, Skin> skinFunction, @NotNull String placeholder, @Nullable Skin fallback)
    {
        return new NpcSkin(skinFunction, placeholder, fallback);
    }

    /**
     * Creates a new {@link NpcSkin} that resolves its skin using PlaceholderAPI.
     * <p>
     * This method creates a dynamic skin that evaluates the provided placeholder string for each player to determine the skin. The placeholder should resolve
     * to either:
     * <ul>
     *     <li>A valid UUID string (with or without hyphens)</li>
     *     <li>A valid Minecraft username</li>
     * </ul>
     * </p>
     * <p>
     * The skin is resolved asynchronously when needed. If the placeholder cannot be resolved
     * or the skin cannot be fetched, the fallback skin will be used.
     * </p>
     * <p>
     * Example usage:
     * <pre>{@code
     * // Create a skin that uses a player's skin based on their name
     * NpcSkin skin = NpcSkin.ofPlaceholderAPI("%player_name%", defaultSkin);
     * }</pre>
     * </p>
     *
     * @param placeholder The PlaceholderAPI placeholder string to resolve (e.g., "%player_name%")
     * @param fallback    The fallback skin to use if the placeholder cannot be resolved or the skin cannot be fetched. Can be {@code null}.
     * @return A new {@link NpcSkin} instance that resolves its skin using the specified placeholder
     * @throws IllegalArgumentException if the placeholder is null
     * @see Skin#fetchSkin(String)
     * @see Skin#fetchSkin(UUID)
     */
    public static @NotNull NpcSkin ofPlaceholderAPI(@NotNull String placeholder, @Nullable Skin fallback)
    {
        return of((player, npc, s) ->
        {
            String newPlaceholder = (String) Reflections.invokeStaticMethod("me.clip.placeholderapi.PlaceholderAPI",
                    "setPlaceholders", player, s).get();
            try
            {
                UUID uuid = UUID.fromString(newPlaceholder);

                if(Skin.isPreLoaded(uuid))
                    return Skin.fetchSkin(uuid).orElse(null);
            }
            catch(IllegalArgumentException e)
            {
                if(Skin.isPreLoaded(newPlaceholder))
                    return Skin.fetchSkin(newPlaceholder).orElse(null);
            }
            return null;
        }, placeholder, fallback);
    }

    @Serial
    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException
    {
        in.defaultReadObject();

        if(newSkinFunction == null)
            newSkinFunction = ((player, npc, s) -> skinFunction != null ? skinFunction.apply(player, npc) : null);
    }

    /**
     * Gets the static skin value.
     *
     * @return the {@link Skin}, or {@code null} if no valid value is set.
     */
    public @Nullable Skin getSkin()
    {
        return skin == null || skin.value() == null || skin.value().isEmpty() ? null : skin;
    }

    /**
     * Gets the skin for a specific player and NPC.
     * <p>
     * If this is a dynamic skin, the result of the {@link TriFunction} is returned. The function receives the player, NPC, and placeholder string as
     * parameters. If the function returns null or this is a static skin, the fallback skin is used.
     * </p>
     *
     * @param player the player for whom to get the skin.
     * @param npc    the NPC whose skin is being retrieved.
     * @return the resolved {@link Skin}, or the fallback skin if unavailable, or {@code null} if no fallback exists.
     */
    public @Nullable Skin getSkin(@NotNull Player player, @NotNull NPC npc)
    {
        Skin skin = null;
        if(newSkinFunction != null)
            skin = newSkinFunction.apply(player, npc, placeholder);
        return skin != null ? skin : getSkin();
    }

    /**
     * Gets the placeholder string used for dynamic skin resolution.
     *
     * @return the placeholder string, or {@code null} if this is a static skin.
     */
    public @Nullable String getPlaceholder()
    {
        return placeholder;
    }


    /**
     * Checks whether this NPC skin is static (i.e., has no dynamic function).
     *
     * @return {@code true} if static, {@code false} if dynamic.
     */
    public boolean isStatic()
    {
        return newSkinFunction == null;
    }

    /**
     * Creates a deep copy of this {@link NpcSkin}.
     *
     * @return a new {@code NpcSkin} instance with the same configuration.
     */
    public @NotNull NpcSkin copy()
    {
        return isStatic() ? new NpcSkin(skin) : new NpcSkin(newSkinFunction, placeholder, skin);
    }

    /**
     * Returns a string representation of this {@link NpcSkin}, showing whether it is static or dynamic and its associated skin.
     *
     * @return a string describing this skin.
     */
    @Override
    public String toString()
    {
        return "{" + (isStatic() ? "static" : "dynamic") + " -> " + skin + "}";
    }
}
