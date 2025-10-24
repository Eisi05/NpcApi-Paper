package de.eisi05.npc.api.utils;

import java.io.Serializable;
import java.util.function.Function;

/**
 * A {@link Function} that is also {@link Serializable}.
 * <p>
 * Useful for lambda expressions or method references that need to be
 * serialized, such as in dynamic NPC names.
 *
 * @param <T> the type of the input to the function
 * @param <R> the type of the result of the function
 */
@FunctionalInterface
public interface SerializableFunction<T, R> extends Function<T, R>, Serializable
{
}
