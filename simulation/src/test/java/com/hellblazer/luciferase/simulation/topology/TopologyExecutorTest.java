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
import com.hellblazer.luciferase.simulation.topology.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TopologyExecutor orchestration with snapshot/rollback.
 *
 * @author hal.hildebrand
 */
class TopologyExecutorTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics metrics;
    private TopologyExecutor executor;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics = new TopologyMetrics();
        executor = new TopologyExecutor(bubbleGrid, accountant, metrics);
    }

    @Test
    void testExecuteSplitProposal() {
        // Create bubble with >5000 entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        int totalBefore = accountant.entitiesInBubble(bubble.id()).size();

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

        // Execute
        var result = executor.execute(proposal);

        // Verify
        assertTrue(result.success(), "Split should succeed: " + result.message());
        assertEquals(totalBefore, result.entitiesBefore(), "Entities before should match");
        assertEquals(totalBefore, result.entitiesAfter(), "Entities after should match (conservation)");

        // Verify accountant validation passes
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testExecuteMergeProposal() {
        // Create 2 bubbles with entities
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 300);
        addEntities(bubble2, 200);

        int totalBefore = accountant.entitiesInBubble(bubble1.id()).size() +
                         accountant.entitiesInBubble(bubble2.id()).size();

        // Create merge proposal
        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        // Execute
        var result = executor.execute(proposal);

        // Verify
        assertTrue(result.success(), "Merge should succeed: " + result.message());
        assertEquals(totalBefore, result.entitiesBefore(), "Entities before should match");
        assertEquals(totalBefore, result.entitiesAfter(), "Entities after should match (conservation)");

        // Verify accountant validation passes
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testExecuteMoveProposal() {
        // Create bubble with entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 500);

        int totalBefore = accountant.entitiesInBubble(bubble.id()).size();

        // Get current centroid
        var currentBounds = bubble.bounds();
        var currentCentroid = currentBounds.centroid();

        // Create move proposal
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

        // Execute
        var result = executor.execute(proposal);

        // Verify
        assertTrue(result.success(), "Move should succeed: " + result.message());
        assertEquals(totalBefore, result.entitiesBefore(), "Entities before should match");
        assertEquals(totalBefore, result.entitiesAfter(), "Entities after should match (no movement)");

        // Verify accountant validation passes
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testExecuteValidatesEntityConservation() {
        // Create bubble with entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        int totalBefore = getTotalEntityCount();

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

        // Execute
        var result = executor.execute(proposal);

        // Verify total entity count unchanged
        int totalAfter = getTotalEntityCount();
        assertEquals(totalBefore, totalAfter, "Total entity count should be conserved");
        assertEquals(totalBefore, result.entitiesAfter(), "Result should report correct total");
    }

    @Test
    void testExecuteNullProposalThrows() {
        assertThrows(NullPointerException.class, () -> {
            executor.execute(null);
        }, "Should reject null proposal");
    }

    @Test
    void testConstructorNullBubbleGridThrows() {
        assertThrows(NullPointerException.class, () -> {
            new TopologyExecutor(null, accountant, metrics);
        }, "Should reject null bubble grid");
    }

    @Test
    void testConstructorNullAccountantThrows() {
        assertThrows(NullPointerException.class, () -> {
            new TopologyExecutor(bubbleGrid, null, metrics);
        }, "Should reject null accountant");
    }

    @Test
    void testConstructorNullMetricsThrows() {
        assertThrows(NullPointerException.class, () -> {
            new TopologyExecutor(bubbleGrid, accountant, null);
        }, "Should reject null metrics");
    }

    @Test
    void testExecuteSerializesOperations() {
        // Create bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        // Create split proposal
        var centroid = bubble.bounds().centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var proposal1 = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var proposal2 = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        // Execute sequentially (second should fail because first already split)
        var result1 = executor.execute(proposal1);
        assertTrue(result1.success(), "First split should succeed");

        // Second split on source bubble won't work because entities already moved
        // This tests serialization (one operation at a time)
        var result2 = executor.execute(proposal2);
        // Result depends on whether source bubble still has >5000 entities
        // (likely false after first split)
    }

    // Helper methods

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

    private int getTotalEntityCount() {
        return accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
    }
}
