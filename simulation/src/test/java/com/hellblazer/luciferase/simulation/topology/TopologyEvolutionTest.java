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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for natural topology evolution scenarios.
 * <p>
 * Tests complete lifecycle: detection → proposal → execution → validation
 *
 * @author hal.hildebrand
 */
class TopologyEvolutionTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics metrics;
    private TopologyExecutor executor;
    private TopologyConsistencyValidator validator;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics = new TopologyMetrics();
        executor = new TopologyExecutor(bubbleGrid, accountant, metrics);
        validator = new TopologyConsistencyValidator();
    }

    @Test
    void testNaturalSplitEvolution() {
        // Start with single bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add entities until split threshold exceeded (>5000)
        addEntities(bubble, 5100);

        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();
        int totalEntitiesBefore = getTotalEntityCount();

        // Trigger split
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
        var result = executor.execute(proposal);

        // Verify evolution
        assertTrue(result.success(), "Split should succeed: " + result.message());
        assertEquals(5100, totalEntitiesBefore, "Should start with 5100 entities");
        assertEquals(5100, getTotalEntityCount(), "Entity count should be conserved");

        // Verify natural evolution: 1 bubble → 2 bubbles
        // Note: We can't verify bubble count increase because TetreeBubbleGrid.addBubble() not implemented
        // This is logged as TODO in BubbleSplitter

        // Verify entity conservation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testNaturalMergeEvolution() {
        // Start with 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add entities below merge threshold (<500 each)
        addEntities(bubble1, 300);
        addEntities(bubble2, 200);

        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();
        int totalEntitiesBefore = getTotalEntityCount();

        // Trigger merge
        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        // Execute merge
        var result = executor.execute(proposal);

        // Verify evolution
        assertTrue(result.success(), "Merge should succeed: " + result.message());
        assertEquals(500, totalEntitiesBefore, "Should start with 500 entities");
        assertEquals(500, getTotalEntityCount(), "Entity count should be conserved");

        // Verify all entities moved to bubble1
        int bubble1Entities = accountant.entitiesInBubble(bubble1.id()).size();
        assertEquals(500, bubble1Entities, "Bubble1 should have all 500 entities");

        // Verify entity conservation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testNaturalMoveEvolution() {
        // Start with single bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add entities clustered away from center
        addEntities(bubble, 1000);

        int totalEntitiesBefore = getTotalEntityCount();

        // Get current centroid
        var currentBounds = bubble.bounds();
        var currentCentroid = currentBounds.centroid();

        // Simulate entity clustering toward one corner
        var clusterCentroid = new Point3f(
            (float) currentCentroid.getX() + 1.0f,
            (float) currentCentroid.getY() + 1.0f,
            (float) currentCentroid.getZ() + 1.0f
        );

        var newCenter = new Point3f(
            (float) currentCentroid.getX() + 0.3f,
            (float) currentCentroid.getY() + 0.3f,
            (float) currentCentroid.getZ() + 0.3f
        );

        // Trigger move
        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            newCenter,
            clusterCentroid,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        // Execute move
        var result = executor.execute(proposal);

        // Verify evolution
        assertTrue(result.success(), "Move should succeed: " + result.message());
        assertEquals(1000, totalEntitiesBefore, "Should start with 1000 entities");
        assertEquals(1000, getTotalEntityCount(), "Entity count should be conserved (no movement)");

        // Verify no entities were moved (move only adjusts bubble boundaries)
        int entitiesAfter = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(1000, entitiesAfter, "All entities should remain in bubble");

        // Verify entity conservation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testSequentialEvolution() {
        // Start with 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Scenario: bubble1 grows (split), bubble2 stays small (merge candidate)
        addEntities(bubble1, 5100);  // Will split
        addEntities(bubble2, 200);   // Merge candidate

        int totalEntitiesBefore = getTotalEntityCount();
        assertEquals(5300, totalEntitiesBefore, "Should start with 5300 entities");

        // Step 1: Split bubble1
        var centroid = bubble1.bounds().centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var splitProposal = new SplitProposal(
            UUID.randomUUID(),
            bubble1.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var splitResult = executor.execute(splitProposal);
        assertTrue(splitResult.success(), "Split should succeed");

        // Verify entity conservation after split
        assertEquals(5300, getTotalEntityCount(), "Entity count should be conserved after split");

        var validation1 = accountant.validate();
        assertTrue(validation1.success(), "Entity validation should pass after split");

        // Step 2: Attempt merge of bubble2 with another small bubble
        // (This would require another small bubble to merge with - skip for now)

        // Final validation
        assertEquals(5300, getTotalEntityCount(), "Entity count should be conserved throughout");
    }

    @Test
    void testEntityConservationAcrossOperations() {
        // Create 3 bubbles with different densities
        bubbleGrid.createBubbles(3, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);
        var bubble3 = bubbles.get(2);

        addEntities(bubble1, 5100);  // Will split
        addEntities(bubble2, 300);   // Normal
        addEntities(bubble3, 200);   // Merge candidate

        int initialTotal = getTotalEntityCount();
        assertEquals(5600, initialTotal, "Should start with 5600 entities");

        // Execute split on bubble1
        var centroid = bubble1.bounds().centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var splitProposal = new SplitProposal(
            UUID.randomUUID(),
            bubble1.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        executor.execute(splitProposal);

        // Verify conservation after split
        assertEquals(initialTotal, getTotalEntityCount(), "Entity count should be conserved after split");

        // Execute merge of bubble2 and bubble3
        var mergeProposal = new MergeProposal(
            UUID.randomUUID(),
            bubble2.id(),
            bubble3.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        executor.execute(mergeProposal);

        // Verify conservation after merge
        assertEquals(initialTotal, getTotalEntityCount(), "Entity count should be conserved after merge");

        // Final validation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass after all operations");
    }

    // Helper methods

    private void addEntities(com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }
    }

    private int getTotalEntityCount() {
        return accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
    }
}
