package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.utils.PacketReader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

public class ServerReadyListener implements Listener
{
    @EventHandler
    public void onServerReady(ServerLoadEvent event)
    {
        PacketReader.injectAll();
    }
}
