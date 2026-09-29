package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

public class PlayerRespawnListener implements Listener
{
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event)
    {
        Player player = event.getPlayer();

        SchedulerProvider.get().runLaterForEntity(player, () ->
        {
            if (player == null || !player.isOnline())
                return;

            NpcManager.getList().forEach(npc ->
            {
                npc.hideNpcFromPlayer(player);
                npc.showNPCToPlayer(player);
            });
        }, 10L);
    }
}
