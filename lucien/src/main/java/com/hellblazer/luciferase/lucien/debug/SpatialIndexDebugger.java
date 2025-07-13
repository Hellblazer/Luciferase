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

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Debug utilities for spatial indices including ASCII visualization, OBJ export, and tree analysis.
 *
 * @param <Key>     The spatial key type
 * @param <ID>      The entity ID type
 * @param <Content> The content type
 */
public class SpatialIndexDebugger<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private final SpatialIndex<Key, ID, Content> spatialIndex;

    public SpatialIndexDebugger(SpatialIndex<Key, ID, Content> spatialIndex) {
        this.spatialIndex = Objects.requireNonNull(spatialIndex);
    }

    /**
     * Generate ASCII art representation of the spatial index tree structure
     *
     * @param maxDepth Maximum depth to display
     * @return ASCII art string representation
     */
    public String toAsciiArt(int maxDepth) {
        var builder = new StringBuilder();
        builder.append("Spatial Index Tree Structure\n");
        builder.append("============================\n");
        
        var stats = spatialIndex.getStats();
        builder.append(String.format("Total Nodes: %d, Total Entities: %d\n", 
                                   stats.nodeCount(), stats.entityCount()));
        builder.append(String.format("Entity References: %d, Max Depth: %d\n", 
                                   stats.totalEntityReferences(), stats.maxDepth()));
        builder.append(String.format("Avg Entities/Node: %.2f, Entity Spanning Factor: %.2f\n\n",
                                   stats.averageEntitiesPerNode(), stats.entitySpanningFactor()));

        // Collect nodes by level
        var nodesByLevel = new TreeMap<Integer, List<NodeInfo>>();
        spatialIndex.nodes().forEach(node -> {
            var level = getNodeLevel(node.sfcIndex());
            nodesByLevel.computeIfAbsent(level, k -> new ArrayList<>())
                       .add(new NodeInfo(node.sfcIndex(), node.entityIds()));
        });

        // Display tree structure
        for (var entry : nodesByLevel.entrySet()) {
            var level = entry.getKey();
            if (level > maxDepth) break;
            
            var nodes = entry.getValue();
            var indent = "  ".repeat(level);
            
            builder.append(String.format("Level %d: %d nodes\n", level, nodes.size()));
            
            // Limit display to first 10 nodes per level for readability
            var displayCount = Math.min(nodes.size(), 10);
            for (int i = 0; i < displayCount; i++) {
                var node = nodes.get(i);
                builder.append(indent)
                       .append("├─ ")
                       .append(formatNodeKey((Key) node.key))
                       .append(String.format(" [%d entities]", node.entityIds.size()));
                
                // Show first few entity IDs
                if (!node.entityIds.isEmpty()) {
                    var entityList = node.entityIds.stream()
                                                  .limit(3)
                                                  .map(Object::toString)
                                                  .collect(Collectors.joining(", "));
                    if (node.entityIds.size() > 3) {
                        entityList += ", ...";
                    }
                    builder.append(" {").append(entityList).append("}");
                }
                builder.append("\n");
            }
            
            if (nodes.size() > 10) {
                builder.append(indent)
                       .append("└─ ... and ")
                       .append(nodes.size() - 10)
                       .append(" more nodes\n");
            }
            builder.append("\n");
        }

        return builder.toString();
    }

    /**
     * Export spatial index structure to Wavefront OBJ format for 3D visualization
     *
     * @param filename Output filename
     * @throws IOException if file writing fails
     */
    public void exportToObj(String filename) throws IOException {
        try (var writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write("# Spatial Index Export\n");
            writer.write("# Generated by SpatialIndexDebugger\n");
            writer.write(String.format("# Nodes: %d, Entities: %d\n\n", 
                                     spatialIndex.nodeCount(), 
                                     spatialIndex.entityCount()));

            final var vertexIndexRef = new int[]{1};
            final var nodeVertexMap = new HashMap<Key, Integer>();

            // Export vertices (node centers)
            writer.write("# Vertices (Node Centers)\n");
            spatialIndex.nodes().forEach(node -> {
                var bounds = getNodeBounds(node.sfcIndex());
                if (bounds != null) {
                    var center = bounds.getCenter();
                    try {
                        writer.write(String.format("v %.6f %.6f %.6f\n", 
                                                 center[0], center[1], center[2]));
                        nodeVertexMap.put(node.sfcIndex(), vertexIndexRef[0]++);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            writer.write("\n# Edges (Parent-Child Relationships)\n");
            // Export edges as lines
            spatialIndex.nodes().forEach(node -> {
                var nodeVertex = nodeVertexMap.get(node.sfcIndex());
                if (nodeVertex != null) {
                    // Find children and create edges
                    var children = findChildren(node.sfcIndex());
                    for (var child : children) {
                        var childVertex = nodeVertexMap.get(child);
                        if (childVertex != null) {
                            try {
                                writer.write(String.format("l %d %d\n", nodeVertex, childVertex));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            });

            // Export node bounds as wireframe cubes
            writer.write("\n# Node Bounds (Wireframe Cubes)\n");
            spatialIndex.nodes().forEach(node -> {
                var bounds = getNodeBounds(node.sfcIndex());
                if (bounds != null) {
                    try {
                        exportBoundsAsWireframe(writer, bounds, vertexIndexRef[0]);
                        vertexIndexRef[0] += 8; // 8 vertices per cube
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    /**
     * Analyze tree balance and generate statistics
     *
     * @return Tree balance statistics
     */
    public TreeStats analyzeBalance() {
        var stats = new TreeStats();
        
        // Collect nodes by level
        var nodesByLevel = new TreeMap<Integer, List<NodeInfo>>();
        var totalEntities = 0;
        var leafNodes = 0;
        var maxDepth = 0;

        for (var node : spatialIndex.nodes().toList()) {
            var level = getNodeLevel(node.sfcIndex());
            maxDepth = Math.max(maxDepth, level);
            
            nodesByLevel.computeIfAbsent(level, k -> new ArrayList<>())
                       .add(new NodeInfo(node.sfcIndex(), node.entityIds()));
            
            totalEntities += node.entityIds().size();
            
            // Check if leaf node (no children)
            if (findChildren(node.sfcIndex()).isEmpty()) {
                leafNodes++;
            }
        }

        stats.totalNodes = spatialIndex.nodeCount();
        stats.totalEntities = totalEntities;
        stats.maxDepth = maxDepth;
        stats.leafNodes = leafNodes;
        stats.internalNodes = stats.totalNodes - leafNodes;

        // Calculate balance metrics
        if (!nodesByLevel.isEmpty()) {
            // Perfect balance would have all leaf nodes at the same level
            var leafLevels = new HashSet<Integer>();
            for (var entry : nodesByLevel.entrySet()) {
                var level = entry.getKey();
                var nodes = entry.getValue();
                
                for (var node : nodes) {
                    if (findChildren((Key) node.key).isEmpty()) {
                        leafLevels.add(level);
                    }
                }
            }
            
            stats.minLeafDepth = leafLevels.stream().mapToInt(Integer::intValue).min().orElse(0);
            stats.maxLeafDepth = leafLevels.stream().mapToInt(Integer::intValue).max().orElse(0);
            stats.balanceFactor = stats.maxLeafDepth - stats.minLeafDepth;
            
            // Calculate average branching factor
            var totalChildren = 0;
            var nodesWithChildren = 0;
            for (var node : spatialIndex.nodes().toList()) {
                var children = findChildren(node.sfcIndex());
                if (!children.isEmpty()) {
                    totalChildren += children.size();
                    nodesWithChildren++;
                }
            }
            stats.avgBranchingFactor = nodesWithChildren > 0 ? 
                                      (double) totalChildren / nodesWithChildren : 0.0;
            
            // Distribution by level
            stats.nodesByLevel = new TreeMap<>();
            nodesByLevel.forEach((level, nodes) -> 
                stats.nodesByLevel.put(level, nodes.size()));
        }

        return stats;
    }

    // Helper methods

    private int getNodeLevel(Key nodeKey) {
        // This is a placeholder - actual implementation depends on the Key type
        // For MortonKey, this would be key.getLevel()
        // For TetreeKey, this would be similar
        // Using reflection or visitor pattern would be cleaner
        try {
            var method = nodeKey.getClass().getMethod("getLevel");
            return (int) (byte) method.invoke(nodeKey);
        } catch (Exception e) {
            // Fallback: estimate from key value
            return 0;
        }
    }

    private String formatNodeKey(Key key) {
        // Format the key for display
        return key.toString();
    }

    private NodeBounds getNodeBounds(Key nodeKey) {
        // This would need to be implemented based on the actual spatial index
        // For now, return a placeholder
        // In practice, this would call methods on the spatial index to get bounds
        return null; // Placeholder
    }

    private List<Key> findChildren(Key parentKey) {
        // Find all nodes that are children of this node
        // This is a simplified implementation - actual would be more efficient
        var children = new ArrayList<Key>();
        var parentLevel = getNodeLevel(parentKey);
        
        spatialIndex.nodes().forEach(node -> {
            var nodeLevel = getNodeLevel(node.sfcIndex());
            if (nodeLevel == parentLevel + 1) {
                // Check if this node is a child of parent
                // This would need proper parent-child relationship checking
                // based on the key structure
                children.add(node.sfcIndex());
            }
        });
        
        return children;
    }

    private void exportBoundsAsWireframe(BufferedWriter writer, NodeBounds bounds, 
                                       int startVertex) throws IOException {
        var min = bounds.getMin();
        var max = bounds.getMax();
        
        // Write 8 vertices of the cube
        writer.write(String.format("v %.6f %.6f %.6f\n", min[0], min[1], min[2])); // 0
        writer.write(String.format("v %.6f %.6f %.6f\n", max[0], min[1], min[2])); // 1
        writer.write(String.format("v %.6f %.6f %.6f\n", max[0], max[1], min[2])); // 2
        writer.write(String.format("v %.6f %.6f %.6f\n", min[0], max[1], min[2])); // 3
        writer.write(String.format("v %.6f %.6f %.6f\n", min[0], min[1], max[2])); // 4
        writer.write(String.format("v %.6f %.6f %.6f\n", max[0], min[1], max[2])); // 5
        writer.write(String.format("v %.6f %.6f %.6f\n", max[0], max[1], max[2])); // 6
        writer.write(String.format("v %.6f %.6f %.6f\n", min[0], max[1], max[2])); // 7
        
        // Write 12 edges of the cube
        // Bottom face
        writer.write(String.format("l %d %d\n", startVertex, startVertex + 1));
        writer.write(String.format("l %d %d\n", startVertex + 1, startVertex + 2));
        writer.write(String.format("l %d %d\n", startVertex + 2, startVertex + 3));
        writer.write(String.format("l %d %d\n", startVertex + 3, startVertex));
        
        // Top face
        writer.write(String.format("l %d %d\n", startVertex + 4, startVertex + 5));
        writer.write(String.format("l %d %d\n", startVertex + 5, startVertex + 6));
        writer.write(String.format("l %d %d\n", startVertex + 6, startVertex + 7));
        writer.write(String.format("l %d %d\n", startVertex + 7, startVertex + 4));
        
        // Vertical edges
        writer.write(String.format("l %d %d\n", startVertex, startVertex + 4));
        writer.write(String.format("l %d %d\n", startVertex + 1, startVertex + 5));
        writer.write(String.format("l %d %d\n", startVertex + 2, startVertex + 6));
        writer.write(String.format("l %d %d\n", startVertex + 3, startVertex + 7));
    }

    // Inner classes

    private static class NodeInfo {
        final Object key; // Using Object to avoid generic complexity
        final Set<?> entityIds;

        NodeInfo(Object key, Set<?> entityIds) {
            this.key = key;
            this.entityIds = entityIds;
        }
    }

    /**
     * Tree balance statistics
     */
    public static class TreeStats {
        public int totalNodes;
        public int totalEntities;
        public int leafNodes;
        public int internalNodes;
        public int maxDepth;
        public int minLeafDepth;
        public int maxLeafDepth;
        public int balanceFactor; // Difference between max and min leaf depth
        public double avgBranchingFactor;
        public Map<Integer, Integer> nodesByLevel;

        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("Tree Balance Statistics\n");
            sb.append("======================\n");
            sb.append(String.format("Total Nodes: %d (Leaf: %d, Internal: %d)\n", 
                                  totalNodes, leafNodes, internalNodes));
            sb.append(String.format("Total Entities: %d\n", totalEntities));
            sb.append(String.format("Tree Depth: %d (Leaf depth range: %d-%d)\n", 
                                  maxDepth, minLeafDepth, maxLeafDepth));
            sb.append(String.format("Balance Factor: %d (0 is perfectly balanced)\n", 
                                  balanceFactor));
            sb.append(String.format("Average Branching Factor: %.2f\n", avgBranchingFactor));
            
            if (nodesByLevel != null && !nodesByLevel.isEmpty()) {
                sb.append("\nNodes by Level:\n");
                nodesByLevel.forEach((level, count) -> 
                    sb.append(String.format("  Level %d: %d nodes\n", level, count)));
            }
            
            return sb.toString();
        }
    }

    /**
     * Placeholder for node bounds - would be replaced with actual geometry
     */
    private static class NodeBounds {
        float[] getMin() { return new float[]{0, 0, 0}; }
        float[] getMax() { return new float[]{1, 1, 1}; }
        float[] getCenter() { 
            return new float[]{0.5f, 0.5f, 0.5f}; 
        }
    }
}