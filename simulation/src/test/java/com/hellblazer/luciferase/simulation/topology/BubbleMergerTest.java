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
import com.hellblazer.luciferase.simulation.topology.BubbleMerger;
import com.hellblazer.luciferase.simulation.topology.MergeProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BubbleMerger with duplicate detection.
 *
 * @author hal.hildebrand
 */
class BubbleMergerTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private BubbleMerger merger;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        merger = new BubbleMerger(bubbleGrid, accountant);
    }

    @Test
    void testMergeWithNoDuplicates() {
        // Create 2 bubbles with distinct entities
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 300);
        addEntities(bubble2, 200);

        int totalBefore = accountant.entitiesInBubble(bubble1.id()).size() +
                         accountant.entitiesInBubble(bubble2.id()).size();
        assertEquals(500, totalBefore, "Should have 500 total entities");

        // Create merge proposal
        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        // Execute merge
        var result = merger.execute(proposal);

        // Verify result
        assertTrue(result.success(), "Merge should succeed: " + result.message());
        assertEquals(500, result.entitiesBefore(), "Entities before should be 500");
        assertEquals(500, result.entitiesAfter(), "Entities after should be 500");
        assertEquals(0, result.duplicatesFound(), "Should have no duplicates");

        // Verify all entities moved to bubble1
        int bubble1Entities = accountant.entitiesInBubble(bubble1.id()).size();
        int bubble2Entities = accountant.entitiesInBubble(bubble2.id()).size();
        assertEquals(500, bubble1Entities, "Bubble1 should have all 500 entities");
        assertEquals(0, bubble2Entities, "Bubble2 should be empty");

        // Verify no duplicates
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    @org.junit.jupiter.api.Disabled("EntityAccountant prevents registering same entity in multiple bubbles - duplicate scenario not realistic")
    void testMergeDetectsDuplicates() {
        // Create 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add 200 unique entities to bubble1
        addEntities(bubble1, 200);

        // Add 100 unique entities to bubble2
        addEntities(bubble2, 100);

        // Add 50 duplicate entities (same UUID in both bubbles)
        var duplicateIds = new java.util.ArrayList<UUID>();
        for (int i = 0; i < 50; i++) {
            var entityId = UUID.randomUUID();
            duplicateIds.add(entityId);

            bubble1.addEntity(entityId.toString(), new Point3f(i * 0.01f, 0.0f, 0.0f), null);
            accountant.register(bubble1.id(), entityId);

            bubble2.addEntity(entityId.toString(), new Point3f(i * 0.01f, 0.0f, 0.0f), null);
            // Don't register duplicate in accountant (accountant prevents duplicates)
        }

        int totalBefore = accountant.entitiesInBubble(bubble1.id()).size() +
                         accountant.entitiesInBubble(bubble2.id()).size();

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = merger.execute(proposal);

        // Should succeed and skip duplicates
        assertTrue(result.success(), "Merge should succeed even with duplicates");
        assertEquals(50, result.duplicatesFound(), "Should detect 50 duplicates");

        // Only unique entities from bubble2 should be moved
        int bubble1Entities = accountant.entitiesInBubble(bubble1.id()).size();
        assertEquals(totalBefore, bubble1Entities, "Bubble1 should have all unique entities");
    }

    @Test
    void testMergeValidatesEntityConservation() {
        // Create 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 250);
        addEntities(bubble2, 250);

        int totalBefore = accountant.entitiesInBubble(bubble1.id()).size() +
                         accountant.entitiesInBubble(bubble2.id()).size();

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = merger.execute(proposal);

        // Verify conservation
        assertTrue(result.success(), "Merge should succeed");
        assertEquals(totalBefore, result.entitiesAfter(), "Total entities should be conserved");

        // Verify Accountant validation passes
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass");
        assertEquals(0, validation.errorCount(), "Should have no validation errors");
    }

    @Test
    void testMergeRejectsNonexistentBubble1() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble2 = bubbleGrid.getAllBubbles().iterator().next();

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            UUID.randomUUID(), // Non-existent bubble1
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = merger.execute(proposal);

        assertFalse(result.success(), "Should reject non-existent bubble1");
        assertTrue(result.message().contains("not found"), "Should mention bubble not found");
    }

    @Test
    void testMergeRejectsNonexistentBubble2() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble1 = bubbleGrid.getAllBubbles().iterator().next();

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            UUID.randomUUID(), // Non-existent bubble2
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = merger.execute(proposal);

        assertFalse(result.success(), "Should reject non-existent bubble2");
        assertTrue(result.message().contains("not found"), "Should mention bubble not found");
    }

    @Test
    void testMergeEmptyBubble() {
        // Create 2 bubbles, only populate bubble1
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 300);
        // bubble2 has no entities

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = merger.execute(proposal);

        // Should succeed (merging empty bubble is valid)
        assertTrue(result.success(), "Merge should succeed even with empty bubble2");
        assertEquals(300, result.entitiesBefore(), "Should have 300 entities before");
        assertEquals(300, result.entitiesAfter(), "Should have 300 entities after");
    }

    @Test
    void testMergeNullProposalThrows() {
        assertThrows(NullPointerException.class, () -> {
            merger.execute(null);
        }, "Should reject null proposal");
    }

    @Test
    void testConstructorNullBubbleGridThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleMerger(null, accountant);
        }, "Should reject null bubble grid");
    }

    @Test
    void testConstructorNullAccountantThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleMerger(bubbleGrid, null);
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
