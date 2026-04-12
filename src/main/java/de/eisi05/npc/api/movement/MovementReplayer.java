package de.eisi05.npc.api.movement;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.objects.NPC;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Replays recorded movements on NPCs with precise timing and smooth transitions. Supports different replay modes and provides callbacks for completion.
 */
public class MovementReplayer
{
    private static final ConcurrentHashMap<UUID, ReplaySession> activeReplays = new ConcurrentHashMap<>();
    private static final AtomicLong replayIdCounter = new AtomicLong(0);

    /**
     * Starts replaying recorded movements on an NPC.
     *
     * @param npc                The NPC to control
     * @param recording          The movement recording
     * @param speedMultiplier    Speed multiplier (speed up or slows down the replay), normal speed = 1
     * @param changeRealLocation Whether to change the real location of the NPC
     * @param onComplete         Callback when replay completes
     * @return The replay session ID
     */
    public static long startReplay(@NotNull Function<NPC, NPC> npc, @NotNull MovementRecording recording, double speedMultiplier, boolean changeRealLocation,
                                   @Nullable Consumer<ReplayResult> onComplete, @NotNull Player... viewers)
    {
        if(recording.movements().isEmpty())
        {
            if(onComplete != null)
                onComplete.accept(ReplayResult.COMPLETED);
            return -1;
        }

        long replayId = replayIdCounter.incrementAndGet();
        ReplaySession session = new ReplaySession(npc, recording, speedMultiplier, changeRealLocation, onComplete, replayId, viewers);

        session.start();
        return replayId;
    }

    /**
     * Stops an active replay session.
     *
     * @param npc The NPC whose replay to stop
     * @return true if a replay was stopped, false otherwise
     */
    public static boolean stopReplay(@NotNull NPC npc)
    {
        ReplaySession session = activeReplays.remove(npc.getUUID());
        if(session != null)
        {
            session.stop();
            return true;
        }
        return false;
    }

    /**
     * Stops a replay by session ID.
     *
     * @param replayId The replay session ID
     * @return true if a replay was stopped, false otherwise
     */
    public static boolean stopReplay(long replayId)
    {
        ReplaySession session = activeReplays.values().stream()
                .filter(s -> s.getReplayId() == replayId)
                .findFirst()
                .orElse(null);

        if(session != null)
        {
            if(session.getNpc() != null)
                activeReplays.remove(session.getNpc().getUUID());
            session.stop();
            return true;
        }
        return false;
    }

    /**
     * Checks if an NPC is currently replaying movements.
     *
     * @param npc The NPC to check
     * @return true if actively replaying, false otherwise
     */
    public static boolean isReplaying(@NotNull NPC npc)
    {
        return activeReplays.containsKey(npc.getUUID());
    }

    /**
     * Represents the result of a replay session.
     */
    public enum ReplayResult
    {
        COMPLETED,
        CANCELLED,
        ERROR
    }

    /**
     * Represents an active replay session for an NPC.
     */
    private static class ReplaySession
    {
        private final Function<NPC, NPC> npcFunction;
        private final MovementRecording recording;
        private final double speedMultiplier;
        private final boolean changeRealLocation;
        private final Consumer<ReplayResult> onComplete;
        private final long replayId;
        private final long startTime;
        private final Player[] viewers;

        private NPC npc;
        private BukkitTask replayTask;
        private int currentIndex;
        private long lastTimestamp;

        private ReplaySession(@NotNull Function<NPC, NPC> npcFunction, @NotNull MovementRecording recording, double speedMultiplier, boolean changeRealLocation,
                              @Nullable Consumer<ReplayResult> onComplete, long replayId, @NotNull Player... viewers)
        {
            this.npcFunction = npcFunction;
            this.recording = recording;
            this.speedMultiplier = Math.max(0.1, speedMultiplier); // Minimum 0.1x speed
            this.changeRealLocation = changeRealLocation;
            this.onComplete = onComplete;
            this.replayId = replayId;
            this.startTime = System.currentTimeMillis();
            this.currentIndex = 0;
            this.lastTimestamp = 0;
            this.viewers = viewers;
        }

        private void start()
        {
            // Teleport NPC to starting position
            MovementData firstMovement = recording.getFirstMovement();

            if(firstMovement == null)
            {
                complete(ReplayResult.ERROR);
                return;
            }

            World world = Bukkit.getWorld(firstMovement.getWorldUUID());
            if(world == null)
            {
                complete(ReplayResult.ERROR);
                return;
            }

            Location startLocation = firstMovement.toLocation(world);
            NPC newNpc = new NPC(startLocation);
            newNpc.addCustomData("movement-replayer", "delete");
            npc = npcFunction.apply(newNpc);

            npc.setEnabled(true);
            for(Player p : viewers)
                npc.showNPCToPlayer(p);

            Location currentLocation = npc.getLocation();
            npc.changeRealLocation(startLocation);
            if(!changeRealLocation)
                npc.setLocation(currentLocation);

            activeReplays.put(npc.getUUID(), this);

            // Start replay task
            replayTask = new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    if(currentIndex >= recording.getMovementCount())
                    {
                        complete(ReplayResult.COMPLETED);
                        return;
                    }

                    // Calculate timing based on mode - process multiple movements per tick for high speed multipliers
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    int movementsProcessed = 0;

                    while(currentIndex < recording.movements().size() && movementsProcessed < speedMultiplier)
                    {
                        MovementData currentMovement = recording.movements().get(currentIndex);
                        long scaledTime = (long) (currentMovement.getTimestamp() / speedMultiplier);

                        if(elapsedTime >= scaledTime)
                        {
                            executeMovement(currentMovement);
                            currentIndex++;
                            movementsProcessed++;
                        }
                        else
                            break;
                    }
                }
            }.runTaskTimer(NpcApi.plugin, 1L, 1L);
        }

        private void executeMovement(@NotNull MovementData movement)
        {
            World world = Bukkit.getWorld(movement.getWorldUUID());
            if(world == null)
            {
                complete(ReplayResult.ERROR);
                return;
            }

            Location targetLocation = movement.toLocation(world);

            // Check if this is a teleport (large distance) or smooth movement
            ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket((Entity) npc.getEntity(), (byte) (targetLocation.getYaw() * 256 / 360));
            if(lastTimestamp > 0)
            {
                MovementData previousMovement = recording.movements().get(Math.max(0, currentIndex - 1));
                double distance = previousMovement.toLocation(world).distance(targetLocation);

                if(distance > 10) // Teleport threshold
                {
                    ClientboundTeleportEntityPacket teleport1 = new ClientboundTeleportEntityPacket(((Entity) npc.getEntity()).getId(),
                            new PositionMoveRotation(
                                    new Vec3(targetLocation.toVector().getX(), targetLocation.toVector().getY(), targetLocation.toVector().getZ()),
                                    new Vec3(0, 0, 0), targetLocation.getYaw(), targetLocation.getPitch()), Set.of(), true);
                    npc.sendNpcMovePackets(teleport1, head, viewers);
                }
                else
                {
                    Vector currentVec = targetLocation.toVector();
                    Vector movementVec = movement.toLocation(world).toVector();
                    Vec3 currentVec3 = new Vec3(currentVec.getX(), currentVec.getY(), currentVec.getZ());
                    Vec3 movementVec3 = new Vec3(movementVec.getX(), movementVec.getY(), movementVec.getZ());
                    ClientboundTeleportEntityPacket teleport = new ClientboundTeleportEntityPacket(((Entity) npc.getEntity()).getId(),
                            new PositionMoveRotation(currentVec3, movementVec3, targetLocation.getYaw(), targetLocation.getPitch()), Set.of(), true);

                    npc.sendNpcMovePackets(teleport, head, viewers);

                    // Update NPC's internal location
                    if(changeRealLocation)
                        npc.setLocation(targetLocation);
                }
            }
            else
            {
                // First movement - teleport to position
                Vector currentVec = targetLocation.toVector();
                Vec3 currentVec3 = new Vec3(currentVec.getX(), currentVec.getY(), currentVec.getZ());
                ClientboundTeleportEntityPacket teleport1 = new ClientboundTeleportEntityPacket(((Entity) npc.getEntity()).getId(),
                        new PositionMoveRotation(currentVec3, new Vec3(0, 0, 0), targetLocation.getYaw(), targetLocation.getPitch()), Set.of(), true);
                npc.sendNpcMovePackets(teleport1, head, viewers);
            }

            lastTimestamp = movement.getTimestamp();
        }

        private void stop()
        {
            if(replayTask != null)
            {
                replayTask.cancel();
                replayTask = null;
            }
            complete(ReplayResult.CANCELLED);
        }

        private void complete(@NotNull ReplayResult result)
        {
            if(replayTask != null)
            {
                replayTask.cancel();
                replayTask = null;
            }

            if(npc != null)
            {
                activeReplays.remove(npc.getUUID());

                if(!"delete".equals(npc.getCustomData("movement-replayer")))
                    return;
                
                try
                {
                    npc.delete();
                }
                catch(IOException e)
                {
                }
            }

            if(onComplete != null)
                onComplete.accept(result);
        }

        public @Nullable NPC getNpc()
        {
            return npc;
        }

        public long getReplayId()
        {
            return replayId;
        }

        public int getCurrentIndex()
        {
            return currentIndex;
        }

        public int getTotalMovements()
        {
            return recording.movements().size();
        }

        public double getProgress()
        {
            return recording.movements().isEmpty() ? 1.0 : (double) currentIndex / recording.movements().size();
        }
    }
}
