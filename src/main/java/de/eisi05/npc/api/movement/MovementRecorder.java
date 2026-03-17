package de.eisi05.npc.api.movement;

import de.eisi05.npc.api.NpcApi;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
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
    public static class RecordingSession
    {
        private final Player player;
        private final long sessionId;
        private final int intervalTicks;
        private final ArrayList<MovementData> movements;
        private final long startTime;
        private BukkitTask recordingTask;

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
            movements.add(new MovementData(player.getLocation(), 0));

            recordingTask = new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    if (!player.isOnline())
                    {
                        stop();
                        return;
                    }

                    long timestamp = System.currentTimeMillis() - startTime;
                    movements.add(new MovementData(player.getLocation(), timestamp));
                }
            }.runTaskTimer(NpcApi.plugin, intervalTicks, intervalTicks);
        }

        private @NotNull MovementRecording stop()
        {
            if (recordingTask != null)
            {
                recordingTask.cancel();
                recordingTask = null;
            }

            long endTime = System.currentTimeMillis();
            return new MovementRecording(movements, sessionId, startTime, endTime, player.getUniqueId(), intervalTicks);
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
