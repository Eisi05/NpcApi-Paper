package de.eisi05.npc.api.events;

import de.eisi05.npc.api.enums.WalkingResult;
import de.eisi05.npc.api.objects.NPC;
import org.jetbrains.annotations.NotNull;

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

    public @NotNull WalkingResult getWalkingResult()
    {
        return walkingResult;
    }

    public boolean changeRealLocation()
    {
        return changeRealLocation;
    }

    public void setChangeRealLocation(boolean changeRealLocation)
    {
        this.changeRealLocation = changeRealLocation;
    }
}
