package de.eisi05.npc.api.utils;

import de.eisi05.npc.api.NpcApi;
import de.eisi05.npc.api.enums.ClickActionType;
import de.eisi05.npc.api.events.NpcInteractEvent;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.scheduler.SchedulerProvider;
import de.eisi05.npc.api.wrapper.packets.AnimatePacket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * The {@link PacketReader} class is responsible for injecting a custom Netty channel handler into a player's network pipeline to intercept incoming packets. It
 * specifically listens for packets related to entity interaction (e.g., attacking or interacting with NPCs) and dispatches custom events based on these
 * interactions. It also allows for custom packet readers to be added.
 */
public class PacketReader
{
    private static final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private static final List<BiConsumer<Player, Object>> readers = new CopyOnWriteArrayList<>();
    private static final Map<UUID, Long> cancelUseUntilTick = new ConcurrentHashMap<>();

    /**
     * Adds a custom packet reader to the list of readers. This reader will be called for every incoming packet processed by the injected handler.
     *
     * @param reader The {@link BiConsumer} to add. It accepts the {@link Player} and the raw packet {@link Object}. Must not be {@code null}.
     */
    public static void addReader(@NotNull BiConsumer<Player, Object> reader)
    {
        readers.add(reader);
    }

    /**
     * Injects a custom {@link ChannelDuplexHandler} into the specified player's Netty pipeline. This handler intercepts incoming packets to check for NPC
     * interactions. The handler is named after the plugin's name to avoid conflicts and ensure proper removal.
     *
     * @param player The {@link Player} whose pipeline is to be injected. Must not be {@code null}.
     */
    public static void inject(@NotNull Player player)
    {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;

        if(channel == null || NpcApi.plugin == null)
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

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception
            {
                if(msg instanceof ClientboundChangeDifficultyPacket difficultyPacket)
                {
                    if(difficultyPacket.getDifficulty() == Difficulty.PEACEFUL)
                    {
                        SchedulerProvider.get().runSyncAtLocation(player.getWorld().getSpawnLocation(), () ->
                        {
                            long amount = NpcManager.unloadMonsterNpcs(player.getWorld());
                            Component message =
                                    Component.text("Unloaded " + amount + " NPCs because the difficulty changed to peaceful!").color(NamedTextColor.RED);
                            Bukkit.getOnlinePlayers().stream().filter(player1 -> player1.isOp() || player1.hasPermission("npc.admin"))
                                            .forEach(player1 -> player1.sendMessage(message));
                        });

                        return;
                    }

                    NpcManager.loadMonsterNpcs((location, runnable) ->
                    {
                        if(location.getWorld().equals(player.getWorld()))
                            SchedulerProvider.get().runLaterAtLocation(location, runnable, 1L);
                    });
                }

                super.write(ctx, msg, promise);
            }
        };

        if(channel.pipeline().get("packet_handler") == null)
            channel.pipeline().addLast(NpcApi.plugin.getName(), duplexHandler);
        else
            channel.pipeline().addBefore("packet_handler", NpcApi.plugin.getName(), duplexHandler);
    }

    /**
     * Checks if the given packet is a {@link ServerboundInteractPacket} and, if so, processes the interaction to dispatch a {@link NpcInteractEvent}. This
     * method is responsible for determining if a player has clicked or attacked an NPC.
     *
     * @param packet The raw packet object received from the Netty pipeline. Must not be {@code null}.
     * @param player The {@link Player} who sent the packet. Must not be {@code null}.
     */
    private static void checkForPacket(@NotNull Object packet, @NotNull Player player)
    {
        if(!(packet instanceof Packet<?>))
            return;

        if(packet instanceof ServerboundUseItemPacket)
        {
            long currentTick = SchedulerProvider.isFolia() ? System.currentTimeMillis() : Bukkit.getCurrentTick();
            Long until = cancelUseUntilTick.remove(player.getUniqueId());

            if(until == null || currentTick > until)
                return;

            SchedulerProvider.get().runSyncForEntity(player, () ->
            {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
                serverPlayer.stopUsingItem();
                serverPlayer.connection.send((Packet<?>) AnimatePacket.create(serverPlayer, AnimatePacket.Animation.SWING_MAIN_HAND));
                player.updateInventory();
            });
        }

        long currentTick = SchedulerProvider.isFolia() ? System.currentTimeMillis() : Bukkit.getCurrentTick();

        if(!(packet instanceof ServerboundInteractPacket interactPacket))
            return;

        int id = interactPacket.getEntityId();
        NPC npc = NpcManager.fromId(id).orElse(null);
        if(npc == null)
            return;

        if(interactPacket.isAttack())
        {
            if(interactPacket.isUsingSecondaryAction())
                return;

            callNpc(player, npc, ClickActionType.LEFT);
            cancelUseUntilTick.put(player.getUniqueId(), SchedulerProvider.isFolia() ? currentTick + 500 : currentTick + 10);
            return;
        }

        var action = Reflections.getField(interactPacket, Var.obfuscated ? "c" : "action");
        if(action.get().getClass().getDeclaredFields().length == 2)
                return;

        InteractionHand hand = (InteractionHand) action.thenGetField(Var.obfuscated ? "a" : "hand").get();
        if(hand == InteractionHand.MAIN_HAND)
        {
            callNpc(player, npc, ClickActionType.RIGHT);
            cancelUseUntilTick.put(player.getUniqueId(), SchedulerProvider.isFolia() ? currentTick + 500 : currentTick + 10);
        }
    }

    /**
     * Calls the {@link NpcInteractEvent} for the specified player and NPC.
     *
     * @param player The {@link Player} who interacted with the NPC. Must not be {@code null}.
     * @param npc    The {@link NPC} that was interacted with. Must not be {@code null}.
     * @param type   The type of interaction (left or right click). Must not be {@code null}.
     */
    public static void callNpc(@NotNull Player player, @NotNull NPC npc, @NotNull ClickActionType type)
    {
        SchedulerProvider.get().runSyncForEntity(player, () -> Bukkit.getPluginManager().callEvent(new NpcInteractEvent(player, npc, type)));
    }

    /**
     * Uninjects the custom {@link ChannelDuplexHandler} from the specified player's Netty pipeline. This stops the interception of packets for that player.
     *
     * @param player The {@link Player} whose pipeline is to be uninject. Must not be {@code null}.
     */
    public static void uninject(@NotNull Player player)
    {
        Channel channel = channels.get(player.getUniqueId());

        if(channel == null || NpcApi.plugin == null)
            return;

        if(channel.pipeline().get(NpcApi.plugin.getName()) != null)
            channel.pipeline().remove(NpcApi.plugin.getName());
    }

    /**
     * Uninjects the custom packet handler from all currently online players. This is typically called during plugin shutdown or reload.
     */
    public static void uninjectAll()
    {
        Var.safeForEachOnlinePlayer(PacketReader::uninject);
    }

    /**
     * Injects the custom packet handler into all currently online players. This is typically called during plugin startup or after a reload.
     */
    public static void injectAll()
    {
        Var.safeForEachOnlinePlayer(PacketReader::inject);
    }
}
