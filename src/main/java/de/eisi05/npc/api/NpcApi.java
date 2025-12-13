package de.eisi05.npc.api;

import de.eisi05.npc.api.listeners.ChangeWorldListener;
import de.eisi05.npc.api.listeners.ConnectionListener;
import de.eisi05.npc.api.listeners.NpcInteractListener;
import de.eisi05.npc.api.listeners.WorldLoadListener;
import de.eisi05.npc.api.manager.NpcManager;
import de.eisi05.npc.api.manager.TeamManager;
import de.eisi05.npc.api.objects.NPC;
import de.eisi05.npc.api.objects.NpcConfig;
import de.eisi05.npc.api.pathfinding.Path;
import de.eisi05.npc.api.scheduler.Tasks;
import de.eisi05.npc.api.utils.Metrics;
import de.eisi05.npc.api.utils.PacketReader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Function;

/**
 * The main entry point and singleton class for the NPC API.
 * This class handles the initialization, configuration, and shutdown
 * of the NPC functionality within a Bukkit plugin.
 */
public final class NpcApi
{
    private static final List<Listener> listeners = List.of(new ChangeWorldListener(), new ConnectionListener(), new NpcInteractListener(),
            new WorldLoadListener());
    /**
     * A static reference to the Bukkit plugin instance that is using this API.
     * This is set during the API's initialization.
     */
    public static Plugin plugin;
    public static Function<Player, Component> DISABLED_MESSAGE_PROVIDER = player ->
            Component.text("DISABLED").color(NamedTextColor.RED);

    /**
     * The configuration object for the NPC API, containing various settings
     * like the look-at timer.
     */
    public static NpcConfig config;

    private static NpcApi npcApi;

    /**
     * Private constructor to enforce the singleton pattern.
     * Initializes the API by registering listeners, loading existing NPCs,
     * injecting packet readers, and starting recurring tasks.
     *
     * @param plugin The {@link JavaPlugin} instance using this API. Must not be {@code null}.
     * @param config The {@link NpcConfig} object for the API. Must not be {@code null}.
     */
    private NpcApi(@NotNull JavaPlugin plugin, @NotNull NpcConfig config)
    {
        NpcApi.plugin = plugin;
        NpcApi.config = config;

        listeners.forEach(listener -> Bukkit.getPluginManager().registerEvents(listener, plugin));

        ConfigurationSerialization.registerClass(Path.class);

        NpcManager.loadNPCs();
        PacketReader.injectAll();

        Tasks.start();

        new Metrics(plugin, 28179).addCustomChart(new Metrics.SingleLineChart("npcCount", () -> NpcManager.getList().size()));
    }

    /**
     * Creates or retrieves the singleton instance of the {@code NpcApi} with a default configuration.
     * If the API instance does not exist or the provided plugin is null, a new instance is created.
     *
     * @param plugin The {@link JavaPlugin} instance using this API. Must not be {@code null}.
     * @return The singleton {@link NpcApi} instance. Must not be {@code null}.
     */
    public static @NotNull NpcApi createInstance(@NotNull JavaPlugin plugin)
    {
        return createInstance(plugin, new NpcConfig());
    }

    /**
     * Creates or retrieves the singleton instance of the {@code NpcApi} with a custom configuration.
     * If the API instance does not exist or the provided plugin is null, a new instance is created.
     *
     * @param plugin The {@link JavaPlugin} instance using this API. Must not be {@code null}.
     * @param config The {@link NpcConfig} object to use for the API. Must not be {@code null}.
     * @return The singleton {@link NpcApi} instance. Must not be {@code null}.
     */
    public static @NotNull NpcApi createInstance(@NotNull JavaPlugin plugin, @NotNull NpcConfig config)
    {
        if(npcApi == null || plugin == null)
            npcApi = new NpcApi(plugin, config);

        return npcApi;
    }

    /**
     * Disables the NPC API, performing the necessary cleanup.
     * This includes hiding all active NPCs from players, clearing the NPC manager,
     * and uninjecting packet readers. It also nullifies the static references.
     */
    public static void disable()
    {
        NpcManager.getList().forEach(NPC::hideNpcFromAllPlayers);
        NpcManager.clear();
        PacketReader.uninjectAll();
        Tasks.stop();
        TeamManager.clear();
        ConfigurationSerialization.unregisterClass(Path.class);

        listeners.forEach(HandlerList::unregisterAll);

        NpcManager.loadExceptions.clear();
        npcApi = null;
        plugin = null;
    }

    /**
     * Sets a function that provides the message shown when an NPC is disabled.
     *
     * @param function a {@link Function} that takes a {@link Player} and returns the disabled message
     * @return this {@link NpcApi} instance for method chaining
     */
    public @NotNull NpcApi setDisabledMessageProvider(Function<Player, Component> function)
    {
        DISABLED_MESSAGE_PROVIDER = function;
        return this;
    }
}
