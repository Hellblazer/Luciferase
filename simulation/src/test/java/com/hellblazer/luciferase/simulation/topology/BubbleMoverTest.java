/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.topology.BubbleMover;
import com.hellblazer.luciferase.simulation.topology.MoveProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BubbleMover boundary relocation.
 *
 * @author hal.hildebrand
 */
class BubbleMoverTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private BubbleMover mover;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        mover = new BubbleMover(bubbleGrid, accountant);
    }

    @Test
    void testMovePreservesEntityCount() {
        // Create bubble with entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 500);

        int entitiesBefore = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(500, entitiesBefore, "Should have 500 entities before move");

        // Get current centroid
        var currentBounds = bubble.bounds();
        var currentCentroid = currentBounds.centroid();

        // Calculate new center (small shift toward cluster)
        var clusterCentroid = new Point3f(
            (float) currentCentroid.getX() + 0.5f,
            (float) currentCentroid.getY() + 0.5f,
            (float) currentCentroid.getZ() + 0.5f
        );

        var newCenter = new Point3f(
            (float) currentCentroid.getX() + 0.1f,
            (float) currentCentroid.getY() + 0.1f,
            (float) currentCentroid.getZ() + 0.1f
        );

        // Create move proposal
        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            newCenter,
            clusterCentroid,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        // Execute move
        var result = mover.execute(proposal);

        // Verify result
        assertTrue(result.success(), "Move should succeed: " + result.message());
        assertEquals(500, result.entitiesBefore(), "Entities before should be 500");
        assertEquals(500, result.entitiesAfter(), "Entities after should be 500");

        // Verify entity count unchanged
        int entitiesAfter = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(entitiesBefore, entitiesAfter, "Entity count should be unchanged");

        // Verify no duplicates
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testMoveRejectsNonexistentBubble() {
        var proposal = new MoveProposal(
            UUID.randomUUID(),
            UUID.randomUUID(), // Non-existent bubble
            new Point3f(1.0f, 1.0f, 1.0f),
            new Point3f(2.0f, 2.0f, 2.0f),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = mover.execute(proposal);

        assertFalse(result.success(), "Should reject non-existent bubble");
        assertTrue(result.message().contains("not found"), "Should mention bubble not found");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Empty bubble handling not implemented in MVP - TODO for Phase 9D")
    void testMoveRejectsEmptyBubble() {
        // Create bubble with no entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            new Point3f(1.0f, 1.0f, 1.0f),
            new Point3f(2.0f, 2.0f, 2.0f),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = mover.execute(proposal);

        assertFalse(result.success(), "Should reject bubble with no bounds");
        assertTrue(result.message().contains("no bounds"), "Should mention no bounds");
    }

    @Test
    void testMoveValidatesEntityConservation() {
        // Create bubble with entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 1000);

        var currentBounds = bubble.bounds();
        var currentCentroid = currentBounds.centroid();

        var clusterCentroid = new Point3f(
            (float) currentCentroid.getX() + 0.5f,
            (float) currentCentroid.getY() + 0.5f,
            (float) currentCentroid.getZ() + 0.5f
        );

        var newCenter = new Point3f(
            (float) currentCentroid.getX() + 0.1f,
            (float) currentCentroid.getY() + 0.1f,
            (float) currentCentroid.getZ() + 0.1f
        );

        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            newCenter,
            clusterCentroid,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = mover.execute(proposal);

        // Verify conservation
        assertTrue(result.success(), "Move should succeed");
        assertEquals(result.entitiesBefore(), result.entitiesAfter(), "Entity count should be unchanged");

        // Verify Accountant validation passes
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass");
        assertEquals(0, validation.errorCount(), "Should have no validation errors");
    }

    @Test
    void testMoveNullProposalThrows() {
        assertThrows(NullPointerException.class, () -> {
            mover.execute(null);
        }, "Should reject null proposal");
    }

    @Test
    void testConstructorNullBubbleGridThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleMover(null, accountant);
        }, "Should reject null bubble grid");
    }

    @Test
    void testConstructorNullAccountantThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleMover(bubbleGrid, null);
        }, "Should reject null accountant");
    }

    // Helper method

    private void addEntities(com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }
    }
}
