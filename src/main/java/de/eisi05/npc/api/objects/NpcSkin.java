package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.utils.SerializableBiFunction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;

/**
 * Represents the skin data used by an NPC.
 * <p>
 * An {@code NpcSkin} can either be:
 * <ul>
 *     <li><b>Static</b> — using a fixed {@link Skin}.</li>
 *     <li><b>Dynamic</b> — using a {@link SerializableBiFunction} that calculates
 *     a {@link Skin} based on a {@link Player} and {@link NPC}.</li>
 * </ul>
 * This class is serializable, allowing NPCs to persist their skin information.
 * </p>
 */
public class NpcSkin implements SkinData
{
    @Serial
    private static final long serialVersionUID = 1L;

    private final Skin skin;
    private final SerializableBiFunction<Player, NPC, Skin> skinFunction;

    /**
     * Creates a dynamic NPC skin that uses a function to determine the skin.
     *
     * @param skinFunction the serializable function that generates a skin based on player and NPC.
     * @param fallback     the fallback {@link Skin} to use if the function returns {@code null}.
     */
    private NpcSkin(@NotNull SerializableBiFunction<Player, NPC, Skin> skinFunction, @NotNull Skin fallback)
    {
        this.skin = fallback;
        this.skinFunction = skinFunction;
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
    public static @NotNull NpcSkin of(@NotNull SerializableBiFunction<Player, NPC, Skin> skinFunction, @NotNull Skin fallback)
    {
        return new NpcSkin(skinFunction, fallback);
    }

    /**
     * Gets the static skin value.
     *
     * @return the {@link Skin}, or {@code null} if no valid value is set.
     */
    public @Nullable Skin getSkin()
    {
        return skin.value() == null || skin.value().isEmpty() ? null : skin;
    }

    /**
     * Gets the skin for a specific player and NPC.
     * <p>
     * If this is a dynamic skin, the result of the {@link SerializableBiFunction} is returned.
     * Otherwise, the static skin is used.
     * </p>
     *
     * @param player the player for whom to get the skin.
     * @param npc    the NPC whose skin is being retrieved.
     * @return the resolved {@link Skin}, or {@code null} if unavailable.
     */
    public @Nullable Skin getSkin(@NotNull Player player, @NotNull NPC npc)
    {
        return skinFunction != null ? skinFunction.apply(player, npc) : getSkin();
    }

    /**
     * Checks whether this NPC skin is static (i.e., has no dynamic function).
     *
     * @return {@code true} if static, {@code false} if dynamic.
     */
    public boolean isStatic()
    {
        return skinFunction == null;
    }

    /**
     * Creates a deep copy of this {@link NpcSkin}.
     *
     * @return a new {@code NpcSkin} instance with the same configuration.
     */
    public @NotNull NpcSkin copy()
    {
        return isStatic() ? new NpcSkin(skin) : new NpcSkin(skinFunction, skin);
    }

    /**
     * Returns a string representation of this {@link NpcSkin},
     * showing whether it is static or dynamic and its associated skin.
     *
     * @return a string describing this skin.
     */
    @Override
    public String toString()
    {
        return "{" + (isStatic() ? "static" : "dynamic") + " -> " + skin + "}";
    }
}
