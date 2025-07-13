/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.neighbor;

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced test suite for TetreeNeighborDetector with more comprehensive testing
 * of edge and vertex neighbor detection.
 * 
 * @author Hal Hildebrand
 */
public class TetreeNeighborDetectorEnhancedTest {
    
    private Tetree<LongEntityID, Point3f> tetree;
    private TetreeNeighborDetector detector;
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        // Create tetree with maxEntitiesPerNode = 1 to force subdivision
        tetree = new Tetree<LongEntityID, Point3f>(idGenerator, 1, (byte)21);
        detector = new TetreeNeighborDetector(tetree);
    }
    
    /**
     * Test that verifies sibling edge neighbor detection works correctly.
     * Creates a configuration where siblings share edges and verifies detection.
     */
    @Test
    void testSiblingEdgeNeighbors() {
        // Create a scenario where we have multiple cells with known relationships
        // Insert entities at specific locations that will create neighboring cells
        
        // Use level 6 where cell size is 16
        byte level = 6;
        int cellSize = 1 << (21 - level); // Should be 32
        
        // Create entities in adjacent cells
        var positions = new Point3f[] {
            // Cell at (0,0,0)
            new Point3f(0, 0, 0),
            new Point3f(10, 10, 10),
            
            // Cell at (1,0,0) - face neighbor
            new Point3f(cellSize, 0, 0),
            new Point3f(cellSize + 10, 10, 10),
            
            // Cell at (0,1,0) - face neighbor
            new Point3f(0, cellSize, 0),
            new Point3f(10, cellSize + 10, 10),
            
            // Cell at (1,1,0) - edge neighbor
            new Point3f(cellSize, cellSize, 0),
            new Point3f(cellSize + 10, cellSize + 10, 10),
        };
        
        // Insert all positions
        for (var pos : positions) {
            tetree.insert(pos, level, pos);
        }
        
        // Get the keys for these positions
        var keys = tetree.getSortedSpatialIndices();
        System.out.printf("Created %d nodes in the tree%n", keys.size());
        
        // With maxEntitiesPerNode=1, we should have multiple nodes
        assertTrue(keys.size() >= 4, "Should have at least 4 nodes, but got " + keys.size());
        
        // Find a non-root key
        TetreeKey<?> testKey = null;
        for (var key : keys) {
            if (key.getLevel() > 0) {
                testKey = key;
                break;
            }
        }
        
        assertNotNull(testKey, "Should find a non-root key");
        
        // Test neighbor detection methods
        var faceNeighbors = detector.findFaceNeighbors(testKey);
        var edgeNeighbors = detector.findEdgeNeighbors(testKey);
        var vertexNeighbors = detector.findVertexNeighbors(testKey);
        
        System.out.printf("Face neighbors: %d, Edge neighbors: %d, Vertex neighbors: %d%n",
                         faceNeighbors.size(), edgeNeighbors.size(), vertexNeighbors.size());
        
        // Verify hierarchy: face ⊆ edge ⊆ vertex
        assertTrue(edgeNeighbors.containsAll(faceNeighbors), 
                  "Edge neighbors should include all face neighbors");
        assertTrue(vertexNeighbors.containsAll(edgeNeighbors), 
                  "Vertex neighbors should include all edge neighbors");
        
        // If we have multiple nodes, we might have neighbors
        if (keys.size() > 1) {
            // At least one type should have neighbors
            assertTrue(faceNeighbors.size() > 0 || edgeNeighbors.size() > 0 || vertexNeighbors.size() > 0,
                      "With multiple nodes, should have some type of neighbors");
        }
    }
    
    /**
     * Test that verifies sibling vertex neighbor detection works correctly.
     * Creates a configuration where siblings share vertices and verifies detection.
     */
    @Test
    void testSiblingVertexNeighbors() {
        // Create positions that will subdivide into all 8 children of a parent
        // At level 4, cells are size 64
        int base = 256;  // Start at a nice round number
        int offset = 32; // Half of level 4 cell size
        
        var positions = new Point3f[] {
            new Point3f(base, base, base),                      // Child 0
            new Point3f(base + offset, base, base),             // Child 1
            new Point3f(base, base + offset, base),             // Child 2
            new Point3f(base + offset, base + offset, base),    // Child 3
            new Point3f(base, base, base + offset),             // Child 4
            new Point3f(base + offset, base, base + offset),    // Child 5
            new Point3f(base, base + offset, base + offset),    // Child 6
            new Point3f(base + offset, base + offset, base + offset), // Child 7
        };
        
        byte level = 4;
        for (var pos : positions) {
            tetree.insert(pos, level, pos);
        }
        
        // Get the keys for these positions
        var keys = tetree.getSortedSpatialIndices();
        
        // Find a non-root key at level > 0
        TetreeKey<?> testKey = null;
        for (var key : keys) {
            if (key.getLevel() > 0) {
                testKey = key;
                break;
            }
        }
        
        assertNotNull(testKey, "Should find a non-root key");
        
        // Get vertex neighbors
        var vertexNeighbors = detector.findVertexNeighbors(testKey);
        
        // Should have vertex neighbors
        assertFalse(vertexNeighbors.isEmpty(), "Should have vertex neighbors");
        
        // Verify vertex neighbors include edge neighbors
        var edgeNeighbors = detector.findEdgeNeighbors(testKey);
        for (var edgeNeighbor : edgeNeighbors) {
            assertTrue(vertexNeighbors.contains(edgeNeighbor), 
                      "Vertex neighbors should include all edge neighbors");
        }
        
        // Vertex neighbors should be at least as many as edge neighbors
        assertTrue(vertexNeighbors.size() >= edgeNeighbors.size(),
                  "Should have at least as many vertex neighbors as edge neighbors");
    }
    
    /**
     * Test neighbor detection at different levels of the tree.
     */
    @Test
    void testMultiLevelNeighbors() {
        // Create a deeper tree by inserting many entities at different scales
        var random = new Random(42);
        
        // Insert at multiple levels to create a varied tree
        for (int level = 3; level <= 10; level++) {
            int scale = 1 << (21 - level); // Cell size at this level
            
            // Insert several entities at this level
            // Make sure coordinates don't exceed the maximum (2^21 = 2097152)
            int maxCoord = Math.min(scale * 8, 2097152 - scale);
            for (int i = 0; i < 10; i++) {
                var pos = new Point3f(
                    random.nextInt(maxCoord / scale) * scale,
                    random.nextInt(maxCoord / scale) * scale,
                    random.nextInt(maxCoord / scale) * scale
                );
                tetree.insert(pos, (byte)level, pos);
            }
        }
        
        // Get keys at different levels
        var keysByLevel = new HashMap<Byte, List<TetreeKey<?>>>();
        for (var key : tetree.getSortedSpatialIndices()) {
            keysByLevel.computeIfAbsent(key.getLevel(), k -> new ArrayList<>()).add(key);
        }
        
        // Test neighbor detection at each level
        for (var entry : keysByLevel.entrySet()) {
            byte level = entry.getKey();
            var keysAtLevel = entry.getValue();
            
            if (level == 0) continue; // Skip root
            
            // Test first key at this level
            var testKey = keysAtLevel.get(0);
            
            var faceNeighbors = detector.findFaceNeighbors(testKey);
            var edgeNeighbors = detector.findEdgeNeighbors(testKey);
            var vertexNeighbors = detector.findVertexNeighbors(testKey);
            
            // Verify hierarchy: face ⊆ edge ⊆ vertex
            assertTrue(edgeNeighbors.containsAll(faceNeighbors),
                      "Edge neighbors should contain all face neighbors at level " + level);
            assertTrue(vertexNeighbors.containsAll(edgeNeighbors),
                      "Vertex neighbors should contain all edge neighbors at level " + level);
            
            // Verify counts make sense
            assertTrue(vertexNeighbors.size() >= edgeNeighbors.size(),
                      "Vertex neighbor count should be >= edge neighbor count at level " + level);
            assertTrue(edgeNeighbors.size() >= faceNeighbors.size(),
                      "Edge neighbor count should be >= face neighbor count at level " + level);
        }
    }
    
    /**
     * Test the enhanced non-sibling neighbor detection.
     */
    @Test
    void testNonSiblingNeighbors() {
        // Create a configuration that will have non-sibling neighbors
        // by inserting entities that cause multiple parent subdivisions
        
        // Two clusters separated enough to have different parents
        // At level 3, cells are size 128
        var positions = new Point3f[] {
            // Cluster 1 - around position 512
            new Point3f(512, 512, 512),
            new Point3f(544, 512, 512),
            new Point3f(512, 544, 512),
            new Point3f(512, 512, 544),
            
            // Cluster 2 - around position 768 (different parent)
            new Point3f(768, 512, 512),
            new Point3f(800, 512, 512),
            new Point3f(768, 544, 512),
            new Point3f(768, 512, 544),
        };
        
        byte level = 3;
        for (var pos : positions) {
            tetree.insert(pos, level, pos);
        }
        
        // The non-sibling neighbor detection is simplified in our implementation
        // but we can verify the basic structure works
        var keys = tetree.getSortedSpatialIndices();
        
        // Find keys from different parents (non-siblings)
        TetreeKey<?> key1 = null, key2 = null;
        for (var key : keys) {
            if (key.getLevel() > 0) {
                if (key1 == null) {
                    key1 = key;
                } else {
                    // Try to find a key that's not a sibling of key1
                    // This is a simple heuristic based on coordinate differences
                    key2 = key;
                    break;
                }
            }
        }
        
        if (key1 != null && key2 != null) {
            // Verify we can find neighbors for both
            var neighbors1 = detector.findVertexNeighbors(key1);
            var neighbors2 = detector.findVertexNeighbors(key2);
            
            // Both should have some neighbors
            assertFalse(neighbors1.isEmpty(), "Key1 should have neighbors");
            assertFalse(neighbors2.isEmpty(), "Key2 should have neighbors");
        }
    }
    
    /**
     * Test neighbor detection with ghost types.
     */
    @Test
    void testGhostTypeNeighbors() {
        // Create a simple tree at integer coordinates
        tetree.insert(new Point3f(100, 100, 100), (byte)5, null);
        tetree.insert(new Point3f(132, 100, 100), (byte)5, null);
        tetree.insert(new Point3f(100, 132, 100), (byte)5, null);
        tetree.insert(new Point3f(100, 100, 132), (byte)5, null);
        
        var keys = tetree.getSortedSpatialIndices();
        TetreeKey<?> testKey = null;
        for (var key : keys) {
            if (key.getLevel() > 0) {
                testKey = key;
                break;
            }
        }
        
        assertNotNull(testKey, "Should find a non-root key");
        
        // Test each ghost type
        var noneNeighbors = detector.findNeighbors(testKey, GhostType.NONE);
        var faceNeighbors = detector.findNeighbors(testKey, GhostType.FACES);
        var edgeNeighbors = detector.findNeighbors(testKey, GhostType.EDGES);
        var vertexNeighbors = detector.findNeighbors(testKey, GhostType.VERTICES);
        
        // Verify counts
        assertTrue(noneNeighbors.isEmpty(), "NONE should return no neighbors");
        assertEquals(detector.findFaceNeighbors(testKey), faceNeighbors);
        assertEquals(detector.findEdgeNeighbors(testKey), edgeNeighbors);
        assertEquals(detector.findVertexNeighbors(testKey), vertexNeighbors);
    }
    
    /**
     * Test performance of neighbor detection with larger trees.
     */
    @Test
    void testNeighborDetectionPerformance() {
        // Create a large tree with proper integer coordinates
        var random = new Random(12345);
        int entityCount = 1000;
        
        for (int i = 0; i < entityCount; i++) {
            // Use coordinates that span multiple cells at level 5
            // Max coordinate is 2097152, level 5 cell size is 32
            // So max cells is 2097152/32 = 65536, use less for safety
            var pos = new Point3f(
                random.nextInt(60) * 32,  // Level 5 cell size is 32
                random.nextInt(60) * 32,
                random.nextInt(60) * 32
            );
            tetree.insert(pos, (byte)5, pos);
        }
        
        // Sample some keys for testing
        var keys = new ArrayList<>(tetree.getSortedSpatialIndices());
        var sampleSize = Math.min(10, keys.size());
        var sampleKeys = new ArrayList<TetreeKey<?>>();
        
        for (int i = 0; i < sampleSize; i++) {
            var key = keys.get(random.nextInt(keys.size()));
            if (key.getLevel() > 0) {
                sampleKeys.add(key);
            }
        }
        
        // Time neighbor detection
        long startTime = System.nanoTime();
        int totalNeighbors = 0;
        
        for (var key : sampleKeys) {
            totalNeighbors += detector.findFaceNeighbors(key).size();
            totalNeighbors += detector.findEdgeNeighbors(key).size();
            totalNeighbors += detector.findVertexNeighbors(key).size();
        }
        
        long endTime = System.nanoTime();
        double avgTimeMs = (endTime - startTime) / 1_000_000.0 / sampleKeys.size();
        
        System.out.printf("Average neighbor detection time: %.2f ms per key%n", avgTimeMs);
        System.out.printf("Total neighbors found: %d%n", totalNeighbors);
        
        // Performance should be reasonable
        assertTrue(avgTimeMs < 10.0, "Neighbor detection should be fast (< 10ms per key)");
    }
}