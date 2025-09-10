package de.eisi05.npc.api.wrapper.packets;

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.NotNull;

public class SetPlayerTeamPacket
{
    public static Object createAddOrModifyPacket(@NotNull PlayerTeam team, boolean create)
    {
        return ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, create);
    }

    public static Object createRemovePacket(@NotNull PlayerTeam team)
    {
        return ClientboundSetPlayerTeamPacket.createRemovePacket(team);
    }

    public static Object createPlayerPacket(@NotNull PlayerTeam team, @NotNull String playerName,
            @NotNull ClientboundSetPlayerTeamPacket.Action action)
    {
        return ClientboundSetPlayerTeamPacket.createPlayerPacket(team, playerName, action);
    }
}
