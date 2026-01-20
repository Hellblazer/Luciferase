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
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TopologyProposal sealed interface and validation.
 *
 * @author hal.hildebrand
 */
class TopologyProposalTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private Clock clock;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        clock = Clock.fixed(1000L);  // Fixed time for deterministic tests
    }

    @Test
    void testSplitProposalValidation() {
        // Create bubble with >5000 entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add 5100 entities from (0, 0, 0) to (~51, ~51, ~51)
        for (int i = 0; i < 5100; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }

        // Entity AABB: (0, 0, 0) to (51, 51, 51), centroid ~(25.5, 25.5, 25.5)
        float entityCentroidX = 25.5f;

        // Create valid split proposal through entity centroid
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f), // Normal along X axis
            entityCentroidX                   // Through entity centroid
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);
        assertTrue(result.isValid(), "Valid split proposal should pass: " + result.reason());
    }

    @Test
    void testSplitProposalRejectsBelowThreshold() {
        // Create bubble with <5000 entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add only 1000 entities (below threshold)
        for (int i = 0; i < 1000; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }

        var centroid2 = bubble.centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid2.getX()
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);
        assertFalse(result.isValid(), "Split proposal should reject below threshold");
        assertTrue(result.reason().contains("does not exceed split threshold"),
                  "Should mention threshold: " + result.reason());
    }

    @Test
    void testSplitProposalRejectsNonexistentBubble() {
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            0.0f
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            UUID.randomUUID(), // Non-existent bubble
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);
        assertFalse(result.isValid(), "Should reject non-existent bubble");
        assertTrue(result.reason().contains("not found"), "Should mention not found: " + result.reason());
    }

    @Test
    void testMergeProposalValidation() {
        // Create 2 bubbles with <500 entities each
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add 300 entities to each (below threshold)
        for (int i = 0; i < 300; i++) {
            var entityId1 = UUID.randomUUID();
            bubble1.addEntity(
                entityId1.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble1.id(), entityId1);

            var entityId2 = UUID.randomUUID();
            bubble2.addEntity(
                entityId2.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble2.id(), entityId2);
        }

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        // Validation depends on whether bubbles are actually neighbors
        var neighbors = bubbleGrid.getNeighbors(bubble1.id());
        if (neighbors.contains(bubble2.id())) {
            assertTrue(result.isValid(), "Valid merge proposal should pass when bubbles are neighbors: " + result.reason());
        } else {
            assertFalse(result.isValid(), "Merge proposal should fail when bubbles are not neighbors");
            assertTrue(result.reason().contains("not adjacent"),
                      "Should mention not adjacent: " + result.reason());
        }
    }

    @Test
    void testMergeProposalRejectsAboveThreshold() {
        // Create 2 bubbles with >500 entities
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add 600 entities to bubble1 (above threshold)
        for (int i = 0; i < 600; i++) {
            var entityId = UUID.randomUUID();
            bubble1.addEntity(
                entityId.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble1.id(), entityId);
        }

        // Add 300 to bubble2
        for (int i = 0; i < 300; i++) {
            var entityId = UUID.randomUUID();
            bubble2.addEntity(
                entityId.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble2.id(), entityId);
        }

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);
        assertFalse(result.isValid(), "Should reject bubble above merge threshold");
        assertTrue(result.reason().contains("exceeds merge threshold"),
                  "Should mention threshold: " + result.reason());
    }

    @Test
    void testMergeProposalRejectsNonAdjacent() {
        // Create 3 bubbles where 1 and 3 are not adjacent
        bubbleGrid.createBubbles(3, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();

        // Assume bubbles 0 and 2 are not neighbors (depends on grid structure)
        // Add entities to both
        for (int i = 0; i < 300; i++) {
            for (var bubble : bubbles) {
                var entityId = UUID.randomUUID();
                bubble.addEntity(
                    entityId.toString(),
                    new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                    null
                );
                accountant.register(bubble.id(), entityId);
            }
        }

        // Try to merge non-adjacent bubbles
        var bubble1 = bubbles.get(0);
        var bubble3 = bubbles.get(2);

        var neighbors = bubbleGrid.getNeighbors(bubble1.id());
        if (!neighbors.contains(bubble3.id())) {
            var proposal = new MergeProposal(
                UUID.randomUUID(),
                bubble1.id(),
                bubble3.id(),
                DigestAlgorithm.DEFAULT.getOrigin(),
                clock.currentTimeMillis()
            );

            var result = proposal.validate(bubbleGrid);
            assertFalse(result.isValid(), "Should reject non-adjacent bubbles");
            assertTrue(result.reason().contains("not adjacent"),
                      "Should mention not adjacent: " + result.reason());
        }
    }

    @Test
    void testMoveProposalValidation() {
        // Create bubble with clustered entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add 500 entities clustered around (10, 10, 10) to (15, 15, 15)
        for (int i = 0; i < 500; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(10 + i * 0.01f, 10 + i * 0.01f, 10 + i * 0.01f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }

        // Entity AABB centroid: ~(12.5, 12.5, 12.5)
        float entityCentroidX = 12.5f;
        float entityCentroidY = 12.5f;
        float entityCentroidZ = 12.5f;

        var clusterCentroid = new Point3f(11.0f, 11.0f, 11.0f);  // Within entity bounds

        // Move 10% toward cluster centroid
        var newCenter = new Point3f(
            entityCentroidX + 0.1f * (clusterCentroid.x - entityCentroidX),
            entityCentroidY + 0.1f * (clusterCentroid.y - entityCentroidY),
            entityCentroidZ + 0.1f * (clusterCentroid.z - entityCentroidZ)
        );

        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            newCenter,
            clusterCentroid,
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);
        assertTrue(result.isValid(), "Valid move proposal should pass: " + result.reason());
    }

    @Test
    void testMoveProposalRejectsTooFarMove() {
        // Create bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add entities to enable entity-based Byzantine-resistant validation
        for (int i = 0; i < 1000; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(10 + i * 0.01f, 10 + i * 0.01f, 10 + i * 0.01f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }

        // Entity AABB: (10, 10, 10) to (20, 20, 20), centroid ~(15, 15, 15)
        // Radius ~8.66 (half diagonal of 10x10x10 cube)
        float entityCentroidX = 15.0f;
        float entityCentroidY = 15.0f;
        float entityCentroidZ = 15.0f;
        float approxRadius = 8.66f;

        // Try to move 3x the radius (too far - exceeds 2x limit)
        var newCenter = new Point3f(
            entityCentroidX + 3.0f * approxRadius,
            entityCentroidY,
            entityCentroidZ
        );

        var clusterCentroid = new Point3f(
            entityCentroidX,
            entityCentroidY,
            entityCentroidZ
        );

        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            newCenter,
            clusterCentroid,
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);
        assertFalse(result.isValid(), "Should reject move that's too far");
        assertTrue(result.reason().contains("too far"), "Should mention distance: " + result.reason());
    }

    @Test
    void testMoveProposalRejectsClusterOutsideBounds() {
        // Create bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add entities to enable entity-based Byzantine-resistant validation
        for (int i = 0; i < 1000; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(10 + i * 0.01f, 10 + i * 0.01f, 10 + i * 0.01f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }

        // Entity AABB: (10, 10, 10) to (20, 20, 20)
        // Cluster centroid way outside entity bounds
        var clusterCentroid = new Point3f(
            1000.0f,  // Way outside (10-20) range
            1000.0f,
            1000.0f
        );

        // newCenter within entity bounds (will pass distance check)
        var newCenter = new Point3f(
            15.0f,
            15.0f,
            15.0f
        );

        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            newCenter,
            clusterCentroid,
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);
        assertFalse(result.isValid(), "Should reject cluster outside bounds");
        assertTrue(result.reason().contains("outside") || result.reason().contains("bounds"),
                  "Should mention bounds: " + result.reason());
    }

    @Test
    void testValidationResultSuccess() {
        var success = ValidationResult.success();
        assertTrue(success.isValid(), "Success should be valid");
        assertNull(success.reason(), "Success should have no reason");
    }

    @Test
    void testSplitPlaneIntersection() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        var bounds = bubble.bounds();
        var centroid = bounds.centroid();

        // Plane through centroid should intersect
        var plane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        assertTrue(plane.intersects(bounds), "Plane through centroid should intersect bounds");

        // Plane far away should not intersect
        var rdgMin = bounds.rdgMin();
        var rdgMax = bounds.rdgMax();
        int rdgExtentX = rdgMax.x - rdgMin.x;
        var farPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX() + 10.0f * rdgExtentX
        );

        assertFalse(farPlane.intersects(bounds), "Plane far away should not intersect bounds");
    }
}
