package de.eisi05.npc.api.objects;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Abstract class representing an entity that can hold NPC-related data
 * and has a concept of unsaved changes.
 * It implements {@link InventoryHolder} but onlay as placeholder.
 */
public abstract class NpcHolder implements InventoryHolder
{
    /**
     * Flag indicating whether there are unsaved changes to this holder.
     * Defaults to {@code false}.
     */
    private boolean unsavedChanges = false;

    /**
     * Checks if there are any unsaved changes for this NPC holder.
     *
     * @return {@code true} if there are unsaved changes, {@code false} otherwise.
     */

    public boolean hasUnsavedChanges()
    {
        return unsavedChanges;
    }

    /**
     * Marks that there are unsaved changes to this NPC holder.
     * This should be called whenever a modifiable property of the holder is changed.
     */
    public void markChange()
    {
        unsavedChanges = true;
    }

    /**
     * Saves the current state of the NPC holder.
     * This method is intended to persist any changes. After successful execution,
     * the {@code unsavedChanges} flag is reset to {@code false}.
     *
     * @throws IOException if an error occurs during the saving process.
     */
    public void save() throws IOException
    {
        unsavedChanges = false;
    }

    /**
     * @throws UnsupportedOperationException always, as this inventory is not used.
     */
    @Override
    public @NotNull Inventory getInventory()
    {
        throw new UnsupportedOperationException("This inventory is not used!");
    }
}
