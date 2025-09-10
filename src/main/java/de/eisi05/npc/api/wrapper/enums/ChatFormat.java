package de.eisi05.npc.api.wrapper.enums;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextFormat;
import net.kyori.adventure.text.serializer.legacy.Reset;

import java.io.Serializable;

public enum ChatFormat implements Serializable
{
    BLACK('0', NamedTextColor.BLACK),
    DARK_BLUE('1', NamedTextColor.DARK_BLUE),
    DARK_GREEN('2', NamedTextColor.DARK_GREEN),
    DARK_AQUA('3', NamedTextColor.DARK_AQUA),
    DARK_RED('4', NamedTextColor.DARK_RED),
    DARK_PURPLE('5', NamedTextColor.DARK_PURPLE),
    GOLD('6', NamedTextColor.GOLD),
    GRAY('7', NamedTextColor.GRAY),
    DARK_GRAY('8', NamedTextColor.DARK_GRAY),
    BLUE('9', NamedTextColor.BLUE),
    GREEN('a', NamedTextColor.GREEN),
    AQUA('b', NamedTextColor.AQUA),
    RED('c', NamedTextColor.RED),
    LIGHT_PURPLE('d', NamedTextColor.LIGHT_PURPLE),
    YELLOW('e', NamedTextColor.YELLOW),
    WHITE('f', NamedTextColor.WHITE),
    OBFUSCATED('k', TextDecoration.OBFUSCATED),
    BOLD('l', TextDecoration.BOLD),
    STRIKETHROUGH('m', TextDecoration.STRIKETHROUGH),
    UNDERLINE('n', TextDecoration.UNDERLINED),
    ITALIC('o', TextDecoration.ITALIC),
    RESET('p', Reset.INSTANCE);

    private final char color;
    private final TextFormat textFormat;

    ChatFormat(char color, TextFormat textFormat)
    {
        this.color = color;
        this.textFormat = textFormat;
    }

    public char getColorCode()
    {
        return color;
    }

    public TextFormat getTextFormat()
    {
        return textFormat;
    }

    public boolean isColor()
    {
        return textFormat instanceof NamedTextColor;
    }
}
