package de.eisi05.npc.api.utils.serialize;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;

/**
 * The {@link ItemSerializer} class provides utility methods for serializing and deserializing Bukkit {@link ItemStack} arrays, {@link PlayerInventory}
 * contents, and generic {@link Inventory} contents to and from Base64 encoded strings. This is useful for storing inventory data in configurations or
 * databases.
 */
public class ItemSerializer
{
    /**
     * Serializes a single {@link ItemStack} into a Base64 encoded string.
     *
     * @param item The {@link ItemStack} to serialize. Must not be {@code null}.
     * @return A Base64 encoded {@link String} representing the item. Returns {@code null} if an error occurs during serialization.
     */
    public static @NotNull String itemStackToBase64(@NotNull ItemStack item)
    {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /**
     * Deserializes a single {@link ItemStack} from a Base64 encoded string. This method expects the Base64 string to represent a single item.
     *
     * @param data The Base64 encoded {@link String} representing the item. Must not be {@code null}.
     * @return The deserialized {@link ItemStack}, or {@code null} if an error occurs during deserialization or if the input data does not represent a valid
     * item.
     */
    public static @Nullable ItemStack itemStackFromBase64(@NotNull String data)
    {
        try
        {
            return ItemStack.deserializeBytes(Base64.getMimeDecoder().decode(data));
        }
        catch(Exception e)
        {
            return null;
        }
    }
}
