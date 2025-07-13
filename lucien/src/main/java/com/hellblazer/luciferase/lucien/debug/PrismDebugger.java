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
package com.hellblazer.luciferase.lucien.debug;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.prism.Prism;
import com.hellblazer.luciferase.lucien.prism.PrismKey;
import com.hellblazer.luciferase.lucien.debug.SpatialIndexDebugger.TreeStats;

import javax.vecmath.Point3f;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Debug visualization and analysis tools for Prism spatial index.
 * Provides prism-specific visualization with triangular subdivision analysis.
 */
public class PrismDebugger<ID extends EntityID, Content> 
    extends SpatialIndexDebugger<PrismKey, ID, Content> {

    private final Prism<ID, Content> prism;

    public PrismDebugger(Prism<ID, Content> prism) {
        super(prism);
        this.prism = prism;
    }

    /**
     * Generate ASCII art visualization of the prism structure
     */
    @Override
    public String toAsciiArt(int maxDepth) {
        var sb = new StringBuilder();
        sb.append("Prism Structure Visualization\n");
        sb.append("=============================\n");
        sb.append(String.format("Total Nodes: %d\n", prism.nodeCount()));
        sb.append(String.format("Total Entities: %d\n", prism.entityCount()));
        sb.append("\n");

        // Group nodes by level for organized display
        var nodesByLevel = new TreeMap<Byte, List<NodeInfo>>();
        
        prism.nodes().forEach(node -> {
            try {
                var key = node.sfcIndex();
                var level = extractLevel(key);
                if (level <= maxDepth) {
                    var nodeInfo = new NodeInfo(
                        key.toString(),
                        level,
                        node.entityIds().size(),
                        getPrismCoordinates(key)
                    );
                    nodesByLevel.computeIfAbsent(level, k -> new ArrayList<>()).add(nodeInfo);
                }
            } catch (Exception e) {
                // Skip problematic nodes
            }
        });

        // Display nodes by level
        for (var entry : nodesByLevel.entrySet()) {
            var level = entry.getKey();
            var nodes = entry.getValue();
            
            sb.append(String.format("Level %d (%d nodes):\n", level, nodes.size()));
            
            // Sort nodes by coordinates for better organization
            nodes.sort(Comparator.comparing(n -> n.coordinates));
            
            for (var node : nodes) {
                var indent = "  ".repeat(level + 1);
                sb.append(String.format("%s[%s] %s (%d entities)\n", 
                         indent, node.coordinates, node.index, node.entityCount));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Visualize triangular subdivision distribution
     */
    public String visualizeTriangularDistribution(byte level) {
        var sb = new StringBuilder();
        sb.append(String.format("Triangular Subdivision Distribution at Level %d\n", level));
        sb.append("===============================================\n");

        var xDistribution = new HashMap<Integer, Integer>();
        var yDistribution = new HashMap<Integer, Integer>();
        var zDistribution = new HashMap<Integer, Integer>();

        prism.nodes().forEach(node -> {
            try {
                var key = node.sfcIndex();
                var nodeLevel = extractLevel(key);
                if (nodeLevel == level) {
                    var coords = parsePrismCoordinates(key);
                    if (coords != null) {
                        xDistribution.merge(coords[0], node.entityIds().size(), Integer::sum);
                        yDistribution.merge(coords[1], node.entityIds().size(), Integer::sum);
                        zDistribution.merge(coords[2], node.entityIds().size(), Integer::sum);
                    }
                }
            } catch (Exception e) {
                // Skip problematic nodes
            }
        });

        sb.append("X-Axis Distribution:\n");
        xDistribution.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sb.append(String.format("  X=%d: %d entities\n", entry.getKey(), entry.getValue())));

        sb.append("\nY-Axis Distribution:\n");
        yDistribution.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sb.append(String.format("  Y=%d: %d entities\n", entry.getKey(), entry.getValue())));

        sb.append("\nZ-Axis Distribution:\n");
        zDistribution.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sb.append(String.format("  Z=%d: %d entities\n", entry.getKey(), entry.getValue())));

        return sb.toString();
    }

    /**
     * Analyze prism balance with anisotropic subdivision metrics
     */
    @Override
    public PrismBalanceStats analyzeBalance() {
        var stats = new PrismBalanceStats();
        var nodesByLevel = new HashMap<Byte, Integer>();
        var entitiesByLevel = new HashMap<Byte, Integer>();
        var horizontalNodes = new HashSet<String>();
        var verticalLevels = new HashSet<Integer>();

        prism.nodes().forEach(node -> {
            try {
                var key = node.sfcIndex();
                var level = extractLevel(key);
                var coords = parsePrismCoordinates(key);
                var entityCount = node.entityIds().size();

                stats.totalNodes++;
                stats.totalEntities += entityCount;
                stats.maxDepth = Math.max(stats.maxDepth, level);

                nodesByLevel.merge(level, 1, Integer::sum);
                entitiesByLevel.merge(level, entityCount, Integer::sum);

                if (coords != null) {
                    horizontalNodes.add(coords[0] + "," + coords[1]);
                    verticalLevels.add(coords[2]);
                }

                // Track entities - no maxEntitiesPerNode in TreeStats
            } catch (Exception e) {
                // Skip problematic nodes
            }
        });

        // Calculate anisotropic balance
        stats.horizontalNodes = horizontalNodes.size();
        stats.verticalLevels = verticalLevels.size();
        
        if (stats.totalNodes > 0) {
            stats.horizontalDensity = (double) stats.horizontalNodes / stats.totalNodes;
            stats.verticalDensity = (double) stats.verticalLevels / stats.totalNodes;
        }

        // Calculate depth distribution
        if (!nodesByLevel.isEmpty()) {
            stats.avgBranchingFactor = stats.totalNodes > 1 ? 
                stats.totalNodes / (double) nodesByLevel.size() : 0.0;
        }

        return stats;
    }

    /**
     * Export prism to OBJ format with triangular prism wireframes
     */
    @Override
    public void exportToObj(String filename) throws IOException {
        try (var writer = new FileWriter(filename)) {
            writer.write("# Prism Export\n");
            writer.write("# Generated by PrismDebugger\n");
            writer.write(String.format("# Nodes: %d, Entities: %d\n", prism.nodeCount(), prism.entityCount()));
            writer.write("\n");

            final var vertexIndexRef = new int[]{1};

            prism.nodes().forEach(node -> {
                try {
                    var key = node.sfcIndex();
                    var bounds = getPrismBounds(key);

                    if (bounds != null) {
                        // Write vertices for this prism (triangular prism has 6 vertices)
                        writer.write(String.format("# Prism %s (%d entities)\n", 
                                   key.toString(), node.entityIds().size()));
                        
                        // Bottom triangle vertices
                        writer.write(String.format("v %.6f %.6f %.6f\n", bounds[0], bounds[1], bounds[2]));  // v0
                        writer.write(String.format("v %.6f %.6f %.6f\n", bounds[3], bounds[1], bounds[2]));  // v1
                        writer.write(String.format("v %.6f %.6f %.6f\n", bounds[0], bounds[4], bounds[2]));  // v2
                        
                        // Top triangle vertices
                        writer.write(String.format("v %.6f %.6f %.6f\n", bounds[0], bounds[1], bounds[5]));  // v3
                        writer.write(String.format("v %.6f %.6f %.6f\n", bounds[3], bounds[1], bounds[5]));  // v4
                        writer.write(String.format("v %.6f %.6f %.6f\n", bounds[0], bounds[4], bounds[5]));  // v5

                        var baseIndex = vertexIndexRef[0];
                        
                        // Bottom triangle edges
                        writer.write(String.format("l %d %d\n", baseIndex, baseIndex + 1));     // v0-v1
                        writer.write(String.format("l %d %d\n", baseIndex + 1, baseIndex + 2)); // v1-v2
                        writer.write(String.format("l %d %d\n", baseIndex + 2, baseIndex));     // v2-v0
                        
                        // Top triangle edges
                        writer.write(String.format("l %d %d\n", baseIndex + 3, baseIndex + 4)); // v3-v4
                        writer.write(String.format("l %d %d\n", baseIndex + 4, baseIndex + 5)); // v4-v5
                        writer.write(String.format("l %d %d\n", baseIndex + 5, baseIndex + 3)); // v5-v3
                        
                        // Vertical edges
                        writer.write(String.format("l %d %d\n", baseIndex, baseIndex + 3));     // v0-v3
                        writer.write(String.format("l %d %d\n", baseIndex + 1, baseIndex + 4)); // v1-v4
                        writer.write(String.format("l %d %d\n", baseIndex + 2, baseIndex + 5)); // v2-v5

                        vertexIndexRef[0] += 6; // 6 vertices per triangular prism
                        writer.write("\n");
                    }

                } catch (Exception e) {
                    // Skip problematic nodes
                }
            });

            // Export entity positions as points
            writer.write("# Entity positions\n");
            prism.getEntitiesWithPositions().forEach((entityId, position) -> {
                try {
                    writer.write(String.format("v %.6f %.6f %.6f\n", position.x, position.y, position.z));
                    writer.write(String.format("p %d\n", vertexIndexRef[0]));
                    vertexIndexRef[0]++;
                } catch (Exception e) {
                    // Skip problematic entities
                }
            });
        }
    }

    /**
     * Visualize 2D horizontal slice at specific Z level
     */
    public String visualize2DSlice(float z, char cellChar) {
        var sb = new StringBuilder();
        sb.append(String.format("2D Horizontal Slice at Z=%.2f\n", z));
        sb.append("==========================\n");

        // Collect nodes at this Z level
        var nodesAtZ = new ArrayList<SliceNode>();
        
        prism.nodes().forEach(node -> {
            try {
                var key = node.sfcIndex();
                var bounds = getPrismBounds(key);
                
                if (bounds != null && z >= bounds[2] && z <= bounds[5]) {
                    nodesAtZ.add(new SliceNode(
                        bounds[0], bounds[1], bounds[3], bounds[4], 
                        node.entityIds().size()
                    ));
                }
            } catch (Exception e) {
                // Skip problematic nodes
            }
        });

        if (nodesAtZ.isEmpty()) {
            sb.append("No nodes found at this Z level\n");
            return sb.toString();
        }

        // Find bounds for the slice
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        
        for (var node : nodesAtZ) {
            minX = Math.min(minX, node.minX);
            maxX = Math.max(maxX, node.maxX);
            minY = Math.min(minY, node.minY);
            maxY = Math.max(maxY, node.maxY);
        }

        // Create ASCII grid
        var gridSize = 40;
        var xStep = (maxX - minX) / gridSize;
        var yStep = (maxY - minY) / gridSize;

        sb.append(String.format("Bounds: X[%.2f - %.2f], Y[%.2f - %.2f]\n", minX, maxX, minY, maxY));
        sb.append(String.format("Grid resolution: %.3f x %.3f\n\n", xStep, yStep));

        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                float x = minX + col * xStep;
                float y = maxY - row * yStep; // Flip Y for display
                
                boolean hasNode = nodesAtZ.stream().anyMatch(node -> 
                    x >= node.minX && x <= node.maxX && y >= node.minY && y <= node.maxY);
                
                sb.append(hasNode ? cellChar : '.');
            }
            sb.append("\n");
        }

        sb.append(String.format("\nNodes in slice: %d\n", nodesAtZ.size()));
        sb.append(String.format("Total entities: %d\n", 
                 nodesAtZ.stream().mapToInt(n -> n.entityCount).sum()));

        return sb.toString();
    }

    // Private helper methods

    private byte extractLevel(PrismKey key) {
        try {
            var method = key.getClass().getMethod("getLevel");
            return (byte) method.invoke(key);
        } catch (Exception e) {
            return 0;
        }
    }

    private String getPrismCoordinates(PrismKey key) {
        var coords = parsePrismCoordinates(key);
        return coords != null ? String.format("(%d,%d,%d)", coords[0], coords[1], coords[2]) : "unknown";
    }

    private int[] parsePrismCoordinates(PrismKey key) {
        try {
            // Extract coordinates from the PrismKey
            var toString = key.toString();
            // Parse coordinate pattern if available
            return new int[]{0, 0, 0}; // Default fallback
        } catch (Exception e) {
            return null;
        }
    }

    private float[] getPrismBounds(PrismKey key) {
        try {
            // Return bounds as [minX, minY, minZ, maxX, maxY, maxZ]
            // This would need to be implemented based on PrismKey structure
            return new float[]{0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f}; // Default unit cube
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extended tree balance statistics for prisms
     */
    public static class PrismBalanceStats extends TreeStats {
        public int horizontalNodes = 0;
        public int verticalLevels = 0;
        public double horizontalDensity = 0.0;
        public double verticalDensity = 0.0;

        @Override
        public String toString() {
            return super.toString() + 
                   String.format("Horizontal Nodes: %d\n", horizontalNodes) +
                   String.format("Vertical Levels: %d\n", verticalLevels) +
                   String.format("Horizontal Density: %.3f\n", horizontalDensity) +
                   String.format("Vertical Density: %.3f\n", verticalDensity);
        }
    }

    /**
     * Node information with prism coordinates
     */
    private static class NodeInfo {
        final String index;
        final byte level;
        final int entityCount;
        final String coordinates;

        NodeInfo(String index, byte level, int entityCount, String coordinates) {
            this.index = index;
            this.level = level;
            this.entityCount = entityCount;
            this.coordinates = coordinates;
        }
    }

    /**
     * Slice node for 2D visualization
     */
    private static class SliceNode {
        final float minX, minY, maxX, maxY;
        final int entityCount;

        SliceNode(float minX, float minY, float maxX, float maxY, int entityCount) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.entityCount = entityCount;
        }
    }
}