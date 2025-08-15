package de.eisi05.npc.api.utils;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * The {@link ItemSerializer} class provides utility methods for serializing and deserializing
 * Bukkit {@link ItemStack} arrays, {@link PlayerInventory} contents, and generic {@link Inventory}
 * contents to and from Base64 encoded strings. This is useful for storing inventory data
 * in configurations or databases.
 */
public class ItemSerializer
{
    /**
     * Serializes the contents of a {@link PlayerInventory} into a Base64 encoded string array.
     * The array will contain two elements: the first for the main inventory storage contents,
     * and the second for the armor contents plus the off-hand item.
     *
     * @param playerInventory The {@link PlayerInventory} to serialize. Must not be {@code null}.
     * @return A {@code String[]} array containing two Base64 encoded strings:
     * index 0 is the main inventory, index 1 is armor and off-hand.
     * @throws IllegalStateException If an error occurs during serialization (e.g., I/O error).
     */
    public static @NotNull String[] playerInventoryToBase64(@NotNull PlayerInventory playerInventory) throws IllegalStateException
    {
        ItemStack[] storage = playerInventory.getStorageContents();
        if(storage == null)
            throw new IllegalStateException("Storage contents of player inventory is null");

        String content = itemStackArrayToBase64(storage);
        ItemStack[] items = new ItemStack[playerInventory.getArmorContents().length + 1];

        for(int i = 0; i < playerInventory.getArmorContents().length; ++i)
            items[i] = playerInventory.getArmorContents()[i];

        items[items.length - 1] = playerInventory.getItemInOffHand();
        String armor = itemStackArrayToBase64(items);
        return new String[]{content, armor};
    }

    /**
     * Serializes a single {@link ItemStack} into a Base64 encoded string.
     * This is a convenience method that wraps the item in an array and calls {@link #itemStackArrayToBase64(ItemStack[])}.
     *
     * @param item The {@link ItemStack} to serialize. Must not be {@code null}.
     * @return A Base64 encoded {@link String} representing the item.
     * Returns {@code null} if an error occurs during serialization.
     */
    public static @NotNull String itemStackToBase64(@NotNull ItemStack item)
    {
        return itemStackArrayToBase64(new ItemStack[]{item});
    }

    /**
     * Deserializes a single {@link ItemStack} from a Base64 encoded string.
     * This method expects the Base64 string to represent a single item.
     *
     * @param data The Base64 encoded {@link String} representing the item. Must not be {@code null}.
     * @return The deserialized {@link ItemStack}, or {@code null} if an error occurs during deserialization
     * or if the input data does not represent a valid item.
     */
    public static @Nullable ItemStack itemStackFromBase64(@NotNull String data)
    {
        try
        {
            return itemStackArrayFromBase64(data)[0];
        } catch(Exception e)
        {
            return null;
        }
    }

    /**
     * Serializes an array of {@link ItemStack} objects into a Base64 encoded string.
     * This method uses Bukkit's object serialization for {@link ItemStack}s.
     *
     * @param items An array of {@link ItemStack} objects to serialize. Must not be {@code null}.
     * @return A Base64 encoded {@link String} representing the array of items.
     * Returns {@code null} if an error occurs during serialization.
     */
    public static @Nullable String itemStackArrayToBase64(@NotNull ItemStack[] items)
    {
        try
        {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ObjectOutputStream dataOutput = new ObjectOutputStream(outputStream);
            dataOutput.writeInt(items.length);

            for(ItemStack item : items)
                dataOutput.writeObject(item);

            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch(Exception e)
        {
            return null;
        }
    }

    /**
     * Serializes the contents of a generic {@link Inventory} into a Base64 encoded string.
     * This method is suitable for any type of inventory (e.g., chests, custom inventories).
     *
     * @param inventory The {@link Inventory} to serialize. Must not be {@code null}.
     * @return A Base64 encoded {@link String} representing the inventory contents.
     * Returns an empty string if an error occurs during serialization.
     */
    public static @NotNull String toBase64(@NotNull Inventory inventory)
    {
        try
        {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ObjectOutputStream dataOutput = new ObjectOutputStream(outputStream);
            dataOutput.writeInt(inventory.getSize());

            for(ItemStack item : inventory.getContents())
                dataOutput.writeObject(item);

            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch(Exception e)
        {
            return "";
        }
    }

    /**
     * Deserializes two Base64 encoded strings back into two {@link ItemStack} arrays.
     * This is typically used for deserializing player inventory content and armor/off-hand.
     *
     * @param s A {@code String[]} array containing two Base64 encoded strings,
     *          where {@code s[0]} is the main inventory and {@code s[1]} is armor/off-hand. Must not be {@code null}.
     * @return A two-dimensional {@code ItemStack[][]} array, where the first array
     * contains the deserialized main inventory items and the second array
     * contains the deserialized armor and off-hand items.
     */
    public static @NotNull ItemStack[][] doubleInventoryFromBase64(@NotNull String[] s)
    {
        return new ItemStack[][]{itemStackArrayFromBase64(s[0]), itemStackArrayFromBase64(s[1])};
    }

    /**
     * Deserializes a Base64 encoded string back into a Bukkit {@link Inventory} object.
     * The inventory's size and contents are restored from the provided data.
     *
     * @param data The Base64 encoded {@link String} representing the inventory. Must not be {@code null}.
     * @return The deserialized {@link Inventory} object, or {@code null} if an error occurs
     * during deserialization or if the input data is invalid.
     */
    public static @Nullable Inventory fromBase64(@NotNull String data)
    {
        try
        {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            ObjectInputStream dataInput = new ObjectInputStream(inputStream);
            Inventory inventory = Bukkit.getServer().createInventory(null, dataInput.readInt());

            for(int i = 0; i < inventory.getSize(); ++i)
                inventory.setItem(i, (ItemStack) dataInput.readObject());

            dataInput.close();
            return inventory;
        } catch(Exception e)
        {
            return null;
        }
    }

    /**
     * Deserializes a Base64 encoded string back into an array of {@link ItemStack} objects.
     *
     * @param data The Base64 encoded {@link String} representing the array of items. Must not be {@code null}.
     * @return An array of deserialized {@link ItemStack} objects.
     * Returns an empty {@code ItemStack[]} array if an error occurs during deserialization.
     */
    public static @NotNull ItemStack[] itemStackArrayFromBase64(@NotNull String data)
    {
        try
        {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            ObjectInputStream dataInput = new ObjectInputStream(inputStream);
            ItemStack[] items = new ItemStack[dataInput.readInt()];

            for(int i = 0; i < items.length; ++i)
                items[i] = (ItemStack) dataInput.readObject();

            dataInput.close();
            return items;
        } catch(Exception e)
        {
            return new ItemStack[0];
        }
    }
}
