package de.eisi05.npc.api.utils;

import java.io.Serializable;
import java.util.function.Predicate;

/**
 * A serializable version of {@link Predicate} that can be used in contexts where the predicate needs to be serialized.
 *
 * @param <T> the type of the first argument to the predicate
 */
@FunctionalInterface
public interface SerializablePredicate<T> extends Predicate<T>, Serializable
{
}
