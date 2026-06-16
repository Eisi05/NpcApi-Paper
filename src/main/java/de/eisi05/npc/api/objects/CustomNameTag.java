package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.utils.Var;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Utility class to configure and apply custom nametag settings for Minecraft TextDisplay entities.
 */
public class CustomNameTag
{
    private final Display.TextDisplay display;
    private final Map<EntityDataAccessor<?>, Object> dataMap = new LinkedHashMap<>();

    /**
     * Creates a new CustomNameTag wrapper for the given TextDisplay entity.
     *
     * @param display The TextDisplay entity to customize.
     */
    public CustomNameTag(Object display)
    {
        this.display = (Display.TextDisplay) display;
    }

    /**
     * Returns the wrapped TextDisplay entity.
     *
     * @return The TextDisplay entity.
     */
    public Object getDisplay()
    {
        return display;
    }

    private <T> CustomNameTag set(EntityDataAccessor<T> accessor, T value)
    {
        dataMap.put(accessor, value);
        return this;
    }

    /**
     * Sets the translation offset of the nametag.
     * Default: (0.0, 0.25, 0.0)
     *
     * @param vector Translation vector.
     * @return This instance for chaining.
     */
    public CustomNameTag translation(Vector vector)
    {
        return set(EntityDataSerializers.VECTOR3.createAccessor(11), vector.toVector3f());
    }

    /**
     * Sets the scale of the nametag.
     * Default: (1.0, 1.0, 1.0)
     *
     * @param vector Scale vector.
     * @return This instance for chaining.
     */
    public CustomNameTag scale(Vector vector)
    {
        return set(EntityDataSerializers.VECTOR3.createAccessor(12), vector.toVector3f());
    }

    /**
     * Sets the billboard alignment constraints.
     * Default: CENTER
     *
     * @param constraints BillboardConstraints enum.
     * @return This instance for chaining.
     */
    public CustomNameTag billboardConstraints(BillboardConstraints constraints)
    {
        return set(EntityDataSerializers.BYTE.createAccessor(15), (byte) constraints.ordinal());
    }

    /**
     * Sets brightness override.
     * Default: -1
     *
     * @param brightness Brightness value.
     * @return This instance for chaining.
     */
    public CustomNameTag brightnessOverride(int brightness)
    {
        return set(EntityDataSerializers.INT.createAccessor(16), brightness);
    }

    /**
     * Sets the viewing range of the nametag.
     * Default: 1.0
     *
     * @param range View range.
     * @return This instance for chaining.
     */
    public CustomNameTag viewRange(float range)
    {
        return set(EntityDataSerializers.FLOAT.createAccessor(17), range);
    }

    /**
     * Sets the shadow radius.
     * Default: 0.0
     *
     * @param radius Shadow radius.
     * @return This instance for chaining.
     */
    public CustomNameTag shadowRadius(float radius)
    {
        return set(EntityDataSerializers.FLOAT.createAccessor(18), radius);
    }

    /**
     * Sets the shadow strength.
     * Default: 1.0
     *
     * @param strength Shadow strength.
     * @return This instance for chaining.
     */
    public CustomNameTag shadowStrength(float strength)
    {
        return set(EntityDataSerializers.FLOAT.createAccessor(19), strength);
    }

    /**
     * Sets the line width of the text.
     * Default: 200
     *
     * @param width Line width.
     * @return This instance for chaining.
     */
    public CustomNameTag lineWidth(int width)
    {
        return set(EntityDataSerializers.INT.createAccessor(24), width);
    }

    /**
     * Sets the background color.
     * Default: 1073741824 (0x40000000)
     *
     * @param color Background color as integer.
     * @return This instance for chaining.
     */
    public CustomNameTag backgroundColor(int color)
    {
        return set(EntityDataSerializers.INT.createAccessor(25), color);
    }

    /**
     * Sets the text opacity.
     * Default: -1 (fully opaque)
     *
     * @param opacity Text opacity.
     * @return This instance for chaining.
     */
    public CustomNameTag textOpacity(byte opacity)
    {
        return set(EntityDataSerializers.BYTE.createAccessor(26), opacity);
    }

    /**
     * Sets flags including shadow, see-through, background color, and alignment.
     * Default: NONE
     *
     * @param flags Varargs of TextDisplayFlags.
     * @return This instance for chaining.
     */
    public CustomNameTag flags(TextDisplayFlags... flags)
    {
        return set(EntityDataSerializers.BYTE.createAccessor(27), TextDisplayFlags.combineFlags(flags));
    }

    /**
     * Applies all configured data to the given TextDisplay and component.
     *
     * @param component The text component to display.
     * @param displayOptions The display options to apply.
     * @return The SynchedEntityData after applying values.
     */
    Object applyData(@Nullable net.kyori.adventure.text.Component component, @NotNull NameDisplayOptions displayOptions)
    {
        SynchedEntityData data = display.getEntityData();

        if(component == null)
            component = net.kyori.adventure.text.Component.empty();

        String legacy = LegacyComponentSerializer.legacySection().serialize(component).replace("\\n", "\n");
        Component nmsComponent = CraftChatMessage.fromStringOrNull(legacy, true);

        if(nmsComponent == null)
            nmsComponent = Component.empty();

        data.set(EntityDataSerializers.OPTIONAL_COMPONENT.createAccessor(2), Optional.of(nmsComponent));
        data.set(EntityDataSerializers.BOOLEAN.createAccessor(4), true);

        translation(new Vector(0, 0.25f, 0));
        billboardConstraints(BillboardConstraints.CENTER);

        float[] scale = displayOptions.getScale();
        scale(new Vector(scale[0], scale[1], scale[2]));

        int brightness = displayOptions.getBrightness();
        brightnessOverride(brightness);

        float viewRange = displayOptions.getViewRange();
        viewRange(viewRange);

        int lineWidth = displayOptions.getLineWidth();
        lineWidth(lineWidth);

        int backgroundColor = displayOptions.getBackgroundColor();
        backgroundColor(backgroundColor);

        byte textOpacity = displayOptions.getTextOpacity();
        textOpacity(textOpacity);


        boolean isSeeThrough = displayOptions.isSeeThrough();
        TextDisplay.TextAlignment alignment = displayOptions.getAlignment();
        TextDisplayFlags alignmentFlag = switch(alignment)
        {
            case LEFT -> TextDisplayFlags.LEFT_ALIGNMENT;
            case CENTER -> TextDisplayFlags.CENTER_ALIGNMENT;
            case RIGHT -> TextDisplayFlags.RIGHT_ALIGNMENT;
        };

        if(isSeeThrough)
            flags(alignmentFlag, TextDisplayFlags.IS_SEE_THROUGH);
        else
            flags(alignmentFlag);

        data.set(EntityDataSerializers.COMPONENT.createAccessor(23), nmsComponent);

        // Apply custom data
        dataMap.forEach((accessor, value) -> data.set(accessor, Var.unsafeCast(value)));

        return data;
    }


    /**
     * Alignment constraints for TextDisplay nametags.
     */
    public enum BillboardConstraints
    {
        FIXED,
        VERTICAL,
        HORIZONTAL,
        CENTER
    }

    /**
     * Flags for text display, including shadow, see-through, background, and alignment.
     */
    public enum TextDisplayFlags
    {
        NONE((byte) 0x00),
        HAS_SHADOW((byte) 0x01),
        IS_SEE_THROUGH((byte) 0x02),
        USE_DEFAULT_BACKGROUND_COLOR((byte) 0x04),
        CENTER_ALIGNMENT((byte) 0x00),
        LEFT_ALIGNMENT((byte) 0x08),
        RIGHT_ALIGNMENT((byte) 0x10);

        private static final byte ALIGNMENT_MASK = (byte) 0x18;
        private final byte flag;

        TextDisplayFlags(byte flag) {this.flag = flag;}

        public static byte combineFlags(TextDisplayFlags... flags)
        {
            byte result = 0;
            boolean alignmentSet = false;

            for(TextDisplayFlags flag : flags)
            {
                boolean isAlignment = (flag == CENTER_ALIGNMENT || flag == LEFT_ALIGNMENT || flag == RIGHT_ALIGNMENT);

                if(isAlignment)
                {
                    if(alignmentSet)
                        throw new IllegalArgumentException("You cannot clear or set multiple alignments at once!");
                    result = (byte) ((result & ~ALIGNMENT_MASK) | flag.flag);
                    alignmentSet = true;
                }
                else
                    result |= flag.flag;
            }
            return result;
        }
    }
}
