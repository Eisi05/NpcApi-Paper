package de.eisi05.npc.api.utils;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * The {@link Versions} enum represents the supported Minecraft server versions and provides utility methods for working with version-specific paths and
 * comparisons. It helps in adapting the plugin's functionality to different server environments.
 */
public enum Versions
{
    /**
     * Represents an unknown or unsupported version.
     */
    NONE(""),
    /**
     * Minecraft 1.20.6 version.
     */
    V1_20_6("v1_20_R4"),
    /**
     * Minecraft 1.21 version.
     */
    V1_21("v1_21_R1"),
    /**
     * Minecraft 1.21.2 version.
     */
    V1_21_2("v1_21_R2"),
    /**
     * Minecraft 1.21.4 version.
     */
    V1_21_4("v1_21_R3"),
    /**
     * Minecraft 1.21.5 version.
     */
    V1_21_5("v1_21_R4"),
    /**
     * Minecraft 1.21.5 version.
     */
    V1_21_6("v1_21_R5"),

    /**
     * Minecraft 1.21.5 version.
     */
    V1_21_7("v1_21_R5"),

    /**
     * Minecraft 1.21.9 version.
     */
    V1_21_9("v1_21_R6"),

    /**
     * Minecraft 1.21.11 version.
     */
    V1_21_11("v1_21_R7"),
    /**
     * Minecraft 26.1 version.
     */
    V26_1("v26_1"),
    /**
     * Minecraft 26.2 version.
     */
    V26_2("v26_2");

    /**
     * Caches the determined current server version to avoid repeated lookups.
     */
    private static Versions VERSION;

    /**
     * The NMS (Net Minecraft Server) path component corresponding to this version. For example, "v1_17_R1" for Minecraft 1.17.
     */
    private final String path;

    /**
     * Constructs a {@code Versions} enum entry with the specified NMS path.
     *
     * @param path The NMS path string for this version. Must not be {@code null}.
     */
    Versions(@NotNull String path)
    {
        this.path = path;
    }

    /**
     * Determines and returns the current Minecraft server version based on the Bukkit server's package name. The determined version is cached for later calls.
     *
     * @return The {@link Versions} enum entry corresponding to the current server version. Must not be {@code null}.
     */
    public static @NotNull Versions getVersion()
    {
        if(VERSION != null)
            return VERSION;

        return VERSION = switch(Bukkit.getMinecraftVersion())
        {
            case "1.20.6" -> Versions.V1_20_6;
            case "1.21", "1.21.1" -> Versions.V1_21;
            case "1.21.2", "1.21.3" -> Versions.V1_21_2;
            case "1.21.4" -> Versions.V1_21_4;
            case "1.21.5" -> Versions.V1_21_5;
            case "1.21.6" -> Versions.V1_21_6;
            case "1.21.7", "1.21.8" -> V1_21_7;
            case "1.21.9", "1.21.10" -> V1_21_9;
            case "1.21.11" -> V1_21_11;
            case String s when s.startsWith("26.1") -> V26_1;
            case String s when s.startsWith("26.2") -> V26_2;
            default -> Versions.NONE;
        };
    }

    /**
     * Returns an array of {@link Versions} enum entries that fall inclusively between two specified versions (based on their ordinal values). The {@code NONE}
     * version is excluded from the result.
     *
     * @param versions1 The starting version (inclusive). Must not be {@code null}.
     * @param versions2 The ending version (inclusive). Must not be {@code null}.
     * @return An array of {@link Versions} enum entries within the specified range. Must not be {@code null}.
     */
    private static @NotNull Versions[] getVersionBetween(@NotNull Versions versions1, @NotNull Versions versions2)
    {
        return Arrays.stream(values())
                .filter(v -> v != Versions.NONE)
                .filter(v -> v.ordinal() >= versions1.ordinal() && v.ordinal() <= versions2.ordinal())
                .toArray(Versions[]::new);
    }

    /**
     * Checks if the current server version is numerically smaller than a specified version. This comparison is based on the ordinal value of the enum entries.
     *
     * @param versions The version to compare against. Must not be {@code null}.
     * @return {@code true} if the current version is smaller, {@code false} otherwise.
     */
    public static boolean isCurrentVersionSmallerThan(@NotNull Versions versions)
    {
        return getVersion().ordinal() < versions.ordinal();
    }

    /**
     * Returns the NMS (Net Minecraft Server) path component associated with this version.
     *
     * @return The NMS path as a {@link String}. Must not be {@code null}.
     */
    public @NotNull String getPath()
    {
        return path;
    }

    /**
     * Returns the name of the version, with underscores replaced by dots for better readability. For example, {@code V1_17} becomes "V1.17".
     *
     * @return The formatted name of the version as a {@link String}. Must not be {@code null}.
     */
    public @NotNull String getName()
    {
        return name().replace("_", ".");
    }
}
