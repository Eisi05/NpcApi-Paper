package de.eisi05.npc.api.utils;

import java.io.Serializable;
import java.util.function.BiPredicate;

/**
 * A serializable version of {@link BiPredicate} that can be used in contexts where the predicate needs to be serialized.
 *
 * @param <T> the type of the first argument to the predicate
 * @param <U> the type of the second argument to the predicate
 */
@FunctionalInterface
public interface SerializableBiPredicate<T, U> extends BiPredicate<T, U>, Serializable
{
}
