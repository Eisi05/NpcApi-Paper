package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BiConsumer;

public class WorldLoadListener implements Listener
{
    /**
     * Applies an action to every NPC in the chunk and every player of the world. The chunk events run on the thread that owns the chunk, but the players can be
     * anywhere, and on Folia a player may only be handled by the thread that owns it: so each player's part runs on that player's own scheduler (inline on
     * Paper, where there is only one thread).
     */
    private static void forEachNpcAndPlayer(@NotNull World world, int chunkX, int chunkZ, @NotNull BiConsumer<NPC, Player> action)
    {
        List<NPC> npcs = NpcManager.getNpcsInChunk(chunkX, chunkZ)
                .stream()
                .filter(npc -> npc.getLocation().getWorld().getUID().equals(world.getUID()))
                .toList();

        if(npcs.isEmpty())
            return;

        for(Player player : Bukkit.getOnlinePlayers())
        {
            if(!player.getWorld().getUID().equals(world.getUID()))
                continue;

            Runnable job = () -> npcs.forEach(npc -> action.accept(npc, player));

            if(SchedulerProvider.isFolia())
                SchedulerProvider.get().runSyncForEntity(player, job);
            else
                job.run();
        }
    }

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

        forEachNpcAndPlayer(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ(), (npc, player) ->
        {
            if(npc.getVisibilityManager().shouldShowToPlayer(player.getUniqueId()))
            {
                npc.showNPCToPlayer(player);
                npc.addWalkingViewer(player);
            }
        });
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event)
    {
        if(!NpcApi.config.autoManageVisibility())
            return;

        forEachNpcAndPlayer(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ(), NPC::hideNpcFromPlayer);
    }
}