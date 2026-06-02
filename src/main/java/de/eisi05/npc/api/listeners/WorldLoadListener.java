package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.ArrayList;
import java.util.Collection;

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
        if(!NpcApi.config.autoManageVisibility())
            return;

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();

        Collection<Player> players = event.getWorld().getPlayers();
        new ArrayList<>(NpcManager.getList())
                .stream()
                .filter(npc -> npc.getLocation().getWorld().getUID().equals(event.getChunk().getWorld().getUID()))
                .filter(npc -> (npc.getLocation().getBlockX() >> 4) == chunkX && ((npc.getLocation().getBlockZ() >> 4) == chunkZ))
                .forEach(npc -> players.forEach(player ->
                {
                    if(npc.getVisibilityManager().shouldShowToPlayer(player.getUniqueId())) {
                        npc.showNPCToPlayer(player);
                        npc.addWalkingViewer(player);
                    }
                }));
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event)
    {
        if(!NpcApi.config.autoManageVisibility())
            return;

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();

        Collection<Player> players = event.getWorld().getPlayers();
        new ArrayList<>(NpcManager.getList())
                .stream()
                .filter(npc -> npc.getLocation().getWorld().getUID().equals(event.getChunk().getWorld().getUID()))
                .filter(npc -> (npc.getLocation().getBlockX() >> 4) == chunkX && ((npc.getLocation().getBlockZ() >> 4) == chunkZ))
                .forEach(npc -> players.forEach(npc::hideNpcFromPlayer));
    }
}
