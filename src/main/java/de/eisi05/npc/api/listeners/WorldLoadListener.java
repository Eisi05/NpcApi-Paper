package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.objects.NPC;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;

public class WorldLoadListener implements Listener
{
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event)
    {
        NpcManager.loadWorld(event.getWorld());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event)
    {
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();

        NpcManager.getList()
                .stream()
                .filter(npc -> (npc.getLocation().getBlockX() >> 4) == chunkX && ((npc.getLocation().getBlockZ() >> 4) == chunkZ))
                .forEach(NPC::showNpcToAllPlayers);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event)
    {
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();

        NpcManager.getList()
                .stream()
                .filter(npc -> (npc.getLocation().getBlockX() >> 4) == chunkX && ((npc.getLocation().getBlockZ() >> 4) == chunkZ))
                .forEach(NPC::hideNpcFromAllPlayers);
    }
}
