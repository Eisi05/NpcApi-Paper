package de.eisi05.npc.api.utils.serialize;

import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.Skin;
import de.eisi05.npc.api.utils.SerializableBiPredicate;
import de.eisi05.npc.api.utils.TriFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Registry class for managing dynamic callbacks, functions, and expression parsers associated with NPC components (e.g., name formatting, skin generation, goal
 * conditions, and targeting filters).
 */
public final class NpcRegistry
{
    /**
     * Key identifier for PlaceholderAPI-backed functions.
     */
    public static final String KEY_PLACEHOLDER_API = "placeholder";

    /**
     * Key identifier for expression-based condition parsers.
     */
    public static final String EXPRESSION_PARSER = "expression";

    private static final Map<String, BiFunction<Player, String, Component>> NAME_FUNCTIONS = new ConcurrentHashMap<>();
    private static final Map<String, TriFunction<Player, NPC, String, Skin>> SKIN_FUNCTIONS = new ConcurrentHashMap<>();
    private static final Map<String, Function<String, SerializableBiPredicate<Location, NPC>>> CONDITION_PARSERS = new ConcurrentHashMap<>();
    private static final Map<String, Function<String, SerializableBiPredicate<LivingEntity, NPC>>> TARGET_FILTER_PARSERS = new ConcurrentHashMap<>();

    private NpcRegistry() {}

    // ==========================================
    // Name Functions
    // ==========================================

    /**
     * Registers a function used to dynamically compute dynamic name components for players viewing an NPC.
     *
     * @param key      the lookup key identifying the function
     * @param function the function producing a {@link Component} given a viewer and placeholder key
     * @return {@code true} if successfully registered; {@code false} if a mapping already exists for the key
     */
    public static boolean registerNameFunction(@NotNull String key, @NotNull BiFunction<Player, String, Component> function)
    {
        return NAME_FUNCTIONS.putIfAbsent(key.toLowerCase(), function) == null;
    }

    /**
     * Retrieves a registered name function by key.
     *
     * @param key the lookup key
     * @return the associated {@link BiFunction}, or {@code null} if not found
     */
    public static @Nullable BiFunction<Player, String, Component> getNameFunction(@NotNull String key)
    {
        return NAME_FUNCTIONS.get(key.toLowerCase());
    }

    // ==========================================
    // Skin Functions
    // ==========================================

    /**
     * Registers a function used to dynamic resolve NPC skin properties at runtime.
     *
     * @param key      the lookup key identifying the skin resolver
     * @param function the function producing a {@link Skin} given a viewer, target NPC, and placeholder key
     * @return {@code true} if successfully registered; {@code false} if a mapping already exists for the key
     */
    public static boolean registerSkinFunction(@NotNull String key, @NotNull TriFunction<Player, NPC, String, Skin> function)
    {
        return SKIN_FUNCTIONS.putIfAbsent(key.toLowerCase(), function) == null;
    }

    /**
     * Retrieves a registered skin function by key.
     *
     * @param key the lookup key
     * @return the associated {@link TriFunction}, or {@code null} if not found
     */
    public static @Nullable TriFunction<Player, NPC, String, Skin> getSkinFunction(@NotNull String key)
    {
        return SKIN_FUNCTIONS.get(key.toLowerCase());
    }

    // ==========================================
    // Condition Parsers
    // ==========================================

    /**
     * Registers a function capable of parsing conditional expressions into location/NPC predicates.
     *
     * @param key    the lookup key
     * @param parser the parser function translating string expressions into a {@link SerializableBiPredicate}
     * @return {@code true} if successfully registered; {@code false} if a mapping already exists for the key
     */
    public static boolean registerConditionParser(@NotNull String key, @NotNull Function<String, SerializableBiPredicate<Location, NPC>> parser)
    {
        return CONDITION_PARSERS.putIfAbsent(key.toLowerCase(), parser) == null;
    }

    /**
     * Registers a function capable of parsing expression strings into living entity targeting filter predicates.
     *
     * @param key    the lookup key
     * @param parser the parser function translating string expressions into a {@link SerializableBiPredicate}
     * @return {@code true} if successfully registered; {@code false} if a mapping already exists for the key
     */
    public static boolean registerTargetFilterParser(@NotNull String key, @NotNull Function<String, SerializableBiPredicate<LivingEntity, NPC>> parser)
    {
        return TARGET_FILTER_PARSERS.putIfAbsent(key.toLowerCase(), parser) == null;
    }

    /**
     * Compiles a conditional expression string into a location predicate using a registered condition parser.
     *
     * @param key        the condition parser registration key
     * @param expression the string expression to compile
     * @return the compiled {@link SerializableBiPredicate}, or a default predicate returning {@code false} if parser missing
     */
    public static @Nullable SerializableBiPredicate<Location, NPC> compileCondition(@NotNull String key, @Nullable String expression)
    {
        Function<String, SerializableBiPredicate<Location, NPC>> parser = CONDITION_PARSERS.get(key.toLowerCase());
        return parser != null ? parser.apply(expression) : (loc, npc) -> false;
    }

    /**
     * Compiles a targeting filter expression string into an entity predicate using a registered target filter parser.
     *
     * @param key        the target filter parser registration key
     * @param expression the string expression to compile
     * @return the compiled {@link SerializableBiPredicate}, or a default predicate returning {@code false} if parser missing
     */
    public static @Nullable SerializableBiPredicate<LivingEntity, NPC> compileTargetFilter(@NotNull String key, @Nullable String expression)
    {
        Function<String, SerializableBiPredicate<LivingEntity, NPC>> parser = TARGET_FILTER_PARSERS.get(key.toLowerCase());
        return parser != null ? parser.apply(expression) : (loc, npc) -> false;
    }
}