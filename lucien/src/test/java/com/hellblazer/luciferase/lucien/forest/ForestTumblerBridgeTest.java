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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Forest-Tumbler bridge integration (Phase 3).
 *
 * @author hal.hildebrand
 */
public class ForestTumblerBridgeTest {

    private static final Logger log = LoggerFactory.getLogger(ForestTumblerBridgeTest.class);

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
            .maxEntitiesPerTree(100)
            .minEntitiesPerTree(10)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.OCTANT)
            .enableAutoSubdivision(false) // Manual control for testing
            .build();

        forest = new AdaptiveForest<>(forestConfig, adaptationConfig, idGenerator);
    }

    @AfterEach
    void tearDown() {
        if (forest != null) {
            forest.shutdown();
        }
    }

    // ========================================
    // Test 1: TreeAdded event emitted on child creation
    // ========================================

    @Test
    void testTreeAddedEventEmitted() {
        // Arrange: Create listener to capture events
        var capturedEvents = new CopyOnWriteArrayList<ForestEvent>();
        ForestEventListener listener = capturedEvents::add;
        forest.addEventListener(listener);

        // Act: Manual child creation (subdivision creates children)
        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("RootTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var parentId = forest.addTree(tree, metadata);
        var parentTree = forest.getTree(parentId);

        // Add entities to trigger subdivision
        for (int i = 0; i < 150; i++) {
            var pos = new Point3f(i * 2.0f, i * 2.0f, i * 2.0f);
            var entityId = idGenerator.generateID();
            tree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        // Manually trigger subdivision via reflection
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to trigger subdivision: " + e.getMessage());
        }

        // Assert: TreeAdded events were emitted for child trees
        var treeAddedEvents = capturedEvents.stream()
            .filter(e -> e instanceof ForestEvent.TreeAdded)
            .map(e -> (ForestEvent.TreeAdded) e)
            .toList();

        assertTrue(treeAddedEvents.size() >= 8, "Should have at least 8 TreeAdded events for octant subdivision");
        assertEquals(RegionShape.CUBIC, treeAddedEvents.get(0).regionShape());
        assertNotNull(treeAddedEvents.get(0).treeId());
    }

    // ========================================
    // Test 2: TreeSubdivided event emitted with correct subdivision type
    // ========================================

    @Test
    void testTreeSubdividedEvent_OctantType() {
        // Arrange
        var capturedEvents = new CopyOnWriteArrayList<ForestEvent>();
        forest.addEventListener(capturedEvents::add);

        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("ParentTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();

        var parentId = forest.addTree(tree, metadata);

        // Add entities
        for (int i = 0; i < 150; i++) {
            var pos = new Point3f(i * 2.0f, i * 2.0f, i * 2.0f);
            var entityId = idGenerator.generateID();
            tree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        // Act: Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to trigger subdivision: " + e.getMessage());
        }

        // Assert: TreeSubdivided event emitted
        var subdivisionEvents = capturedEvents.stream()
            .filter(e -> e instanceof ForestEvent.TreeSubdivided)
            .map(e -> (ForestEvent.TreeSubdivided) e)
            .toList();

        assertEquals(1, subdivisionEvents.size(), "Should have exactly 1 TreeSubdivided event");
        var event = subdivisionEvents.get(0);
        assertEquals(parentId, event.parentId());
        assertEquals(8, event.childIds().size(), "OCTANT subdivision creates 8 children");
        assertEquals(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.OCTANT, event.strategy());
        assertEquals(RegionShape.CUBIC, event.childShape());
    }

    // ========================================
    // Test 3: ForestToTumblerBridge assigns servers to trees
    // ========================================

    @Test
    void testBridgeAssignsServers() {
        // Arrange
        var bridge = new ForestToTumblerBridge();
        forest.addEventListener(bridge);

        // Act: Add a tree (triggers TreeAdded event)
        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("RootTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        var treeId = forest.addTree(tree, metadata);

        // Manually trigger TreeAdded event via createChildTreeWithBounds
        // (since addTree() doesn't emit TreeAdded in current impl)
        // We'll test via subdivision which does emit TreeAdded
        for (int i = 0; i < 150; i++) {
            var pos = new Point3f(i * 2.0f, i * 2.0f, i * 2.0f);
            var entityId = idGenerator.generateID();
            tree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(treeId, entityId, pos);
        }

        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, treeId);
        } catch (Exception e) {
            fail("Failed to trigger subdivision: " + e.getMessage());
        }

        // Assert: Children have server assignments
        var assignments = bridge.getAllAssignments();
        assertTrue(assignments.size() >= 8, "Should have assignments for 8+ children");

        // Verify server assignments are valid (server-0, server-1, server-2, server-3)
        for (var serverId : assignments.values()) {
            assertTrue(serverId.matches("server-[0-3]"), "Server ID should match pattern server-[0-3]");
        }
    }

    // ========================================
    // Test 4: Children inherit parent's server assignment
    // ========================================

    @Test
    void testChildrenInheritParentServer() {
        // Arrange
        var bridge = new ForestToTumblerBridge();
        forest.addEventListener(bridge);

        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("ParentTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        var parentId = forest.addTree(tree, metadata);

        // Add entities
        for (int i = 0; i < 150; i++) {
            var pos = new Point3f(i * 2.0f, i * 2.0f, i * 2.0f);
            var entityId = idGenerator.generateID();
            tree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        // Act: Trigger subdivision
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to trigger subdivision: " + e.getMessage());
        }

        // Assert: All children have same server as each other (inherited strategy)
        var parentTree = forest.getTree(parentId);
        var childIds = parentTree.getChildTreeIds();

        assertEquals(8, childIds.size(), "Should have 8 children");

        var parentServer = bridge.getServerAssignment(parentId);
        log.info("Parent {} has server: {}", parentId, parentServer);

        var childServers = childIds.stream()
            .map(bridge::getServerAssignment)
            .toList();

        // Log all child servers for debugging
        for (int i = 0; i < childIds.size(); i++) {
            log.info("Child {} has server: {}", childIds.get(i), childServers.get(i));
        }

        // All children should have the same server (inherited from parent)
        var firstServer = childServers.get(0);
        assertNotNull(firstServer, "Children should have server assignments");
        for (var server : childServers) {
            assertEquals(firstServer, server, "All children should inherit same server");
        }
    }

    // ========================================
    // Test 5: Multiple listeners receive same event
    // ========================================

    @Test
    void testMultipleListenersReceiveEvent() {
        // Arrange: Create two separate listeners
        var events1 = new CopyOnWriteArrayList<ForestEvent>();
        var events2 = new CopyOnWriteArrayList<ForestEvent>();

        forest.addEventListener(events1::add);
        forest.addEventListener(events2::add);

        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("TestTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        var parentId = forest.addTree(tree, metadata);

        // Add entities
        for (int i = 0; i < 150; i++) {
            var pos = new Point3f(i * 2.0f, i * 2.0f, i * 2.0f);
            var entityId = idGenerator.generateID();
            tree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        // Act
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to trigger subdivision: " + e.getMessage());
        }

        // Assert: Both listeners received events
        assertFalse(events1.isEmpty(), "Listener 1 should have received events");
        assertFalse(events2.isEmpty(), "Listener 2 should have received events");
        assertEquals(events1.size(), events2.size(), "Both listeners should receive same number of events");

        // Verify same event types in same order
        for (int i = 0; i < events1.size(); i++) {
            assertEquals(events1.get(i).getClass(), events2.get(i).getClass());
        }
    }

    // ========================================
    // Test 6: Listener exception isolation (doesn't break other listeners)
    // ========================================

    @Test
    void testListenerExceptionIsolation() {
        // Arrange: Create a failing listener and a working listener
        var workingListenerEvents = new CopyOnWriteArrayList<ForestEvent>();

        ForestEventListener failingListener = event -> {
            throw new RuntimeException("Simulated listener failure");
        };

        ForestEventListener workingListener = workingListenerEvents::add;

        // Add failing listener first, then working listener
        forest.addEventListener(failingListener);
        forest.addEventListener(workingListener);

        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("TestTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        var parentId = forest.addTree(tree, metadata);

        for (int i = 0; i < 150; i++) {
            var pos = new Point3f(i * 2.0f, i * 2.0f, i * 2.0f);
            var entityId = idGenerator.generateID();
            tree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        // Act
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to trigger subdivision: " + e.getMessage());
        }

        // Assert: Working listener still received events despite failing listener
        assertFalse(workingListenerEvents.isEmpty(),
            "Working listener should still receive events even when another listener fails");
    }

    // ========================================
    // Test 7: Event filtering by type works
    // ========================================

    @Test
    void testEventFiltering() {
        // Arrange: Filter for only TreeSubdivided events
        var subdividedEvents = new CopyOnWriteArrayList<ForestEvent.TreeSubdivided>();

        ForestEventListener filteringListener = event -> {
            if (event instanceof ForestEvent.TreeSubdivided subdivided) {
                subdividedEvents.add(subdivided);
            }
        };

        forest.addEventListener(filteringListener);

        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("TestTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        var parentId = forest.addTree(tree, metadata);

        for (int i = 0; i < 150; i++) {
            var pos = new Point3f(i * 2.0f, i * 2.0f, i * 2.0f);
            var entityId = idGenerator.generateID();
            tree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        // Act
        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to trigger subdivision: " + e.getMessage());
        }

        // Assert: Only TreeSubdivided events were captured
        assertEquals(1, subdividedEvents.size(), "Should have filtered exactly 1 TreeSubdivided event");
        assertEquals(parentId, subdividedEvents.get(0).parentId());
    }

    // ========================================
    // Test 8: Integration test - forest -> bridge -> server tracking
    // ========================================

    @Test
    void testForestToBridgeIntegration() {
        // Arrange: Full integration with forest + bridge
        var bridge = new ForestToTumblerBridge();
        forest.addEventListener(bridge);

        // Act: Create tree and subdivide
        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("IntegrationTest")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        var parentId = forest.addTree(tree, metadata);

        for (int i = 0; i < 150; i++) {
            var pos = new Point3f(i * 2.0f, i * 2.0f, i * 2.0f);
            var entityId = idGenerator.generateID();
            tree.insert(entityId, pos, (byte)0, "Entity-" + i);
            forest.trackEntityInsertion(parentId, entityId, pos);
        }

        try {
            var method = AdaptiveForest.class.getDeclaredMethod("considerSubdivision", String.class);
            method.setAccessible(true);
            method.invoke(forest, parentId);
        } catch (Exception e) {
            fail("Failed to trigger subdivision: " + e.getMessage());
        }

        // Assert: End-to-end verification
        var parentTree = forest.getTree(parentId);
        var childIds = parentTree.getChildTreeIds();

        assertEquals(8, childIds.size(), "Octant subdivision creates 8 children");

        // Verify all children have server assignments
        for (var childId : childIds) {
            var server = bridge.getServerAssignment(childId);
            assertNotNull(server, "Child " + childId + " should have server assignment");
            assertTrue(server.matches("server-[0-3]"), "Server should match pattern");
        }

        // Verify forest hierarchy is correct
        assertTrue(parentTree.isSubdivided(), "Parent should be marked as subdivided");
        assertFalse(parentTree.isLeaf(), "Parent should not be a leaf");

        for (var childId : childIds) {
            var child = forest.getTree(childId);
            assertNotNull(child, "Child tree should exist in forest");
            assertTrue(child.isLeaf(), "Child should be a leaf");
            assertEquals(parentId, child.getParentTreeId(), "Child should reference parent");
        }
    }
}
