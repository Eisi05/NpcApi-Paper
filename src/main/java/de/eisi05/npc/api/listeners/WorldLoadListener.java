package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.manager.NpcManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

public class WorldLoadListener implements Listener
{
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event)
    {
        NpcManager.loadWorld(event.getWorld());
    }
}
