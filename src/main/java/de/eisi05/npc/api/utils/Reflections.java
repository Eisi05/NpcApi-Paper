package de.eisi05.npc.api.utils;

import com.google.common.primitives.Primitives;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * Utility class providing methods for reflective operations such as loading classes,
 * invoking methods, and accessing fields dynamically at runtime.
 */
@SuppressWarnings("unchecked")
public class Reflections
{
    /**
     * Loads a class by its fully qualified name.
     *
     * @param path fully qualified class name
     * @param <T>  type of the class
     * @return Optional containing the Class if found, empty otherwise
     */
    public static <T> @NotNull Optional<Class<T>> getClass(@NotNull String path)
    {
        try
        {
            return Optional.of((Class<T>) Class.forName(path));
        } catch(ClassNotFoundException e)
        {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Instantiates an object of the given class using the constructor that matches the argument types.
     *
     * @param path fully qualified class name
     * @param args constructor arguments
     * @param <T>  type of the instance
     * @return Optional containing the instance if created successfully, empty otherwise
     */
    public static <T> @NotNull Optional<T> getInstance(@NotNull String path, @Nullable Object... args)
    {
        return getClass(path).flatMap(objectClass -> getInstance((Class<T>) objectClass, args));
    }

    public static <T> @NotNull Optional<T> getInstanceFirstConstructor(@NotNull Class<T> clazz, @Nullable Object... args)
    {
        try
        {
            Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return Optional.of((T) ctor.newInstance(args));
        } catch(Exception e)
        {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    public static <T> @NotNull Optional<T> getInstance(@NotNull Class<T> clazz, @Nullable Object... args)
    {
        try
        {
            Class<?>[] argTypes = Arrays.stream(args)
                    .map(Object::getClass)
                    .toArray(Class[]::new);
            Constructor<?> ctor = clazz.getDeclaredConstructor(argTypes);
            ctor.setAccessible(true);
            return Optional.of((T) ctor.newInstance(args));
        } catch(Exception e)
        {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    /**
     * Finds a declared method in the specified class that matches the given name and parameter types.
     *
     * @param clazz the class to search in
     * @param name  the name of the method
     * @param args  the arguments whose types will be used to find the method
     * @return the matching Method object
     * @throws NoSuchMethodException if no matching method is found
     */
    private static @NotNull Method findMethod(@NotNull Class<?> clazz, @NotNull String name, @Nullable Object[] args) throws NoSuchMethodException
    {
        Class<?> current = clazz;
        Class<?>[] argTypes = Arrays.stream(args)
                .map(Object::getClass)
                .toArray(Class<?>[]::new);

        while(current != null)
        {
            for(Method method : current.getDeclaredMethods())
            {
                if(!method.getName().equals(name))
                    continue;

                Class<?>[] paramTypes = method.getParameterTypes();
                boolean isVarArgs = method.isVarArgs();

                if(isCompatible(argTypes, paramTypes, isVarArgs))
                {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }

        throw new NoSuchMethodException("No compatible method " + name + " found in class " + clazz.getName() + "(" + Arrays.toString(args) + ")");
    }

    /**
     * Checks if a given set of argument types is compatible with the parameter types of a method.
     * <p>
     * This method supports both regular and varargs methods. It also handles primitive-to-wrapper
     * type conversions (e.g., int to Integer).
     *
     * @param args      The types of the provided arguments.
     * @param params    The types of the method's parameters.
     * @param isVarArgs Whether the method accepts a variable number of arguments (varargs).
     * @return {@code true} if the argument types are compatible with the parameter types; {@code false} otherwise.
     */
    private static boolean isCompatible(@NotNull Class<?>[] args, @NotNull Class<?>[] params, boolean isVarArgs)
    {
        if(!isVarArgs)
        {
            if(args.length != params.length)
                return false;
            for(int i = 0; i < args.length; i++)
            {
                if(!Primitives.wrap(params[i]).isAssignableFrom(Primitives.wrap(args[i])))
                    return false;
            }
            return true;
        }

        if(args.length < params.length - 1)
            return false;
        for(int i = 0; i < params.length - 1; i++)
        {
            if(!Primitives.wrap(params[i]).isAssignableFrom(Primitives.wrap(args[i])))
                return false;
        }

        Class<?> varArgType = Primitives.wrap(params[params.length - 1].getComponentType());
        for(int i = params.length - 1; i < args.length; i++)
        {
            if(!varArgType.isAssignableFrom(Primitives.wrap(args[i])))
                return false;
        }
        return true;
    }

    /**
     * Invokes an instance method on the given object with specified arguments.
     *
     * @param object     the target object
     * @param methodName name of the method to invoke
     * @param args       method arguments
     * @param <V>        return type of the method
     * @return a ReflectionChain wrapping the method's return value
     */
    public static <V> @NotNull ReflectionChain<V> invokeMethod(@NotNull Object object, @NotNull String methodName, @Nullable Object... args)
    {
        try
        {
            Method method = findMethod(object.getClass(), methodName, args);
            method.setAccessible(true);
            return new ReflectionChain<>((V) method.invoke(object, args));
        } catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Invokes a static method of a class given its fully qualified name.
     *
     * @param classPath  fully qualified class name
     * @param methodName name of the static method
     * @param args       method arguments
     * @param <V>        return type of the method
     * @return a ReflectionChain wrapping the method's return value
     */
    public static <V> @NotNull ReflectionChain<V> invokeStaticMethod(@NotNull String classPath, @NotNull String methodName, @Nullable Object... args)
    {
        try
        {
            Class<?> clazz = Class.forName(classPath);
            Method method = findMethod(clazz, methodName, args);
            method.setAccessible(true);
            return new ReflectionChain<>((V) method.invoke(null, args));
        } catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Finds a declared field in the specified class by its name and makes it accessible.
     *
     * @param clazz     the class to search in
     * @param fieldName the name of the field
     * @return the Field object with accessibility set to true
     * @throws NoSuchFieldException if the field is not found
     */
    private static @NotNull Field findField(@NotNull Class<?> clazz, @NotNull String fieldName) throws NoSuchFieldException
    {
        while(clazz != null)
        {
            try
            {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch(NoSuchFieldException ignored)
            {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException();
    }

    /**
     * Retrieves the value of a field from an object.
     *
     * @param object    the target object
     * @param fieldName name of the field
     * @param <T>       type of the field value
     * @return a ReflectionChain wrapping the field's value
     */
    public static <T> @NotNull ReflectionChain<T> getField(@NotNull Object object, @NotNull String fieldName)
    {
        try
        {
            Field field = findField(object.getClass(), fieldName);
            return new ReflectionChain<>((T) field.get(object));
        } catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves the value of a static field from a class.
     *
     * @param clazz     the target class
     * @param fieldName name of the static field
     * @param <T>       class type
     * @param <V>       type of the field value
     * @return the value of the static field, or null if inaccessible
     */
    public static <T, V> @Nullable V getStaticField(@NotNull Class<T> clazz, @Nullable String fieldName)
    {
        try
        {
            Field field = findField(clazz, fieldName);
            return (V) field.get(null);
        } catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves the value of a static field by class name.
     *
     * @param classPath fully qualified class name
     * @param fieldName name of the static field
     * @param <T>       type of the field value
     * @return the value of the static field, or null if inaccessible
     */
    public static <T> @Nullable T getStaticField(@NotNull String classPath, @NotNull String fieldName)
    {
        try
        {
            Class<?> clazz = Class.forName(classPath);
            Field field = findField(clazz, fieldName);
            return (T) field.get(null);
        } catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the value of a field on an object.
     *
     * @param object    target object
     * @param fieldName name of the field
     * @param value     new value to set
     */
    public static void setField(@NotNull Object object, @NotNull String fieldName, @Nullable Object value)
    {
        try
        {
            Field field = findField(object.getClass(), fieldName);
            field.set(object, value);
        } catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the value of a static field in a class by class name.
     *
     * @param classPath fully qualified class name
     * @param fieldName name of the static field
     * @param value     new value to set
     */
    public static void setStaticField(@NotNull String classPath, @NotNull String fieldName, @Nullable Object value)
    {
        try
        {
            Class<?> clazz = Class.forName(classPath);
            Field field = findField(clazz, fieldName);
            field.set(null, value);
        } catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Helper class to chain reflection calls on the result of previous reflective operations.
     *
     * @param <V> the wrapped value type
     */
    public static class ReflectionChain<V>
    {
        private final @Nullable V value;

        /**
         * Creates a new ReflectionChain wrapping the given value.
         *
         * @param value the wrapped value may be null
         */
        public ReflectionChain(@Nullable V value)
        {
            this.value = value;
        }

        /**
         * Invokes a method on the wrapped object.
         *
         * @param methodName name of the method
         * @param args       method arguments
         * @return new ReflectionChain wrapping the method's result, or null if error
         */
        public @NotNull ReflectionChain<V> thanInvoke(@NotNull String methodName, @Nullable Object... args)
        {
            if(value == null)
                return new ReflectionChain<>(null);

            try
            {
                Method method = findMethod(value.getClass(), methodName, args);
                method.setAccessible(true);
                return new ReflectionChain<>((V) method.invoke(value, args));
            } catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        /**
         * Retrieves a field value from the wrapped object.
         *
         * @param fieldName name of the field
         * @return new ReflectionChain wrapping the field's value, or null if error
         */
        public @NotNull ReflectionChain<V> thanGetField(@NotNull String fieldName)
        {
            if(value == null)
                return new ReflectionChain<>(null);
            try
            {
                Field field = findField(value.getClass(), fieldName);
                return new ReflectionChain<>((V) field.get(value));
            } catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        /**
         * Returns the wrapped value.
         *
         * @return wrapped value may be null
         */
        public @Nullable V get()
        {
            return value;
        }
    }
}
