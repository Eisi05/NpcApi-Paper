package de.eisi05.npc.api.utils;

import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcOption;
import net.minecraft.world.phys.AABB;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

public class NpcHitboxUtil
{
    /**
     * Checks whether a player's line of sight intersects the Oriented Bounding Box (OBB) of an NPC using an optimized RaySlab intersection algorithm.
     * <p>
     * This method accounts for the NPC's custom scale, bounding box dimensions, and spatial orientation (yaw rotation). It supports specialized poses such as
     * {@link org.bukkit.entity.Pose#SLEEPING}, adjusting the bounding box extents and center offset dynamically to match the modified client-side model.
     * </p>
     * <p>
     * To prevent clipping exploits, the method executes a fast-fail line-of-sight raytrace via Bukkit's world physics engine up to the point of structural
     * intersection, ensuring that solid blocks or other valid entities do not obstruct the interaction.
     * </p>
     *
     * @param npc      the {@link NPC} instance whose bounding box is being evaluated
     * @param sleeping {@code true} if the NPC is currently rendered in a sleeping pose, triggering specialized horizontal bounding box transformations
     * @param player   the {@link Player} executing the interaction, used to determine ray origin, direction vector, and maximum interaction range attributes
     * @return {@code true} if the player's ray safely intersects the NPC's bounding box within their maximum interaction range without being obstructed by
     * blocks or other entities; {@code false} otherwise * @see org.bukkit.util.Vector
     * @see RayTraceResult
     */
    public static boolean rayIntersectsNpc(@NotNull NPC npc, boolean sleeping, @NotNull Player player)
    {
        Location eyeLocation = player.getEyeLocation();
        Vector origin = eyeLocation.toVector();
        Vector dir = eyeLocation.getDirection();

        double maxDistance;
        try
        {
            maxDistance = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getValue();
        }
        catch(Exception e)
        {
            maxDistance = player.getGameMode() == GameMode.CREATIVE ? 5.0 : 3.0;
        }

        Location base = npc.getLocation();
        float yaw = base.getYaw();
        double yawRad = Math.toRadians(yaw);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);

        AABB bb = (AABB) npc.getDefaultBoundingBox();
        double scale = npc.getOption(NpcOption.SCALE, player);

        double width, height, length;
        Vector centerOffset = new Vector(0, 0, 0);

        if(sleeping)
        {
            width = bb.getXsize() * scale;
            height = bb.getZsize() * scale;
            length = bb.getYsize() * scale;

            // Inlined forward vector rotation to save allocations
            Vector forward = new Vector(-sin, 0, cos).normalize();
            centerOffset.copy(forward).multiply(-length / 2.0);
            centerOffset.setY(height / 2.0);
        }
        else
        {
            width = bb.getXsize() * scale;
            height = bb.getYsize() * scale;
            length = bb.getZsize() * scale;
            centerOffset.setY(height / 2.0);
        }

        Vector center = base.toVector().add(centerOffset);
        Vector oc = origin.clone().subtract(center);

        // Inverse rotation to bring ray into local OBB space
        double ox = oc.getX() * cos + oc.getZ() * sin;
        double oz = -oc.getX() * sin + oc.getZ() * cos;
        double oy = oc.getY();

        double dx = dir.getX() * cos + dir.getZ() * sin;
        double dz = -dir.getX() * sin + dir.getZ() * cos;
        double dy = dir.getY();

        double minX = -width / 2.0, maxX = width / 2.0;
        double minY = -height / 2.0, maxY = height / 2.0;
        double minZ = -length / 2.0, maxZ = length / 2.0;

        double tMin = 0.0;
        double tMax = maxDistance;

        // X Slab
        if(Math.abs(dx) < 1e-8)
        {
            if(ox < minX || ox > maxX)
                return false;
        }
        else
        {
            double invDx = 1.0 / dx;
            double t1 = (minX - ox) * invDx;
            double t2 = (maxX - ox) * invDx;
            if(t1 > t2)
            {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if(tMin > tMax)
                return false;
        }

        // Y Slab
        if(Math.abs(dy) < 1e-8)
        {
            if(oy < minY || oy > maxY)
                return false;
        }
        else
        {
            double invDy = 1.0 / dy;
            double t1 = (minY - oy) * invDy;
            double t2 = (maxY - oy) * invDy;
            if(t1 > t2)
            {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if(tMin > tMax)
                return false;
        }

        // Z Slab
        if(Math.abs(dz) < 1e-8)
        {
            if(oz < minZ || oz > maxZ)
                return false;
        }
        else
        {
            double invDz = 1.0 / dz;
            double t1 = (minZ - oz) * invDz;
            double t2 = (maxZ - oz) * invDz;
            if(t1 > t2)
            {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if(tMin > tMax)
                return false;
        }

        RayTraceResult blockHit = eyeLocation.getWorld().rayTraceBlocks(eyeLocation, dir, tMin, FluidCollisionMode.NEVER, true);
        if(blockHit != null && blockHit.getHitBlock() != null)
            return false;

        RayTraceResult entityHit = eyeLocation.getWorld().rayTraceEntities(eyeLocation, dir, tMin, 0.1,
                (entity) -> !entity.equals(player) && entity.isValid());
        if(entityHit != null && entityHit.getHitEntity() != null)
            return false;

        return true;
    }
}