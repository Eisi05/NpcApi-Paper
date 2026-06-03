package de.eisi05.npc.api.listeners;

import de.eisi05.npc.api.enums.ClickActionType;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import de.eisi05.npc.api.utils.NpcHitboxUtil;
import de.eisi05.npc.api.utils.PacketReader;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

public class NpcSleepListener implements Listener
{
    private static boolean checkForRay(@NotNull Player player, @NotNull ClickActionType type)
    {
        double rangeSq = 25.0;
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();

        for(NPC npc : NpcManager.getList())
        {
            Location npcLoc = npc.getLocation();

            if(npcLoc.getWorld() != world)
                continue;

            if(npc.getOption(NpcOption.POSE, player) != Pose.SLEEPING)
                continue;

            if(npcLoc.distanceSquared(eye) > rangeSq)
                continue;

            if(NpcHitboxUtil.rayIntersectsNpc(npc, true, player))
            {
                PacketReader.callNpc(player, npc, type);
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event)
    {
        if(event.getHand() == EquipmentSlot.OFF_HAND)
            return;

        Action action = event.getAction();
        Player player = event.getPlayer();
        if(action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR)
            event.setCancelled(checkForRay(player, ClickActionType.RIGHT));

        if(action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR)
            event.setCancelled(checkForRay(player, ClickActionType.LEFT));
    }
}
