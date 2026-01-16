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
import com.hellblazer.luciferase.simulation.topology.BubbleSplitter;
import com.hellblazer.luciferase.simulation.topology.SplitPlane;
import com.hellblazer.luciferase.simulation.topology.SplitProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BubbleSplitter atomic entity redistribution.
 *
 * @author hal.hildebrand
 */
class BubbleSplitterTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics metrics;
    private BubbleSplitter splitter;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics = new TopologyMetrics();
        splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
    }

    @Test
    void testSplitWithSufficientEntities() {
        // Create bubble with >5000 entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        int entitiesBefore = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(5100, entitiesBefore, "Should have 5100 entities before split");

        // Create split proposal
        var centroid = bubble.bounds().centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        // Execute split
        var result = splitter.execute(proposal);

        // Verify result
        assertTrue(result.success(), "Split should succeed: " + result.message());
        assertNotNull(result.newBubbleId(), "New bubble ID should be set");
        assertEquals(5100, result.entitiesBefore(), "Entities before should be 5100");
        assertEquals(5100, result.entitiesAfter(), "Entities after should be 5100");

        // Verify entity conservation
        int sourceBubbleEntities = accountant.entitiesInBubble(bubble.id()).size();
        int newBubbleEntities = accountant.entitiesInBubble(result.newBubbleId()).size();
        assertEquals(5100, sourceBubbleEntities + newBubbleEntities, "Total entities should be conserved");

        // Verify no duplicates
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testSplitPartitionsEntitiesByPlane() {
        // Create bubble with entities on both sides of split plane
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add entities at x=1 (low side) and x=10 (high side)
        for (int i = 0; i < 2550; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(1.0f, 5.0f, 5.0f), null);
            accountant.register(bubble.id(), entityId);
        }
        for (int i = 0; i < 2550; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(10.0f, 5.0f, 5.0f), null);
            accountant.register(bubble.id(), entityId);
        }

        // Split plane at x=5.5 (divides entities evenly)
        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 5.5f);
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        assertTrue(result.success(), "Split should succeed");

        // Verify roughly even partition (entities at x=5 moved, entities at x=-5 stayed)
        int sourceBubbleEntities = accountant.entitiesInBubble(bubble.id()).size();
        int newBubbleEntities = accountant.entitiesInBubble(result.newBubbleId()).size();

        // Entities on positive side (x=5) should have moved to new bubble
        assertEquals(2550, newBubbleEntities, "New bubble should have ~2550 entities (positive side)");
        assertEquals(2550, sourceBubbleEntities, "Source bubble should have ~2550 entities (negative side)");
    }

    @Test
    void testSplitRejectsNonexistentBubble() {
        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 0.0f);
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            UUID.randomUUID(), // Non-existent bubble
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        assertFalse(result.success(), "Should reject non-existent bubble");
        assertTrue(result.message().contains("not found"), "Should mention bubble not found");
        assertNull(result.newBubbleId(), "New bubble ID should be null on failure");
    }

    @Test
    void testSplitRejectsEmptyBubble() {
        // Create bubble with no entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 0.0f);
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        assertFalse(result.success(), "Should reject empty bubble");
        assertTrue(result.message().contains("no entities"), "Should mention no entities");
    }

    @Test
    void testSplitValidatesEntityConservation() {
        // Create bubble with entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        int entitiesBefore = accountant.entitiesInBubble(bubble.id()).size();

        var centroid = bubble.bounds().centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        // Verify conservation
        assertTrue(result.success(), "Split should succeed");
        assertEquals(entitiesBefore, result.entitiesAfter(), "Total entities should be conserved");

        // Verify Accountant validation passes
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass");
        assertEquals(0, validation.errorCount(), "Should have no validation errors");
    }

    @Test
    void testSplitNullProposalThrows() {
        assertThrows(NullPointerException.class, () -> {
            splitter.execute(null);
        }, "Should reject null proposal");
    }

    @Test
    void testConstructorNullBubbleGridThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleSplitter(null, accountant, OperationTracker.NOOP, metrics);
        }, "Should reject null bubble grid");
    }

    @Test
    void testConstructorNullAccountantThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleSplitter(bubbleGrid, null, OperationTracker.NOOP, metrics);
        }, "Should reject null accountant");
    }

    @Test
    void testConstructorNullMetricsThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, null);
        }, "Should reject null metrics");
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
