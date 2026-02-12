/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Phase 2 (Navigable Hierarchy) of Tetrahedral Hierarchical Forest Enhancement.
 *
 * Tests hierarchy preservation, navigation methods, and query filtering with isLeaf().
 *
 * @author hal.hildebrand
 */
class HierarchyNavigationForestTest {

    private static final Logger log = LoggerFactory.getLogger(HierarchyNavigationForestTest.class);

    private AdaptiveForest<MortonKey, LongEntityID, String> forest;
    private EntityIDGenerator<LongEntityID> idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new EntityIDGenerator<>() {
            private long counter = 0;

            @Override
            public LongEntityID generateID() {
                return new LongEntityID(counter++);
            }
        };

        var forestConfig = ForestConfig.defaultConfig();
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .maxEntitiesPerTree(50) // Low threshold to trigger subdivision
            .minEntitiesPerTree(10)
            .enableAutoSubdivision(false) // Disable auto for manual testing
            .enableAutoMerging(false)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.OCTANT)
            .build();

        forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator);

        log.info("Test setup complete");
    }

    /**
     * P2.2: Test that parent tree is preserved after octant subdivision.
     * Parent should remain in forest with isSubdivided=true and 8 children.
     */
    @Test
    void testParentPreservedAfterOctantSubdivision() {
        // Create parent tree with octree
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("ParentTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var parentId = forest.addTree(octree, metadata);
        var parentTree = forest.getTree(parentId);
        assertNotNull(parentTree);

        // Add entities to trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        int treeCountBefore = forest.getTreeCount();
        log.info("Trees before subdivision: {}", treeCountBefore);

        // Manually trigger subdivision (access private method via reflection for testing)
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        // Verify parent still exists
        var parentAfter = forest.getTree(parentId);
        assertNotNull(parentAfter, "Parent tree should still exist after subdivision");

        // Verify parent is marked as subdivided
        assertTrue(parentAfter.isSubdivided(), "Parent should be marked as subdivided");

        // Verify parent has 8 children
        var childIds = parentAfter.getChildTreeIds();
        assertEquals(8, childIds.size(), "Parent should have 8 children from octant subdivision");

        // Verify all children exist
        for (var childId : childIds) {
            var child = forest.getTree(childId);
            assertNotNull(child, "Child tree should exist: " + childId);
        }

        // Verify forest has 9 trees total (1 parent + 8 children)
        assertEquals(treeCountBefore + 8, forest.getTreeCount(),
                    "Forest should have 8 additional trees (children)");

        log.info("Parent preserved test passed: parent exists with {} children", childIds.size());
    }

    /**
     * P2.2: Test that parent-child links are bidirectional.
     * Parent should have childTreeIds list, each child should have parentTreeId.
     */
    @Test
    void testParentChildLinksBidirectional() {
        // Create and subdivide parent
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("ParentTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var parentId = forest.addTree(octree, metadata);

        // Add entities and trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        // Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        var parentTree = forest.getTree(parentId);
        var childIds = parentTree.getChildTreeIds();

        // Verify each child points back to parent
        for (var childId : childIds) {
            var child = forest.getTree(childId);
            assertEquals(parentId, child.getParentTreeId(),
                        "Child should have parentTreeId pointing to parent");
        }

        // Verify parent has all children in childTreeIds
        assertEquals(8, childIds.size(), "Parent should have all 8 children in childTreeIds");

        log.info("Bidirectional linking test passed");
    }

    /**
     * P2.2: Test that hierarchy levels propagate correctly.
     * Children should have level = parent.level + 1.
     */
    @Test
    void testHierarchyLevelPropagation() {
        // Create root tree (level 0)
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("RootTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var rootId = forest.addTree(octree, metadata);
        var rootTree = forest.getTree(rootId);

        assertEquals(0, rootTree.getHierarchyLevel(), "Root should have level 0");

        // Add entities and trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(rootId, entityId, pos);
        }

        // Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, rootId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        var childIds = rootTree.getChildTreeIds();

        // Verify all children have level 1
        for (var childId : childIds) {
            var child = forest.getTree(childId);
            assertEquals(1, child.getHierarchyLevel(),
                        "Child should have level = parent.level + 1 = 1");
        }

        log.info("Hierarchy level propagation test passed");
    }

    /**
     * P2.2: Test that isLeaf() is correct after subdivision.
     * Parent should NOT be a leaf, children should be leaves.
     */
    @Test
    void testIsLeafAfterSubdivision() {
        // Create and subdivide parent
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("ParentTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var parentId = forest.addTree(octree, metadata);
        var parentBefore = forest.getTree(parentId);

        // Parent should be a leaf initially
        assertTrue(parentBefore.isLeaf(), "Parent should be leaf before subdivision");

        // Add entities and trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        // Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        var parentAfter = forest.getTree(parentId);

        // Parent should NOT be a leaf after subdivision
        assertFalse(parentAfter.isLeaf(), "Parent should NOT be leaf after subdivision");

        // All children should be leaves
        for (var childId : parentAfter.getChildTreeIds()) {
            var child = forest.getTree(childId);
            assertTrue(child.isLeaf(), "Child should be leaf (has no children)");
        }

        log.info("isLeaf() test passed");
    }

    /**
     * P2.2: Test root detection.
     * Root trees should have isRoot()=true, children should have isRoot()=false.
     */
    @Test
    void testRootDetection() {
        // Create root tree
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("RootTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var rootId = forest.addTree(octree, metadata);
        var rootTree = forest.getTree(rootId);

        assertTrue(rootTree.isRoot(), "Tree with no parent should be root");

        // Add entities and trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(rootId, entityId, pos);
        }

        // Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, rootId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        // Root should still be root
        assertTrue(rootTree.isRoot(), "Parent should still be root after subdivision");

        // Children should NOT be roots
        for (var childId : rootTree.getChildTreeIds()) {
            var child = forest.getTree(childId);
            assertFalse(child.isRoot(), "Child should NOT be root");
        }

        log.info("Root detection test passed");
    }

    /**
     * P2.3: Test getAncestors() traces leaf to root.
     */
    @Test
    void testGetAncestors() {
        // Create root tree
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("RootTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var rootId = forest.addTree(octree, metadata);

        // Add entities and trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(rootId, entityId, pos);
        }

        // Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, rootId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        var rootTree = forest.getTree(rootId);
        var childIds = rootTree.getChildTreeIds();
        var childId = childIds.get(0); // Pick first child

        // Get ancestors from child
        var ancestors = forest.getAncestors(childId);

        // Should have exactly 1 ancestor (the root)
        assertEquals(1, ancestors.size(), "Child should have exactly 1 ancestor");

        // Ancestor should be the root
        assertEquals(rootId, ancestors.get(0).getTreeId(), "Ancestor should be root");

        log.info("getAncestors test passed");
    }

    /**
     * P2.3: Test getDescendants() returns complete subtree.
     */
    @Test
    void testGetDescendants() {
        // Create root tree
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("RootTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var rootId = forest.addTree(octree, metadata);

        // Add entities and trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(rootId, entityId, pos);
        }

        // Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, rootId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        // Get descendants from root
        var descendants = forest.getDescendants(rootId);

        // Should have exactly 8 descendants (all children)
        assertEquals(8, descendants.size(), "Root should have 8 descendants");

        // All descendants should be children
        var rootTree = forest.getTree(rootId);
        var childIds = rootTree.getChildTreeIds();
        for (var desc : descendants) {
            assertTrue(childIds.contains(desc.getTreeId()),
                      "Descendant should be a child of root");
        }

        log.info("getDescendants test passed");
    }

    /**
     * P2.3: Test getSubtree() includes root + all descendants.
     */
    @Test
    void testGetSubtree() {
        // Create root tree
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("RootTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var rootId = forest.addTree(octree, metadata);

        // Add entities and trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(rootId, entityId, pos);
        }

        // Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, rootId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        // Get subtree from root
        var subtree = forest.getSubtree(rootId);

        // Should have exactly 9 nodes (1 root + 8 children)
        assertEquals(9, subtree.size(), "Subtree should include root + 8 children");

        // First node should be root
        assertEquals(rootId, subtree.get(0).getTreeId(), "First node should be root");

        log.info("getSubtree test passed");
    }

    /**
     * P2.3: Test getLeaves() returns only leaf nodes.
     */
    @Test
    void testGetLeaves() {
        // Create root tree
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("RootTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var rootId = forest.addTree(octree, metadata);

        // Add entities and trigger subdivision
        for (int i = 0; i < 60; i++) {
            var pos = new Point3f(i * 1.5f, i * 1.5f, i * 1.5f);
            var entityId = idGenerator.generateID();
            octree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(rootId, entityId, pos);
        }

        // Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, rootId);
        } catch (Exception e) {
            fail("Failed to invoke considerSubdivision: " + e.getMessage());
        }

        // Get all leaves
        var leaves = forest.getLeaves();

        // Should have exactly 8 leaves (all children)
        assertEquals(8, leaves.size(), "Forest should have 8 leaf nodes");

        // All leaves should have no children
        for (var leaf : leaves) {
            assertTrue(leaf.isLeaf(), "Leaf node should have no children");
        }

        // Root should NOT be in leaves
        assertFalse(leaves.stream().anyMatch(n -> n.getTreeId().equals(rootId)),
                   "Root should not be in leaves (it has children)");

        log.info("getLeaves test passed");
    }
}
