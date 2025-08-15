package de.eisi05.npc.api.wrapper.packets;

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.NotNull;

public class SetPlayerTeamPacket
{
    public static ClientboundSetPlayerTeamPacket createAddOrModifyPacket(@NotNull PlayerTeam team, boolean create)
    {
        return ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, create);
    }

    public static ClientboundSetPlayerTeamPacket createRemovePacket(@NotNull PlayerTeam team)
    {
        return ClientboundSetPlayerTeamPacket.createRemovePacket(team);
    }

    public static ClientboundSetPlayerTeamPacket createPlayerPacket(@NotNull PlayerTeam team, @NotNull String playerName,
            @NotNull ClientboundSetPlayerTeamPacket.Action action)
    {
        return ClientboundSetPlayerTeamPacket.createPlayerPacket(team, playerName, action);
    }
}
