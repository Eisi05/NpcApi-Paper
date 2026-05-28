package de.eisi05.npc.api.utils;

import java.io.Serializable;
import java.util.function.Consumer;

/**
 * A functional interface that combines {@link Consumer} and {@link Serializable}.
 * <p>
 * This interface allows lambda expressions to be used as consumers while also being serializable, which is useful for scenarios where consumer logic needs to
 * be persisted or transmitted across different contexts.
 *
 * @param <T> the type of the input to the operation
 */
@FunctionalInterface
public interface SerializableConsumer<T> extends Consumer<T>, Serializable
{
}
