package de.eisi05.npc.api.utils;

import de.eisi05.npc.api.objects.NPC;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Class holding a key-expression tuple referencing a predicate compiled or managed via {@link de.eisi05.npc.api.utils.serialize.NpcRegistry}.
 *
 * @param <T> the type of entity, location, or target evaluated against the NPC
 */
public class RegistryPredicate<T> implements SerializableBiPredicate<T, NPC>
{
    protected final String key;
    protected final String expression;

    /**
     * Constructs a new {@code RegistryPredicate}.
     *
     * @param key        the key identifying the condition or parser registered in {@link de.eisi05.npc.api.utils.serialize.NpcRegistry}
     * @param expression the raw condition expression string to be parsed or evaluated
     */
    public RegistryPredicate(@NotNull String key, @Nullable String expression)
    {
        this.key = key;
        this.expression = expression;
    }

    /**
     * @return the key identifying the condition or parser
     */
    @NotNull
    public String key()
    {
        return key;
    }

    /**
     * @return the raw condition expression string
     */
    @Nullable
    public String expression()
    {
        return expression;
    }

    /**
     * Evaluates this predicate on the given arguments.
     *
     * @param object  the primary target or context object being evaluated
     * @param object2 the target NPC
     * @return always {@code false} in baseline instance; delegated runtime behavior is provided upon compilation
     */
    @Override
    public boolean test(T object, NPC object2)
    {
        return false;
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
            return true;
        if(o == null || getClass() != o.getClass())
            return false;
        RegistryPredicate<?> that = (RegistryPredicate<?>) o;
        return Objects.equals(key, that.key) && Objects.equals(expression, that.expression);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(key, expression);
    }

    @Override
    public String toString()
    {
        return "RegistryPredicate[" +
                "key=" + key + ", " +
                "expression=" + expression + ']';
    }
}