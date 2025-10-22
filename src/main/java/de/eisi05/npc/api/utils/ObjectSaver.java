package de.eisi05.npc.api.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link ObjectSaver} class provides utility methods for serializing and deserializing
 * Java objects and lists of objects to and from a file. It uses standard Java
 * object serialization.
 */
public class ObjectSaver
{
    private final File file;

    /**
     * Constructs a new {@code ObjectSaver} instance, creating a {@link File} object
     * from the given file path string. It ensures the parent directories exist
     * and creates the file if it doesn't already exist.
     *
     * @param file The path to the file where objects will be saved/loaded. Must not be {@code null}.
     * @throws RuntimeException If an {@link IOException} occurs while creating the file.
     */
    public ObjectSaver(@NotNull String file)
    {
        this(new File(file));
    }

    /**
     * Constructs a new {@code ObjectSaver} instance with the specified {@link File} object.
     * It ensures the parent directories of the file exist and creates the file itself
     * if it does not already exist.
     *
     * @param file The {@link File} object where objects will be saved/loaded. Must not be {@code null}.
     * @throws RuntimeException If an {@link IOException} occurs while creating the file.
     */
    public ObjectSaver(@NotNull File file)
    {
        file.getParentFile().mkdirs();
        this.file = file;
        if(!file.exists())
        {
            try
            {
                file.createNewFile();
            } catch(IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Writes a single serializable object to the file, appending to the file if it already exists.
     * This is a convenience method that calls {@link #write(Serializable, boolean)} with {@code append} set to {@code true}.
     *
     * @param object The object to write to the file. Must implement {@link Serializable}.
     * @param <T>    The type of the object, which must extend {@link Serializable}.
     * @throws IOException If an I/O error occurs during writing.
     */
    public <T extends Serializable> void write(T object) throws IOException
    {
        this.write(object, true);
    }

    /**
     * Writes a single serializable object to the file.
     *
     * @param object The object to write to the file. Must implement {@link Serializable}.
     * @param append If {@code true}, the object will be appended to the end of the file.
     *               If {@code false}, the file will be truncated (its contents deleted)
     *               before writing the new object.
     * @param <T>    The type of the object, which must extend {@link Serializable}.
     * @throws IOException If an I/O error occurs during writing.
     */
    public <T extends Serializable> void write(T object, boolean append) throws IOException
    {
        FileOutputStream fileOut = new FileOutputStream(this.file, append);
        ObjectOutputStream objectOut = new ObjectOutputStream(fileOut);
        objectOut.writeObject(object);
        objectOut.close();
    }

    /**
     * Writes a list of serializable objects to the file, appending to the file if it already exists.
     * This is a convenience method that calls {@link #writeList(List, boolean)} with {@code append} set to {@code true}.
     *
     * @param object The list of objects to write to the file. Each object in the list must implement {@link Serializable}. Must not be {@code null}.
     * @param <T>    The type of the objects in the list, which must extend {@link Serializable}.
     * @throws IOException If an I/O error occurs during writing.
     */
    public <T extends Serializable> void writeList(@NotNull List<T> object) throws IOException
    {
        this.writeList(object, true);
    }

    /**
     * Writes a list of serializable objects to the file.
     *
     * @param object The list of objects to write to the file. Each object in the list must implement {@link Serializable}. Must not be {@code null}.
     * @param append If {@code true}, the list will be appended to the end of the file.
     *               If {@code false}, the file will be truncated (its contents deleted)
     *               before writing the new list.
     * @param <T>    The type of the objects in the list, which must extend {@link Serializable}.
     * @throws IOException If an I/O error occurs during writing.
     */
    public <T extends Serializable> void writeList(@NotNull List<T> object, boolean append) throws IOException
    {
        FileOutputStream fileOut = new FileOutputStream(this.file, append);
        ObjectOutputStream objectOut = new ObjectOutputStream(fileOut);
        objectOut.writeObject(object);
        objectOut.close();
    }

    /**
     * Reads a single serializable object from the file.
     *
     * @param <T> The expected type of the object, which must extend {@link Serializable}.
     * @return The deserialized object, or {@code null} if an error occurs during reading
     * (e.g., file not found, EOF, class not found, or I/O error).
     */
    @SuppressWarnings("unchecked")
    public <T extends Serializable> @Nullable T read()
    {
        try
        {
            FileInputStream fileIn = new FileInputStream(this.file);
            ObjectInputStream objectOut = new ObjectInputStream(fileIn);
            Object object = objectOut.readObject();
            objectOut.close();
            return (T) object;
        } catch(IOException | ClassNotFoundException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Reads a list of serializable objects from the file.
     *
     * @param <T> The expected type of the objects in the list, which must extend {@link Serializable}.
     * @return The deserialized list of objects. Returns an empty {@link ArrayList} if an error occurs
     * during reading (e.g., file not found, EOF, class not found, or I/O error), or if the file is empty.
     */
    @SuppressWarnings("unchecked")
    public <T extends Serializable> @NotNull List<T> readList()
    {
        try
        {
            FileInputStream fileIn = new FileInputStream(this.file);
            ObjectInputStream objectOut = new ObjectInputStream(fileIn);
            Object object = objectOut.readObject();
            objectOut.close();
            return (List<T>) object;
        } catch(Exception var4)
        {
            return new ArrayList();
        }
    }
}

