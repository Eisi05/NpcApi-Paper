package de.eisi05.npc.api.events;

import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.objects.NPC;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an NPC stops walking along its current path.
 * <p>
 * This event is fired after the walking process ends, either because the NPC successfully reached its destination ({@link WalkingResult#SUCCESS}) or because
 * the walk was canceled ({@link WalkingResult#CANCELLED}). Listeners may also control whether the NPC's real (server-side) location should be updated to the
 * NPC's final visual position.
 */
public class NpcStopWalkingEvent extends NpcWalkingEvent
{
    private final WalkingResult walkingResult;
    private boolean changeRealLocation;

    public NpcStopWalkingEvent(@NotNull NPC npc, @NotNull WalkingResult walkingResult, boolean changeRealLocation)
    {
        super(npc);
        this.walkingResult = walkingResult;
        this.changeRealLocation = changeRealLocation;
    }

    /**
     * Gets the result of the walking process.
     * <p>
     * This may be:
     * <ul>
     *     <li>{@link WalkingResult#SUCCESS} – the NPC reached its destination.</li>
     *     <li>{@link WalkingResult#CANCELLED} – the walk was interrupted or stopped.</li>
     * </ul>
     *
     * @return the walking result
     */
    public @NotNull WalkingResult getWalkingResult()
    {
        return walkingResult;
    }

    /**
     * Checks whether the NPC's real location should be updated to its final walking position.
     *
     * @return true if the real location should change, false otherwise
     */
    public boolean changeRealLocation()
    {
        return changeRealLocation;
    }

    /**
     * Sets whether the NPC's real location should be updated when the walk ends.
     *
     * @param changeRealLocation true to apply the real location update
     */
    public void setChangeRealLocation(boolean changeRealLocation)
    {
        this.changeRealLocation = changeRealLocation;
    }
}
