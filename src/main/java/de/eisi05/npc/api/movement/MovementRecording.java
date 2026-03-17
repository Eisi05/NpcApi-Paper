package de.eisi05.npc.api.movement;

import de.eisi05.npc.api.utils.ObjectSaver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Creates a new MovementRecording instance.
 *
 * @param movements     The list of movement data points
 * @param sessionId     The recording session ID
 * @param startTime     The recording start time in milliseconds
 * @param endTime       The recording end time in milliseconds
 * @param playerUUID    The UUID of the recorded player
 * @param intervalTicks The recording interval in ticks
 */
public record MovementRecording(@NotNull ArrayList<MovementData> movements, long sessionId, long startTime, long endTime, @NotNull UUID playerUUID,
                                int intervalTicks) implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Loads a MovementRecording from a file.
     *
     * @param file The file to load the recording from
     * @return The loaded MovementRecording
     * @throws IOException      If an I/O error occurs while reading the file
     * @throws RuntimeException If the file cannot be deserialized
     */
    public static @NotNull MovementRecording loadFromFile(@NotNull File file) throws IOException
    {
        try
        {
            return new ObjectSaver(file).read();
        }
        catch(RuntimeException e)
        {
            if(e.getCause() instanceof IOException ioException)
                throw ioException;
            throw e;
        }
    }

    /**
     * Saves this MovementRecording to a file.
     *
     * @param file The file to save the recording to
     * @throws IOException If an I/O error occurs while writing the file
     */
    public void saveToFile(@NotNull File file) throws IOException
    {
        new ObjectSaver(file).write(this);
    }

    /**
     * Gets the total duration of the recording.
     *
     * @return The duration in milliseconds
     */
    public long getDuration()
    {
        return endTime - startTime;
    }

    /**
     * Gets the number of movement data points in this recording.
     *
     * @return The movement count
     */
    public int getMovementCount()
    {
        return movements.size();
    }

    /**
     * Gets the first movement data point.
     *
     * @return The first movement, or null if empty
     */
    public @Nullable MovementData getFirstMovement()
    {
        return movements.isEmpty() ? null : movements.getFirst();
    }

    /**
     * Gets the last movement data point.
     *
     * @return The last movement, or null if empty
     */
    public @Nullable MovementData getLastMovement()
    {
        return movements.isEmpty() ? null : movements.getLast();
    }

    @Override
    public @NotNull String toString()
    {
        return String.format("MovementRecording{sessionId=%d, movements=%d, duration=%dms, player=%s}", sessionId, movements.size(), getDuration(), playerUUID);
    }
}
