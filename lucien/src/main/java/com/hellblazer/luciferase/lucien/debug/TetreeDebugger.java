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
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.debug.SpatialIndexDebugger.TreeStats;

import javax.vecmath.Point3f;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Debug visualization and analysis tools for Tetree spatial index.
 * Provides tetrahedral-specific visualization and analysis capabilities.
 */
public class TetreeDebugger<ID extends EntityID, Content> 
    extends SpatialIndexDebugger<TetreeKey<? extends TetreeKey>, ID, Content> {

    private final Tetree<ID, Content> tetree;

    public TetreeDebugger(Tetree<ID, Content> tetree) {
        super(tetree);
        this.tetree = tetree;
    }

    /**
     * Generate ASCII art visualization of the tetree structure
     */
    @Override
    public String toAsciiArt(int maxDepth) {
        var sb = new StringBuilder();
        sb.append("Tetree Structure Visualization\n");
        sb.append("==============================\n");
        sb.append(String.format("Total Nodes: %d\n", tetree.nodeCount()));
        sb.append(String.format("Total Entities: %d\n", tetree.entityCount()));
        sb.append("\n");

        // Group nodes by level for organized display
        var nodesByLevel = new TreeMap<Byte, List<NodeInfo>>();
        
        tetree.nodes().forEach(node -> {
            try {
                var key = node.sfcIndex();
                var level = extractLevel(key);
                if (level <= maxDepth) {
                    var nodeInfo = new NodeInfo(
                        key.toString(),
                        level,
                        node.entityIds().size(),
                        getTetType(key)
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
            
            // Sort nodes by tetrahedral type for better organization
            nodes.sort(Comparator.comparing(n -> n.tetType));
            
            for (var node : nodes) {
                var indent = "  ".repeat(level + 1);
                sb.append(String.format("%s[T%d] %s (%d entities)\n", 
                         indent, node.tetType, node.index, node.entityCount));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Visualize tetrahedral distribution at a specific level
     */
    public String visualizeTetTypeDistribution(byte level) {
        var sb = new StringBuilder();
        sb.append(String.format("Tetrahedral Type Distribution at Level %d\n", level));
        sb.append("==========================================\n");

        var typeDistribution = new HashMap<Integer, Integer>();
        var typeEntityCount = new HashMap<Integer, Integer>();

        tetree.nodes().forEach(node -> {
            try {
                var key = node.sfcIndex();
                var nodeLevel = extractLevel(key);
                if (nodeLevel == level) {
                    var tetType = getTetType(key);
                    typeDistribution.merge(tetType, 1, Integer::sum);
                    typeEntityCount.merge(tetType, node.entityIds().size(), Integer::sum);
                }
            } catch (Exception e) {
                // Skip problematic nodes
            }
        });

        // Display distribution
        for (int type = 0; type <= 5; type++) {
            var nodeCount = typeDistribution.getOrDefault(type, 0);
            var entityCount = typeEntityCount.getOrDefault(type, 0);
            var percentage = typeDistribution.isEmpty() ? 0.0 : 
                           (nodeCount * 100.0) / typeDistribution.values().stream().mapToInt(Integer::intValue).sum();
            
            sb.append(String.format("Type %d (S%d): %3d nodes (%5.1f%%), %4d entities\n", 
                     type, type, nodeCount, percentage, entityCount));
        }

        return sb.toString();
    }

    /**
     * Analyze tetree balance with tetrahedral-specific metrics
     */
    @Override
    public TreeBalanceStats analyzeBalance() {
        var stats = new TreeBalanceStats();
        var nodesByLevel = new HashMap<Byte, Integer>();
        var entitiesByLevel = new HashMap<Byte, Integer>();
        var typeDistribution = new HashMap<Integer, Integer>();

        tetree.nodes().forEach(node -> {
            try {
                var key = node.sfcIndex();
                var level = extractLevel(key);
                var tetType = getTetType(key);
                var entityCount = node.entityIds().size();

                stats.totalNodes++;
                stats.totalEntities += entityCount;
                stats.maxDepth = Math.max(stats.maxDepth, level);

                nodesByLevel.merge(level, 1, Integer::sum);
                entitiesByLevel.merge(level, entityCount, Integer::sum);
                typeDistribution.merge(tetType, 1, Integer::sum);

                // Track entities - no maxEntitiesPerNode in TreeStats
            } catch (Exception e) {
                // Skip problematic nodes
            }
        });

        // Calculate tetrahedral balance
        if (!typeDistribution.isEmpty()) {
            var maxTypeCount = typeDistribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            var minTypeCount = typeDistribution.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            stats.tetTypeBalance = minTypeCount > 0 ? (double) minTypeCount / maxTypeCount : 0.0;
        }

        // Calculate depth distribution
        if (!nodesByLevel.isEmpty()) {
            stats.avgBranchingFactor = stats.totalNodes > 1 ? 
                stats.totalNodes / (double) nodesByLevel.size() : 0.0;
        }

        return stats;
    }

    /**
     * Export tetree to OBJ format with tetrahedral wireframes
     */
    @Override
    public void exportToObj(String filename) throws IOException {
        try (var writer = new FileWriter(filename)) {
            writer.write("# Tetree Export\n");
            writer.write("# Generated by TetreeDebugger\n");
            writer.write(String.format("# Nodes: %d, Entities: %d\n", tetree.nodeCount(), tetree.entityCount()));
            writer.write("\n");

            final var vertexIndexRef = new int[]{1};

            tetree.nodes().forEach(node -> {
                try {
                    var key = node.sfcIndex();
                    var tet = getTetFromKey(key);
                    var coords = tet.coordinates();

                    // Write vertices for this tetrahedron
                    writer.write(String.format("# Tetrahedron %s (Type %d, %d entities)\n", 
                               key.toString(), getTetType(key), node.entityIds().size()));
                    
                    for (var vertex : coords) {
                        writer.write(String.format("v %.6f %.6f %.6f\n", vertex.x, vertex.y, vertex.z));
                    }

                    // Write tetrahedral edges (6 edges for a tetrahedron)
                    var baseIndex = vertexIndexRef[0];
                    
                    // Edges: (0,1), (0,2), (0,3), (1,2), (1,3), (2,3)
                    writer.write(String.format("l %d %d\n", baseIndex, baseIndex + 1));     // 0-1
                    writer.write(String.format("l %d %d\n", baseIndex, baseIndex + 2));     // 0-2
                    writer.write(String.format("l %d %d\n", baseIndex, baseIndex + 3));     // 0-3
                    writer.write(String.format("l %d %d\n", baseIndex + 1, baseIndex + 2)); // 1-2
                    writer.write(String.format("l %d %d\n", baseIndex + 1, baseIndex + 3)); // 1-3
                    writer.write(String.format("l %d %d\n", baseIndex + 2, baseIndex + 3)); // 2-3

                    vertexIndexRef[0] += 4; // 4 vertices per tetrahedron
                    writer.write("\n");

                } catch (Exception e) {
                    // Skip problematic nodes
                }
            });

            // Export entity positions as points
            writer.write("# Entity positions\n");
            tetree.getEntitiesWithPositions().forEach((entityId, position) -> {
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
     * Visualize tetrahedral subdivision at a specific position
     */
    public String visualizeSubdivisionAt(Point3f position, byte level) {
        var sb = new StringBuilder();
        sb.append(String.format("Tetrahedral Subdivision at (%.2f, %.2f, %.2f), Level %d\n", 
                 position.x, position.y, position.z, level));
        sb.append("========================================================\n");

        try {
            // Find the containing tetrahedron
            var containingNode = tetree.enclosing(new com.hellblazer.luciferase.lucien.Spatial.Cube(
                position.x - 0.1f, position.y - 0.1f, position.z - 0.1f, 0.2f));
            
            if (containingNode != null) {
                var key = containingNode.sfcIndex();
                var tet = getTetFromKey(key);
                var coords = tet.coordinates();
                var tetType = getTetType(key);

                sb.append(String.format("Containing Tetrahedron: %s (Type %d)\n", key.toString(), tetType));
                sb.append("Vertices:\n");
                for (int i = 0; i < coords.length; i++) {
                    var v = coords[i];
                    sb.append(String.format("  V%d: (%.3f, %.3f, %.3f)\n", i, v.x, v.y, v.z));
                }

                // Check if point is actually contained
                var contained = tet.containsUltraFast(position.x, position.y, position.z);
                sb.append(String.format("Point contained: %s\n", contained));

                sb.append(String.format("Entities in tetrahedron: %d\n", containingNode.entityIds().size()));
            } else {
                sb.append("No containing tetrahedron found\n");
            }

        } catch (Exception e) {
            sb.append("Error analyzing subdivision: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    // Private helper methods

    private byte extractLevel(TetreeKey<? extends TetreeKey> key) {
        try {
            // Use reflection to get level from key
            var method = key.getClass().getMethod("getLevel");
            return (byte) method.invoke(key);
        } catch (Exception e) {
            return 0;
        }
    }

    private int getTetType(TetreeKey<? extends TetreeKey> key) {
        try {
            var tet = getTetFromKey(key);
            return tet.type();
        } catch (Exception e) {
            return 0;
        }
    }

    private Tet getTetFromKey(TetreeKey<? extends TetreeKey> key) {
        try {
            // Access the underlying Tet from the TetreeKey
            var method = key.getClass().getMethod("getTet");
            return (Tet) method.invoke(key);
        } catch (Exception e) {
            // Fallback: create a basic Tet from available information
            return new Tet(0, 0, 0, (byte) 0, (byte) 0); // Default coordinates, level 0, type 0
        }
    }

    /**
     * Extended tree balance statistics for tetrahedra
     */
    public static class TreeBalanceStats extends TreeStats {
        public double tetTypeBalance = 0.0; // Balance across tetrahedral types (0-1, 1 = perfect)

        @Override
        public String toString() {
            return super.toString() + 
                   String.format("Tetrahedral Type Balance: %.3f\n", tetTypeBalance);
        }
    }

    /**
     * Node information with tetrahedral type
     */
    private static class NodeInfo {
        final String index;
        final byte level;
        final int entityCount;
        final int tetType;

        NodeInfo(String index, byte level, int entityCount, int tetType) {
            this.index = index;
            this.level = level;
            this.entityCount = entityCount;
            this.tetType = tetType;
        }
    }
}