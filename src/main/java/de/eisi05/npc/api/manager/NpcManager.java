package de.eisi05.npc.api.manager;

import com.mojang.datafixers.util.Either;
import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.utils.serialize.ObjectSaver;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Manages the collection and lifecycle of NPC instances.
 */
public class NpcManager
{
    /**
     * Stores serialized NPCs that should be loaded once their world becomes available. The key is the world UUID, and the value is a list of NPCs waiting to be
     * deserialized.
     */
    private static final Map<UUID, List<NPC.SerializedNPC>> toLoadNPCs = new ConcurrentHashMap<>();

    private static final Map<Integer, NPC> npcById = new ConcurrentHashMap<>();
    private static final Map<Long, Set<NPC>> npcsByChunk = new ConcurrentHashMap<>();

    private static final Map<NPC.SerializedNPC, Location> monsterNPCs = new ConcurrentHashMap<>();

    /**
     * Map storing the file name and the exception that occurred during loading.
     */
    public static Map<String, Exception> loadExceptions = new ConcurrentHashMap<>();

    /**
     * Adds an NPC to the manager's list.
     *
     * @param npc the NPC to add
     */
    public static void addNPC(@NotNull NPC npc)
    {
        npcById.values().removeIf(existing -> existing.getUUID().equals(npc.getUUID()));
        npcById.put(((ServerPlayer) npc.getServerPlayer()).getId(), npc);
        registerNpcInChunk(npc, npc.getLocation());
    }

    /**
     * Returns the collection of all managed NPCs.
     *
     * @return the collection of NPCs
     */
    public static @NotNull Collection<NPC> getList()
    {
        return Collections.unmodifiableCollection(npcById.values());
    }

    /**
     * Removes an NPC from the manager's list.
     *
     * @param npc the NPC to remove
     */
    public static void removeNPC(@NotNull NPC npc)
    {
        npcById.values().removeIf(value -> value.getUUID().equals(npc.getUUID()));
        unregisterNpcFromChunk(npc, npc.getLocation());
    }

    public static void addID(int id, @NotNull NPC npc)
    {
        npcById.put(id, npc);
    }

    /**
     * Clears all NPCs from the manager.
     */
    public static void clear()
    {
        npcById.clear();
        toLoadNPCs.clear();
        loadExceptions.clear();
        npcsByChunk.clear();
        monsterNPCs.clear();
    }

    /**
     * Finds an NPC by its UUID.
     *
     * @param uuid the UUID to search for
     * @return an Optional containing the NPC if found, empty otherwise
     */
    public static @NotNull Optional<NPC> fromUUID(@NotNull UUID uuid)
    {
        return getList().stream().filter(npc -> npc.getUUID().equals(uuid)).findFirst();
    }

    /**
     * Finds an NPC by its entity ID.
     *
     * @param id the entity ID to search for
     * @return an Optional containing the NPC if found, empty otherwise
     */
    public static @Nullable Optional<NPC> fromId(int id)
    {
        return Optional.ofNullable(npcById.get(id));
    }

    /**
     * Creates a unique long key from the given chunk coordinates.
     *
     * @param chunkX the X coordinate of the chunk
     * @param chunkZ the Z coordinate of the chunk
     * @return a long representing the combined chunk coordinates
     */
    private static long getChunkKey(int chunkX, int chunkZ)
    {
        return (long) chunkX & 0xffffffffL | ((long) chunkZ & 0xffffffffL) << 32;
    }

    /**
     * Returns the chunk key for the given location.
     *
     * @param loc the location
     * @return the chunk key
     */
    private static long getChunkKey(@NotNull Location loc)
    {
        return getChunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    /**
     * Registers an NPC in the chunk containing the given location.
     *
     * @param npc      the NPC to register
     * @param location the location of the NPC
     */
    private static void registerNpcInChunk(@NotNull NPC npc, @NotNull Location location)
    {
        long key = getChunkKey(location);
        npcsByChunk.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(npc);
    }

    /**
     * Unregisters an NPC from the chunk containing the given location.
     *
     * @param npc the NPC to unregister
     * @param loc the previous location of the NPC
     */
    private static void unregisterNpcFromChunk(NPC npc, Location loc)
    {
        long key = getChunkKey(loc);
        Set<NPC> set = npcsByChunk.get(key);
        if(set != null)
        {
            set.remove(npc);
            if(set.isEmpty())
                npcsByChunk.remove(key);
        }
    }

    /**
     * Updates the chunk registration of an NPC when its position changes. If the NPC remains in the same chunk, no update is performed.
     *
     * @param npc    the NPC whose position was updated
     * @param oldLoc the NPC's previous location
     * @param newLoc the NPC's new location
     */
    public static void updateNpcPosition(@NotNull NPC npc, @NotNull Location oldLoc, @NotNull Location newLoc)
    {
        int oldChunkX = oldLoc.getBlockX() >> 4;
        int oldChunkZ = oldLoc.getBlockZ() >> 4;
        int newChunkX = newLoc.getBlockX() >> 4;
        int newChunkZ = newLoc.getBlockZ() >> 4;

        if(oldChunkX != newChunkX || oldChunkZ != newChunkZ)
        {
            unregisterNpcFromChunk(npc, oldLoc);
            registerNpcInChunk(npc, newLoc);
        }
    }

    /**
     * Gets all NPCs located in the specified chunk.
     *
     * @param chunkX the X coordinate of the chunk
     * @param chunkZ the Z coordinate of the chunk
     * @return a set containing the NPCs in the chunk, or an empty set if none are present
     */
    public static @NotNull Set<NPC> getNpcsInChunk(int chunkX, int chunkZ)
    {
        long key = getChunkKey(chunkX, chunkZ);
        return Collections.unmodifiableSet(npcsByChunk.getOrDefault(key, Collections.emptySet()));
    }

    /**
     * Returns a flattened list of all serialized NPCs that are scheduled to be loaded.
     *
     * @return a non-null list containing all {@link NPC.SerializedNPC} instances across all worlds
     */
    public static @NotNull List<NPC.SerializedNPC> getToLoadNPCs()
    {
        return toLoadNPCs.values().stream().flatMap(Collection::stream).toList();
    }

    /**
     * Loads NPCs from disk files in the plugin data folder. Logs the count of successfully and unsuccessfully loaded NPCs.
     */
    public static void loadNPCs()
    {
        File file = new File(NpcApi.plugin.getDataFolder(), "NPC");

        File[] files = file.listFiles();
        if(files == null)
            return;

        long failCounter = 0;
        long successCounter = 0;
        long migrations = 0;

        Exception exception = null;
        for(File file1 : files)
        {
            if(!file1.getName().endsWith(".npc") && !file1.getName().endsWith(".npc.json"))
                continue;

            if(NpcApi.config.debug())
                NpcApi.plugin.getLogger().info("Loading NPC: " + file1.getName());
            try
            {
                ObjectSaver saver = new ObjectSaver(file1);

                NPC.SerializedNPC serializedNPC;
                if(!saver.isJson())
                {
                    File backupFolder = new File(file, "backup");
                    if (!backupFolder.exists())
                        backupFolder.mkdirs();

                    File backupFile = new File(backupFolder, file1.getName());
                    Files.copy(file1.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                    serializedNPC = saver.read();
                    file1.delete();

                    File jsonFile = new File(file, file1.getName() + ".json");
                    new ObjectSaver(jsonFile).write(serializedNPC);
                    migrations++;
                }
                else
                    serializedNPC = saver.read(NPC.SerializedNPC.class);

                Either<NPC, UUID> npcEither = serializedNPC.deserializedNPC();
                if(npcEither.right().isPresent())
                {
                    toLoadNPCs.computeIfAbsent(npcEither.right().get(), k -> new ArrayList<>()).add(serializedNPC);
                    continue;
                }

                if(npcEither.left().isEmpty())
                    continue;

                NPC npc = npcEither.left().get();
                if(npc.isMonster() && npc.getLocation().getWorld() != null && npc.getLocation().getWorld().getDifficulty() == Difficulty.PEACEFUL)
                {
                    removeNPC(npc);
                    monsterNPCs.put(serializedNPC, npc.getLocation());
                    continue;
                }

                loadNpc(npc);
                successCounter++;
            }
            catch(Exception e)
            {
                failCounter++;
                exception = e;
                loadExceptions.put(file1.getName(), e);
            }
        }

        if(migrations > 0)
            NpcApi.plugin.getLogger().info("Successfully migrated " + migrations + " NPC's");

        if(successCounter == 1)
            NpcApi.plugin.getLogger().info("Successfully loaded " + successCounter + " NPC");
        else if(successCounter > 1)
            NpcApi.plugin.getLogger().info("Successfully loaded " + successCounter + " NPC's");

        if(failCounter == 1)
            NpcApi.plugin.getLogger().warning("Failed to load " + failCounter + " NPC");
        else if(failCounter > 1)
            NpcApi.plugin.getLogger().warning("Failed to load " + failCounter + " NPC's");

        if(monsterNPCs.size() == 1)
            NpcApi.plugin.getLogger().warning("Failed to load " + monsterNPCs.size() + " Monster NPC, because the difficulty is set to peaceful!");
        else if(monsterNPCs.size() > 1)
            NpcApi.plugin.getLogger().warning("Failed to load " + monsterNPCs.size() + " Monster NPC's, because the difficulty is set to peaceful!");

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
                    continue;

                NPC npc = either.left().get();
                if(npc.isMonster() && world.getDifficulty() == Difficulty.PEACEFUL)
                    throw new RuntimeException("NPC is a Monster entity, but the difficulty is set to peaceful!");

                loadNpc(npc);
            }
            catch(Exception e)
            {
                exception = e;
            }
        }

        if(exception != null && NpcApi.config.debug())
            exception.printStackTrace();
    }

    /**
     * Attempts to load the given serialized NPC, optionally overriding its location.
     * <p>
     * The NPC is removed from the internal loading map before attempting deserialization. If a non-null location is provided, it will replace the NPC's stored
     * location. If deserialization succeeds, the NPC is loaded and {@code true} is returned. Otherwise, {@code false} is returned.
     * <p>
     * Any exceptions during deserialization are caught and optionally printed if debug mode is enabled.
     *
     * @param serializedNPC the serialized NPC to load, must not be null
     * @param location      an optional location to override the NPC's position, may be null
     * @return {@code true} if the NPC was successfully loaded, otherwise {@code false}
     */
    public static boolean loadSerializedNPC(@NotNull NPC.SerializedNPC serializedNPC, @Nullable Location location)
    {
        toLoadNPCs.computeIfPresent(
                serializedNPC.getWorld(),
                (k, list) ->
                {
                    list.remove(serializedNPC);
                    return list.isEmpty() ? null : list;
                }
        );

        if(location != null)
            serializedNPC.setLocation(location);

        try
        {
            Either<NPC, ?> either = serializedNPC.deserializedNPC();
            if(either.left().isEmpty())
                return false;

            NPC npc = either.left().get();
            if(npc.isMonster() && location.getWorld() != null && location.getWorld().getDifficulty() == Difficulty.PEACEFUL)
                throw new RuntimeException("NPC is a Monster entity, but the difficulty is set to peaceful!");

            npc.markChange();
            loadNpc(npc);
            return true;
        }
        catch(Exception e)
        {
            if(NpcApi.config.debug())
                e.printStackTrace();

            return false;
        }
    }

    /**
     * Unloads all NPCs that are monsters
     *
     * @return the number of NPCs unloaded
     */
    public static long unloadMonsterNpcs(@NotNull World world)
    {
        long counter = 0;
        for (NPC npc : new ArrayList<>(NpcManager.getList()))
        {
            if (npc.getLocation().getWorld() == null || !npc.getLocation().getWorld().equals(world))
                continue;

            if (npc.isMonster())
            {
                monsterNPCs.entrySet().removeIf(entry -> entry.getKey().getId().equals(npc.getUUID()));
                monsterNPCs.put(NPC.SerializedNPC.serializedNPC(npc), npc.getLocation());

                NpcManager.removeNPC(npc);
                npc.hideNpcFromAllPlayers();
                npc.stopGoals();
                counter++;
            }
        }
        return counter;
    }

    /**
     * Loads all monster NPCs that were previously unloaded
     */
    public static void loadMonsterNpcs(BiConsumer<Location, Runnable> consumer)
    {
        new HashMap<>(monsterNPCs).forEach((key, value) ->
                consumer.accept(value, () ->
                {
                    Either<NPC, UUID> npcEither = key.deserializedNPC();
                    if(npcEither.right().isPresent())
                        toLoadNPCs.computeIfAbsent(npcEither.right().get(), k -> new ArrayList<>()).add(key);

                    if(npcEither.left().isEmpty())
                        return;

                    loadNpc(npcEither.left().get());
                }));
        monsterNPCs.clear();
    }

    /**
     * Initializes the given NPC, applying editability rules based on its creation time and making it visible to all online players.
     *
     * @param npc the NPC to load and display
     */
    private static void loadNpc(@NotNull NPC npc)
    {
        if(npc.getVisibilityManager().shouldShowToAllPlayers())
            npc.showNpcToAllPlayers();
        else
            npc.getVisibilityManager().getSpecificPlayers().forEach(uuid ->
            {
                Player player = Bukkit.getPlayer(uuid);
                if(player == null)
                    return;
                npc.showNPCToPlayer(player);
            });
    }
}
