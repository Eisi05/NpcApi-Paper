package de.eisi05.npc.api.manager;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeamManager
{
    public static final Map<UUID, Map<String, TeamData>> teams = new HashMap<>();

    public static @NotNull Object create(@NotNull Player player, @NotNull String name)
    {
        if(exists(player, name))
            return teams.get(player.getUniqueId()).get(name).team;

        Scoreboard scoreboard = ((CraftScoreboard) player.getScoreboard()).getHandle();

        PlayerTeam team = new PlayerTeam(scoreboard, name);

        var map = teams.getOrDefault(player.getUniqueId(), new HashMap<>());
        map.put(name, new TeamData(team, false));
        teams.put(player.getUniqueId(), map);

        return team;
    }

    public static boolean exists(@NotNull Player player, @NotNull String name)
    {
        return teams.getOrDefault(player.getUniqueId(), new HashMap<>()).containsKey(name);
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
        for(var entry : teams.entrySet())
            entry.getValue().keySet().removeIf(s -> s.equals(name));
    }

    public static void clear(UUID uuid, String name)
    {
        teams.get(uuid).remove(name);
    }

    public static @Nullable TeamData getTeamData(@NotNull Player player, @NotNull String name)
    {
        return teams.getOrDefault(player.getUniqueId(), new HashMap<>()).get(name);
    }

    public static class TeamData
    {
        private final PlayerTeam team;
        private boolean shown;

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
