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

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;

import javax.vecmath.Point3f;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Debug utilities specifically for Octree visualization and analysis.
 *
 * @param <ID>      The entity ID type
 * @param <Content> The content type
 */
public class OctreeDebugger<ID extends EntityID, Content> {

    private final Octree<ID, Content> octree;

    public OctreeDebugger(Octree<ID, Content> octree) {
        this.octree = Objects.requireNonNull(octree);
    }

    /**
     * Generate ASCII art representation of the octree structure
     *
     * @param maxDepth Maximum depth to display
     * @return ASCII art string representation
     */
    public String toAsciiArt(int maxDepth) {
        var builder = new StringBuilder();
        builder.append("Octree Structure Visualization\n");
        builder.append("==============================\n");
        
        var stats = octree.getStats();
        builder.append(String.format("Total Nodes: %d, Total Entities: %d\n", 
                                   stats.nodeCount(), stats.entityCount()));
        builder.append(String.format("Entity References: %d, Max Depth: %d\n", 
                                   stats.totalEntityReferences(), stats.maxDepth()));
        builder.append(String.format("Avg Entities/Node: %.2f, Entity Spanning Factor: %.2f\n\n",
                                   stats.averageEntitiesPerNode(), stats.entitySpanningFactor()));

        // Collect nodes by level
        var nodesByLevel = collectNodesByLevel();

        // Display tree structure
        for (var entry : nodesByLevel.entrySet()) {
            var level = entry.getKey();
            if (level > maxDepth) break;
            
            var nodes = entry.getValue();
            builder.append(String.format("Level %d: %d nodes (cell size: %d)\n", 
                                       level, nodes.size(), Constants.lengthAtLevel(level)));
            
            // Group nodes by parent for better visualization
            var nodesByParent = groupByParent(nodes, level);
            
            var displayCount = 0;
            for (var parentEntry : nodesByParent.entrySet()) {
                if (displayCount >= 10) {
                    builder.append("  ... and more\n");
                    break;
                }
                
                var parentKey = parentEntry.getKey();
                var children = parentEntry.getValue();
                
                if (parentKey != null) {
                    builder.append("  Parent: ").append(formatMortonKey(parentKey)).append("\n");
                }
                
                for (var node : children) {
                    if (displayCount++ >= 10) break;
                    
                    var indent = parentKey != null ? "    " : "  ";
                    builder.append(indent)
                           .append("├─ ")
                           .append(formatMortonKey(node.key))
                           .append(String.format(" [%d entities]", node.entityIds.size()));
                    
                    // Show spatial location
                    var coords = MortonCurve.decode(node.key.getMortonCode());
                    builder.append(String.format(" @ (%d,%d,%d)", coords[0], coords[1], coords[2]));
                    
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
            }
            builder.append("\n");
        }

        return builder.toString();
    }

    /**
     * Export octree structure to Wavefront OBJ format for 3D visualization
     *
     * @param filename Output filename
     * @throws IOException if file writing fails
     */
    public void exportToObj(String filename) throws IOException {
        try (var writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write("# Octree Export\n");
            writer.write("# Generated by OctreeDebugger\n");
            writer.write(String.format("# Nodes: %d, Entities: %d\n\n", 
                                     octree.nodeCount(), 
                                     octree.entityCount()));

            var vertexIndex = 1;

            // Export node bounds as wireframe cubes
            writer.write("# Node Bounds (Wireframe Cubes)\n");
            writer.write("# Each node is represented as a wireframe cube\n\n");
            
            var nodesByLevel = collectNodesByLevel();
            
            for (var entry : nodesByLevel.entrySet()) {
                var level = entry.getKey();
                var nodes = entry.getValue();
                
                writer.write(String.format("\n# Level %d (%d nodes)\n", level, nodes.size()));
                
                for (var node : nodes) {
                    var cube = getCubeFromMortonKey(node.key);
                    vertexIndex = exportCubeAsWireframe(writer, cube, vertexIndex, 
                                                       String.format("Node %s", formatMortonKey(node.key)));
                }
            }

            // Export entity positions as points
            writer.write("\n\n# Entity Positions\n");
            var entitiesWithPositions = octree.getEntitiesWithPositions();
            for (var entry : entitiesWithPositions.entrySet()) {
                var pos = entry.getValue();
                writer.write(String.format("v %.6f %.6f %.6f # Entity %s\n", 
                                         pos.x, pos.y, pos.z, entry.getKey()));
            }
        }
    }

    /**
     * Analyze octree balance and generate statistics
     *
     * @return Tree balance statistics
     */
    public TreeBalanceStats analyzeBalance() {
        var stats = new TreeBalanceStats();
        
        var nodesByLevel = collectNodesByLevel();
        stats.totalNodes = octree.nodeCount();
        stats.totalEntities = octree.entityCount();
        stats.maxDepth = nodesByLevel.isEmpty() ? 0 : 
                        nodesByLevel.lastKey();

        // Find leaf nodes
        var leafNodes = new HashSet<MortonKey>();
        var allNodes = octree.nodes().map(n -> n.sfcIndex()).collect(Collectors.toSet());
        
        for (var node : allNodes) {
            var hasChildren = false;
            var parentLevel = node.getLevel();
            
            // Check if any node at the next level is a child
            for (var potential : allNodes) {
                if (potential.getLevel() == parentLevel + 1 && isChildOf(potential, node)) {
                    hasChildren = true;
                    break;
                }
            }
            
            if (!hasChildren) {
                leafNodes.add(node);
            }
        }
        
        stats.leafNodes = leafNodes.size();
        stats.internalNodes = stats.totalNodes - stats.leafNodes;

        // Calculate leaf depth distribution
        var leafDepths = leafNodes.stream()
                                 .mapToInt(MortonKey::getLevel)
                                 .boxed()
                                 .collect(Collectors.toList());
        
        if (!leafDepths.isEmpty()) {
            stats.minLeafDepth = Collections.min(leafDepths);
            stats.maxLeafDepth = Collections.max(leafDepths);
            stats.balanceFactor = stats.maxLeafDepth - stats.minLeafDepth;
        }

        // Calculate average branching factor
        var totalChildren = 0;
        var nodesWithChildren = 0;
        
        for (var parentNode : allNodes) {
            var childCount = 0;
            for (var childNode : allNodes) {
                if (isChildOf(childNode, parentNode)) {
                    childCount++;
                }
            }
            if (childCount > 0) {
                totalChildren += childCount;
                nodesWithChildren++;
            }
        }
        
        stats.avgBranchingFactor = nodesWithChildren > 0 ? 
                                  (double) totalChildren / nodesWithChildren : 0.0;

        // Node distribution by level
        stats.nodesByLevel = new TreeMap<>();
        nodesByLevel.forEach((level, nodes) -> 
            stats.nodesByLevel.put(level, nodes.size()));

        // Calculate fullness ratio per level
        stats.fullnessRatioByLevel = new TreeMap<>();
        for (var entry : nodesByLevel.entrySet()) {
            var level = entry.getKey();
            var actualNodes = entry.getValue().size();
            var maxPossibleNodes = (long) Math.pow(8, level); // 8^level for octree
            var ratio = maxPossibleNodes > 0 ? (double) actualNodes / maxPossibleNodes : 0.0;
            stats.fullnessRatioByLevel.put(level, Math.min(ratio, 1.0)); // Cap at 1.0
        }

        return stats;
    }

    /**
     * Generate a 2D slice visualization at a specific Z coordinate
     *
     * @param z        Z coordinate to slice at
     * @param cellChar Character to use for occupied cells
     * @return ASCII art representation of the slice
     */
    public String visualize2DSlice(float z, char cellChar) {
        var builder = new StringBuilder();
        builder.append(String.format("2D Slice at Z=%.2f\n", z));
        builder.append("==================\n\n");

        // Find all nodes that intersect with this Z plane
        var intersectingNodes = new ArrayList<NodeInfo>();
        
        octree.nodes().forEach(node -> {
            var cube = getCubeFromMortonKey(node.sfcIndex());
            if (cube.originZ() <= z && z <= cube.originZ() + cube.extent()) {
                intersectingNodes.add(new NodeInfo(node.sfcIndex(), node.entityIds()));
            }
        });

        if (intersectingNodes.isEmpty()) {
            builder.append("No nodes at this Z coordinate\n");
            return builder.toString();
        }

        // Find bounds
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        
        for (var node : intersectingNodes) {
            var cube = getCubeFromMortonKey(node.key);
            minX = Math.min(minX, cube.originX());
            maxX = Math.max(maxX, cube.originX() + cube.extent());
            minY = Math.min(minY, cube.originY());
            maxY = Math.max(maxY, cube.originY() + cube.extent());
        }

        // Create grid for visualization (limited resolution)
        var gridSize = 50;
        var grid = new char[gridSize][gridSize];
        for (var row : grid) {
            Arrays.fill(row, '.');
        }

        // Map nodes to grid
        for (var node : intersectingNodes) {
            var cube = getCubeFromMortonKey(node.key);
            var startX = (int) ((cube.originX() - minX) / (maxX - minX) * (gridSize - 1));
            var endX = (int) ((cube.originX() + cube.extent() - minX) / (maxX - minX) * (gridSize - 1));
            var startY = (int) ((cube.originY() - minY) / (maxY - minY) * (gridSize - 1));
            var endY = (int) ((cube.originY() + cube.extent() - minY) / (maxY - minY) * (gridSize - 1));
            
            for (var y = Math.max(0, startY); y <= Math.min(gridSize - 1, endY); y++) {
                for (var x = Math.max(0, startX); x <= Math.min(gridSize - 1, endX); x++) {
                    grid[gridSize - 1 - y][x] = node.entityIds.isEmpty() ? '□' : cellChar;
                }
            }
        }

        // Build output
        builder.append(String.format("X: [%.2f, %.2f], Y: [%.2f, %.2f]\n", minX, maxX, minY, maxY));
        builder.append("Legend: . = empty, □ = empty node, ").append(cellChar).append(" = node with entities\n\n");
        
        for (var row : grid) {
            builder.append(row).append("\n");
        }

        return builder.toString();
    }

    // Helper methods

    private TreeMap<Byte, List<NodeInfo>> collectNodesByLevel() {
        var nodesByLevel = new TreeMap<Byte, List<NodeInfo>>();
        
        octree.nodes().forEach(node -> {
            var level = node.sfcIndex().getLevel();
            nodesByLevel.computeIfAbsent(level, k -> new ArrayList<>())
                       .add(new NodeInfo(node.sfcIndex(), node.entityIds()));
        });
        
        return nodesByLevel;
    }

    private Map<MortonKey, List<NodeInfo>> groupByParent(List<NodeInfo> nodes, byte level) {
        var grouped = new LinkedHashMap<MortonKey, List<NodeInfo>>();
        
        if (level == 0) {
            grouped.put(null, nodes);
        } else {
            for (var node : nodes) {
                var parentKey = getParentKey(node.key);
                grouped.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(node);
            }
        }
        
        return grouped;
    }

    private MortonKey getParentKey(MortonKey childKey) {
        var childLevel = childKey.getLevel();
        if (childLevel == 0) return null;
        
        var childCoords = MortonCurve.decode(childKey.getMortonCode());
        var parentLevel = (byte) (childLevel - 1);
        var parentCellSize = Constants.lengthAtLevel(parentLevel);
        
        var parentX = (childCoords[0] / parentCellSize) * parentCellSize;
        var parentY = (childCoords[1] / parentCellSize) * parentCellSize;
        var parentZ = (childCoords[2] / parentCellSize) * parentCellSize;
        
        var parentCenter = new Point3f(parentX + parentCellSize / 2.0f,
                                     parentY + parentCellSize / 2.0f,
                                     parentZ + parentCellSize / 2.0f);
        
        return new MortonKey(Constants.calculateMortonIndex(parentCenter, parentLevel), parentLevel);
    }

    private boolean isChildOf(MortonKey child, MortonKey parent) {
        if (child.getLevel() != parent.getLevel() + 1) {
            return false;
        }
        
        var childCoords = MortonCurve.decode(child.getMortonCode());
        var parentCoords = MortonCurve.decode(parent.getMortonCode());
        var parentCellSize = Constants.lengthAtLevel(parent.getLevel());
        
        return childCoords[0] >= parentCoords[0] && 
               childCoords[0] < parentCoords[0] + parentCellSize &&
               childCoords[1] >= parentCoords[1] && 
               childCoords[1] < parentCoords[1] + parentCellSize &&
               childCoords[2] >= parentCoords[2] && 
               childCoords[2] < parentCoords[2] + parentCellSize;
    }

    private String formatMortonKey(MortonKey key) {
        var coords = MortonCurve.decode(key.getMortonCode());
        return String.format("L%d:%d,%d,%d", key.getLevel(), coords[0], coords[1], coords[2]);
    }

    private Spatial.Cube getCubeFromMortonKey(MortonKey key) {
        var coords = MortonCurve.decode(key.getMortonCode());
        var cellSize = Constants.lengthAtLevel(key.getLevel());
        return new Spatial.Cube(coords[0], coords[1], coords[2], cellSize);
    }

    private int exportCubeAsWireframe(BufferedWriter writer, Spatial.Cube cube, 
                                    int startVertex, String comment) throws IOException {
        writer.write("# " + comment + "\n");
        
        var x0 = cube.originX();
        var y0 = cube.originY();
        var z0 = cube.originZ();
        var x1 = x0 + cube.extent();
        var y1 = y0 + cube.extent();
        var z1 = z0 + cube.extent();
        
        // Write 8 vertices of the cube
        writer.write(String.format("v %.6f %.6f %.6f\n", x0, y0, z0)); // 0
        writer.write(String.format("v %.6f %.6f %.6f\n", x1, y0, z0)); // 1
        writer.write(String.format("v %.6f %.6f %.6f\n", x1, y1, z0)); // 2
        writer.write(String.format("v %.6f %.6f %.6f\n", x0, y1, z0)); // 3
        writer.write(String.format("v %.6f %.6f %.6f\n", x0, y0, z1)); // 4
        writer.write(String.format("v %.6f %.6f %.6f\n", x1, y0, z1)); // 5
        writer.write(String.format("v %.6f %.6f %.6f\n", x1, y1, z1)); // 6
        writer.write(String.format("v %.6f %.6f %.6f\n", x0, y1, z1)); // 7
        
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
        
        return startVertex + 8;
    }

    // Inner classes

    private static class NodeInfo {
        final MortonKey key;
        final Set<?> entityIds;

        NodeInfo(MortonKey key, Set<?> entityIds) {
            this.key = key;
            this.entityIds = entityIds;
        }
    }

    /**
     * Octree-specific tree balance statistics
     */
    public static class TreeBalanceStats {
        public int totalNodes;
        public int totalEntities;
        public int leafNodes;
        public int internalNodes;
        public int maxDepth;
        public int minLeafDepth;
        public int maxLeafDepth;
        public int balanceFactor;
        public double avgBranchingFactor;
        public Map<Byte, Integer> nodesByLevel;
        public Map<Byte, Double> fullnessRatioByLevel;

        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("Octree Balance Statistics\n");
            sb.append("=========================\n");
            sb.append(String.format("Total Nodes: %d (Leaf: %d, Internal: %d)\n", 
                                  totalNodes, leafNodes, internalNodes));
            sb.append(String.format("Total Entities: %d\n", totalEntities));
            sb.append(String.format("Tree Depth: %d (Leaf depth range: %d-%d)\n", 
                                  maxDepth, minLeafDepth, maxLeafDepth));
            sb.append(String.format("Balance Factor: %d (0 = perfectly balanced)\n", 
                                  balanceFactor));
            sb.append(String.format("Average Branching Factor: %.2f / 8.0\n", avgBranchingFactor));
            
            if (nodesByLevel != null && !nodesByLevel.isEmpty()) {
                sb.append("\nNodes by Level:\n");
                nodesByLevel.forEach((level, count) -> {
                    sb.append(String.format("  Level %d: %d nodes", level, count));
                    if (fullnessRatioByLevel != null && fullnessRatioByLevel.containsKey(level)) {
                        sb.append(String.format(" (%.1f%% full)", 
                                              fullnessRatioByLevel.get(level) * 100));
                    }
                    sb.append("\n");
                });
            }
            
            return sb.toString();
        }
    }
}