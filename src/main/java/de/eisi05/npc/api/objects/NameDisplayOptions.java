package de.eisi05.npc.api.objects;

import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Represents the layout and visual rendering options for an NPC's name display. Maps directly to Minecraft's Text Display entity metadata properties.
 */
public class NameDisplayOptions implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    public static final float[] DEFAULT_SCALE = new float[]{1.0f, 1.0f, 1.0f};
    public static final int DEFAULT_BRIGHTNESS = -1;
    public static final float DEFAULT_VIEW_RANGE = 1.0f;
    public static final int DEFAULT_LINE_WIDTH = 200;
    public static final int DEFAULT_BACKGROUND_COLOR = 0x40000000;
    public static final byte DEFAULT_TEXT_OPACITY = (byte) 127;
    public static final boolean DEFAULT_SEE_THROUGH = false;
    public static final int DEFAULT_ALIGNMENT = TextDisplay.TextAlignment.CENTER.ordinal();

    private float[] scale = DEFAULT_SCALE.clone();
    private int brightness = DEFAULT_BRIGHTNESS;
    private float viewRange = DEFAULT_VIEW_RANGE;
    private int lineWidth = DEFAULT_LINE_WIDTH;
    private int backgroundColor = DEFAULT_BACKGROUND_COLOR;
    private byte textOpacity = DEFAULT_TEXT_OPACITY;
    private boolean isSeeThrough = DEFAULT_SEE_THROUGH;
    private int alignment = DEFAULT_ALIGNMENT;

    /**
     * Creates a new configuration instance initialized with standard Minecraft text display default settings.
     */
    public NameDisplayOptions()
    {
    }

    // ==========================================
    // Getters & Fluent Setters
    // ==========================================

    /**
     * Gets the 3D scale vector of the name display text.
     *
     * @return a float array of size 3 representing [x, y, z] scale dimensions
     */
    public float[] getScale()
    {
        return this.scale;
    }

    /**
     * Sets the 3D scale dimensions of the name text using a pre-configured array. Passing {@code null} resets the scale to the default
     * {@code [1.0f, 1.0f, 1.0f]}.
     *
     * @param scale a float array representing [x, y, z], or null to reset
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setScale(float[] scale)
    {
        this.scale = scale != null ? scale.clone() : new float[]{1.0f, 1.0f, 1.0f};
        return this;
    }

    /**
     * Sets the 3D scale dimensions of the name text using individual coordinate factors.
     *
     * @param x the scale factor along the X-axis
     * @param y the scale factor along the Y-axis
     * @param z the scale factor along the Z-axis
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setScale(float x, float y, float z)
    {
        this.scale = new float[]{x, y, z};
        return this;
    }

    /**
     * Gets the packed block and skylight override value for the text display.
     *
     * @return the packed brightness value, or -1 if using the default environmental lighting
     */
    public int getBrightness()
    {
        return this.brightness;
    }

    /**
     * Sets the packed block and skylight override value for the text display. Setting this to -1 reverts the rendering back to natural entity lighting.
     *
     * @param brightness the packed brightness integer values
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setBrightness(int brightness)
    {
        this.brightness = brightness;
        return this;
    }

    /**
     * Gets the maximum block distance at which this name display is visible to players.
     *
     * @return the maximum rendering range in blocks
     */
    public float getViewRange()
    {
        return this.viewRange;
    }

    /**
     * Sets the maximum block distance at which this name display remains visible.
     *
     * @param viewRange the maximum rendering range in blocks
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setViewRange(float viewRange)
    {
        this.viewRange = viewRange;
        return this;
    }

    /**
     * Gets the line split threshold length before the text automatically wraps to a new line.
     *
     * @return the line width threshold value
     */
    public int getLineWidth()
    {
        return this.lineWidth;
    }

    /**
     * Sets the line split threshold length before the text automatically wraps to a new line.
     *
     * @param lineWidth the line wrap boundary width (Minecraft default is typically 200)
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setLineWidth(int lineWidth)
    {
        this.lineWidth = lineWidth;
        return this;
    }

    /**
     * Gets the ARGB background color rendered directly behind the name text.
     *
     * @return the background color packed integer
     */
    public int getBackgroundColor()
    {
        return this.backgroundColor;
    }

    /**
     * Sets the ARGB background color rendered directly behind the name text. Use an alpha channel of {@code 0x00} to render an entirely transparent
     * background.
     *
     * @param backgroundColor the packed ARGB background integer code
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setBackgroundColor(int backgroundColor)
    {
        this.backgroundColor = backgroundColor;
        return this;
    }

    /**
     * Gets the specific opacity level applied to the rendered text characters.
     *
     * @return the opacity value index, where -1 signals default full alpha opacity
     */
    public byte getTextOpacity()
    {
        return this.textOpacity;
    }

    /**
     * Sets the specific opacity level applied to the rendered text characters.
     *
     * @param textOpacity alpha channel depth values, or -1 for default full opacity
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setTextOpacity(byte textOpacity)
    {
        this.textOpacity = textOpacity;
        return this;
    }

    /**
     * Checks whether the text characters remain visible through solid geometry blocks.
     *
     * @return true if the name text is see-through, false otherwise
     */
    public boolean isSeeThrough()
    {
        return this.isSeeThrough;
    }

    /**
     * Configures whether the text characters remain visible through solid geometry blocks. Enabling this acts like an X-ray effect for the text.
     *
     * @param seeThrough true to force see-through rendering, false for occluded behavior
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setSeeThrough(boolean seeThrough)
    {
        this.isSeeThrough = seeThrough;
        return this;
    }


    public @NotNull TextDisplay.TextAlignment getAlignment()
    {
        return TextDisplay.TextAlignment.values()[this.alignment];
    }

    /**
     * Sets the horizontal alignment formatting applied to multi-line text strings.
     * @param alignment
     * alignment
     *
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setAlignment(@NotNull TextDisplay.TextAlignment alignment)
    {
        this.alignment = alignment.ordinal();
        return this;
    }

    // ==========================================
    // Utility Methods
    // ==========================================

    /**
     * Creates a deep copy of this NameDisplayOptions instance. Essential for cloning NPCs without transferring internal field references.
     *
     * @return a completely independent, deep-copied duplicate of this configuration
     */
    public @NotNull NameDisplayOptions copy()
    {
        NameDisplayOptions cloned = new NameDisplayOptions();
        cloned.scale = this.scale.clone();
        cloned.brightness = this.brightness;
        cloned.viewRange = this.viewRange;
        cloned.lineWidth = this.lineWidth;
        cloned.backgroundColor = this.backgroundColor;
        cloned.textOpacity = this.textOpacity;
        cloned.isSeeThrough = this.isSeeThrough;
        cloned.alignment = this.alignment;
        return cloned;
    }

    /**
     * Returns a string representation of this configuration containing the values of all internal display metrics.
     *
     * @return a descriptive text block summary of this options instance
     */
    @Override
    public String toString()
    {
        return "NameDisplayOptions{" +
                "scale=" + Arrays.toString(scale) +
                ", brightness=" + brightness +
                ", viewRange=" + viewRange +
                ", lineWidth=" + lineWidth +
                ", backgroundColor=" + backgroundColor +
                ", textOpacity=" + textOpacity +
                ", isSeeThrough=" + isSeeThrough +
                ", alignment=" + alignment +
                '}';
    }
}