package de.eisi05.npc.api.utils;

import java.io.Serializable;
import java.util.function.BiFunction;

/**
 * A {@link BiFunction} that is also {@link Serializable}.
 * <p>
 * This allows you to use BiFunctions (often implemented as lambdas) in places where
 * objects need to be serialized, such as saving plugin data or network transfers.
 * </p>
 *
 * @param <T> the type of the first input argument
 * @param <U> the type of the second input argument
 * @param <R> the type of the result
 */
@FunctionalInterface
public interface SerializableBiFunction<T, U, R> extends BiFunction<T, U, R>, Serializable
{
}
