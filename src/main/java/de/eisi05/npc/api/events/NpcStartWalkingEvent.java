package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.Path;
import org.bukkit.event.Cancellable;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an NPC is about to begin walking along a specified {@link Path}.
 * <p>
 * This event is fired before the walking actually starts, allowing listeners to modify walking parameters such as speed or whether the NPC's real location
 * should be updated. The event can also be canceled to prevent the NPC from starting to walk.
 */
public class NpcStartWalkingEvent extends NpcWalkingEvent implements Cancellable
{
    /**
     * The path the NPC will follow.
     */
    private final Path path;

    /**
     * The walking speed the NPC will use.
     */
    private double walkSpeed;

    /**
     * Whether the NPC's actual (server-side) location should be updated. If false, only the visual movement may be applied.
     */
    private boolean changeRealLocation;

    /**
     * Whether this event has been canceled.
     */
    private boolean cancelled = false;

    /**
     * Creates a new {@code NpcStartWalkingEvent}.
     *
     * @param npc                the NPC that is about to start walking
     * @param path               the path the NPC will follow
     * @param walkSpeed          the speed at which the NPC will walk
     * @param changeRealLocation whether the NPC's real location should change during walking
     */
    public NpcStartWalkingEvent(@NotNull NPC npc, @NotNull Path path, double walkSpeed, boolean changeRealLocation)
    {
        super(npc);
        this.path = path;
        this.walkSpeed = walkSpeed;
        this.changeRealLocation = changeRealLocation;
    }


    /**
     * Gets the path the NPC will follow.
     *
     * @return the walking path
     */
    public @NotNull Path getPath()
    {
        return path;
    }

    /**
     * Gets the NPC’s walking speed.
     *
     * @return the walking speed
     */
    public double getWalkSpeed()
    {
        return walkSpeed;
    }

    /**
     * Sets the NPC’s walking speed.
     *
     * @param walkSpeed the new walking speed
     */
    public void setWalkSpeed(double walkSpeed)
    {
        this.walkSpeed = walkSpeed;
    }

    /**
     * Checks whether the NPC's real location will be updated while walking.
     *
     * @return true if the real location changes, false otherwise
     */
    public boolean isChangeRealLocation()
    {
        return changeRealLocation;
    }

    /**
     * Sets whether the NPC's real location should update during walking.
     *
     * @param changeRealLocation true to update the real location
     */
    public void setChangeRealLocation(boolean changeRealLocation)
    {
        this.changeRealLocation = changeRealLocation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled()
    {
        return cancelled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCancelled(boolean cancel)
    {
        this.cancelled = cancel;
    }
}
