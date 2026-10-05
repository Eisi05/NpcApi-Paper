package de.eisi05.npc.api.ai.goals;

import de.eisi05.npc.api.ai.Goal;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class EntityTargetGoal extends Goal
{
    protected transient LivingEntity target;

    private transient Block lastTargetBlock = null;
    private transient Location cachedFloorLocation = null;

    /**
     * Creates a new goal with the specified priority.
     *
     * @param priority The priority level for this goal
     */
    protected EntityTargetGoal(@NotNull Priority priority)
    {
        super(priority);
    }

    /**
     * Gets the current target location for this goal.
     *
     * @return the current target location, or null if no target is set
     */
    protected final @Nullable Location getTargetLocation()
    {
        if(target == null)
            return null;

        Block currentTargetBlock = target.getLocation().getBlock();
        if(!currentTargetBlock.equals(lastTargetBlock))
        {
            lastTargetBlock = currentTargetBlock;

            RayTraceResult result = target.getWorld().rayTraceBlocks(
                    target.getLocation(),
                    new Vector(0, -1, 0),
                    256,
                    FluidCollisionMode.NEVER,
                    true
            );

            if(result != null && result.getHitBlock() != null)
                cachedFloorLocation = result.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
            else
                cachedFloorLocation = target.getLocation();
        }

        return cachedFloorLocation;
    }
}
