package de.eisi05.npc.api.enums;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/**
 * Represents the different customizable skin parts of an NPC,
 * each associated with a unique byte value and an icon material.
 */
public enum SkinParts implements Serializable
{
    CAPE((byte) 0x01, Material.ELYTRA),
    JACKET((byte) 0x02, Material.LEATHER_CHESTPLATE),
    LEFT_SLEEVE((byte) 0x04, Material.SHIELD),
    RIGHT_SLEEVE((byte) 0x08, Material.DIAMOND_SWORD),
    LEFT_PANTS_LEG((byte) 0x10, Material.LEATHER_LEGGINGS),
    RIGHT_PANTS_LEG((byte) 0x20, Material.LEATHER_LEGGINGS),
    HAT((byte) 0x40, Material.TURTLE_HELMET);

    private final byte value;
    private final Material icon;

    SkinParts(byte value, @NotNull Material icon)
    {
        this.value = value;
        this.icon = icon;
    }

    /**
     * Returns the byte value representing this skin part.
     *
     * @return the bitmask value
     */
    public byte getValue()
    {
        return value;
    }

    /**
     * Returns the icon material associated with this skin part.
     *
     * @return the icon Material, never null
     */
    public @NotNull Material getIcon()
    {
        return icon;
    }
}
