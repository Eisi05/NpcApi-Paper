package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

public class ChangeWorldListener implements Listener
{
    @EventHandler
    public void onChange(PlayerChangedWorldEvent event)
    {
        if(!NpcApi.config.autoManageVisibility())
            return;

        SchedulerProvider.get().runLaterForEntity(event.getPlayer(), () ->
        {
            Player player = event.getPlayer();
            if(player == null || !player.isOnline())
                return;

            NpcManager.getList().forEach(npc ->
            {
                if(npc.getVisibilityManager().shouldShowToPlayer(event.getPlayer().getUniqueId()))
                {
                    npc.showNPCToPlayer(event.getPlayer());
                    npc.addWalkingViewer(event.getPlayer());
                }
            });
        }, 10L);
    }
}
