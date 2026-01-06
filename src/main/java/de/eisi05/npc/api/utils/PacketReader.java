package de.eisi05.npc.api.utils;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.enums.ClickActionType;
import de.eisi05.npc.api.events.NpcInteractEvent;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.objects.NPC;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * The {@link PacketReader} class is responsible for injecting a custom Netty
 * channel handler into a player's network pipeline to intercept incoming packets.
 * It specifically listens for packets related to entity interaction (e.g., attacking or interacting with NPCs)
 * and dispatches custom events based on these interactions. It also allows for
 * custom packet readers to be added.
 */
public class PacketReader
{
    private static final Map<UUID, Channel> channels = new HashMap<>();
    private static final List<BiConsumer<Player, Object>> readers = new ArrayList<>();

    /**
     * Adds a custom packet reader to the list of readers.
     * This reader will be called for every incoming packet processed by the injected handler.
     *
     * @param reader The {@link BiConsumer} to add. It accepts the {@link Player}
     *               and the raw packet {@link Object}. Must not be {@code null}.
     */
    public static void addReader(@NotNull BiConsumer<Player, Object> reader)
    {
        readers.add(reader);
    }

    /**
     * Injects a custom {@link ChannelDuplexHandler} into the specified player's Netty pipeline.
     * This handler intercepts incoming packets to check for NPC interactions.
     * The handler is named after the plugin's name to avoid conflicts and ensure proper removal.
     *
     * @param player The {@link Player} whose pipeline is to be injected. Must not be {@code null}.
     */
    public static void inject(@NotNull Player player)
    {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;

        if(channel == null)
            return;

        channels.put(player.getUniqueId(), channel);

        if(channel.pipeline().get(NpcApi.plugin.getName()) != null)
            return;

        ChannelDuplexHandler duplexHandler = new ChannelDuplexHandler()
        {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception
            {
                checkForPacket(msg, player);

                readers.forEach(consumer -> consumer.accept(player, msg));

                super.channelRead(ctx, msg);
            }
        };

        if(channel.pipeline().get("packet_handler") == null)
            channel.pipeline().addLast(NpcApi.plugin.getName(), duplexHandler);
        else
            channel.pipeline().addBefore("packet_handler", NpcApi.plugin.getName(), duplexHandler);
    }

    /**
     * Checks if the given packet is a {@link ServerboundInteractPacket} and, if so,
     * processes the interaction to dispatch a {@link NpcInteractEvent}.
     * This method is responsible for determining if a player has clicked or attacked an NPC.
     *
     * @param packet The raw packet object received from the Netty pipeline. Must not be {@code null}.
     * @param player The {@link Player} who sent the packet. Must not be {@code null}.
     */
    private static void checkForPacket(@NotNull Object packet, @NotNull Player player)
    {
        if(!(packet instanceof Packet<?>))
            return;

        if(!(packet instanceof ServerboundInteractPacket interactPacket))
            return;

        int id = interactPacket.getEntityId();

        NPC npc = NpcManager.getList().stream().filter(npc1 -> ((ServerPlayer) npc1.getServerPlayer()).getId() == id).findFirst().orElse(null);

        if(npc == null)
            return;

        if(interactPacket.isAttack())
        {
            Bukkit.getScheduler().scheduleSyncDelayedTask(NpcApi.plugin,
                    () -> Bukkit.getPluginManager().callEvent(new NpcInteractEvent(player, npc, ClickActionType.LEFT)), 0);

            return;
        }

        var action = Reflections.getField(interactPacket, "action");

        if(action.get().getClass().getDeclaredFields().length == 2)
            return;

        InteractionHand hand = (InteractionHand) action.thanGetField("hand").get();

        if(hand == InteractionHand.MAIN_HAND)
            Bukkit.getScheduler().scheduleSyncDelayedTask(NpcApi.plugin,
                    () -> Bukkit.getPluginManager().callEvent(new NpcInteractEvent(player, npc, ClickActionType.RIGHT)), 0);
    }

    /**
     * Uninjects the custom {@link ChannelDuplexHandler} from the specified player's Netty pipeline.
     * This stops the interception of packets for that player.
     *
     * @param player The {@link Player} whose pipeline is to be uninject. Must not be {@code null}.
     */
    public static void uninject(@NotNull Player player)
    {
        Channel channel = channels.get(player.getUniqueId());

        if(channel == null)
            return;

        if(channel.pipeline().get(NpcApi.plugin.getName()) != null)
            channel.pipeline().remove(NpcApi.plugin.getName());
    }

    /**
     * Uninjects the custom packet handler from all currently online players.
     * This is typically called during plugin shutdown or reload.
     */
    public static void uninjectAll()
    {
        for(Player player : Bukkit.getOnlinePlayers())
            uninject(player);
    }

    /**
     * Injects the custom packet handler into all currently online players.
     * This is typically called during plugin startup or after a reload.
     */
    public static void injectAll()
    {
        for(Player player : Bukkit.getOnlinePlayers())
            inject(player);
    }
}
