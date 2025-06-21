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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.OctreeNode;
import com.hellblazer.luciferase.lucien.tetree.TetreeNodeImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the unified AbstractSpatialNode implementation, verifying that both OctreeNode and TetreeNodeImpl behave
 * consistently with the unified architecture.
 *
 * @author hal.hildebrand
 */
public class UnifiedSpatialNodeTest {

    @Test
    public void testAllChildrenMask() {
        AbstractSpatialNode<LongEntityID> node = new TetreeNodeImpl<>();

        // Set all children
        for (int i = 0; i < 8; i++) {
            node.setChildBit(i);
        }

        // All bits should be set
        assertEquals((byte) 0xFF, node.getChildrenMask());
        for (int i = 0; i < 8; i++) {
            assertTrue(node.hasChild(i));
        }

        // Clear every other child
        for (int i = 0; i < 8; i += 2) {
            node.clearChildBit(i);
        }

        // Check pattern
        assertEquals((byte) 0xAA, node.getChildrenMask()); // 10101010
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 0) {
                assertFalse(node.hasChild(i));
            } else {
                assertTrue(node.hasChild(i));
            }
        }
    }

    @Test
    public void testBackwardCompatibility() {
        // Test OctreeNode octant methods
        OctreeNode<LongEntityID> octreeNode = new OctreeNode<>();
        octreeNode.setChildBit(3); // Using octant terminology
        assertTrue(octreeNode.hasChild(3));
        octreeNode.clearChildBit(3);
        assertFalse(octreeNode.hasChild(3));

        // Test TetreeNodeImpl Set view
        TetreeNodeImpl<LongEntityID> tetreeNode = new TetreeNodeImpl<>();
        LongEntityID id1 = new LongEntityID(1);
        LongEntityID id2 = new LongEntityID(2);
        tetreeNode.addEntity(id1);
        tetreeNode.addEntity(id2);

        Set<LongEntityID> entitySet = tetreeNode.getEntityIdsAsSet();
        assertEquals(2, entitySet.size());
        assertTrue(entitySet.contains(id1));
        assertTrue(entitySet.contains(id2));

        // Verify Set is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> entitySet.add(new LongEntityID(3)));
    }

    @Test
    public void testChildIndexValidation() {
        AbstractSpatialNode<LongEntityID> node = new OctreeNode<>();

        // Valid indices
        for (int i = 0; i < 8; i++) {
            final int index = i;
            assertDoesNotThrow(() -> node.setChildBit(index));
            assertDoesNotThrow(() -> node.hasChild(index));
            assertDoesNotThrow(() -> node.clearChildBit(index));
        }

        // Invalid indices
        final AbstractSpatialNode<LongEntityID> finalNode = node;
        assertThrows(IllegalArgumentException.class, () -> finalNode.setChildBit(-1));
        assertThrows(IllegalArgumentException.class, () -> finalNode.setChildBit(8));
        assertThrows(IllegalArgumentException.class, () -> finalNode.hasChild(-1));
        assertThrows(IllegalArgumentException.class, () -> finalNode.hasChild(8));
        assertThrows(IllegalArgumentException.class, () -> finalNode.clearChildBit(-1));
        assertThrows(IllegalArgumentException.class, () -> finalNode.clearChildBit(8));
    }

    @Test
    public void testChildMaskBitOperations() {
        AbstractSpatialNode<LongEntityID> node = new TetreeNodeImpl<>();

        // Test individual bit operations
        for (int i = 0; i < 8; i++) {
            node.setChildBit(i);
            assertEquals(1 << i, node.getChildrenMask() & (1 << i));

            // Set another bit shouldn't affect this one
            if (i < 7) {
                node.setChildBit(i + 1);
                assertTrue(node.hasChild(i));
                assertTrue(node.hasChild(i + 1));
            }

            // Clear and verify
            node.clearChildBit(i);
            assertFalse(node.hasChild(i));
        }
    }

    @Test
    public void testChildTracking() {
        // Test both node types
        testChildTrackingForNode(new OctreeNode<>());
        testChildTrackingForNode(new TetreeNodeImpl<>());
    }

    @Test
    public void testEntityOrderPreservation() {
        AbstractSpatialNode<LongEntityID> node = new OctreeNode<>();

        // Add entities in specific order
        List<LongEntityID> insertOrder = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            LongEntityID id = new LongEntityID(i);
            insertOrder.add(id);
            node.addEntity(id);
        }

        // Verify order is preserved
        List<LongEntityID> retrievedOrder = new ArrayList<>(node.getEntityIds());
        assertEquals(insertOrder, retrievedOrder);
    }

    @Test
    public void testEntityStorage() {
        // Test both node types
        testEntityStorageForNode(new OctreeNode<>());
        testEntityStorageForNode(new TetreeNodeImpl<>());
    }

    @Test
    public void testMutableEntityAccess() {
        // This tests the protected getMutableEntityIds method
        // We'll use a test subclass to access it
        TestNode<LongEntityID> node = new TestNode<>();

        LongEntityID id1 = new LongEntityID(1);
        LongEntityID id2 = new LongEntityID(2);
        node.addEntity(id1);
        node.addEntity(id2);

        // Get mutable list
        List<LongEntityID> mutableList = node.getMutableEntityIds();
        assertEquals(2, mutableList.size());

        // Verify it's actually mutable
        mutableList.add(new LongEntityID(3));
        assertEquals(3, node.getEntityCount());

        // Verify immutable view is still immutable
        assertThrows(UnsupportedOperationException.class, () -> node.getEntityIds().add(new LongEntityID(4)));
    }

    @Test
    public void testNullEntityHandling() {
        AbstractSpatialNode<LongEntityID> node = new TetreeNodeImpl<>();

        // Null checks
        assertThrows(IllegalArgumentException.class, () -> node.addEntity(null));
        assertFalse(node.removeEntity(null));
        assertFalse(node.containsEntity(null));
    }

    @Test
    public void testSetHasChildren() {
        AbstractSpatialNode<LongEntityID> node = new OctreeNode<>();

        // Test that existing bits are preserved
        node.setChildBit(3);
        node.setChildBit(5); // Should not change existing bits
        assertTrue(node.hasChild(3));
        assertTrue(node.hasChild(5));
    }

    @Test
    public void testSplitThreshold() {
        // Test with custom threshold
        OctreeNode<LongEntityID> octreeNode = new OctreeNode<>(3);
        TetreeNodeImpl<LongEntityID> tetreeNode = new TetreeNodeImpl<>(3);

        testSplitThresholdForNode(octreeNode, 3);
        testSplitThresholdForNode(tetreeNode, 3);
    }

    private void testChildTrackingForNode(AbstractSpatialNode<LongEntityID> node) {
        // Initially no children
        assertFalse(node.hasChildren());
        for (int i = 0; i < 8; i++) {
            assertFalse(node.hasChild(i));
        }
        assertEquals((byte) 0, node.getChildrenMask());

        // Set some children
        node.setChildBit(0);
        assertTrue(node.hasChild(0));
        assertTrue(node.hasChildren());
        assertEquals((byte) 1, node.getChildrenMask());

        node.setChildBit(3);
        assertTrue(node.hasChild(3));
        assertEquals((byte) 9, node.getChildrenMask()); // bits 0 and 3 set

        node.setChildBit(7);
        assertTrue(node.hasChild(7));
        assertEquals((byte) 137, node.getChildrenMask()); // bits 0, 3, and 7 set

        // Clear a child
        node.clearChildBit(3);
        assertFalse(node.hasChild(3));
        assertTrue(node.hasChild(0));
        assertTrue(node.hasChild(7));
        assertEquals((byte) 129, node.getChildrenMask()); // bits 0 and 7 set

        // Clear all children
        node.clearChildBit(0);
        node.clearChildBit(7);
        assertFalse(node.hasChildren());
        assertEquals((byte) 0, node.getChildrenMask());
    }

    private void testEntityStorageForNode(AbstractSpatialNode<LongEntityID> node) {
        assertTrue(node.isEmpty());
        assertEquals(0, node.getEntityCount());

        // Add entities
        LongEntityID id1 = new LongEntityID(1);
        LongEntityID id2 = new LongEntityID(2);
        LongEntityID id3 = new LongEntityID(3);

        assertFalse(node.addEntity(id1)); // Should not trigger split
        assertEquals(1, node.getEntityCount());
        assertTrue(node.containsEntity(id1));

        node.addEntity(id2);
        node.addEntity(id3);
        assertEquals(3, node.getEntityCount());

        // Test entity retrieval
        List<LongEntityID> entities = new ArrayList<>(node.getEntityIds());
        assertEquals(3, entities.size());
        assertTrue(entities.contains(id1));
        assertTrue(entities.contains(id2));
        assertTrue(entities.contains(id3));

        // Test removal
        assertTrue(node.removeEntity(id2));
        assertFalse(node.containsEntity(id2));
        assertEquals(2, node.getEntityCount());

        // Test clear
        node.clearEntities();
        assertTrue(node.isEmpty());
        assertEquals(0, node.getEntityCount());
    }

    private void testSplitThresholdForNode(AbstractSpatialNode<LongEntityID> node, int threshold) {
        assertEquals(threshold, node.getMaxEntitiesBeforeSplit());

        // Add entities up to threshold
        for (int i = 1; i <= threshold; i++) {
            assertFalse(node.addEntity(new LongEntityID(i)));
            assertFalse(node.shouldSplit());
        }

        // Next entity should trigger split
        assertTrue(node.addEntity(new LongEntityID(threshold + 1)));
        assertTrue(node.shouldSplit());
    }

    // Test subclass to access protected methods
    private static class TestNode<ID extends com.hellblazer.luciferase.lucien.entity.EntityID>
    extends AbstractSpatialNode<ID> {
        @Override
        public List<ID> getMutableEntityIds() {
            return super.getMutableEntityIds();
        }
    }
}
