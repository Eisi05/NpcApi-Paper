package de.eisi05.npc.api.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class BedrockConnectionListener
{
    public static boolean isBedrockPlayer(@NotNull Player player)
    {
        UUID uuid = player.getUniqueId();
        if(Bukkit.getPluginManager().isPluginEnabled("floodgate"))
            return FloodgateApi.getInstance().isFloodgatePlayer(uuid);

        if(Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot"))
            return GeyserApi.api().isBedrockPlayer(uuid);

        return false;
    }

    public static void register()
    {
        if(!Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot"))
            return;

        GeyserApi.api().eventBus().subscribe(new EventRegistrar() {}, SessionJoinEvent.class,
                event ->
                {
                    Player player = Bukkit.getPlayer(event.connection().javaUuid());
                    if(player != null)
                        ConnectionListener.join(player, 5L);
                });
    }
}
