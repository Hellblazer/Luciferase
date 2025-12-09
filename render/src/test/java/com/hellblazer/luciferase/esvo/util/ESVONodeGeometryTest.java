/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.util;

import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVONodeGeometry utility class.
 */
class ESVONodeGeometryTest {
    
    private static final float EPSILON = 1e-6f;
    
    @Test
    void testGetNodeLevel() {
        // Root node
        assertEquals(0, ESVONodeGeometry.getNodeLevel(0));
        
        // First level (8 children)
        for (int i = 1; i <= 8; i++) {
            assertEquals(1, ESVONodeGeometry.getNodeLevel(i), "Node " + i + " should be at level 1");
        }
        
        // Second level (64 children)
        for (int i = 9; i <= 72; i++) {
            assertEquals(2, ESVONodeGeometry.getNodeLevel(i), "Node " + i + " should be at level 2");
        }
        
        // Third level starts at 73
        assertEquals(3, ESVONodeGeometry.getNodeLevel(73));
        
        // Negative index should throw
        assertThrows(IllegalArgumentException.class, () -> ESVONodeGeometry.getNodeLevel(-1));
    }
    
    @Test
    void testGetNodeSize() {
        assertEquals(1.0f, ESVONodeGeometry.getNodeSize(0), EPSILON);
        assertEquals(0.5f, ESVONodeGeometry.getNodeSize(1), EPSILON);
        assertEquals(0.25f, ESVONodeGeometry.getNodeSize(2), EPSILON);
        assertEquals(0.125f, ESVONodeGeometry.getNodeSize(3), EPSILON);
        
        // Negative level should throw
        assertThrows(IllegalArgumentException.class, () -> ESVONodeGeometry.getNodeSize(-1));
    }
    
    @Test
    void testGetNodeBoundsRoot() {
        var bounds = ESVONodeGeometry.getNodeBounds(0, 10);
        
        assertVectorEquals(new Vector3f(0.0f, 0.0f, 0.0f), bounds.min);
        assertVectorEquals(new Vector3f(1.0f, 1.0f, 1.0f), bounds.max);
        
        var center = bounds.getCenter();
        assertVectorEquals(new Vector3f(0.5f, 0.5f, 0.5f), center);
        
        var size = bounds.getSize();
        assertVectorEquals(new Vector3f(1.0f, 1.0f, 1.0f), size);
    }
    
    @Test
    void testGetNodeBoundsFirstLevel() {
        // Test all 8 octants of first level
        var maxDepth = 10;
        
        // Octant 0: (0,0,0) - min corner
        var bounds0 = ESVONodeGeometry.getNodeBounds(1, maxDepth);
        assertVectorEquals(new Vector3f(0.0f, 0.0f, 0.0f), bounds0.min);
        assertVectorEquals(new Vector3f(0.5f, 0.5f, 0.5f), bounds0.max);
        
        // Octant 1: (1,0,0) - +x
        var bounds1 = ESVONodeGeometry.getNodeBounds(2, maxDepth);
        assertVectorEquals(new Vector3f(0.5f, 0.0f, 0.0f), bounds1.min);
        assertVectorEquals(new Vector3f(1.0f, 0.5f, 0.5f), bounds1.max);
        
        // Octant 2: (0,1,0) - +y
        var bounds2 = ESVONodeGeometry.getNodeBounds(3, maxDepth);
        assertVectorEquals(new Vector3f(0.0f, 0.5f, 0.0f), bounds2.min);
        assertVectorEquals(new Vector3f(0.5f, 1.0f, 0.5f), bounds2.max);
        
        // Octant 3: (1,1,0) - +x+y
        var bounds3 = ESVONodeGeometry.getNodeBounds(4, maxDepth);
        assertVectorEquals(new Vector3f(0.5f, 0.5f, 0.0f), bounds3.min);
        assertVectorEquals(new Vector3f(1.0f, 1.0f, 0.5f), bounds3.max);
        
        // Octant 4: (0,0,1) - +z
        var bounds4 = ESVONodeGeometry.getNodeBounds(5, maxDepth);
        assertVectorEquals(new Vector3f(0.0f, 0.0f, 0.5f), bounds4.min);
        assertVectorEquals(new Vector3f(0.5f, 0.5f, 1.0f), bounds4.max);
        
        // Octant 5: (1,0,1) - +x+z
        var bounds5 = ESVONodeGeometry.getNodeBounds(6, maxDepth);
        assertVectorEquals(new Vector3f(0.5f, 0.0f, 0.5f), bounds5.min);
        assertVectorEquals(new Vector3f(1.0f, 0.5f, 1.0f), bounds5.max);
        
        // Octant 6: (0,1,1) - +y+z
        var bounds6 = ESVONodeGeometry.getNodeBounds(7, maxDepth);
        assertVectorEquals(new Vector3f(0.0f, 0.5f, 0.5f), bounds6.min);
        assertVectorEquals(new Vector3f(0.5f, 1.0f, 1.0f), bounds6.max);
        
        // Octant 7: (1,1,1) - max corner
        var bounds7 = ESVONodeGeometry.getNodeBounds(8, maxDepth);
        assertVectorEquals(new Vector3f(0.5f, 0.5f, 0.5f), bounds7.min);
        assertVectorEquals(new Vector3f(1.0f, 1.0f, 1.0f), bounds7.max);
    }
    
    @Test
    void testGetNodeBoundsSecondLevel() {
        var maxDepth = 10;
        
        // Node 9 is first child of node 1 (which is octant 0 of root)
        // Node 1 bounds: [0,0,0] to [0.5,0.5,0.5]
        // Node 9 should be octant 0 of node 1: [0,0,0] to [0.25,0.25,0.25]
        var bounds9 = ESVONodeGeometry.getNodeBounds(9, maxDepth);
        assertVectorEquals(new Vector3f(0.0f, 0.0f, 0.0f), bounds9.min);
        assertVectorEquals(new Vector3f(0.25f, 0.25f, 0.25f), bounds9.max);
        
        // Verify size at level 2
        var size = bounds9.getSize();
        assertVectorEquals(new Vector3f(0.25f, 0.25f, 0.25f), size);
    }
    
    @Test
    void testGetNodeCenter() {
        var maxDepth = 10;
        
        // Root center
        var rootCenter = ESVONodeGeometry.getNodeCenter(0, maxDepth);
        assertVectorEquals(new Vector3f(0.5f, 0.5f, 0.5f), rootCenter);
        
        // First octant (min corner) center
        var octant0Center = ESVONodeGeometry.getNodeCenter(1, maxDepth);
        assertVectorEquals(new Vector3f(0.25f, 0.25f, 0.25f), octant0Center);
        
        // Last octant (max corner) center
        var octant7Center = ESVONodeGeometry.getNodeCenter(8, maxDepth);
        assertVectorEquals(new Vector3f(0.75f, 0.75f, 0.75f), octant7Center);
    }
    
    @Test
    void testBoundsCoversFullSpace() {
        // Verify that all 8 first-level octants cover the full unit cube without overlap
        var maxDepth = 10;
        var totalVolume = 0.0f;
        
        for (int i = 1; i <= 8; i++) {
            var bounds = ESVONodeGeometry.getNodeBounds(i, maxDepth);
            var size = bounds.getSize();
            var volume = size.x * size.y * size.z;
            totalVolume += volume;
        }
        
        // Total volume should equal unit cube volume (1.0)
        assertEquals(1.0f, totalVolume, EPSILON, "All octants should cover the full unit cube");
    }
    
    @Test
    void testMaxDepthValidation() {
        // Node at level 3 should fail if maxDepth is 2
        assertThrows(IllegalArgumentException.class, 
            () -> ESVONodeGeometry.getNodeBounds(73, 2));
    }
    
    @Test
    void testNegativeIndexThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> ESVONodeGeometry.getNodeBounds(-1, 10));
    }
    
    @Test
    void testBoundsConsistency() {
        // Verify that parent bounds contain all child bounds
        var maxDepth = 10;
        
        for (int parentIndex = 0; parentIndex < 9; parentIndex++) {
            var parentBounds = ESVONodeGeometry.getNodeBounds(parentIndex, maxDepth);
            
            // Check all 8 children
            for (int childOffset = 0; childOffset < 8; childOffset++) {
                int childIndex = parentIndex * 8 + 1 + childOffset;
                
                if (ESVONodeGeometry.getNodeLevel(childIndex) <= maxDepth) {
                    var childBounds = ESVONodeGeometry.getNodeBounds(childIndex, maxDepth);
                    
                    // Child bounds should be contained within parent bounds
                    assertTrue(childBounds.min.x >= parentBounds.min.x - EPSILON,
                        String.format("Child %d min.x should be >= parent %d min.x", childIndex, parentIndex));
                    assertTrue(childBounds.min.y >= parentBounds.min.y - EPSILON,
                        String.format("Child %d min.y should be >= parent %d min.y", childIndex, parentIndex));
                    assertTrue(childBounds.min.z >= parentBounds.min.z - EPSILON,
                        String.format("Child %d min.z should be >= parent %d min.z", childIndex, parentIndex));
                    
                    assertTrue(childBounds.max.x <= parentBounds.max.x + EPSILON,
                        String.format("Child %d max.x should be <= parent %d max.x", childIndex, parentIndex));
                    assertTrue(childBounds.max.y <= parentBounds.max.y + EPSILON,
                        String.format("Child %d max.y should be <= parent %d max.y", childIndex, parentIndex));
                    assertTrue(childBounds.max.z <= parentBounds.max.z + EPSILON,
                        String.format("Child %d max.z should be <= parent %d max.z", childIndex, parentIndex));
                }
            }
        }
    }
    
    private void assertVectorEquals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPSILON, "x component");
        assertEquals(expected.y, actual.y, EPSILON, "y component");
        assertEquals(expected.z, actual.z, EPSILON, "z component");
    }
}
