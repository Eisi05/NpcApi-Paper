package de.eisi05.npc.api.events;

import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.pathfinding.Path;
import org.bukkit.event.Cancellable;
import org.jetbrains.annotations.NotNull;

//TODO write comments
public class NpcStartWalkingEvent extends NpcWalkingEvent implements Cancellable
{
    private final Path path;
    private double walkSpeed;
    private boolean changeRealLocation;
    private boolean cancelled = false;

    public NpcStartWalkingEvent(@NotNull NPC npc, @NotNull Path path, double walkSpeed, boolean changeRealLocation)
    {
        super(npc);
        this.path = path;
        this.walkSpeed = walkSpeed;
        this.changeRealLocation = changeRealLocation;
    }

    public @NotNull Path getPath()
    {
        return path;
    }

    public double getWalkSpeed()
    {
        return walkSpeed;
    }

    public void setWalkSpeed(double walkSpeed)
    {
        this.walkSpeed = walkSpeed;
    }

    public boolean isChangeRealLocation()
    {
        return changeRealLocation;
    }

    public void setChangeRealLocation(boolean changeRealLocation)
    {
        this.changeRealLocation = changeRealLocation;
    }

    @Override
    public boolean isCancelled()
    {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel)
    {
        this.cancelled = cancel;
    }
}
