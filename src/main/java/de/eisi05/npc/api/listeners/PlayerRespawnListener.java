package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class PlayerRespawnListener implements Listener
{
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event)
    {
        Player player = event.getPlayer();

        new BukkitRunnable()
        {
            @Override
            public void run()
            {
                NpcManager.getList().forEach(npc ->
                {
                    npc.hideNpcFromPlayer(player);
                    npc.showNPCToPlayer(player);
                });
            }
        }.runTaskLater(NpcApi.plugin, 10L);
    }
}
