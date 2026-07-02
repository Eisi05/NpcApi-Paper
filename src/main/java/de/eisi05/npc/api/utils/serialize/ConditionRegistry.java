package de.eisi05.npc.api.utils.serialize;

import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.utils.SerializableBiPredicate;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.function.Function;

/**
 * A centralized registry bridge that allows the decoupled API layer to compile string-based predicate expressions without having direct compile-time access to
 * the plugin's internal parsing engine.
 * <p>
 * The plugin implementation must register the parsing functions during its initialization phase so that GSON deserializers can reconstruct raw expression paths
 * back into live executable predicates.
 * </p>
 */
public final class ConditionRegistry
{
    /**
     * The bridge function provided by the plugin to parse structural goal conditions.
     */
    private static Function<String, SerializableBiPredicate<Location, NPC>> conditionParser = null;

    /**
     * The bridge function provided by the plugin to parse combat target filtering conditions.
     */
    private static Function<String, SerializableBiPredicate<LivingEntity, NPC>> targetFilterParser = null;

    /**
     * Private constructor to prevent instantiation of this utility bridge class.
     */
    private ConditionRegistry() {}

    /**
     * Registers the parser implementation used to handle evaluation conditions for general AI behaviors.
     * <p>
     * Typically invoked by the plugin's main entry point during initialization.
     * </p>
     *
     * @param bridge A functional mapping that compiles a raw String expression into a {@link SerializableBiPredicate} centered around a {@link Location} and an
     *               {@link NPC}.
     */
    public static void registerConditionParser(Function<String, SerializableBiPredicate<Location, NPC>> bridge)
    {
        conditionParser = bridge;
    }

    /**
     * Registers the parser implementation used to handle targeted filter evaluations during entity combat searching.
     * <p>
     * Typically invoked by the plugin's main entry point during initialization.
     * </p>
     *
     * @param bridge A functional mapping that compiles a raw String expression into a {@link SerializableBiPredicate} centered around a {@link LivingEntity}
     *               and an {@link NPC}.
     */
    public static void registerTargetFilterParser(Function<String, SerializableBiPredicate<LivingEntity, NPC>> bridge)
    {
        targetFilterParser = bridge;
    }

    /**
     * Compiles a string-based condition expression into an executable bi-predicate format.
     * <p>
     * If no parser has been linked by the plugin implementation layer yet, a fallback predicate that consistently evaluates to {@code false} is returned to
     * maintain stability.
     * </p>
     *
     * @param expression The raw configuration string expression to evaluate.
     * @return An executable {@link SerializableBiPredicate} bound to the configuration string, or a safe fallback evaluating to false if the registry is
     * unbound.
     */
    public static SerializableBiPredicate<Location, NPC> compileCondition(String expression)
    {
        if(conditionParser == null)
            return (location, npc) -> false;
        return conditionParser.apply(expression);
    }

    /**
     * Compiles a string-based combat targeting expression into an executable bi-predicate format.
     * <p>
     * If no parser has been linked by the plugin implementation layer yet, a fallback predicate that consistently evaluates to {@code false} is returned to
     * maintain stability.
     * </p>
     *
     * @param expression The raw configuration string expression to evaluate against potential combat targets.
     * @return An executable {@link SerializableBiPredicate} bound to the configuration string, or a safe fallback evaluating to false if the registry is
     * unbound.
     */
    public static SerializableBiPredicate<LivingEntity, NPC> compileTargetFilter(String expression)
    {
        if(targetFilterParser == null)
            return (entity, npc) -> false;
        return targetFilterParser.apply(expression);
    }
}