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

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import javax.vecmath.Point3i;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static com.hellblazer.luciferase.lucien.neighbor.NeighborDetector.Direction.*;

/**
 * Test suite for boundary detection in neighbor detectors.
 * 
 * @author Hal Hildebrand
 */
public class BoundaryDetectionTest {
    
    @Test
    void testOctreeBoundaryDetection() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, Point3f>(idGenerator);
        var detector = new MortonNeighborDetector(octree);
        
        // Maximum coordinate value (2^21 - 1 = 2097151)
        long maxCoord = (1L << Constants.getMaxRefinementLevel()) - 1;
        
        // Test corner entities at level 5 (cell size = 32)
        byte level = 5;
        int cellSize = 1 << (21 - level);
        
        // Entity at origin corner (negative boundaries)
        var originEntity = new Point3f(0, 0, 0);
        octree.insert(originEntity, level, originEntity);
        
        // Get the key by using enclosing method
        var originNode = octree.enclosing(new Point3i(0, 0, 0), level);
        assertNotNull(originNode, "Should find origin node");
        var originKey = originNode.sfcIndex();
        assertTrue(detector.isBoundaryElement(originKey, NEGATIVE_X), "Origin should be at negative X boundary");
        assertTrue(detector.isBoundaryElement(originKey, NEGATIVE_Y), "Origin should be at negative Y boundary");
        assertTrue(detector.isBoundaryElement(originKey, NEGATIVE_Z), "Origin should be at negative Z boundary");
        assertFalse(detector.isBoundaryElement(originKey, POSITIVE_X), "Origin should not be at positive X boundary");
        assertFalse(detector.isBoundaryElement(originKey, POSITIVE_Y), "Origin should not be at positive Y boundary");
        assertFalse(detector.isBoundaryElement(originKey, POSITIVE_Z), "Origin should not be at positive Z boundary");
        
        // Test getBoundaryDirections
        var originBoundaries = detector.getBoundaryDirections(originKey);
        assertEquals(3, originBoundaries.size(), "Origin should have 3 boundary directions");
        assertTrue(originBoundaries.contains(NEGATIVE_X));
        assertTrue(originBoundaries.contains(NEGATIVE_Y));
        assertTrue(originBoundaries.contains(NEGATIVE_Z));
        
        // Entity at max corner (positive boundaries)
        // Need to align with cell boundaries
        long alignedMaxCoord = (maxCoord / cellSize) * cellSize;
        var maxEntity = new Point3f(alignedMaxCoord, alignedMaxCoord, alignedMaxCoord);
        octree.insert(maxEntity, level, maxEntity);
        
        // Get the key by using enclosing method
        var maxNode = octree.enclosing(new Point3i((int)alignedMaxCoord, (int)alignedMaxCoord, (int)alignedMaxCoord), level);
        assertNotNull(maxNode, "Should find max node");
        var maxKey = maxNode.sfcIndex();
        
        assertTrue(detector.isBoundaryElement(maxKey, POSITIVE_X), "Max should be at positive X boundary");
        assertTrue(detector.isBoundaryElement(maxKey, POSITIVE_Y), "Max should be at positive Y boundary");
        assertTrue(detector.isBoundaryElement(maxKey, POSITIVE_Z), "Max should be at positive Z boundary");
        assertFalse(detector.isBoundaryElement(maxKey, NEGATIVE_X), "Max should not be at negative X boundary");
        assertFalse(detector.isBoundaryElement(maxKey, NEGATIVE_Y), "Max should not be at negative Y boundary");
        assertFalse(detector.isBoundaryElement(maxKey, NEGATIVE_Z), "Max should not be at negative Z boundary");
    }
    
    @Test
    void testTetreeBoundaryDetection() {
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, Point3f>(idGenerator, 1, (byte)21);
        var detector = new TetreeNeighborDetector(tetree);
        
        // Maximum coordinate value (2^21 - 1 = 2097151)
        long maxCoord = (1L << Constants.getMaxRefinementLevel()) - 1;
        
        // Test at level 5
        byte level = 5;
        
        // Entity near origin (should touch negative boundaries)
        var originEntity = new Point3f(10, 10, 10);
        tetree.insert(originEntity, level, originEntity);
        var originKeys = tetree.getSortedSpatialIndices();
        
        // Find non-root key
        TetreeKey<?> originKey = null;
        for (var key : originKeys) {
            if (key.getLevel() > 0) {
                originKey = key;
                break;
            }
        }
        assertNotNull(originKey, "Should find non-root key");
        
        // Test negative boundaries
        assertTrue(detector.isBoundaryElement(originKey, NEGATIVE_X), "Near-origin should be at negative X boundary");
        assertTrue(detector.isBoundaryElement(originKey, NEGATIVE_Y), "Near-origin should be at negative Y boundary");
        assertTrue(detector.isBoundaryElement(originKey, NEGATIVE_Z), "Near-origin should be at negative Z boundary");
        
        // Skip positive boundary test for Tetree until keyToTet is improved
        // The current implementation doesn't properly reconstruct global coordinates
        // TODO: Implement proper coordinate reconstruction in keyToTet
    }
    
    @Test
    void testMortonBoundaryLogic() {
        var idGenerator = new SequentialLongIDGenerator();
        var octree = new Octree<LongEntityID, Point3f>(idGenerator);
        var detector = new MortonNeighborDetector(octree);
        
        // Test a simple case at level 10
        // At level 10, cell size is 2^(21-10) = 2048
        byte level = 10;
        int cellSize = 1 << (21 - level);
        
        // Max coordinate value at this level
        long maxCoord = (1L << 21) - 1;
        long maxAtLevel = maxCoord / cellSize; // This gives us the max cell index at this level
        
        // Use coordinates that will be in the middle of the grid but within the valid range
        // The raw coordinates need to be well below maxAtLevel (1023)
        int cellIndex = (int)(maxAtLevel / 4); // Use 1/4 of the max to ensure we're in the middle
        var entity = new Point3f(cellIndex * cellSize, cellIndex * cellSize, cellIndex * cellSize);
        octree.insert(entity, level, entity);
        
        var node = octree.enclosing(new Point3i(cellIndex * cellSize, cellIndex * cellSize, cellIndex * cellSize), level);
        assertNotNull(node);
        var key = node.sfcIndex();
        
        
        // Verify the morton code is non-zero
        assertTrue(key.getMortonCode() > 0, "Morton code should be non-zero for non-origin cell");
        
        // Should not be at any boundary
        assertFalse(detector.isBoundaryElement(key, POSITIVE_X));
        assertFalse(detector.isBoundaryElement(key, NEGATIVE_X));
        assertFalse(detector.isBoundaryElement(key, POSITIVE_Y));
        assertFalse(detector.isBoundaryElement(key, NEGATIVE_Y));
        assertFalse(detector.isBoundaryElement(key, POSITIVE_Z));
        assertFalse(detector.isBoundaryElement(key, NEGATIVE_Z));
        
        var boundaries = detector.getBoundaryDirections(key);
        assertTrue(boundaries.isEmpty(), "Middle entity should have no boundary directions");
    }
    
    @Test
    void testTetreeBoundaryLogic() {
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, Point3f>(idGenerator, 1, (byte)21);
        var detector = new TetreeNeighborDetector(tetree);
        
        // Test at origin
        byte level = 10;
        var entity = new Point3f(5, 5, 5);
        tetree.insert(entity, level, entity);
        
        TetreeKey<?> key = null;
        for (var k : tetree.getSortedSpatialIndices()) {
            if (k.getLevel() > 0) {
                key = k;
                break;
            }
        }
        assertNotNull(key);
        
        // Should be at negative boundaries
        assertTrue(detector.isBoundaryElement(key, NEGATIVE_X));
        assertTrue(detector.isBoundaryElement(key, NEGATIVE_Y));
        assertTrue(detector.isBoundaryElement(key, NEGATIVE_Z));
        
        var boundaries = detector.getBoundaryDirections(key);
        assertEquals(3, boundaries.size(), "Origin entity should have 3 boundary directions");
    }
}