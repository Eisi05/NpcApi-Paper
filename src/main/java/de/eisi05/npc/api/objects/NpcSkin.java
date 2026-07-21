package de.eisi05.npc.api.objects;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import de.eisi05.npc.api.scheduler.Tasks;
import de.eisi05.npc.api.utils.Reflections;
import de.eisi05.npc.api.utils.TriFunction;
import de.eisi05.npc.api.utils.serialize.NpcRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the skin data used by an NPC.
 * <p>
 * An {@code NpcSkin} can be:
 * <ul>
 *     <li><b>Static</b> — using a fixed {@link Skin}.</li>
 *     <li><b>Dynamic</b> — using a registered function key that resolves a {@link Skin}
 *     based on a {@link Player}, {@link NPC}, and placeholder string.</li>
 *     <li><b>Placeholder-based</b> — using PlaceholderAPI to resolve skins from placeholders.</li>
 * </ul>
 * This class is serializable, allowing NPCs to persist their skin information.
 * </p>
 */
@JsonAdapter(NpcSkin.NpcSkinAdapter.class)
public class NpcSkin implements SkinData
{
    @Serial
    private static final long serialVersionUID = 1L;

    static
    {
        NpcRegistry.registerSkinFunction(NpcRegistry.KEY_PLACEHOLDER_API, (player, npc, placeholder) ->
        {
            if(!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                return null;

            String newPlaceholder = Tasks.placeholderCache
                    .computeIfAbsent(npc.getUUID(), k -> new ConcurrentHashMap<>())
                    .get(player.getUniqueId());

            if(newPlaceholder == null)
                newPlaceholder = (String) Reflections.invokeStaticMethod("me.clip.placeholderapi.PlaceholderAPI", "setPlaceholders", player, placeholder).get();

            if(newPlaceholder == null)
                return null;

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
        });
    }

    private final Skin skin;
    private final String placeholder;
    private final String skinFunctionKey;

    /**
     * Creates a dynamic NPC skin using a registered function key.
     *
     * @param skinFunctionKey the registry key corresponding to a registered skin function
     * @param placeholder     the placeholder string evaluated by the function
     * @param fallback        the fallback {@link Skin} if resolution fails or returns {@code null}
     */
    private NpcSkin(@NotNull String skinFunctionKey, @NotNull String placeholder, @Nullable Skin fallback)
    {
        this.skin = fallback;
        this.skinFunctionKey = skinFunctionKey;
        this.placeholder = placeholder;
    }


    /**
     * Creates a static NPC skin that always uses the given {@link Skin}.
     *
     * @param skin the static skin to apply.
     */
    private NpcSkin(@NotNull Skin skin)
    {
        this.skin = skin;
        this.skinFunctionKey = null;
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
     * Creates a new dynamic {@link NpcSkin} that determines the skin at runtime using a registered registry key.
     *
     * @param skinFunctionKey the registry key identifying the skin function to evaluate
     * @param placeholder     the placeholder string passed to the function
     * @param fallback        the fallback skin if the function fails or returns {@code null}
     * @return a new dynamic {@code NpcSkin}
     */
    public static @NotNull NpcSkin of(@NotNull String skinFunctionKey, @NotNull String placeholder, @Nullable Skin fallback)
    {
        return new NpcSkin(skinFunctionKey, placeholder, fallback);
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
        return new NpcSkin(NpcRegistry.KEY_PLACEHOLDER_API, placeholder, fallback);
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
     * If this is a dynamic skin, the result of the registered {@link TriFunction} is returned. The function receives the player, NPC, and placeholder string as
     * parameters. If the function returns null or this is a static skin, the fallback skin is used.
     * </p>
     *
     * @param player the player for whom to get the skin
     * @param npc    the NPC whose skin is being retrieved
     * @return the resolved {@link Skin}, or the fallback skin if unavailable, or {@code null} if no fallback exists
     */
    public @Nullable Skin getSkin(@NotNull Player player, @NotNull NPC npc)
    {
        if(isStatic())
            return getSkin();

        TriFunction<Player, NPC, String, Skin> function = NpcRegistry.getSkinFunction(skinFunctionKey.toLowerCase());
        if(function != null)
        {
            try
            {
                Skin resolved = function.apply(player, npc, placeholder);
                if(resolved != null)
                    return resolved;
            }
            catch(Exception ignored) {}
        }

        return getSkin();
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
        return skinFunctionKey == null;
    }

    /**
     * Gets the registry key identifying the skin function to evaluate.
     *
     * @return the registry key, or {@code null} if this is a static skin.
     */
    public @Nullable String getSkinFunctionKey()
    {
        return skinFunctionKey;
    }

    /**
     * Creates a deep copy of this {@link NpcSkin}.
     *
     * @return a new {@code NpcSkin} instance with the same configuration.
     */
    public @NotNull NpcSkin copy()
    {
        return isStatic() ? new NpcSkin(skin) : new NpcSkin(skinFunctionKey, placeholder, skin);
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

    /**
     * Gson JSON adapter for serializing and deserializing {@link NpcSkin} objects.
     */
    static class NpcSkinAdapter implements JsonSerializer<NpcSkin>, JsonDeserializer<NpcSkin>
    {
        @Override
        public JsonElement serialize(NpcSkin src, Type typeOfSrc, JsonSerializationContext context)
        {
            if(src == null)
                return JsonNull.INSTANCE;

            JsonObject obj = new JsonObject();

            if(src.getSkin() != null)
            {
                JsonElement skinElem = context.serialize(src.getSkin());
                if(skinElem != null && skinElem.isJsonObject())
                {
                    for(var entry : skinElem.getAsJsonObject().entrySet())
                        obj.add(entry.getKey(), entry.getValue());
                }
            }

            if(src.skinFunctionKey != null)
                obj.addProperty(src.skinFunctionKey, src.getPlaceholder() != null ? src.getPlaceholder() : "");
            else if(src.getPlaceholder() != null)
                obj.addProperty(NpcRegistry.KEY_PLACEHOLDER_API, src.getPlaceholder());

            return obj;
        }

        @Override
        public NpcSkin deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException
        {
            if(json == null || json.isJsonNull())
                return null;

            JsonObject obj = json.getAsJsonObject();

            Skin fallback = null;
            if(obj.has("fallbackSkin"))
                fallback = context.deserialize(obj.get("fallbackSkin"), Skin.class);
            else if(obj.has("skin"))
                fallback = context.deserialize(obj.get("skin"), Skin.class);
            else if(obj.has("value") || obj.has("signature"))
                fallback = context.deserialize(obj, Skin.class);

            String functionKey = null;
            String placeholder = null;

            if(obj.has("skinFunctionKey"))
            {
                functionKey = obj.get("skinFunctionKey").getAsString();
                placeholder = obj.has("placeholder") ? obj.get("placeholder").getAsString() : null;
            }
            else
            {
                for(var entry : obj.entrySet())
                {
                    String key = entry.getKey();
                    if(key.equals("fallbackSkin") || key.equals("skin") || key.equals("value") || key.equals("signature"))
                        continue;

                    functionKey = key;
                    JsonElement val = entry.getValue();
                    placeholder = (val != null && !val.isJsonNull() && !val.getAsString().isEmpty()) ? val.getAsString() : null;
                    break;
                }

                if(functionKey == null && obj.has("placeholder"))
                    placeholder = obj.get("placeholder").getAsString();
            }

            if(functionKey == null && placeholder != null)
                functionKey = NpcRegistry.KEY_PLACEHOLDER_API;

            if(functionKey != null)
                return NpcSkin.of(functionKey, placeholder, fallback);

            return NpcSkin.of(fallback);
        }
    }
}
