package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class ChangeWorldListener implements Listener
{
    @EventHandler
    public void onChange(PlayerChangedWorldEvent event)
    {
        if(!NpcApi.config.autoManageVisibility())
            return;

        new BukkitRunnable()
        {
            @Override
            public void run()
            {
                NpcManager.getList().forEach(npc ->
                {
                    if(npc.getVisibilityManager().shouldShowToPlayer(event.getPlayer().getUniqueId()))
                        npc.showNPCToPlayer(event.getPlayer());
                });
            }
        }.runTaskLater(NpcApi.plugin, 10L);
    }
}
