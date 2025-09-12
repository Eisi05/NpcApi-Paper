package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.utils.Var;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

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
     * Sets the width of the nametag.
     * Default: 0.0
     *
     * @param width Width value.
     * @return This instance for chaining.
     */
    public CustomNameTag width(float width)
    {
        return set(EntityDataSerializers.FLOAT.createAccessor(20), width);
    }

    /**
     * Sets the height of the nametag.
     * Default: 1.0
     *
     * @param height Height value.
     * @return This instance for chaining.
     */
    public CustomNameTag height(float height)
    {
        return set(EntityDataSerializers.FLOAT.createAccessor(21), height);
    }

    /**
     * Sets a glow color override.
     * Default: -1
     *
     * @param color Glow color as integer.
     * @return This instance for chaining.
     */
    public CustomNameTag glowColorOverride(int color)
    {
        return set(EntityDataSerializers.INT.createAccessor(22), color);
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
     * @return The SynchedEntityData after applying values.
     */
    Object applyData(@Nullable net.kyori.adventure.text.Component component)
    {
        SynchedEntityData data = display.getEntityData();

        if(component == null)
            component = net.kyori.adventure.text.Component.empty();

        String legacy = LegacyComponentSerializer.legacySection().serialize(component).replace("\\n", "\n");
        Component nmsComponent = CraftChatMessage.fromStringOrNull(legacy, true);

        if(nmsComponent == null)
            nmsComponent = Component.empty();

        // Default values
        data.set(EntityDataSerializers.OPTIONAL_COMPONENT.createAccessor(2), Optional.of(nmsComponent));
        data.set(EntityDataSerializers.BOOLEAN.createAccessor(4), true);
        data.set(EntityDataSerializers.VECTOR3.createAccessor(11), new Vector3f(0, 0.25f, 0));
        data.set(EntityDataSerializers.BYTE.createAccessor(15), (byte) 3);
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
        LEFT_ALIGNMENT((byte) 0x01),
        RIGHT_ALIGNMENT((byte) 0x02);

        private final byte flag;

        TextDisplayFlags(byte flag) {this.flag = flag;}

        public static byte combineFlags(TextDisplayFlags... flags)
        {
            byte result = 0;
            for(TextDisplayFlags flag : flags)
                result |= flag.flag;
            return result;
        }
    }
}
