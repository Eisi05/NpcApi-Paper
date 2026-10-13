package de.eisi05.npc.api.manager;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the scoreboard teams the API created per player. The maps are concurrent because they are used from several threads at once: the players' own
 * threads (join, show/hide), the global thread and the Netty thread of the packet handler ({@code PacketReader}), which asks {@link #getTeamData} for every
 * outgoing team packet.
 */
public class TeamManager
{
    public static final Map<UUID, Map<String, TeamData>> teams = new ConcurrentHashMap<>();

    public static @NotNull Object create(@NotNull Player player, @NotNull String name)
    {
        return teams.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).computeIfAbsent(name, n ->
        {
            Scoreboard scoreboard = ((CraftScoreboard) player.getScoreboard()).getHandle();
            return new TeamData(new PlayerTeam(scoreboard, n), false);
        }).team;
    }

    public static boolean exists(@NotNull Player player, @NotNull String name)
    {
        Map<String, TeamData> map = teams.get(player.getUniqueId());
        return map != null && map.containsKey(name);
    }

    public static void clear()
    {
        teams.clear();
    }

    public static void clear(@NotNull UUID uuid)
    {
        teams.remove(uuid);
    }

    public static void clear(String name)
    {
        for(Map<String, TeamData> map : teams.values())
            map.remove(name);
    }

    public static void clear(UUID uuid, String name)
    {
        Map<String, TeamData> map = teams.get(uuid);
        if(map != null)
            map.remove(name);
    }

    public static @Nullable TeamData getTeamData(@NotNull Player player, @NotNull String name)
    {
        Map<String, TeamData> map = teams.get(player.getUniqueId());
        return map == null ? null : map.get(name);
    }

    public static class TeamData
    {
        private final PlayerTeam team;
        private volatile boolean shown;

        private TeamData(PlayerTeam team, boolean shown)
        {
            this.team = team;
            this.shown = shown;
        }

        public boolean isShown()
        {
            return shown;
        }

        public void setShown(boolean shown)
        {
            this.shown = shown;
        }
    }
}