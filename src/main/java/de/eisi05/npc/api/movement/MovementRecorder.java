package de.eisi05.npc.api.movement;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.scheduler.PluginTask;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import de.eisi05.npc.api.wrapper.packets.AnimatePacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records player movements at a specified interval using a scheduler-based approach.
 * This provides more precise and consistent tracking compared to PlayerMoveEvent.
 */
public class MovementRecorder
{
    private static final ConcurrentHashMap<UUID, RecordingSession> activeRecordings = new ConcurrentHashMap<>();
    private static final AtomicLong sessionIdCounter = new AtomicLong(0);

    /**
     * Starts recording a player's movements.
     *
     * @param player The player to record
     * @param intervalTicks The recording interval in ticks (1 tick = 50ms)
     * @return The recording session ID
     */
    public static long startRecording(@NotNull Player player, int intervalTicks)
    {
        long sessionId = sessionIdCounter.incrementAndGet();
        RecordingSession session = new RecordingSession(player, sessionId, intervalTicks);
        activeRecordings.put(player.getUniqueId(), session);
        session.start();
        return sessionId;
    }

    /**
     * Stops recording a player's movements and returns the recorded data.
     *
     * @param player The player to stop recording
     * @return The recorded movement data, or null if no active recording
     */
    public static @Nullable MovementRecording stopRecording(@NotNull Player player)
    {
        RecordingSession session = activeRecordings.remove(player.getUniqueId());
        if (session != null)
            return session.stop();
        return null;
    }

    /**
     * Stops recording by session ID and returns the recorded data.
     *
     * @param sessionId The session ID to stop
     * @return The recorded movement data, or null if session not found
     */
    public static @Nullable MovementRecording stopRecording(long sessionId)
    {
        RecordingSession session = activeRecordings.values().stream()
                .filter(s -> s.getSessionId() == sessionId)
                .findFirst()
                .orElse(null);

        if (session != null)
        {
            activeRecordings.remove(session.getPlayer().getUniqueId());
            return session.stop();
        }
        return null;
    }

    /**
     * Checks if a player is currently being recorded.
     *
     * @param player The player to check
     * @return true if actively recording, false otherwise
     */
    public static boolean isRecording(@NotNull Player player)
    {
        return activeRecordings.containsKey(player.getUniqueId());
    }

    /**
     * Gets the active recording session for a player.
     *
     * @param player The player
     * @return The recording session, or null if not recording
     */
    public static @Nullable RecordingSession getActiveSession(@NotNull Player player)
    {
        return activeRecordings.get(player.getUniqueId());
    }

    /**
     * Represents an active recording session for a player.
     */
    public static class RecordingSession implements Listener
    {
        private final Player player;
        private final long sessionId;
        private final int intervalTicks;
        private final ArrayList<MovementData> movements;
        private final long startTime;
        private PluginTask recordingTask;

        private boolean nextTickSwingMain = false;
        private boolean nextTickSwingOff = false;

        private RecordingSession(@NotNull Player player, long sessionId, int intervalTicks)
        {
            this.player = player;
            this.sessionId = sessionId;
            this.intervalTicks = Math.max(1, intervalTicks);
            this.movements = new ArrayList<>();
            this.startTime = System.currentTimeMillis();
        }

        private void start()
        {
            Bukkit.getPluginManager().registerEvents(this, NpcApi.plugin);

            movements.add(new MovementData(player.getLocation(), player.getPose(), null, 0));

            recordingTask = SchedulerProvider.get().runTimerForEntity(player, () ->
            {
                if(!player.isOnline())
                {
                    stop();
                    return;
                }

                AnimatePacket.Animation animation = null;
                if(nextTickSwingMain)
                    animation = AnimatePacket.Animation.SWING_MAIN_HAND;
                if(nextTickSwingOff)
                    animation = AnimatePacket.Animation.SWING_OFF_HAND;
                nextTickSwingMain = false;
                nextTickSwingOff = false;

                long timestamp = System.currentTimeMillis() - startTime;
                movements.add(new MovementData(player.getLocation(), player.getPose(), animation, timestamp));
            }, intervalTicks, intervalTicks);
        }

        private @NotNull MovementRecording stop()
        {
            HandlerList.unregisterAll(this);

            if (recordingTask != null)
            {
                recordingTask.cancel();
                recordingTask = null;
            }

            long endTime = System.currentTimeMillis();
            return new MovementRecording(movements, sessionId, startTime, endTime, player.getUniqueId(), intervalTicks);
        }

        @EventHandler
        public void onPlayerAnimation(PlayerAnimationEvent event)
        {
            if(!event.getPlayer().getUniqueId().equals(this.player.getUniqueId()))
                return;

            if(event.getAnimationType() == PlayerAnimationType.ARM_SWING)
                nextTickSwingMain = true;
            else
                nextTickSwingOff = true;
        }

        public @NotNull Player getPlayer()
        {
            return player;
        }

        public long getSessionId()
        {
            return sessionId;
        }

        public int getIntervalTicks()
        {
            return intervalTicks;
        }

        public @NotNull List<MovementData> getMovements()
        {
            return new ArrayList<>(movements);
        }

        public long getStartTime()
        {
            return startTime;
        }

        public long getDuration()
        {
            return System.currentTimeMillis() - startTime;
        }

        public int getMovementCount()
        {
            return movements.size();
        }
    }
}
