package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.events.NpcDeathEvent;
import de.eisi05.npc.api.events.NpcInteractEvent;
import de.eisi05.npc.api.interfaces.NpcClickAction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class NpcInteractListener implements Listener
{
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(NpcInteractEvent event)
    {
        if(event.getNpc().getClickEvent() != null)
            event.getNpc().getClickEvent().call(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(NpcDeathEvent event)
    {
        NpcClickAction action = event.getNpc().getCombatManager().getDeathAction();
        if(action != null)
            action.call(event);
    }
}