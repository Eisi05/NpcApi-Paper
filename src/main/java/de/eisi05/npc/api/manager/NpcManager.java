package de.eisi05.npc.api.manager;

import com.mojang.datafixers.util.Either;
import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.utils.ObjectSaver;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.time.*;
import java.util.*;

/**
 * Manages the collection and lifecycle of NPC instances.
 */
public class NpcManager
{
    /**
     * Stores serialized NPCs that should be loaded once their world becomes available.
     * The key is the world UUID, and the value is a list of NPCs waiting to be deserialized.
     */
    private static final Map<UUID, List<NPC.SerializedNPC>> toLoadNPCs = new HashMap<>();

    /**
     * Map storing the file name and the exception that occurred during loading.
     */
    public static Map<String, Exception> loadExceptions = new HashMap<>();

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

        Exception exception = null;
        for(File file1 : files)
        {
            if(!file1.getName().endsWith(".npc"))
                continue;

            try
            {
                NPC.SerializedNPC serializedNPC = new ObjectSaver(file1).read();

                Either<NPC, UUID> npcEither = serializedNPC.deserializedNPC();

                if(npcEither.right().isPresent())
                {
                    toLoadNPCs.computeIfAbsent(npcEither.right().get(), k -> new ArrayList<>()).add(serializedNPC);
                    return;
                }

                if(npcEither.left().isEmpty())
                    return;

                loadNpc(npcEither.left().get());
                successCounter++;
            } catch(Exception e)
            {
                failCounter++;
                exception = e;
                loadExceptions.put(file1.getName(), e);
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

        if(exception != null && NpcApi.config.debug())
            exception.printStackTrace();
    }

    /**
     * Loads all serialized NPCs that were queued for the given world and initializes them.
     *
     * @param world the world whose queued NPCs should be deserialized and spawned
     */
    public static void loadWorld(@NotNull World world)
    {
        List<NPC.SerializedNPC> serializedNPCS = toLoadNPCs.remove(world.getUID());

        if(serializedNPCS == null || serializedNPCS.isEmpty())
            return;

        Exception exception = null;
        for(NPC.SerializedNPC serializedNPC : serializedNPCS)
        {
            try
            {
                Either<NPC, ?> either = serializedNPC.deserializedNPC();

                if(either.left().isEmpty())
                    return;

                loadNpc(either.left().get());
            } catch(Exception e)
            {
                exception = e;
            }
        }

        if(exception != null && NpcApi.config.debug())
            exception.printStackTrace();
    }

    /**
     * Initializes the given NPC, applying editability rules based on its creation time
     * and making it visible to all online players.
     *
     * @param npc the NPC to load and display
     */
    private static void loadNpc(@NotNull NPC npc)
    {
        LocalDate date = LocalDate.of(2025, 10, 22);
        LocalTime time = LocalTime.of(22, 0);
        Instant instant = LocalDateTime.of(date, time).atZone(ZoneId.of("UTC")).toInstant();
        if(npc.getCreatedAt().isBefore(instant))
            npc.setEditable(true);

        npc.showNpcToAllPlayers();
    }
}
