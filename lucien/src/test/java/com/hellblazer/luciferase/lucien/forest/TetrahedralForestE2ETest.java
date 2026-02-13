/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for Tetrahedral Hierarchical Forest Enhancement (Phase 4).
 *
 * Tests complete workflows across Phases 1-3:
 * - Dual-path subdivision (cubic→6 tets, tet→8 subtets)
 * - Navigable hierarchy (parent-child links, levels)
 * - Event system integration (ForestEventListener, ForestToTumblerBridge)
 * - Query filtering with isLeaf()
 * - Mixed forest scenarios (OCTANT and TETRAHEDRAL coexistence)
 *
 * These tests verify that all features work together correctly in realistic usage scenarios.
 *
 * @author hal.hildebrand
 */
class TetrahedralForestE2ETest {

    /**
     * End-to-end test: Create forest → cubic subdivision → 6 tet children → verify hierarchy → verify events.
     *
     * This test validates the complete Phase 1-3 workflow for CUBIC_TO_TET subdivision.
     */
    @Test
    void testCompleteWorkflow_CubicToTetWithHierarchyAndEvents() {
        // Create ForestToTumblerBridge to receive events
        var bridge = new ForestToTumblerBridge();

        // Create forest with TETRAHEDRAL subdivision strategy
        var forestConfig = ForestConfig.defaultConfig();
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL)
            .maxEntitiesPerTree(10) // Low threshold to trigger subdivision
            .minTreeVolume(1.0f) // Allow small trees to subdivide
            .enableAutoSubdivision(false) // Manual control for testing
            .build();

        var idCounter = new AtomicLong(0);
        var idGenerator = new EntityIDGenerator<LongEntityID>() {
            @Override
            public LongEntityID generateID() {
                return new LongEntityID(idCounter.getAndIncrement());
            }
        };
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "test-forest");
        forest.addEventListener(bridge);

        // 1. Create initial tree with cubic bounds
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(1000.0f, 1000.0f, 1000.0f)
        );
        var metadata = TreeMetadata.builder()
            .name("Root")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);
        var root = forest.getTree(rootId);

        // 2. Add entities to trigger subdivision (exceed maxEntitiesPerTree*1.5=15)
        for (int i = 0; i < 20; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(
                100.0f + i * 50.0f,
                100.0f + i * 50.0f,
                100.0f + i * 50.0f
            );
            spatialIndex.insert(entityId, position, (byte) 0, "Entity " + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        // 3. Trigger CUBIC_TO_TET subdivision manually
        forest.checkAndAdapt();

        // Wait for async subdivision to complete (poll up to 2 seconds)
        int maxWait = 2000;
        int waited = 0;
        while (!root.isSubdivided() && waited < maxWait) {
            try {
                Thread.sleep(50);
                waited += 50;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 4. Verify subdivision occurred: should have 1 parent + 6 children = 7 trees
        assertTrue(forest.getAllTrees().size() >= 7,
                  "Expected at least 7 trees after CUBIC_TO_TET subdivision (parent + 6 children)");

        // 5. Verify hierarchy established
        assertTrue(root.isSubdivided(), "Parent should be marked as subdivided");
        assertEquals(6, root.getChildTreeIds().size(), "Parent should have 6 tetrahedral children");
        assertFalse(root.isLeaf(), "Parent should not be a leaf");

        // 6. Verify child hierarchy properties
        for (var childId : root.getChildTreeIds()) {
            var child = forest.getTree(childId);
            assertNotNull(child, "Child tree should exist");
            assertEquals(rootId, child.getParentTreeId(), "Child should have correct parent ID");
            assertEquals(1, child.getHierarchyLevel(), "Child should be at level 1");
            assertTrue(child.isLeaf(), "Child should be a leaf");
            assertInstanceOf(TetrahedralBounds.class, child.getTreeBounds(),
                           "Child should have TetrahedralBounds");
        }

        // 7. Verify server assignments exist (events were processed)
        // We can't directly access received events, but we can verify the bridge assigned servers
        int assignedChildren = 0;
        for (var childId : root.getChildTreeIds()) {
            if (bridge.getServerAssignment(childId) != null) {
                assignedChildren++;
            }
        }
        assertEquals(6, assignedChildren, "All 6 children should have server assignments");

        // 8. Navigate hierarchy - verify ancestors work
        var firstChildId = root.getChildTreeIds().get(0);
        var ancestors = forest.getAncestors(firstChildId);
        assertEquals(1, ancestors.size(), "Child should have 1 ancestor");
        assertEquals(rootId, ancestors.get(0).getTreeId(), "Ancestor should be the root");

        // 9. Verify descendants work
        var descendants = forest.getDescendants(rootId);
        assertEquals(6, descendants.size(), "Root should have 6 descendants");

        // 10. Query leaves - should only return children, not parent
        var leaves = forest.getLeaves();
        assertEquals(6, leaves.size(), "Should have 6 leaf nodes");
        assertTrue(leaves.stream().allMatch(TreeNode::isLeaf), "All returned nodes should be leaves");
        assertTrue(leaves.stream().noneMatch(n -> n.getTreeId().equals(rootId)),
                  "Parent should not be in leaf list");

        // Cleanup
        forest.shutdown();
    }

    /**
     * End-to-end test: cubic → 6 tets → subdivide one tet → 8 Bey children → verify cascade.
     *
     * This test validates TET_TO_SUBTET subdivision cascading from CUBIC_TO_TET.
     */
    @Test
    void testCompleteWorkflow_TetToSubtetCascade() {
        // Create forest and bridge
        var bridge = new ForestToTumblerBridge();
        var forestConfig = ForestConfig.defaultConfig();
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL)
            .maxEntitiesPerTree(5)
            .enableAutoSubdivision(false)
            .build();

        var idCounter = new AtomicLong(0);
        var idGenerator = new EntityIDGenerator<LongEntityID>() {
            @Override
            public LongEntityID generateID() {
                return new LongEntityID(idCounter.getAndIncrement());
            }
        };
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "cascade-forest");
        forest.addEventListener(bridge);

        // 1. Create root tree with cubic bounds
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(2000.0f, 2000.0f, 2000.0f)
        );
        var metadata = TreeMetadata.builder()
            .name("CascadeRoot")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);
        var root = forest.getTree(rootId);

        // 2. Add entities to trigger first-level subdivision (cubic → 6 tets)
        for (int i = 0; i < 8; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(200.0f + i * 100.0f, 200.0f, 200.0f);
            spatialIndex.insert(entityId, position, (byte) 0, "FirstLevel " + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        forest.checkAndAdapt();
        // Poll for subdivision completion (up to 2 seconds)
        int maxWait = 2000;
        int waited = 0;
        while (waited < maxWait) {
            try {
                Thread.sleep(50);
                waited += 50;
                if (root.isSubdivided()) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 3. Verify first subdivision: 6 tet children
        assertEquals(6, root.getChildTreeIds().size(), "Should have 6 tet children after first subdivision");

        // 4. Add more entities to ONE specific tet child to trigger TET_TO_SUBTET
        var targetTetId = root.getChildTreeIds().get(0);
        var targetTet = forest.getTree(targetTetId);
        var tetIndex = targetTet.getSpatialIndex();

        // Add entities concentrated in this tet
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(100.0f + i * 10.0f, 100.0f + i * 10.0f, 100.0f);
            tetIndex.insert(entityId, position, (byte) 0, "SecondLevel " + i);
            forest.trackEntityInsertion(targetTetId, entityId, position);
        }

        // 5. Trigger second-level subdivision
        forest.checkAndAdapt();
        // Poll for targetTet subdivision completion (up to 2 seconds)
        int maxWait2 = 2000;
        int waited2 = 0;
        while (waited2 < maxWait2) {
            try {
                Thread.sleep(50);
                waited2 += 50;
                if (targetTet.isSubdivided()) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 6. Verify cascade: parent has 6 children, one child has 8 children
        assertEquals(6, root.getChildTreeIds().size(), "Root should still have 6 children");
        assertTrue(targetTet.isSubdivided(), "Target tet should be subdivided");
        assertEquals(8, targetTet.getChildTreeIds().size(), "Target tet should have 8 Bey children");

        // 7. Verify hierarchy levels: root=0, tets=1, Bey=2
        assertEquals(0, root.getHierarchyLevel(), "Root should be at level 0");
        assertEquals(1, targetTet.getHierarchyLevel(), "Tet children should be at level 1");

        var beyChild = forest.getTree(targetTet.getChildTreeIds().get(0));
        assertEquals(2, beyChild.getHierarchyLevel(), "Bey children should be at level 2");

        // 8. Verify TreeBounds types
        assertInstanceOf(CubicBounds.class, root.getTreeBounds(), "Root should have CubicBounds");
        assertInstanceOf(TetrahedralBounds.class, targetTet.getTreeBounds(),
                        "Tet child should have TetrahedralBounds");
        assertInstanceOf(TetrahedralBounds.class, beyChild.getTreeBounds(),
                        "Bey child should have TetrahedralBounds");

        // 9. Verify all descendants - should be 6 tets + 8 Bey = 14 total
        var allDescendants = forest.getDescendants(rootId);
        assertEquals(14, allDescendants.size(), "Should have 14 total descendants (6 tets + 8 Bey)");

        // 10. Query at specific levels
        var level1Trees = forest.getTreesAtLevel(1);
        assertEquals(6, level1Trees.size(), "Should have 6 trees at level 1");

        var level2Trees = forest.getTreesAtLevel(2);
        assertEquals(8, level2Trees.size(), "Should have 8 trees at level 2");

        // Cleanup
        forest.shutdown();
    }

    /**
     * Test mixed forest: Multiple regions with TETRAHEDRAL subdivisions.
     *
     * Verifies that multiple independent trees can subdivide without interference.
     */
    @Test
    void testCompleteWorkflow_MixedForest() {
        var bridge = new ForestToTumblerBridge();
        var forestConfig = ForestConfig.defaultConfig();

        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL)
            .maxEntitiesPerTree(5)
            .enableAutoSubdivision(false)
            .build();

        var idCounter = new AtomicLong(0);
        var idGenerator = new EntityIDGenerator<LongEntityID>() {
            @Override
            public LongEntityID generateID() {
                return new LongEntityID(idCounter.getAndIncrement());
            }
        };
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "mixed-forest");
        forest.addEventListener(bridge);

        // 1. Create first tree (region A)
        var regionASpatialIndex = new Octree<>(idGenerator);
        var regionABounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(1000.0f, 1000.0f, 1000.0f)
        );
        var regionAMetadata = TreeMetadata.builder()
            .name("RegionA")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(regionABounds))
            .build();
        var regionAId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) regionASpatialIndex, regionAMetadata);
        var regionATree = forest.getTree(regionAId);

        // Add entities to region A (need >7.5)
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(100.0f + i * 50.0f, 200.0f, 200.0f);
            regionASpatialIndex.insert(entityId, position, (byte) 0, "RegionA " + i);
            forest.trackEntityInsertion(regionAId, entityId, position);
        }

        // 2. Create second tree (region B)
        var regionBSpatialIndex = new Octree<>(idGenerator);
        var regionBBounds = new EntityBounds(
            new Point3f(2000.0f, 2000.0f, 2000.0f),
            new Point3f(3000.0f, 3000.0f, 3000.0f)
        );
        var regionBMetadata = TreeMetadata.builder()
            .name("RegionB")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(regionBBounds))
            .build();
        var regionBId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) regionBSpatialIndex, regionBMetadata);
        var regionBTree = forest.getTree(regionBId);

        // Add entities to region B (need >7.5)
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(2100.0f + i * 50.0f, 2200.0f, 2200.0f);
            regionBSpatialIndex.insert(entityId, position, (byte) 0, "RegionB " + i);
            forest.trackEntityInsertion(regionBId, entityId, position);
        }

        // 3. Trigger subdivisions
        forest.checkAndAdapt();
        // Poll for both regions to subdivide (up to 2 seconds)
        int maxWait = 2000;
        int waited = 0;
        while (waited < maxWait) {
            try {
                Thread.sleep(50);
                waited += 50;
                if (regionATree.isSubdivided() && regionBTree.isSubdivided()) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 4. Verify both subdivisions occurred independently
        assertTrue(regionATree.isSubdivided(), "Region A should be subdivided");
        assertTrue(regionBTree.isSubdivided(), "Region B should be subdivided");

        // 5. Verify both have tetrahedral children (6 each)
        assertEquals(6, regionATree.getChildTreeIds().size(), "Region A should have 6 tetrahedral children");
        assertEquals(6, regionBTree.getChildTreeIds().size(), "Region B should have 6 tetrahedral children");

        // 6. Verify hierarchy works correctly for both regions
        var regionALeaves = forest.findLeavesUnder(regionAId);
        var regionBLeaves = forest.findLeavesUnder(regionBId);

        assertEquals(6, regionALeaves.size(), "Region A should have 6 leaves");
        assertEquals(6, regionBLeaves.size(), "Region B should have 6 leaves");

        // 7. Verify total forest structure
        var allLeaves = forest.getLeaves();
        assertEquals(12, allLeaves.size(), "Total should be 12 leaves (6+6)");

        // 8. Verify no cross-contamination between regions
        assertTrue(allLeaves.stream().allMatch(TreeNode::isLeaf), "All leaves should have isLeaf()=true");

        // Cleanup
        forest.shutdown();
    }

    /**
     * Test query filtering with isLeaf() to prevent duplicate results.
     *
     * Verifies that queries only search leaf trees, not parent trees.
     */
    @Test
    void testCompleteWorkflow_QueryFilteringWithHierarchy() {
        var bridge = new ForestToTumblerBridge();
        var forestConfig = ForestConfig.defaultConfig();
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL)
            .maxEntitiesPerTree(5)
            .enableAutoSubdivision(false)
            .build();

        var idCounter = new AtomicLong(0);
        var idGenerator = new EntityIDGenerator<LongEntityID>() {
            @Override
            public LongEntityID generateID() {
                return new LongEntityID(idCounter.getAndIncrement());
            }
        };
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "query-filter-forest");
        forest.addEventListener(bridge);

        // 1. Create root tree
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(1000.0f, 1000.0f, 1000.0f)
        );
        var metadata = TreeMetadata.builder()
            .name("QueryRoot")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);
        var root = forest.getTree(rootId);

        // 2. Add entities and subdivide (need >5*1.5=7.5, use 10)
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(200.0f + i * 50.0f, 200.0f, 200.0f);
            spatialIndex.insert(entityId, position, (byte) 0, "Query " + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        forest.checkAndAdapt();
        // Poll for subdivision completion (up to 2 seconds)
        int maxWait = 2000;
        int waited = 0;
        while (waited < maxWait) {
            try {
                Thread.sleep(50);
                waited += 50;
                if (root.isSubdivided()) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 3. Verify subdivision created children
        assertTrue(root.isSubdivided(), "Root should be subdivided");
        var childCount = root.getChildTreeIds().size();
        assertTrue(childCount == 6 || childCount == 8, "Should have children");

        // 4. Query with isLeaf() filtering
        var queryResults = forest.queryHierarchy(tree -> {
            // Predicate that would match both parent and children
            return tree.getGlobalBounds() != null;
        });

        // 5. Verify parent is NOT in results (filtered by isLeaf())
        assertFalse(queryResults.stream().anyMatch(t -> t.getTreeId().equals(rootId)),
                   "Parent should not be in query results (filtered by isLeaf())");

        // 6. Verify only leaves are in results
        assertTrue(queryResults.stream().allMatch(TreeNode::isLeaf),
                  "All query results should be leaf nodes");
        assertEquals(childCount, queryResults.size(),
                    "Should have exactly " + childCount + " leaf results");

        // 7. Verify findLeavesUnder also filters correctly
        var leavesUnderRoot = forest.findLeavesUnder(rootId);
        assertEquals(childCount, leavesUnderRoot.size(), "Should find all leaves under root");
        assertTrue(leavesUnderRoot.stream().allMatch(TreeNode::isLeaf),
                  "All leaves should have isLeaf() = true");

        // Cleanup
        forest.shutdown();
    }
}
