package de.eisi05.npc.api.utils;

import org.jetbrains.annotations.Nullable;

public class Var
{
    /**
     * Performs an unchecked cast of an object to a specified type.
     * This method can be used to bypass Java's type checking at compile time,
     * but it comes with the risk of {@link ClassCastException} at runtime if the
     * object is not an instance of the target type.
     *
     * @param o   The object to cast. Can be {@code null}.
     * @param <T> The target type to which the object will be cast.
     * @return The object cast to the specified type, or {@code null} if the input object was {@code null}.
     */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable T unsafeCast(@Nullable Object o)
    {
        return (T) o;
    }
}
