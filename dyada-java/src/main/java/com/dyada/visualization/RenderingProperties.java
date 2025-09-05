package com.dyada.visualization;

import java.awt.Color;

/**
 * Immutable properties for rendering objects in a visualization system.
 * Supports various rendering modes and visual attributes.
 */
public record RenderingProperties(
    Color color,
    double opacity,
    boolean wireframe,
    boolean filled,
    double lineWidth,
    RenderingMode mode,
    boolean castShadows,
    boolean receiveShadows,
    double shininess,
    Material material
) {
    
    /**
     * Rendering modes for different visualization purposes.
     */
    public enum RenderingMode {
        /** Solid color rendering */
        SOLID,
        /** Wireframe rendering */
        WIREFRAME,
        /** Point cloud rendering */
        POINTS,
        /** Textured rendering */
        TEXTURED,
        /** Shaded rendering with lighting */
        SHADED,
        /** Transparent rendering */
        TRANSPARENT,
        /** Debug rendering with normals/bounds */
        DEBUG
    }
    
    /**
     * Material properties for realistic rendering.
     */
    public record Material(
        Color ambient,
        Color diffuse,
        Color specular,
        double shininess,
        double metallic,
        double roughness,
        String textureId
    ) {
        /**
         * Creates a basic material with default properties.
         * 
         * @param baseColor the base color
         * @return default material
         */
        public static Material basic(Color baseColor) {
            return new Material(
                baseColor.darker(),
                baseColor,
                Color.WHITE,
                32.0,
                0.0,
                0.5,
                null
            );
        }
        
        /**
         * Creates a metallic material.
         * 
         * @param baseColor the base color
         * @return metallic material
         */
        public static Material metallic(Color baseColor) {
            return new Material(
                baseColor.darker(),
                baseColor,
                Color.WHITE,
                128.0,
                1.0,
                0.1,
                null
            );
        }
        
        /**
         * Creates a matte material.
         * 
         * @param baseColor the base color
         * @return matte material
         */
        public static Material matte(Color baseColor) {
            return new Material(
                baseColor.darker(),
                baseColor,
                baseColor.brighter(),
                8.0,
                0.0,
                0.9,
                null
            );
        }
    }
    
    /**
     * Validation for rendering properties.
     */
    public RenderingProperties {
        if (opacity < 0.0 || opacity > 1.0) {
            throw new IllegalArgumentException("Opacity must be between 0.0 and 1.0");
        }
        if (lineWidth < 0.0) {
            throw new IllegalArgumentException("Line width cannot be negative");
        }
        if (shininess < 0.0) {
            throw new IllegalArgumentException("Shininess cannot be negative");
        }
        if (color == null) {
            color = Color.WHITE;
        }
        if (mode == null) {
            mode = RenderingMode.SOLID;
        }
        if (material == null) {
            material = Material.basic(color);
        }
    }
    
    /**
     * Creates default rendering properties.
     * 
     * @return default properties
     */
    public static RenderingProperties defaults() {
        return new RenderingProperties(
            Color.WHITE,
            1.0,
            false,
            true,
            1.0,
            RenderingMode.SOLID,
            true,
            true,
            32.0,
            Material.basic(Color.WHITE)
        );
    }
    
    /**
     * Creates wireframe rendering properties.
     * 
     * @param color the wireframe color
     * @return wireframe properties
     */
    public static RenderingProperties wireframe(Color color) {
        return new RenderingProperties(
            color,
            1.0,
            true,
            false,
            1.0,
            RenderingMode.WIREFRAME,
            false,
            false,
            0.0,
            Material.basic(color)
        );
    }
    
    /**
     * Creates transparent rendering properties.
     * 
     * @param color the base color
     * @param opacity the opacity level
     * @return transparent properties
     */
    public static RenderingProperties transparent(Color color, double opacity) {
        return new RenderingProperties(
            color,
            opacity,
            false,
            true,
            1.0,
            RenderingMode.TRANSPARENT,
            false,
            true,
            16.0,
            Material.basic(color)
        );
    }
    
    /**
     * Creates point cloud rendering properties.
     * 
     * @param color the point color
     * @param pointSize the point size
     * @return point properties
     */
    public static RenderingProperties points(Color color, double pointSize) {
        return new RenderingProperties(
            color,
            1.0,
            false,
            false,
            pointSize,
            RenderingMode.POINTS,
            false,
            false,
            0.0,
            Material.basic(color)
        );
    }
    
    /**
     * Creates debug rendering properties.
     * 
     * @return debug properties
     */
    public static RenderingProperties debug() {
        return new RenderingProperties(
            Color.MAGENTA,
            0.7,
            true,
            false,
            2.0,
            RenderingMode.DEBUG,
            false,
            false,
            0.0,
            Material.basic(Color.MAGENTA)
        );
    }
    
    /**
     * Creates shaded rendering properties with lighting.
     * 
     * @param color the base color
     * @param material the material properties
     * @return shaded properties
     */
    public static RenderingProperties shaded(Color color, Material material) {
        return new RenderingProperties(
            color,
            1.0,
            false,
            true,
            1.0,
            RenderingMode.SHADED,
            true,
            true,
            material.shininess(),
            material
        );
    }
    
    /**
     * Builder class for gradual construction of rendering properties.
     */
    public static class Builder {
        private Color color = Color.WHITE;
        private double opacity = 1.0;
        private boolean wireframe = false;
        private boolean filled = true;
        private double lineWidth = 1.0;
        private RenderingMode mode = RenderingMode.SOLID;
        private boolean castShadows = true;
        private boolean receiveShadows = true;
        private double shininess = 32.0;
        private Material material;
        
        public Builder color(Color color) {
            this.color = color;
            return this;
        }
        
        public Builder opacity(double opacity) {
            this.opacity = opacity;
            return this;
        }
        
        public Builder wireframe(boolean wireframe) {
            this.wireframe = wireframe;
            return this;
        }
        
        public Builder filled(boolean filled) {
            this.filled = filled;
            return this;
        }
        
        public Builder lineWidth(double lineWidth) {
            this.lineWidth = lineWidth;
            return this;
        }
        
        public Builder mode(RenderingMode mode) {
            this.mode = mode;
            return this;
        }
        
        public Builder castShadows(boolean castShadows) {
            this.castShadows = castShadows;
            return this;
        }
        
        public Builder receiveShadows(boolean receiveShadows) {
            this.receiveShadows = receiveShadows;
            return this;
        }
        
        public Builder shininess(double shininess) {
            this.shininess = shininess;
            return this;
        }
        
        public Builder material(Material material) {
            this.material = material;
            return this;
        }
        
        public RenderingProperties build() {
            if (material == null) {
                material = Material.basic(color);
            }
            return new RenderingProperties(
                color, opacity, wireframe, filled, lineWidth,
                mode, castShadows, receiveShadows, shininess, material
            );
        }
    }
    
    /**
     * Creates a new builder for rendering properties.
     * 
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a copy of these properties with a different color.
     * 
     * @param newColor the new color
     * @return modified properties
     */
    public RenderingProperties withColor(Color newColor) {
        return new RenderingProperties(
            newColor, opacity, wireframe, filled, lineWidth,
            mode, castShadows, receiveShadows, shininess,
            Material.basic(newColor)
        );
    }
    
    /**
     * Creates a copy of these properties with a different opacity.
     * 
     * @param newOpacity the new opacity
     * @return modified properties
     */
    public RenderingProperties withOpacity(double newOpacity) {
        return new RenderingProperties(
            color, newOpacity, wireframe, filled, lineWidth,
            mode, castShadows, receiveShadows, shininess, material
        );
    }
    
    /**
     * Creates a copy of these properties with a different rendering mode.
     * 
     * @param newMode the new rendering mode
     * @return modified properties
     */
    public RenderingProperties withMode(RenderingMode newMode) {
        return new RenderingProperties(
            color, opacity, wireframe, filled, lineWidth,
            newMode, castShadows, receiveShadows, shininess, material
        );
    }
    
    /**
     * Creates a copy of these properties with wireframe enabled/disabled.
     * 
     * @param isWireframe whether to enable wireframe
     * @return modified properties
     */
    public RenderingProperties withWireframe(boolean isWireframe) {
        return new RenderingProperties(
            color, opacity, isWireframe, filled, lineWidth,
            isWireframe ? RenderingMode.WIREFRAME : mode,
            castShadows, receiveShadows, shininess, material
        );
    }
    
    /**
     * Checks if this rendering style requires transparency support.
     * 
     * @return true if transparency is needed
     */
    public boolean requiresTransparency() {
        return opacity < 1.0 || mode == RenderingMode.TRANSPARENT;
    }
    
    /**
     * Checks if this rendering style requires lighting calculations.
     * 
     * @return true if lighting is needed
     */
    public boolean requiresLighting() {
        return mode == RenderingMode.SHADED && (castShadows || receiveShadows || shininess > 0);
    }
    
    /**
     * Gets the effective color including opacity.
     * 
     * @return color with alpha channel
     */
    public Color getEffectiveColor() {
        if (opacity >= 1.0) {
            return color;
        }
        var alpha = (int) (opacity * 255);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}