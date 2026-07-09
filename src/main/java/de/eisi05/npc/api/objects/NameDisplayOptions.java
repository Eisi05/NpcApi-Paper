package de.eisi05.npc.api.objects;

import de.eisi05.npc.api.NpcApi;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

/**
 * Represents the layout and visual rendering options for an NPC's name display. Maps directly to Minecraft's Text Display entity metadata properties.
 */
public class NameDisplayOptions implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    private float height = 0;
    private float[] scale = NameDisplayDefaults.DEFAULT_SCALE.clone();
    private int brightness = NameDisplayDefaults.DEFAULT_BRIGHTNESS;
    private float viewRange = NameDisplayDefaults.DEFAULT_VIEW_RANGE;
    private int lineWidth = NameDisplayDefaults.DEFAULT_LINE_WIDTH;
    private int backgroundColor = NameDisplayDefaults.DEFAULT_BACKGROUND_COLOR;
    private byte textOpacity = NameDisplayDefaults.DEFAULT_TEXT_OPACITY;
    private boolean isSeeThrough = NameDisplayDefaults.DEFAULT_SEE_THROUGH;
    private int alignment = NameDisplayDefaults.DEFAULT_ALIGNMENT;

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
     *
     * @param alignment alignment
     * @return this options instance for method chaining
     */
    public @NotNull NameDisplayOptions setAlignment(@NotNull TextDisplay.TextAlignment alignment)
    {
        this.alignment = alignment.ordinal();
        return this;
    }

    public float getHeight()
    {
        return this.height;
    }

    @NotNull NameDisplayOptions setHeight(float height)
    {
        this.height = height;
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

    public static class NameDisplayDefaults extends NameDisplayOptions
    {
        private static final File file;
        private static YamlConfiguration config;
        private static NameDisplayDefaults INSTANCE;

        private static float[] DEFAULT_SCALE;
        private static int DEFAULT_BRIGHTNESS;
        private static float DEFAULT_VIEW_RANGE;
        private static int DEFAULT_LINE_WIDTH;
        private static int DEFAULT_BACKGROUND_COLOR;
        private static byte DEFAULT_TEXT_OPACITY;
        private static boolean DEFAULT_SEE_THROUGH;
        private static int DEFAULT_ALIGNMENT;

        static
        {
            file = new File(NpcApi.plugin.getDataFolder(), "config.yml");
            reload();
        }

        private NameDisplayDefaults() {}

        public static NameDisplayOptions getInstance()
        {
            if(INSTANCE == null)
                INSTANCE = new NameDisplayDefaults();

            return INSTANCE;
        }

        private static void save()
        {
            try
            {
                config.save(file);
            }
            catch(Exception e)
            {
            }
        }

        public static void reload()
        {
            config = YamlConfiguration.loadConfiguration(file);

            @SuppressWarnings("unchecked")
            List<Double> list = (List<Double>) config.get("name-display.scale", List.of(1.0f, 1.0f, 1.0f));
            DEFAULT_SCALE = new float[]{list.get(0).floatValue(), list.get(1).floatValue(), list.get(2).floatValue()};
            DEFAULT_BRIGHTNESS = config.getInt("name-display.brightness", -1);
            DEFAULT_VIEW_RANGE = (float) config.getDouble("name-display.view-range", 1.0f);
            DEFAULT_LINE_WIDTH = config.getInt("name-display.line-width", 200);
            DEFAULT_BACKGROUND_COLOR = config.getInt("name-display.background-color", 0x40000000);
            DEFAULT_TEXT_OPACITY = (byte) config.getInt("name-display.text-opacity", 255);
            DEFAULT_SEE_THROUGH = config.getBoolean("name-display.see-through", false);
            DEFAULT_ALIGNMENT = config.getInt("name-display.text-alignment", TextDisplay.TextAlignment.CENTER.ordinal());
        }

        @Override
        public float[] getScale()
        {
            return DEFAULT_SCALE;
        }

        @Override
        public @NotNull NameDisplayDefaults setScale(float[] defaultScale)
        {
            DEFAULT_SCALE = defaultScale;
            config.set("name-display.scale", List.of(DEFAULT_SCALE[0], DEFAULT_SCALE[1], DEFAULT_SCALE[2]));
            save();
            return this;
        }

        @Override
        public int getBrightness()
        {
            return DEFAULT_BRIGHTNESS;
        }

        @Override
        public @NotNull NameDisplayDefaults setBrightness(int defaultBrightness)
        {
            DEFAULT_BRIGHTNESS = defaultBrightness;
            config.set("name-display.brightness", DEFAULT_BRIGHTNESS);
            save();
            return this;
        }

        @Override
        public float getViewRange()
        {
            return DEFAULT_VIEW_RANGE;
        }

        @Override
        public @NotNull NameDisplayDefaults setViewRange(float defaultViewRange)
        {
            DEFAULT_VIEW_RANGE = defaultViewRange;
            config.set("name-display.view-range", DEFAULT_VIEW_RANGE);
            save();
            return this;
        }

        @Override
        public int getLineWidth()
        {
            return DEFAULT_LINE_WIDTH;
        }

        @Override
        public @NotNull NameDisplayDefaults setLineWidth(int defaultLineWidth)
        {
            DEFAULT_LINE_WIDTH = defaultLineWidth;
            config.set("name-display.line-width", DEFAULT_LINE_WIDTH);
            save();
            return this;
        }

        @Override
        public int getBackgroundColor()
        {
            return DEFAULT_BACKGROUND_COLOR;
        }

        @Override
        public @NotNull NameDisplayDefaults setBackgroundColor(int defaultBackgroundColor)
        {
            DEFAULT_BACKGROUND_COLOR = defaultBackgroundColor;
            config.set("name-display.background-color", DEFAULT_BACKGROUND_COLOR);
            save();
            return this;
        }

        @Override
        public byte getTextOpacity()
        {
            return DEFAULT_TEXT_OPACITY;
        }

        @Override
        public @NotNull NameDisplayDefaults setTextOpacity(byte defaultTextOpacity)
        {
            DEFAULT_TEXT_OPACITY = defaultTextOpacity;
            config.set("name-display.text-opacity", DEFAULT_TEXT_OPACITY);
            save();
            return this;
        }

        @Override
        public boolean isSeeThrough()
        {
            return DEFAULT_SEE_THROUGH;
        }

        @Override
        public @NotNull NameDisplayDefaults setSeeThrough(boolean defaultSeeThrough)
        {
            DEFAULT_SEE_THROUGH = defaultSeeThrough;
            config.set("name-display.see-through", DEFAULT_SEE_THROUGH);
            save();
            return this;
        }

        @Override
        public @NotNull TextDisplay.TextAlignment getAlignment()
        {
            return TextDisplay.TextAlignment.values()[DEFAULT_ALIGNMENT];
        }

        @Override
        public @NotNull NameDisplayDefaults setAlignment(TextDisplay.TextAlignment defaultAlignment)
        {
            DEFAULT_ALIGNMENT = defaultAlignment.ordinal();
            config.set("name-display.text-alignment", DEFAULT_ALIGNMENT);
            save();
            return this;
        }
    }
}