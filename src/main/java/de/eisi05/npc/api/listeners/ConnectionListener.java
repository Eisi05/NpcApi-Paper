package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.manager.TeamManager;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.objects.NpcSkin;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import de.eisi05.npc.api.scheduler.tasks.Tasks;
import de.eisi05.npc.api.utils.PacketReader;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;

public class ConnectionListener implements Listener
{
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();
        SchedulerProvider.get().runSyncForEntity(player, () ->
        {
            PacketReader.inject(player);
            TeamManager.clear(player.getUniqueId());
        });

        if(!NpcApi.config.autoManageVisibility())
            return;

        SchedulerProvider.get().runLaterForEntity(player, () ->
        {
            if (player == null || !player.isOnline())
                return;

            for(NPC npc : new ArrayList<>(NpcManager.getList()))
            {
                if(!npc.getVisibilityManager().shouldShowToPlayer(event.getPlayer().getUniqueId()))
                    continue;

                npc.showNPCToPlayer(event.getPlayer());
                npc.addWalkingViewer(event.getPlayer());
                NpcSkin npcSkin = npc.getOption(NpcOption.SKIN, event.getPlayer());
                if(npcSkin == null || npcSkin.isStatic() || npcSkin.getPlaceholder() == null || npc.getOption(NpcOption.USE_PLAYER_SKIN, event.getPlayer()))
                    continue;

                Tasks.updateSkin(event.getPlayer(), npc, npcSkin);
            }
        }, 10L);
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent event)
    {
        Player player = event.getPlayer();
        SchedulerProvider.get().runSyncForEntity(player, () ->
        {
            PacketReader.inject(player);
            TeamManager.clear(player.getUniqueId());
        });

        for(NPC npc : NpcManager.getList())
        {
            npc.nameCache.remove(event.getPlayer().getUniqueId());
            npc.removeWalkingViewer(event.getPlayer());
            if(NpcApi.config.autoManageVisibility())
                npc.hideNpcFromPlayer(event.getPlayer());
        }
    }
}
