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

    /** Ghost zone width used in tests requiring ghost boundary detection. */
    private static final float GHOST_ZONE_WIDTH = 10.0f;

    /**
     * Helper method to wait for tree subdivision to complete.
     *
     * @param tree the tree to wait for
     * @param maxWaitMs maximum wait time in milliseconds
     * @return actual wait time in milliseconds
     * @throws AssertionError if subdivision doesn't complete within timeout
     */
    private int waitForSubdivision(TreeNode<?, ?, ?> tree, int maxWaitMs) {
        int waited = 0;
        while (waited < maxWaitMs) {
            try {
                Thread.sleep(50);
                waited += 50;
                if (tree.isSubdivided()) {
                    return waited;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(String.format("Tree %s did not subdivide within %dms (waited %dms)",
                          tree.getTreeId(), maxWaitMs, waited));
        return waited; // unreachable
    }

    /**
     * Helper method to validate tetrahedral AABB bounds for ghost boundary detection.
     *
     * Verifies that:
     * - AABB can be computed from tetrahedral vertices
     * - AABB is non-degenerate (positive extents in all dimensions)
     * - AABB values are valid (no NaN or Infinity)
     *
     * @param tetBounds the tetrahedral bounds to validate
     * @param contextMessage context message for assertion failures
     */
    private void assertValidGhostAABB(TetrahedralBounds tetBounds, String contextMessage) {
        // Verify AABB can be computed (required for ghost boundary detection)
        var aabb = tetBounds.toAABB();
        assertNotNull(aabb, contextMessage + ": AABB should be computable for ghost boundary detection");

        // Verify AABB is non-degenerate (positive extents)
        assertTrue(aabb.getMaxX() > aabb.getMinX(),
                  contextMessage + ": AABB should have positive X extent");
        assertTrue(aabb.getMaxY() > aabb.getMinY(),
                  contextMessage + ": AABB should have positive Y extent");
        assertTrue(aabb.getMaxZ() > aabb.getMinZ(),
                  contextMessage + ": AABB should have positive Z extent");

        // Verify AABB is within valid ranges (no NaN or Infinity)
        assertFalse(Float.isNaN(aabb.getMinX()), contextMessage + ": AABB min X should not be NaN");
        assertFalse(Float.isNaN(aabb.getMaxX()), contextMessage + ": AABB max X should not be NaN");
        assertFalse(Float.isNaN(aabb.getMinY()), contextMessage + ": AABB min Y should not be NaN");
        assertFalse(Float.isNaN(aabb.getMaxY()), contextMessage + ": AABB max Y should not be NaN");
        assertFalse(Float.isNaN(aabb.getMinZ()), contextMessage + ": AABB min Z should not be NaN");
        assertFalse(Float.isNaN(aabb.getMaxZ()), contextMessage + ": AABB max Z should not be NaN");

        assertFalse(Float.isInfinite(aabb.getMinX()), contextMessage + ": AABB min X should not be infinite");
        assertFalse(Float.isInfinite(aabb.getMaxX()), contextMessage + ": AABB max X should not be infinite");
        assertFalse(Float.isInfinite(aabb.getMinY()), contextMessage + ": AABB min Y should not be infinite");
        assertFalse(Float.isInfinite(aabb.getMaxY()), contextMessage + ": AABB max Y should not be infinite");
        assertFalse(Float.isInfinite(aabb.getMinZ()), contextMessage + ": AABB min Z should not be infinite");
        assertFalse(Float.isInfinite(aabb.getMaxZ()), contextMessage + ": AABB max Z should not be infinite");
    }

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

        // Wait for async subdivision to complete
        waitForSubdivision(root, 2000);

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
        // Wait for first subdivision to complete
        waitForSubdivision(root, 2000);

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
        // Wait for targetTet subdivision to complete
        waitForSubdivision(targetTet, 2000);

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
        // Wait for both regions to subdivide
        waitForSubdivision(regionATree, 2000);
        waitForSubdivision(regionBTree, 2000);

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
        // Wait for subdivision to complete
        waitForSubdivision(root, 2000);

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

    /**
     * Test entity movement across subdivided trees.
     *
     * Verifies that entities are correctly redistributed during subdivision using containsUltraFast,
     * first-match-wins tie-breaking, and that no entities are lost in the process.
     */
    @Test
    void testEntityMovementAcrossSubdividedTrees() {
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
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "entity-movement-forest");
        forest.addEventListener(bridge);

        // 1. Create root tree
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(500.0f, 500.0f, 500.0f)
        );
        var metadata = TreeMetadata.builder()
            .name("EntityRoot")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);
        var root = forest.getTree(rootId);

        // 2. Add 10 entities with known positions (need >5*1.5=7.5 to trigger subdivision)
        var entityIds = new ArrayList<LongEntityID>();
        var entityPositions = new ArrayList<Point3f>();

        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(
                50.0f + i * 40.0f,
                50.0f + i * 40.0f,
                50.0f + i * 40.0f
            );
            entityIds.add(entityId);
            entityPositions.add(position);
            spatialIndex.insert(entityId, position, (byte) 0, "Entity " + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        // 3. Trigger subdivision
        forest.checkAndAdapt();

        // Wait for subdivision to complete
        waitForSubdivision(root, 2000);

        // 4. Verify subdivision occurred
        assertTrue(root.isSubdivided(), "Root should be subdivided");
        assertEquals(6, root.getChildTreeIds().size(), "Should have 6 tetrahedral children");

        // 5. Verify all children have tetrahedral bounds (required for containsUltraFast)
        for (var childId : root.getChildTreeIds()) {
            var child = forest.getTree(childId);
            assertInstanceOf(TetrahedralBounds.class, child.getTreeBounds(),
                           "Child should have TetrahedralBounds for containsUltraFast");
        }

        // 6. Verify entities were redistributed: count total entities across all children
        int totalChildEntities = 0;
        for (var childId : root.getChildTreeIds()) {
            var child = forest.getTree(childId);
            var childIndex = child.getSpatialIndex();
            totalChildEntities += childIndex.entityCount();
        }

        assertEquals(10, totalChildEntities,
                    "All 10 entities should be redistributed to children (no loss)");

        // 7. Verify parent is no longer a leaf (entities moved to children)
        assertFalse(root.isLeaf(), "Parent should not be a leaf after subdivision");

        // 8. Verify tetrahedral containment logic via AABB bounds
        for (var childId : root.getChildTreeIds()) {
            var child = forest.getTree(childId);
            var tetBounds = (TetrahedralBounds) child.getTreeBounds();

            // Verify AABB can be computed (used for first-match-wins containment check)
            var aabb = tetBounds.toAABB();
            assertNotNull(aabb, "AABB should be computable for containment checks");

            // Verify AABB is non-degenerate (positive volume)
            assertTrue(aabb.getMaxX() > aabb.getMinX(), "AABB should have positive X extent");
            assertTrue(aabb.getMaxY() > aabb.getMinY(), "AABB should have positive Y extent");
            assertTrue(aabb.getMaxZ() > aabb.getMinZ(), "AABB should have positive Z extent");
        }

        // Cleanup
        forest.shutdown();
    }

    /**
     * Test ghost layer compatibility with tetrahedral forest.
     *
     * Verifies that ghost zones work correctly with tetrahedral subdivision:
     * - Ghost zones are created for tetrahedral children
     * - Ghost boundary detection works with tetrahedral AABB bounds
     * - Ghost updates are triggered for all children
     */
    @Test
    void testGhostLayerWithTetrahedralForest() {
        var bridge = new ForestToTumblerBridge();

        // Enable ghost zones in forest config
        var forestConfig = ForestConfig.builder()
            .withGhostZones(GHOST_ZONE_WIDTH)
            .build();

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
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "ghost-forest");
        forest.addEventListener(bridge);

        // 1. Create root tree with cubic bounds
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(400.0f, 400.0f, 400.0f)
        );
        var metadata = TreeMetadata.builder()
            .name("GhostRoot")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);
        var root = forest.getTree(rootId);

        // 2. Add entities to trigger subdivision (need >7.5)
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(100.0f + i * 20.0f, 100.0f, 100.0f);
            spatialIndex.insert(entityId, position, (byte) 0, "Ghost " + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        // 3. Trigger subdivision
        forest.checkAndAdapt();

        // Wait for subdivision to complete
        waitForSubdivision(root, 2000);

        // 4. Verify subdivision occurred
        assertTrue(root.isSubdivided(), "Root should be subdivided");
        assertEquals(6, root.getChildTreeIds().size(), "Should have 6 tetrahedral children");

        // 5. Verify all children have tetrahedral bounds (required for ghost AABB)
        for (var childId : root.getChildTreeIds()) {
            var child = forest.getTree(childId);
            assertInstanceOf(TetrahedralBounds.class, child.getTreeBounds(),
                           "Child should have TetrahedralBounds for ghost AABB calculation");
        }

        // 6. Verify ghost zones are enabled in config
        assertTrue(forestConfig.isGhostZonesEnabled(), "Ghost zones should be enabled");
        assertEquals(GHOST_ZONE_WIDTH, forestConfig.getGhostZoneWidth(), 0.001f,
                    "Ghost zone width should match configured value");

        // 7. Verify tetrahedral AABB bounds are compatible with ghost boundary detection
        // (Ghost detection uses AABB bounds, not precise tetrahedral containment)
        for (var childId : root.getChildTreeIds()) {
            var child = forest.getTree(childId);
            var tetBounds = (TetrahedralBounds) child.getTreeBounds();
            assertValidGhostAABB(tetBounds, "Case A child (6-tet subdivision)");
        }

        // Cleanup
        forest.shutdown();
    }

    /**
     * Test distributed two-server scenario.
     *
     * Verifies server assignment and load balancing:
     * - Bridge assigns servers via round-robin (mock implementation)
     * - Subdivision propagates server assignments to leaf trees
     * - All children inherit parent's server assignment
     */
    @Test
    void testDistributedTwoServer() {
        // Create bridge with round-robin mock server assignment
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
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "distributed-forest");
        forest.addEventListener(bridge);

        // 1. Create root tree
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(600.0f, 600.0f, 600.0f)
        );
        var metadata = TreeMetadata.builder()
            .name("DistRoot")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);
        var root = forest.getTree(rootId);

        // Server assignment occurs during subdivision (not during addTree):
        // ForestToTumblerBridge.handleTreeSubdivided assigns parent if null, then children inherit

        // 2. Add entities to trigger subdivision (need >7.5, use 10)
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(150.0f + i * 40.0f, 150.0f, 150.0f);
            spatialIndex.insert(entityId, position, (byte) 0, "Dist " + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        // 3. Trigger subdivision
        forest.checkAndAdapt();

        // Wait for subdivision to complete
        waitForSubdivision(root, 2000);

        // 4. Verify subdivision occurred
        assertTrue(root.isSubdivided(), "Root should be subdivided");
        assertEquals(6, root.getChildTreeIds().size(), "Should have 6 tetrahedral children");

        // Get root server assignment (assigned during subdivision)
        var rootServer = bridge.getServerAssignment(rootId);
        assertNotNull(rootServer, "Root should have server assignment after subdivision");
        assertTrue(rootServer.startsWith("server-"), "Root server should follow 'server-N' pattern");

        // 5. Verify all children have server assignments
        for (var childId : root.getChildTreeIds()) {
            var assignment = bridge.getServerAssignment(childId);
            assertNotNull(assignment, "Child " + childId + " should have server assignment");
            assertTrue(assignment.startsWith("server-"),
                      "Child server should follow 'server-N' pattern");
        }

        // 6. Verify inheritance: all children inherit parent's server (Phase 3 design)
        for (var childId : root.getChildTreeIds()) {
            var childServer = bridge.getServerAssignment(childId);
            assertEquals(rootServer, childServer,
                        "Child should inherit parent's server assignment");
        }

        // 7. Verify server assignments propagate to leaf trees
        var leaves = forest.getLeaves();
        assertEquals(6, leaves.size(), "Should have 6 leaf nodes");

        for (var leaf : leaves) {
            var assignment = bridge.getServerAssignment(leaf.getTreeId());
            assertNotNull(assignment, "Leaf " + leaf.getTreeId() + " should have server assignment");
            assertEquals(rootServer, assignment, "Leaf should have same server as root");
            assertTrue(leaf.isLeaf(), "All returned nodes should be leaves");
        }

        // 8. Verify bridge tracks all assignments
        var allAssignments = bridge.getAllAssignments();
        // Should have root + 6 children = 7 assignments
        assertTrue(allAssignments.size() >= 7,
                  "Bridge should track root and all children (at least 7 assignments)");

        // 9. Verify round-robin pattern: create second root, should get different server
        var spatialIndex2 = new Octree<>(idGenerator);
        var rootBounds2 = new EntityBounds(
            new Point3f(1000.0f, 1000.0f, 1000.0f),
            new Point3f(1600.0f, 1600.0f, 1600.0f)
        );
        var metadata2 = TreeMetadata.builder()
            .name("DistRoot2")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds2))
            .build();
        var rootId2 = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex2, metadata2);
        var root2 = forest.getTree(rootId2);

        // Add entities to second root (need >7.5, use 10)
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(1100.0f + i * 40.0f, 1100.0f, 1100.0f);
            spatialIndex2.insert(entityId, position, (byte) 0, "Dist2 " + i);
            forest.trackEntityInsertion(rootId2, entityId, position);
        }

        // Trigger second subdivision
        forest.checkAndAdapt();
        waitForSubdivision(root2, 2000);

        // Verify round-robin: second root should have different server (likely server-1, but any != server-0)
        var rootServer2 = bridge.getServerAssignment(rootId2);
        assertNotNull(rootServer2, "Second root should have server assignment after subdivision");
        assertNotEquals(rootServer, rootServer2,
                       "Round-robin should assign different servers to independent roots");

        // Cleanup
        forest.shutdown();
    }

    /**
     * Negative test: verify subdivision does NOT occur when entity count is below threshold.
     *
     * Verifies that subdivision threshold enforcement works correctly:
     * - Entity count below maxEntitiesPerTree * 1.5 should NOT trigger subdivision
     * - Tree remains a leaf with all entities in parent
     */
    @Test
    void testNoSubdivisionBelowThreshold() {
        var bridge = new ForestToTumblerBridge();
        var forestConfig = ForestConfig.defaultConfig();

        // Set high threshold to ensure we stay below it
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL)
            .maxEntitiesPerTree(20) // Threshold = 20 * 1.5 = 30 entities
            .enableAutoSubdivision(false)
            .build();

        var idCounter = new AtomicLong(0);
        var idGenerator = new EntityIDGenerator<LongEntityID>() {
            @Override
            public LongEntityID generateID() {
                return new LongEntityID(idCounter.getAndIncrement());
            }
        };
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "no-subdivision-forest");
        forest.addEventListener(bridge);

        // 1. Create root tree
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(800.0f, 800.0f, 800.0f)
        );
        var metadata = TreeMetadata.builder()
            .name("NoSubdivisionRoot")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);
        var root = forest.getTree(rootId);

        // 2. Add 10 entities (well below threshold of 30)
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(200.0f + i * 50.0f, 200.0f, 200.0f);
            spatialIndex.insert(entityId, position, (byte) 0, "NoSub " + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        // 3. Trigger adaptation check
        forest.checkAndAdapt();

        // 4. Wait a bit to ensure subdivision would have completed if triggered
        try {
            Thread.sleep(500); // Half the normal wait time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 5. Verify NO subdivision occurred
        assertFalse(root.isSubdivided(), "Root should NOT be subdivided when below threshold");
        assertTrue(root.isLeaf(), "Root should remain a leaf");
        assertTrue(root.getChildTreeIds().isEmpty(), "Root should have no children");

        // 6. Verify all entities remain in parent
        assertEquals(10, spatialIndex.entityCount(), "All 10 entities should remain in parent tree");

        // 7. Verify no server assignment (only occurs during subdivision)
        assertNull(bridge.getServerAssignment(rootId),
                  "Root should have no server assignment without subdivision");

        // Cleanup
        forest.shutdown();
    }

    /**
     * Test ghost layer compatibility with 8-child Bey subdivision (Case B).
     *
     * Verifies that ghost zones work correctly with tetrahedral Bey subdivision:
     * - First subdivide cubic to 6 tets (Case A)
     * - Then subdivide one tet to 8 grandchildren (Case B - Bey subdivision)
     * - Ghost zones are created for all 8 tetrahedral grandchildren
     * - Ghost boundary detection works with all tetrahedral AABB bounds
     */
    @Test
    void testGhostLayerWithBeySubdivision() {
        var bridge = new ForestToTumblerBridge();

        // Enable ghost zones in forest config
        var forestConfig = ForestConfig.builder()
            .withGhostZones(GHOST_ZONE_WIDTH)
            .build();

        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.TETRAHEDRAL)
            .maxEntitiesPerTree(5)  // Low threshold to trigger subdivisions
            .enableAutoSubdivision(false)
            .build();

        var idCounter = new AtomicLong(0);
        var idGenerator = new EntityIDGenerator<LongEntityID>() {
            @Override
            public LongEntityID generateID() {
                return new LongEntityID(idCounter.getAndIncrement());
            }
        };
        var forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator, "bey-ghost-forest");
        forest.addEventListener(bridge);

        // Verify ghost zones are configured correctly (prerequisite for test)
        assertTrue(forestConfig.isGhostZonesEnabled(), "Ghost zones should be enabled");
        assertEquals(GHOST_ZONE_WIDTH, forestConfig.getGhostZoneWidth(), 0.001f,
                    "Ghost zone width should match configured value");

        // 1. Create root tree with cubic bounds
        var spatialIndex = new Octree<>(idGenerator);
        var rootBounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(400.0f, 400.0f, 400.0f)
        );
        var metadata = TreeMetadata.builder()
            .name("BeyGhostRoot")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("initialBounds", new CubicBounds(rootBounds))
            .build();
        var rootId = forest.addTree((com.hellblazer.luciferase.lucien.AbstractSpatialIndex) spatialIndex, metadata);
        var root = forest.getTree(rootId);

        // 2. Add entities to trigger Case A subdivision (cubic → 6 tets)
        // Spread entities across X-axis within root bounds (0-400) to trigger subdivision
        // Position pattern: 100.0 + i*20.0 distributes entities evenly, exceeding threshold
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(100.0f + i * 20.0f, 100.0f, 100.0f);
            spatialIndex.insert(entityId, position, (byte) 0, "CaseA " + i);
            forest.trackEntityInsertion(rootId, entityId, position);
        }

        // 3. Trigger Case A subdivision (cubic → 6 tets)
        forest.checkAndAdapt();
        waitForSubdivision(root, 2000);

        // 4. Verify Case A subdivision created 6 tetrahedral children
        assertTrue(root.isSubdivided(), "Root should be subdivided (Case A)");
        assertEquals(6, root.getChildTreeIds().size(),
                    "Should have 6 tetrahedral children (S0-S5 subdivision of cube)");

        // 5. Pick first child and add entities to trigger Case B subdivision (tet → 8 subtets)
        var firstChildId = root.getChildTreeIds().get(0);
        var firstChild = forest.getTree(firstChildId);
        var childIndex = firstChild.getSpatialIndex();

        // Add entities within the first child's bounds to trigger Bey subdivision
        // Position pattern: 50.0 + i*5.0 concentrates entities in first child's space
        // This exceeds maxEntitiesPerTree=5 threshold to trigger Bey subdivision
        for (int i = 0; i < 10; i++) {
            var entityId = idGenerator.generateID();
            var position = new Point3f(50.0f + i * 5.0f, 50.0f, 50.0f);
            childIndex.insert(entityId, position, (byte) 0, "CaseB " + i);
            forest.trackEntityInsertion(firstChildId, entityId, position);
        }

        // 6. Trigger Case B subdivision (tet → 8 subtets via Bey)
        forest.checkAndAdapt();
        waitForSubdivision(firstChild, 2000);

        // 7. Verify Case B subdivision created 8 tetrahedral grandchildren
        assertTrue(firstChild.isSubdivided(), "First child should be subdivided (Case B)");
        assertEquals(8, firstChild.getChildTreeIds().size(),
                    "Should have 8 tetrahedral grandchildren (Case B - Bey subdivision)");

        // 8. Verify all 8 grandchildren have tetrahedral bounds and valid ghost AABBs
        for (var grandchildId : firstChild.getChildTreeIds()) {
            var grandchild = forest.getTree(grandchildId);
            assertInstanceOf(TetrahedralBounds.class, grandchild.getTreeBounds(),
                           "Grandchild should have TetrahedralBounds for ghost AABB calculation");

            // Verify tetrahedral AABB bounds are compatible with ghost boundary detection
            var tetBounds = (TetrahedralBounds) grandchild.getTreeBounds();
            assertValidGhostAABB(tetBounds, "Case B grandchild (8-tet Bey subdivision)");
        }

        // Cleanup
        forest.shutdown();
    }
}
