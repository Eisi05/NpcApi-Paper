package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.manager.NpcManager;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;

public class ProjectileHitListener implements Listener
{
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event)
    {
        if(!(event.getEntity() instanceof AbstractArrow arrow))
            return;

        if(!(arrow.getShooter() instanceof Entity entity))
            return;

        if(NpcManager.fromId(entity.getEntityId()).isPresent())
            arrow.remove();
    }
}
