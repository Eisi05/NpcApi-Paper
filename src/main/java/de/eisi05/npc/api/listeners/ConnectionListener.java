package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.manager.TeamManager;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.objects.NpcSkin;
import de.eisi05.npc.api.scheduler.Tasks;
import de.eisi05.npc.api.utils.PacketReader;
import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class ConnectionListener implements Listener
{
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event)
    {
        PacketReader.inject(event.getPlayer());

        TeamManager.clear(event.getPlayer().getUniqueId());

        new BukkitRunnable()
        {
            @Override
            public void run()
            {
                NpcManager.getList().forEach(npc ->
                {
                    npc.showNPCToPlayer(event.getPlayer());
                    NpcSkin npcSkin = npc.getOption(NpcOption.SKIN);
                    if(npcSkin == null || npcSkin.isStatic() || npcSkin.getPlaceholder() == null || npc.getOption(NpcOption.USE_PLAYER_SKIN))
                        return;

                    Tasks.updateSkin(event.getPlayer(), npc, npcSkin);
                });
            }
        }.runTaskLater(NpcApi.plugin, 10L);
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent event)
    {
        PacketReader.uninject(event.getPlayer());

        for(NPC npc : NpcManager.getList())
            npc.hideNpcFromPlayer(event.getPlayer());
    }
}
