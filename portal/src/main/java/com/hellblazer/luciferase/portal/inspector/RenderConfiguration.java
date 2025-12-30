/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.inspector;

/**
 * Configuration record for spatial structure rendering.
 *
 * Encapsulates all rendering parameters that are common across
 * different spatial structure types.
 *
 * @param maxDepth Maximum depth to render
 * @param minLevel Minimum level to show (for level filtering)
 * @param maxLevel Maximum level to show (for level filtering)
 * @param opacity Opacity of rendered elements (0.0 to 1.0)
 * @param showWireframe Whether to show wireframe overlay
 * @param colorScheme Name of the color scheme to use
 * @param renderMode Name of the render mode
 * @param isolateLevel If >= 0, show only this level
 * @param ghostMode Show non-isolated levels as transparent ghosts
 *
 * @author hal.hildebrand
 */
public record RenderConfiguration(
    int maxDepth,
    int minLevel,
    int maxLevel,
    double opacity,
    boolean showWireframe,
    String colorScheme,
    String renderMode,
    int isolateLevel,
    boolean ghostMode
) {

    /**
     * Default configuration for initial rendering.
     */
    public static final RenderConfiguration DEFAULT = new RenderConfiguration(
        6,      // maxDepth
        0,      // minLevel
        6,      // maxLevel
        0.8,    // opacity
        false,  // showWireframe
        "DEPTH_GRADIENT",
        "LEAVES_ONLY",
        -1,     // isolateLevel (-1 = disabled)
        false   // ghostMode
    );

    /**
     * Builder for creating RenderConfiguration instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a copy with modified maxDepth.
     */
    public RenderConfiguration withMaxDepth(int maxDepth) {
        return new RenderConfiguration(maxDepth, minLevel, Math.min(maxLevel, maxDepth),
            opacity, showWireframe, colorScheme, renderMode, isolateLevel, ghostMode);
    }

    /**
     * Create a copy with modified opacity.
     */
    public RenderConfiguration withOpacity(double opacity) {
        return new RenderConfiguration(maxDepth, minLevel, maxLevel,
            opacity, showWireframe, colorScheme, renderMode, isolateLevel, ghostMode);
    }

    /**
     * Create a copy with modified wireframe setting.
     */
    public RenderConfiguration withWireframe(boolean showWireframe) {
        return new RenderConfiguration(maxDepth, minLevel, maxLevel,
            opacity, showWireframe, colorScheme, renderMode, isolateLevel, ghostMode);
    }

    /**
     * Create a copy with modified color scheme.
     */
    public RenderConfiguration withColorScheme(String colorScheme) {
        return new RenderConfiguration(maxDepth, minLevel, maxLevel,
            opacity, showWireframe, colorScheme, renderMode, isolateLevel, ghostMode);
    }

    /**
     * Create a copy with modified render mode.
     */
    public RenderConfiguration withRenderMode(String renderMode) {
        return new RenderConfiguration(maxDepth, minLevel, maxLevel,
            opacity, showWireframe, colorScheme, renderMode, isolateLevel, ghostMode);
    }

    /**
     * Create a copy with level range.
     */
    public RenderConfiguration withLevelRange(int minLevel, int maxLevel) {
        return new RenderConfiguration(maxDepth, minLevel, maxLevel,
            opacity, showWireframe, colorScheme, renderMode, isolateLevel, ghostMode);
    }

    /**
     * Create a copy with level isolation.
     */
    public RenderConfiguration withIsolateLevel(int level, boolean ghost) {
        return new RenderConfiguration(maxDepth, minLevel, maxLevel,
            opacity, showWireframe, colorScheme, renderMode, level, ghost);
    }

    /**
     * Check if level isolation is enabled.
     */
    public boolean isLevelIsolated() {
        return isolateLevel >= 0;
    }

    /**
     * Check if a GPU render mode is selected.
     */
    public boolean isGPUMode() {
        return renderMode != null && renderMode.contains("GPU");
    }

    /**
     * Builder for RenderConfiguration.
     */
    public static class Builder {
        private int maxDepth = 6;
        private int minLevel = 0;
        private int maxLevel = 6;
        private double opacity = 0.8;
        private boolean showWireframe = false;
        private String colorScheme = "DEPTH_GRADIENT";
        private String renderMode = "LEAVES_ONLY";
        private int isolateLevel = -1;
        private boolean ghostMode = false;

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            this.maxLevel = Math.min(this.maxLevel, maxDepth);
            return this;
        }

        public Builder minLevel(int minLevel) {
            this.minLevel = minLevel;
            return this;
        }

        public Builder maxLevel(int maxLevel) {
            this.maxLevel = maxLevel;
            return this;
        }

        public Builder opacity(double opacity) {
            this.opacity = opacity;
            return this;
        }

        public Builder showWireframe(boolean showWireframe) {
            this.showWireframe = showWireframe;
            return this;
        }

        public Builder colorScheme(String colorScheme) {
            this.colorScheme = colorScheme;
            return this;
        }

        public Builder renderMode(String renderMode) {
            this.renderMode = renderMode;
            return this;
        }

        public Builder isolateLevel(int level) {
            this.isolateLevel = level;
            return this;
        }

        public Builder ghostMode(boolean ghostMode) {
            this.ghostMode = ghostMode;
            return this;
        }

        public RenderConfiguration build() {
            return new RenderConfiguration(maxDepth, minLevel, maxLevel, opacity,
                showWireframe, colorScheme, renderMode, isolateLevel, ghostMode);
        }
    }
}
