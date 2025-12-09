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

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVOTopology utility class.
 */
class ESVOTopologyTest {
    
    @Test
    void testGetRootIndex() {
        assertEquals(0, ESVOTopology.getRootIndex());
    }
    
    @Test
    void testGetParentIndex() {
        // Children of root (indices 1-8) should have parent 0
        for (int i = 1; i <= 8; i++) {
            assertEquals(0, ESVOTopology.getParentIndex(i), "Node " + i + " parent should be 0");
        }
        
        // Children of node 1 (indices 9-16) should have parent 1
        for (int i = 9; i <= 16; i++) {
            assertEquals(1, ESVOTopology.getParentIndex(i), "Node " + i + " parent should be 1");
        }
        
        // Children of node 2 (indices 17-24) should have parent 2
        for (int i = 17; i <= 24; i++) {
            assertEquals(2, ESVOTopology.getParentIndex(i), "Node " + i + " parent should be 2");
        }
        
        // Root has no parent
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getParentIndex(0));
        
        // Negative index throws
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getParentIndex(-1));
    }
    
    @Test
    void testGetChildIndices() {
        // Root's children should be 1-8
        var rootChildren = ESVOTopology.getChildIndices(0);
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8}, rootChildren);
        
        // Node 1's children should be 9-16
        var node1Children = ESVOTopology.getChildIndices(1);
        assertArrayEquals(new int[]{9, 10, 11, 12, 13, 14, 15, 16}, node1Children);
        
        // Node 2's children should be 17-24
        var node2Children = ESVOTopology.getChildIndices(2);
        assertArrayEquals(new int[]{17, 18, 19, 20, 21, 22, 23, 24}, node2Children);
        
        // Negative index throws
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getChildIndices(-1));
    }
    
    @Test
    void testGetOctantIndex() {
        // Root's children (1-8) should have octants 0-7
        for (int i = 1; i <= 8; i++) {
            assertEquals(i - 1, ESVOTopology.getOctantIndex(i), "Node " + i + " octant");
        }
        
        // Node 1's children (9-16) should also have octants 0-7
        for (int i = 9; i <= 16; i++) {
            assertEquals((i - 9), ESVOTopology.getOctantIndex(i), "Node " + i + " octant");
        }
        
        // Root has no octant
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getOctantIndex(0));
        
        // Negative index throws
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getOctantIndex(-1));
    }
    
    @Test
    void testGetNodeLevel() {
        // Root
        assertEquals(0, ESVOTopology.getNodeLevel(0));
        
        // Level 1 (indices 1-8)
        for (int i = 1; i <= 8; i++) {
            assertEquals(1, ESVOTopology.getNodeLevel(i), "Node " + i);
        }
        
        // Level 2 (indices 9-72)
        for (int i = 9; i <= 72; i++) {
            assertEquals(2, ESVOTopology.getNodeLevel(i), "Node " + i);
        }
        
        // Level 3 starts at 73
        assertEquals(3, ESVOTopology.getNodeLevel(73));
        assertEquals(3, ESVOTopology.getNodeLevel(100));
        
        // Negative index throws
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getNodeLevel(-1));
    }
    
    @Test
    void testGetSiblingIndices() {
        // Node 1's siblings should be 2-8 (all children of root except 1)
        var node1Siblings = ESVOTopology.getSiblingIndices(1);
        assertEquals(7, node1Siblings.length);
        assertArrayEquals(new int[]{2, 3, 4, 5, 6, 7, 8}, node1Siblings);
        
        // Node 5's siblings should be 1-4 and 6-8
        var node5Siblings = ESVOTopology.getSiblingIndices(5);
        assertEquals(7, node5Siblings.length);
        assertArrayEquals(new int[]{1, 2, 3, 4, 6, 7, 8}, node5Siblings);
        
        // All siblings should have same parent
        int parentIndex = ESVOTopology.getParentIndex(5);
        for (int sibling : node5Siblings) {
            assertEquals(parentIndex, ESVOTopology.getParentIndex(sibling),
                "Sibling " + sibling + " should have same parent");
        }
        
        // Root has no siblings
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getSiblingIndices(0));
        
        // Negative index throws
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getSiblingIndices(-1));
    }
    
    @Test
    void testGetFirstNodeAtLevel() {
        assertEquals(0, ESVOTopology.getFirstNodeAtLevel(0));  // Root
        assertEquals(1, ESVOTopology.getFirstNodeAtLevel(1));  // First child
        assertEquals(9, ESVOTopology.getFirstNodeAtLevel(2));  // First grandchild
        assertEquals(73, ESVOTopology.getFirstNodeAtLevel(3)); // First great-grandchild
        
        // Negative level throws
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getFirstNodeAtLevel(-1));
    }
    
    @Test
    void testGetNodeCountAtLevel() {
        assertEquals(1, ESVOTopology.getNodeCountAtLevel(0));    // 8^0 = 1
        assertEquals(8, ESVOTopology.getNodeCountAtLevel(1));    // 8^1 = 8
        assertEquals(64, ESVOTopology.getNodeCountAtLevel(2));   // 8^2 = 64
        assertEquals(512, ESVOTopology.getNodeCountAtLevel(3));  // 8^3 = 512
        
        // Negative level throws
        assertThrows(IllegalArgumentException.class, () -> ESVOTopology.getNodeCountAtLevel(-1));
    }
    
    @Test
    void testIsValidNodeIndex() {
        int maxDepth = 2;
        
        // Valid indices
        assertTrue(ESVOTopology.isValidNodeIndex(0, maxDepth));   // Root
        assertTrue(ESVOTopology.isValidNodeIndex(1, maxDepth));   // Level 1
        assertTrue(ESVOTopology.isValidNodeIndex(8, maxDepth));   // Level 1
        assertTrue(ESVOTopology.isValidNodeIndex(9, maxDepth));   // Level 2
        assertTrue(ESVOTopology.isValidNodeIndex(72, maxDepth));  // Level 2
        
        // Invalid - too deep
        assertFalse(ESVOTopology.isValidNodeIndex(73, maxDepth)); // Level 3
        
        // Invalid - negative
        assertFalse(ESVOTopology.isValidNodeIndex(-1, maxDepth));
    }
    
    @Test
    void testParentChildConsistency() {
        // Verify that parent-child relationships are consistent
        for (int parentIndex = 0; parentIndex < 73; parentIndex++) {
            var children = ESVOTopology.getChildIndices(parentIndex);
            
            // Each child should report this node as its parent
            for (int child : children) {
                assertEquals(parentIndex, ESVOTopology.getParentIndex(child),
                    String.format("Child %d should have parent %d", child, parentIndex));
            }
        }
    }
    
    @Test
    void testSiblingUniqueness() {
        // Verify siblings are unique and don't include the node itself
        for (int nodeIndex = 1; nodeIndex <= 72; nodeIndex++) {
            var siblings = ESVOTopology.getSiblingIndices(nodeIndex);
            
            // Check uniqueness
            var uniqueSiblings = new HashSet<Integer>();
            for (int sibling : siblings) {
                uniqueSiblings.add(sibling);
            }
            assertEquals(7, uniqueSiblings.size(), "Should have 7 unique siblings for node " + nodeIndex);
            
            // Node should not be in its own sibling list
            assertFalse(uniqueSiblings.contains(nodeIndex), 
                "Node " + nodeIndex + " should not be in its own sibling list");
        }
    }
    
    @Test
    void testLevelBoundaries() {
        // Verify first and last nodes of each level
        assertEquals(0, ESVOTopology.getNodeLevel(0));   // Root
        assertEquals(1, ESVOTopology.getNodeLevel(1));   // First of level 1
        assertEquals(1, ESVOTopology.getNodeLevel(8));   // Last of level 1
        assertEquals(2, ESVOTopology.getNodeLevel(9));   // First of level 2
        assertEquals(2, ESVOTopology.getNodeLevel(72));  // Last of level 2
        assertEquals(3, ESVOTopology.getNodeLevel(73));  // First of level 3
    }
}
