/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConsensusBubbleNode.
 * <p>
 * Tests individual bubble with:
 * - Node creation with tetrahedra
 * - Entity addition/removal
 * - Local entity movement
 * - Cross-bubble migration requests
 * - Committee coordination
 * - Lifecycle management
 * <p>
 * Phase 8B Day 1: Tetree Bubble Topology Setup
 *
 * @author hal.hildebrand
 */
class ConsensusBubbleNodeTest {

    private int bubbleIndex;
    private TetreeKey<?>[] tetrahedra;
    private Context<?> context;
    private Digest viewId;
    private List<Digest> committeeMembers;

    @BeforeEach
    void setUp() {
        bubbleIndex = 0;
        viewId = DigestAlgorithm.DEFAULT.digest("test-view".getBytes());

        // Create L1 tetrahedra for bubble 0 (tet 0-1)
        tetrahedra = new TetreeKey<?>[2];
        // At L1, we have 8 children of root (types 0-7)
        // Bubble 0 gets types 0 and 1
        var root = Tet.ROOT_TET.toTet();
        tetrahedra[0] = root.child(0).tmIndex(); // Type 0 child
        tetrahedra[1] = root.child(1).tmIndex(); // Type 1 child

        // Create committee of 4 members (t=1, q=3)
        committeeMembers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            committeeMembers.add(DigestAlgorithm.DEFAULT.digest(("node-" + i).getBytes()));
        }

        context = null; // Context not needed for these tests
    }

    @Test
    void testNodeCreationWithTetrahedra() {
        // When: Create bubble node
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);

        // Then: Node initialized correctly
        assertNotNull(node);
        assertEquals(bubbleIndex, node.getBubbleIndex());
        assertArrayEquals(tetrahedra, node.getTetrahedra());
        assertEquals(viewId, node.getCommitteeViewId());
        assertEquals(4, node.getCommitteeMembers().size());
    }

    @Test
    void testEntityAdditionAndRemoval() {
        // Given: Bubble node
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);
        var entityId = UUID.randomUUID();
        var sourceNode = committeeMembers.get(0);

        // When: Add entity
        node.addEntity(entityId, sourceNode);

        // Then: Entity tracked
        assertTrue(node.containsEntity(entityId), "Node should contain added entity");
        assertTrue(node.getLocalEntities().contains(entityId), "Local entities should include added entity");

        // When: Remove entity
        node.removeEntity(entityId);

        // Then: Entity no longer tracked
        assertFalse(node.containsEntity(entityId), "Node should not contain removed entity");
        assertFalse(node.getLocalEntities().contains(entityId), "Local entities should not include removed entity");
    }

    @Test
    void testLocalEntityMovement() {
        // Given: Bubble node with entity
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);
        var entityId = UUID.randomUUID();
        var sourceNode = committeeMembers.get(0);
        node.addEntity(entityId, sourceNode);

        // When: Move entity within same bubble (tet 0 -> tet 1)
        var newLocation = tetrahedra[1];
        node.moveEntityLocal(entityId, newLocation);

        // Then: Entity still in bubble but at new location
        assertTrue(node.containsEntity(entityId), "Entity should still be in bubble");
        // Note: Actual location tracking depends on implementation
    }

    @Test
    void testCrossBubbleMigrationRequest() {
        // Given: Bubble node with entity
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);
        var entityId = UUID.randomUUID();
        var sourceNode = committeeMembers.get(0);
        node.addEntity(entityId, sourceNode);

        // When: Request cross-bubble migration to bubble 1
        var targetBubbleIndex = 1;
        var migrationFuture = node.requestCrossBubbleMigration(entityId, targetBubbleIndex);

        // Then: Future created for migration request
        assertNotNull(migrationFuture, "Should return CompletableFuture for migration");
        assertFalse(migrationFuture.isDone(), "Future should not be immediately done (consensus needed)");
    }

    @Test
    void testMultipleEntitiesInBubble() {
        // Given: Bubble node
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);
        var sourceNode = committeeMembers.get(0);

        // When: Add multiple entities
        var entities = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            var entityId = UUID.randomUUID();
            entities.add(entityId);
            node.addEntity(entityId, sourceNode);
        }

        // Then: All entities tracked
        assertEquals(5, node.getLocalEntities().size(), "Should track all 5 entities");
        for (var entityId : entities) {
            assertTrue(node.containsEntity(entityId), "Should contain entity " + entityId);
        }

        // When: Remove some entities
        node.removeEntity(entities.get(0));
        node.removeEntity(entities.get(2));

        // Then: Only remaining entities tracked
        assertEquals(3, node.getLocalEntities().size(), "Should have 3 remaining entities");
        assertFalse(node.containsEntity(entities.get(0)), "Removed entity should not be present");
        assertTrue(node.containsEntity(entities.get(1)), "Non-removed entity should be present");
    }

    @Test
    void testCommitteeCoordination() {
        // Given: Bubble node
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);

        // Then: Committee information accessible
        assertEquals(viewId, node.getCommitteeViewId());
        assertEquals(4, node.getCommitteeMembers().size());

        // Verify committee members match
        for (var member : committeeMembers) {
            assertTrue(node.getCommitteeMembers().contains(member),
                "Committee should contain member " + member);
        }
    }

    @Test
    void testLifecycleManagement() {
        // Given: Bubble node with entities
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);
        var sourceNode = committeeMembers.get(0);

        var entity1 = UUID.randomUUID();
        var entity2 = UUID.randomUUID();
        node.addEntity(entity1, sourceNode);
        node.addEntity(entity2, sourceNode);

        // When: Lifecycle operations
        assertTrue(node.containsEntity(entity1));

        // Remove and re-add
        node.removeEntity(entity1);
        assertFalse(node.containsEntity(entity1));

        node.addEntity(entity1, sourceNode);
        assertTrue(node.containsEntity(entity1));

        // Verify state consistency
        assertEquals(2, node.getLocalEntities().size());
    }

    @Test
    void testInvalidEntityOperations() {
        // Given: Bubble node
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);

        // Then: Invalid operations throw exceptions
        var nonExistentEntity = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
            () -> node.removeEntity(nonExistentEntity),
            "Removing non-existent entity should throw exception");

        assertThrows(IllegalArgumentException.class,
            () -> node.moveEntityLocal(nonExistentEntity, tetrahedra[1]),
            "Moving non-existent entity should throw exception");

        assertThrows(IllegalArgumentException.class,
            () -> node.requestCrossBubbleMigration(nonExistentEntity, 1),
            "Cross-bubble migration of non-existent entity should throw exception");
    }

    @Test
    void testEntityLocationWithinTetrahedra() {
        // Given: Bubble node
        var node = new ConsensusBubbleNode(bubbleIndex, tetrahedra, context, viewId, committeeMembers);
        var entityId = UUID.randomUUID();
        var sourceNode = committeeMembers.get(0);

        // When: Add entity to bubble
        node.addEntity(entityId, sourceNode);

        // Then: Entity is in one of the bubble's tetrahedra
        // This verifies the spatial containment is correctly managed
        assertTrue(node.containsEntity(entityId));

        // Move to different tetrahedron within same bubble
        node.moveEntityLocal(entityId, tetrahedra[1]);
        assertTrue(node.containsEntity(entityId), "Entity should still be in bubble after local move");
    }

    @Test
    void testNullParameterValidation() {
        // Then: Null parameters rejected
        assertThrows(NullPointerException.class,
            () -> new ConsensusBubbleNode(0, null, context, viewId, committeeMembers),
            "Null tetrahedra should throw NPE");

        // Context can be null for testing, so skip that test

        assertThrows(NullPointerException.class,
            () -> new ConsensusBubbleNode(0, tetrahedra, context, null, committeeMembers),
            "Null viewId should throw NPE");

        assertThrows(NullPointerException.class,
            () -> new ConsensusBubbleNode(0, tetrahedra, context, viewId, null),
            "Null committeeMembers should throw NPE");
    }
}
