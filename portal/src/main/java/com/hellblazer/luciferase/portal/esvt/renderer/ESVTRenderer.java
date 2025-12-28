/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.esvt.renderer;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-level renderer for ESVT (Efficient Sparse Voxel Tetrahedra) visualization.
 * Provides color-coded depth visualization, level range filtering, and multiple
 * rendering modes for the ESVTInspectorApp.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Color schemes: depth gradient, tet type, single color</li>
 *   <li>Level range filtering: show only specific tree levels</li>
 *   <li>Wireframe and solid rendering modes</li>
 *   <li>Leaf-only rendering for performance</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTRenderer {

    private static final Logger log = LoggerFactory.getLogger(ESVTRenderer.class);

    /**
     * Rendering mode for ESVT visualization.
     */
    public enum RenderMode {
        /** Render only leaf nodes (most common) */
        LEAVES_ONLY,
        /** Render all nodes in level range */
        ALL_LEVELS,
        /** Render wireframe outlines */
        WIREFRAME,
        /** Render both solid and wireframe */
        SOLID_WITH_WIREFRAME,
        /** GPU raycast rendering using compute shader */
        GPU_RAYCAST
    }

    private final int maxDepth;
    private final ESVTNodeMeshRenderer.ColorScheme colorScheme;
    private final RenderMode renderMode;
    private final double opacity;

    private ESVTData currentData;
    private ESVTNodeMeshRenderer meshRenderer;
    private Group currentRendering;

    /**
     * Create a new ESVT renderer with specified configuration.
     *
     * @param maxDepth Maximum tree depth (1-21)
     * @param colorScheme Color scheme for visualization
     * @param renderMode How to render the tree
     * @param opacity Opacity for solid rendering (0.0-1.0)
     */
    public ESVTRenderer(int maxDepth,
                        ESVTNodeMeshRenderer.ColorScheme colorScheme,
                        RenderMode renderMode,
                        double opacity) {
        if (maxDepth < 1 || maxDepth > 21) {
            throw new IllegalArgumentException("Max depth must be between 1 and 21, got: " + maxDepth);
        }

        this.maxDepth = maxDepth;
        this.colorScheme = colorScheme;
        this.renderMode = renderMode;
        this.opacity = Math.max(0.0, Math.min(1.0, opacity));

        log.debug("ESVTRenderer created: depth={}, colors={}, mode={}, opacity={}",
                 maxDepth, colorScheme, renderMode, opacity);
    }

    /**
     * Convenience constructor with default settings.
     */
    public ESVTRenderer(int maxDepth) {
        this(maxDepth, ESVTNodeMeshRenderer.ColorScheme.DEPTH_GRADIENT, RenderMode.LEAVES_ONLY, 0.7);
    }

    /**
     * Set the ESVT data to render.
     */
    public void setData(ESVTData data) {
        this.currentData = data;
        this.meshRenderer = data != null ? new ESVTNodeMeshRenderer(data) : null;
        this.currentRendering = null; // Clear cached rendering
        log.debug("ESVTRenderer data set: {}", data);
    }

    /**
     * Get the current ESVT data.
     */
    public ESVTData getData() {
        return currentData;
    }

    /**
     * Render the entire ESVT tree.
     *
     * @return Group containing the rendered meshes
     */
    public Group render() {
        return render(0, maxDepth);
    }

    /**
     * Render a specific level range of the tree.
     *
     * @param minLevel Minimum level to render (0 = root)
     * @param maxLevel Maximum level to render
     * @return Group containing the rendered meshes
     */
    public Group render(int minLevel, int maxLevel) {
        if (meshRenderer == null) {
            log.warn("No ESVT data set, returning empty group");
            return new Group();
        }

        log.debug("Rendering ESVT levels {}-{} with mode={}, colorScheme={}",
                 minLevel, maxLevel, renderMode, colorScheme);

        var group = new Group();

        switch (renderMode) {
            case LEAVES_ONLY -> {
                group.getChildren().add(meshRenderer.renderLeaves(colorScheme, opacity));
            }
            case ALL_LEVELS -> {
                group.getChildren().add(meshRenderer.renderLevelRange(minLevel, maxLevel, colorScheme, opacity));
            }
            case WIREFRAME -> {
                group.getChildren().add(meshRenderer.renderLeafWireframes());
            }
            case SOLID_WITH_WIREFRAME -> {
                group.getChildren().add(meshRenderer.renderLeaves(colorScheme, opacity));
                group.getChildren().add(meshRenderer.renderLeafWireframes());
            }
            case GPU_RAYCAST -> {
                // GPU rendering is handled separately via ESVTGPURenderBridge
                // Return empty group - the app will overlay GPU-rendered image
                log.debug("GPU_RAYCAST mode - mesh rendering skipped, using GPU bridge");
            }
        }

        currentRendering = group;

        // Count actual meshes (not just top-level Groups)
        int meshCount = countMeshesRecursively(group);
        log.debug("Rendered {} meshes in {} top-level groups", meshCount, group.getChildren().size());

        return group;
    }

    /**
     * Count meshes recursively through nested Groups.
     */
    private int countMeshesRecursively(javafx.scene.Node node) {
        if (node instanceof Group group) {
            return group.getChildren().stream()
                       .mapToInt(this::countMeshesRecursively)
                       .sum();
        } else if (node instanceof javafx.scene.shape.MeshView ||
                   node instanceof javafx.scene.shape.Shape3D) {
            return 1;
        }
        return 0;
    }

    /**
     * Render only leaf nodes with custom settings.
     */
    public Group renderLeaves(ESVTNodeMeshRenderer.ColorScheme scheme, double opacity) {
        if (meshRenderer == null) {
            return new Group();
        }
        return meshRenderer.renderLeaves(scheme, opacity);
    }

    /**
     * Get the last rendered group.
     */
    public Group getCurrentRendering() {
        return currentRendering;
    }

    /**
     * Get statistics about the current render.
     */
    public String getStatistics() {
        if (meshRenderer == null) {
            return "No data loaded";
        }
        return meshRenderer.getStatistics();
    }

    /**
     * Get the maximum depth setting.
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * Get the current color scheme.
     */
    public ESVTNodeMeshRenderer.ColorScheme getColorScheme() {
        return colorScheme;
    }

    /**
     * Get the current render mode.
     */
    public RenderMode getRenderMode() {
        return renderMode;
    }

    /**
     * Check if the current render mode requires GPU rendering.
     */
    public boolean isGPUMode() {
        return renderMode == RenderMode.GPU_RAYCAST;
    }

    /**
     * Check if a render mode requires GPU rendering.
     */
    public static boolean isGPUMode(RenderMode mode) {
        return mode == RenderMode.GPU_RAYCAST;
    }

    /**
     * Check if GPU rendering is available on this system.
     * Uses OpenCL which is supported on macOS, Linux, and Windows.
     *
     * @return true if GPU raycast rendering is available
     */
    public static boolean isGPUAvailable() {
        return ESVTOpenCLRenderBridge.isAvailable();
    }

    /**
     * Get the current opacity.
     */
    public double getOpacity() {
        return opacity;
    }

    /**
     * Create a renderer builder for fluent configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ESVTRenderer configuration.
     */
    public static class Builder {
        private int maxDepth = 10;
        private ESVTNodeMeshRenderer.ColorScheme colorScheme = ESVTNodeMeshRenderer.ColorScheme.DEPTH_GRADIENT;
        private RenderMode renderMode = RenderMode.LEAVES_ONLY;
        private double opacity = 0.7;

        public Builder maxDepth(int depth) {
            this.maxDepth = depth;
            return this;
        }

        public Builder colorScheme(ESVTNodeMeshRenderer.ColorScheme scheme) {
            this.colorScheme = scheme;
            return this;
        }

        public Builder renderMode(RenderMode mode) {
            this.renderMode = mode;
            return this;
        }

        public Builder opacity(double opacity) {
            this.opacity = opacity;
            return this;
        }

        public ESVTRenderer build() {
            return new ESVTRenderer(maxDepth, colorScheme, renderMode, opacity);
        }
    }
}
