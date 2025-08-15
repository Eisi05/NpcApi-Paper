package de.eisi05.npc.api.manager;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.utils.ObjectSaver;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the collection and lifecycle of NPC instances.
 */
public class NpcManager
{
    private static final List<NPC> listNPC = new ArrayList<>();

    /**
     * Adds an NPC to the manager's list.
     *
     * @param npc the NPC to add
     */
    public static void addNPC(@NotNull NPC npc)
    {
        listNPC.add(npc);
    }

    /**
     * Returns the list of all managed NPCs.
     *
     * @return the list of NPCs
     */
    public static @NotNull List<NPC> getList()
    {
        return listNPC;
    }

    /**
     * Removes an NPC from the manager's list.
     *
     * @param npc the NPC to remove
     */
    public static void removeNPC(@NotNull NPC npc)
    {
        listNPC.remove(npc);
    }

    /**
     * Clears all NPCs from the manager.
     */
    public static void clear()
    {
        listNPC.clear();
    }

    /**
     * Finds an NPC by its UUID.
     *
     * @param uuid the UUID to search for
     * @return an Optional containing the NPC if found, empty otherwise
     */
    public static @NotNull Optional<NPC> fromUUID(@NotNull UUID uuid)
    {
        return listNPC.stream().filter(npc -> npc.getUUID().equals(uuid)).findFirst();
    }

    /**
     * Loads NPCs from disk files in the plugin data folder.
     * Logs the count of successfully and unsuccessfully loaded NPCs.
     */
    public static void loadNPCs()
    {
        File file = new File(NpcApi.plugin.getDataFolder(), "NPC");

        File[] files = file.listFiles();
        if(files == null)
            return;

        long failCounter = 0;
        long successCounter = 0;

        for(File file1 : files)
        {
            if(!file1.getName().endsWith(".npc"))
                continue;

            try
            {
                NPC.SerializedNPC serializedNPC = new ObjectSaver(file1).read();
                serializedNPC.deserializedNPC().showNpcToAllPlayers();
                successCounter++;
            } catch(Exception e)
            {
                failCounter++;
            }
        }

        if(successCounter == 1)
            NpcApi.plugin.getLogger().info("Successfully loaded " + successCounter + " NPC");
        else if(successCounter > 1)
            NpcApi.plugin.getLogger().info("Successfully loaded " + successCounter + " NPC's");

        if(failCounter == 1)
            NpcApi.plugin.getLogger().warning("Failed to load " + failCounter + " NPC");
        else if(failCounter > 1)
            NpcApi.plugin.getLogger().warning("Failed to load " + failCounter + " NPC's");
    }
}
