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
package com.hellblazer.luciferase.portal.esvo.renderer;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.portal.mesh.octree.OctreeNodeMeshRenderer;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level renderer for ESVO octree visualization in the OctreeInspectorApp.
 * Provides color-coded depth visualization, level range filtering, and multiple
 * rendering strategies for performance optimization.
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Color schemes: depth gradient, random, single color, level bands</li>
 *   <li>Level range filtering: show only specific octree levels</li>
 *   <li>Wireframe and solid rendering modes</li>
 *   <li>Multiple rendering strategies: batched (recommended), instancing, hybrid</li>
 * </ul>
 * 
 * @author hal.hildebrand
 */
public class OctreeRenderer {
    
    private static final Logger log = LoggerFactory.getLogger(OctreeRenderer.class);
    
    /**
     * Color scheme for octree visualization.
     */
    public enum ColorScheme {
        /** Red (root) to Blue (leaves) gradient based on depth */
        DEPTH_GRADIENT,
        /** Random color per node for distinction */
        RANDOM,
        /** Single uniform color for all nodes */
        SINGLE_COLOR,
        /** Distinct color per octree level */
        LEVEL_BANDS
    }
    
    private final OctreeNodeMeshRenderer.Strategy renderStrategy;
    private final int maxDepth;
    private final ColorScheme colorScheme;
    private final boolean wireframe;
    
    /**
     * Create a new octree renderer with specified configuration.
     * 
     * @param maxDepth Maximum octree depth (1-15)
     * @param renderStrategy Rendering strategy (BATCHED recommended)
     * @param colorScheme Color scheme for visualization
     * @param wireframe True for wireframe, false for solid
     */
    public OctreeRenderer(int maxDepth, 
                         OctreeNodeMeshRenderer.Strategy renderStrategy,
                         ColorScheme colorScheme,
                         boolean wireframe) {
        if (maxDepth < 1 || maxDepth > 15) {
            throw new IllegalArgumentException("Max depth must be between 1 and 15, got: " + maxDepth);
        }
        
        this.maxDepth = maxDepth;
        this.renderStrategy = renderStrategy;
        this.colorScheme = colorScheme;
        this.wireframe = wireframe;
        
        log.debug("OctreeRenderer created: depth={}, strategy={}, colors={}, wireframe={}", 
                 maxDepth, renderStrategy, colorScheme, wireframe);
    }
    
    /**
     * Convenience constructor with default settings (BATCHED strategy, DEPTH_GRADIENT colors, wireframe).
     * 
     * @param maxDepth Maximum octree depth
     */
    public OctreeRenderer(int maxDepth) {
        this(maxDepth, OctreeNodeMeshRenderer.Strategy.BATCHED, ColorScheme.DEPTH_GRADIENT, true);
    }
    
    /**
     * Render octree nodes to a JavaFX Group.
     * 
     * @param octreeData ESVO octree data to visualize
     * @param minLevel Minimum level to show (inclusive, 0-based)
     * @param maxLevel Maximum level to show (inclusive, 0-based)
     * @return Group containing rendered geometry
     * @throws IllegalArgumentException if octree is null or level range is invalid
     */
    public Group render(ESVOOctreeData octreeData, int minLevel, int maxLevel) {
        if (octreeData == null) {
            throw new IllegalArgumentException("Octree data cannot be null");
        }
        
        if (minLevel < 0 || maxLevel > maxDepth || minLevel > maxLevel) {
            throw new IllegalArgumentException(
                String.format("Invalid level range [%d, %d] for max depth %d", 
                             minLevel, maxLevel, maxDepth));
        }
        
        log.debug("Rendering octree: {} nodes, levels [{}, {}]", 
                 octreeData.getNodeCount(), minLevel, maxLevel);
        
        var startTime = System.nanoTime();
        
        // Collect visible nodes within level range
        var visibleNodes = collectVisibleNodes(octreeData, minLevel, maxLevel);
        
        if (visibleNodes.isEmpty()) {
            log.warn("No nodes found in level range [{}, {}]", minLevel, maxLevel);
            return new Group(); // Empty group
        }
        
        // Render based on color scheme
        Group result = switch (colorScheme) {
            case DEPTH_GRADIENT -> renderWithDepthGradient(visibleNodes, minLevel, maxLevel);
            case RANDOM -> renderWithRandomColors(visibleNodes);
            case SINGLE_COLOR -> renderWithSingleColor(visibleNodes);
            case LEVEL_BANDS -> renderWithLevelBands(visibleNodes, minLevel, maxLevel);
        };
        
        var renderTime = (System.nanoTime() - startTime) / 1_000_000.0;
        log.debug("Rendered {} nodes in {:.2f}ms", visibleNodes.size(), renderTime);
        
        return result;
    }
    
    /**
     * Convenience method: render all levels.
     * 
     * @param octreeData ESVO octree data
     * @return Group containing rendered geometry
     */
    public Group renderAllLevels(ESVOOctreeData octreeData) {
        return render(octreeData, 0, maxDepth);
    }
    
    /**
     * Collect all node indices within the specified level range.
     * 
     * @param octreeData Octree data
     * @param minLevel Minimum level (inclusive)
     * @param maxLevel Maximum level (inclusive)
     * @return List of node indices
     */
    private List<Integer> collectVisibleNodes(ESVOOctreeData octreeData, int minLevel, int maxLevel) {
        var nodes = new ArrayList<Integer>();
        int nodeCount = octreeData.getNodeCount();
        
        // Simple approach: collect all nodes (TODO: add level filtering in Phase 3)
        // For now, we assume all nodes should be rendered
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(i);
        }
        
        log.debug("Collected {} nodes for rendering", nodes.size());
        return nodes;
    }
    
    /**
     * Render with depth gradient: red (shallow) to blue (deep).
     */
    private Group renderWithDepthGradient(List<Integer> nodeIndices, int minLevel, int maxLevel) {
        // For now, use a single blue material
        // TODO: Implement per-node coloring based on depth in Phase 3
        var material = createDepthGradientMaterial(maxLevel, minLevel, maxLevel);
        var renderer = new OctreeNodeMeshRenderer(maxDepth, renderStrategy, material);
        return renderer.render(nodeIndices);
    }
    
    /**
     * Render with random colors per node.
     */
    private Group renderWithRandomColors(List<Integer> nodeIndices) {
        // For now, use a single color
        // TODO: Implement per-node random coloring in Phase 3
        var material = createMaterial(Color.LIMEGREEN.deriveColor(0, 1, 1, 0.6));
        var renderer = new OctreeNodeMeshRenderer(maxDepth, renderStrategy, material);
        return renderer.render(nodeIndices);
    }
    
    /**
     * Render with single uniform color.
     */
    private Group renderWithSingleColor(List<Integer> nodeIndices) {
        var material = createMaterial(Color.DODGERBLUE.deriveColor(0, 1, 1, 0.5));
        var renderer = new OctreeNodeMeshRenderer(maxDepth, renderStrategy, material);
        return renderer.render(nodeIndices);
    }
    
    /**
     * Render with distinct colors per level.
     */
    private Group renderWithLevelBands(List<Integer> nodeIndices, int minLevel, int maxLevel) {
        // For now, use a single color
        // TODO: Implement per-level coloring in Phase 3
        var material = createMaterial(Color.ORANGE.deriveColor(0, 1, 1, 0.6));
        var renderer = new OctreeNodeMeshRenderer(maxDepth, renderStrategy, material);
        return renderer.render(nodeIndices);
    }
    
    /**
     * Create a depth-gradient material color.
     * 
     * @param depth Current depth level
     * @param minLevel Minimum level in range
     * @param maxLevel Maximum level in range
     * @return Color interpolated between red (shallow) and blue (deep)
     */
    private PhongMaterial createDepthGradientMaterial(int depth, int minLevel, int maxLevel) {
        // Normalize depth to [0, 1] range
        float t = maxLevel > minLevel ? 
            (float)(depth - minLevel) / (maxLevel - minLevel) : 0.5f;
        
        // Interpolate: red (t=0) -> blue (t=1)
        Color baseColor = Color.RED.interpolate(Color.BLUE, t);
        
        // Make semi-transparent for better depth perception
        Color finalColor = baseColor.deriveColor(0, 1, 1, 0.6);
        
        return createMaterial(finalColor);
    }
    
    /**
     * Create a PhongMaterial with the specified color.
     * 
     * @param color Base color
     * @return Configured PhongMaterial
     */
    private PhongMaterial createMaterial(Color color) {
        var material = new PhongMaterial(color);
        
        // Add subtle specular highlight
        material.setSpecularColor(color.brighter());
        material.setSpecularPower(5);
        
        return material;
    }
    
    /**
     * Get the current rendering strategy.
     * 
     * @return Strategy
     */
    public OctreeNodeMeshRenderer.Strategy getRenderStrategy() {
        return renderStrategy;
    }
    
    /**
     * Get the color scheme.
     * 
     * @return Color scheme
     */
    public ColorScheme getColorScheme() {
        return colorScheme;
    }
    
    /**
     * Check if wireframe mode is enabled.
     * 
     * @return True if wireframe
     */
    public boolean isWireframe() {
        return wireframe;
    }
    
    /**
     * Get the maximum depth.
     * 
     * @return Max depth
     */
    public int getMaxDepth() {
        return maxDepth;
    }
}
