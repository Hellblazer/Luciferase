package com.dyada.visualization.data;

import java.util.Map;

/**
 * Configuration options for visualization rendering including colors, styles, and display settings.
 */
public record RenderingOptions(
    ColorScheme colorScheme,
    boolean showWireframe,
    boolean showRefinementLevels,
    boolean showErrorDistribution,
    boolean enableInteractivity,
    double transparency,
    String renderingStyle,
    Map<String, Object> customOptions
) {
    
    public RenderingOptions {
        if (colorScheme == null) {
            colorScheme = ColorScheme.DEFAULT;
        }
        if (transparency < 0.0 || transparency > 1.0) {
            throw new IllegalArgumentException("Transparency must be between 0.0 and 1.0");
        }
        if (renderingStyle == null || renderingStyle.isBlank()) {
            renderingStyle = "solid";
        }
        if (customOptions == null) {
            customOptions = Map.of();
        }
    }
    
    /**
     * Color schemes for visualization.
     */
    public enum ColorScheme {
        DEFAULT("default", new String[]{"#3498db", "#e74c3c", "#2ecc71", "#f39c12"}),
        RAINBOW("rainbow", new String[]{"#ff0000", "#ff8000", "#ffff00", "#80ff00", "#00ff00", "#00ff80", "#00ffff", "#0080ff", "#0000ff"}),
        HEATMAP("heatmap", new String[]{"#000080", "#0000ff", "#0080ff", "#00ffff", "#80ff00", "#ffff00", "#ff8000", "#ff0000"}),
        GRAYSCALE("grayscale", new String[]{"#000000", "#404040", "#808080", "#c0c0c0", "#ffffff"}),
        VIRIDIS("viridis", new String[]{"#440154", "#31688e", "#35b779", "#fde725"});
        
        private final String name;
        private final String[] colors;
        
        ColorScheme(String name, String[] colors) {
            this.name = name;
            this.colors = colors;
        }
        
        public String getName() { return name; }
        public String[] getColors() { return colors.clone(); }
    }
    
    /**
     * Creates default rendering options.
     */
    public static RenderingOptions defaultOptions() {
        return new RenderingOptions(
            ColorScheme.DEFAULT,
            false,
            true,
            true,
            true,
            0.8,
            "solid",
            Map.of()
        );
    }
    
    /**
     * Creates wireframe rendering options.
     */
    public static RenderingOptions wireframe() {
        return new RenderingOptions(
            ColorScheme.DEFAULT,
            true,
            true,
            false,
            true,
            1.0,
            "wireframe",
            Map.of()
        );
    }
    
    /**
     * Creates options for error visualization.
     */
    public static RenderingOptions errorVisualization() {
        return new RenderingOptions(
            ColorScheme.HEATMAP,
            false,
            false,
            true,
            true,
            0.9,
            "solid",
            Map.of("showErrorBars", true, "normalizeErrors", true)
        );
    }
    
    /**
     * Creates options for refinement level visualization.
     */
    public static RenderingOptions refinementLevels() {
        return new RenderingOptions(
            ColorScheme.RAINBOW,
            false,
            true,
            false,
            true,
            0.8,
            "solid",
            Map.of("levelColors", true, "showLevelLabels", true)
        );
    }
    
    /**
     * Creates high-quality rendering options.
     */
    public static RenderingOptions highQuality() {
        return new RenderingOptions(
            ColorScheme.VIRIDIS,
            false,
            true,
            true,
            true,
            0.9,
            "smooth",
            Map.of(
                "antialiasing", true,
                "highResolution", true,
                "shadowMapping", true,
                "ambientOcclusion", true
            )
        );
    }
    
    /**
     * Creates options with custom color scheme.
     */
    public RenderingOptions withColorScheme(ColorScheme scheme) {
        return new RenderingOptions(
            scheme, showWireframe, showRefinementLevels, showErrorDistribution,
            enableInteractivity, transparency, renderingStyle, customOptions
        );
    }
    
    /**
     * Creates options with custom transparency.
     */
    public RenderingOptions withTransparency(double alpha) {
        return new RenderingOptions(
            colorScheme, showWireframe, showRefinementLevels, showErrorDistribution,
            enableInteractivity, alpha, renderingStyle, customOptions
        );
    }
    
    /**
     * Creates options with additional custom settings.
     */
    public RenderingOptions withCustomOption(String key, Object value) {
        var newOptions = new java.util.HashMap<>(customOptions);
        newOptions.put(key, value);
        return new RenderingOptions(
            colorScheme, showWireframe, showRefinementLevels, showErrorDistribution,
            enableInteractivity, transparency, renderingStyle, newOptions
        );
    }
    
    /**
     * Gets a custom option value with type casting.
     */
    @SuppressWarnings("unchecked")
    public <T> T getCustomOption(String key, Class<T> type, T defaultValue) {
        var value = customOptions.get(key);
        return type.isInstance(value) ? (T) value : defaultValue;
    }
}