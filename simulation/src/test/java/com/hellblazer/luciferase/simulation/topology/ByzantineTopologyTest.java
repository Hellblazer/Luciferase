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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Byzantine proposal rejection for topology changes.
 * <p>
 * Validates pre-validation logic that rejects invalid proposals
 * before they reach consensus voting.
 *
 * @author hal.hildebrand
 */
class ByzantineTopologyTest {

    private TetreeBubbleGrid bubbleGrid;
    private Clock clock;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        clock = Clock.fixed(1000L);  // Fixed time for deterministic tests
    }

    // ========== Split Proposal Byzantine Tests ==========

    @Test
    void testSplitProposal_RejectsNonexistentBubble() {
        // Create proposal for bubble that doesn't exist
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),  // Non-existent bubble
            new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 0.0f),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject split of non-existent bubble");
        assertTrue(result.reason().contains("not found"), "Should explain bubble not found");
    }

    @Test
    void testSplitProposal_RejectsBelowThreshold() {
        // Create bubble with <5000 entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add only 100 entities (well below 5000 threshold)
        for (int i = 0; i < 100; i++) {
            bubble.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 5.0f),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject split below threshold");
        assertTrue(result.reason().contains("5000"), "Should mention split threshold");
        assertTrue(result.reason().contains("100"), "Should mention actual entity count");
    }

    @Test
    void testSplitProposal_RejectsNullSplitPlane() {
        // Create bubble with sufficient entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        for (int i = 0; i < 5100; i++) {
            bubble.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        // Create proposal with null split plane (Byzantine input)
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            null,  // Byzantine: null split plane
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject null split plane");
        assertTrue(result.reason().contains("null"), "Should explain null plane rejection");
    }

    @Test
    void testSplitProposal_RejectsSplitPlaneOutsideBounds() {
        // Create bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        for (int i = 0; i < 5100; i++) {
            bubble.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        // Get bubble bounds
        var bounds = bubble.bounds();
        var centroid = bounds.centroid();

        // Create split plane far outside bubble (Byzantine: trying to create empty partition)
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX() + 100000.0f  // Very far from bubble
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject split plane outside bounds");
        assertTrue(result.reason().contains("intersect"), "Should explain intersection failure");
    }

    // ========== Merge Proposal Byzantine Tests ==========

    @Test
    void testMergeProposal_RejectsNonexistentBubble1() {
        // Create one bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble2 = bubbleGrid.getAllBubbles().iterator().next();

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),  // Non-existent bubble1
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject merge with non-existent bubble1");
        assertTrue(result.reason().contains("Bubble1 not found"), "Should explain bubble1 not found");
    }

    @Test
    void testMergeProposal_RejectsNonexistentBubble2() {
        // Create one bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble1 = bubbleGrid.getAllBubbles().iterator().next();

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            UUID.randomUUID(),  // Non-existent bubble2
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject merge with non-existent bubble2");
        assertTrue(result.reason().contains("Bubble2 not found"), "Should explain bubble2 not found");
    }

    @Test
    void testMergeProposal_RejectsBubble1AboveThreshold() {
        // Create 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add 600 entities to bubble1 (above 500 threshold)
        for (int i = 0; i < 600; i++) {
            bubble1.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        // Add 200 to bubble2 (below threshold)
        for (int i = 0; i < 200; i++) {
            bubble2.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject merge when bubble1 above threshold");
        assertTrue(result.reason().contains("600"), "Should mention bubble1 entity count");
        assertTrue(result.reason().contains("500"), "Should mention merge threshold");
    }

    @Test
    void testMergeProposal_RejectsBubble2AboveThreshold() {
        // Create 2 bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Add 200 to bubble1 (below threshold)
        for (int i = 0; i < 200; i++) {
            bubble1.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        // Add 600 entities to bubble2 (above 500 threshold)
        for (int i = 0; i < 600; i++) {
            bubble2.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject merge when bubble2 above threshold");
        assertTrue(result.reason().contains("600"), "Should mention bubble2 entity count");
        assertTrue(result.reason().contains("500"), "Should mention merge threshold");
    }

    @Test
    void testMergeProposal_RejectsNonAdjacentBubbles() {
        // Create 3 bubbles in a line: A - B - C
        // Try to merge A and C (not adjacent)
        bubbleGrid.createBubbles(3, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble3 = bubbles.get(2);

        // Add entities below threshold
        for (int i = 0; i < 200; i++) {
            bubble1.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
            bubble3.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble3.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject merge of non-adjacent bubbles");
        assertTrue(result.reason().contains("adjacent") || result.reason().contains("neighbor"),
                   "Should explain adjacency requirement");
    }

    // ========== Move Proposal Byzantine Tests ==========

    @Test
    void testMoveProposal_RejectsNonexistentBubble() {
        var proposal = new MoveProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),  // Non-existent bubble
            new Point3f(1.0f, 1.0f, 1.0f),
            new Point3f(2.0f, 2.0f, 2.0f),
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject move of non-existent bubble");
        assertTrue(result.reason().contains("not found"), "Should explain bubble not found");
    }

    @Test
    void testMoveProposal_RejectsExcessiveDistance() {
        // Create bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add entities
        for (int i = 0; i < 500; i++) {
            bubble.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        var currentBounds = bubble.bounds();
        var currentCentroid = currentBounds.centroid();

        // Try to move bubble very far (Byzantine: >2x radius)
        var newCenter = new Point3f(
            (float) currentCentroid.getX() + 1000000.0f,  // Very far
            (float) currentCentroid.getY() + 1000000.0f,
            (float) currentCentroid.getZ() + 1000000.0f
        );

        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            newCenter,
            new Point3f(5.0f, 5.0f, 5.0f),  // Doesn't matter for this test
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject excessive move distance");
        assertTrue(result.reason().contains("too far") || result.reason().contains("distance"),
                   "Should explain distance limit");
    }

    @Test
    void testMoveProposal_RejectsNullClusterCentroid() {
        // Create bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        for (int i = 0; i < 500; i++) {
            bubble.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        var currentBounds = bubble.bounds();
        var currentCentroid = currentBounds.centroid();

        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            new Point3f((float) currentCentroid.getX() + 1.0f,
                       (float) currentCentroid.getY() + 1.0f,
                       (float) currentCentroid.getZ() + 1.0f),
            null,  // Byzantine: null cluster centroid
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject null cluster centroid");
        assertTrue(result.reason().contains("null"), "Should explain null centroid rejection");
    }

    @Test
    void testMoveProposal_RejectsClusterCentroidOutsideBubble() {
        // Create bubble
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        for (int i = 0; i < 500; i++) {
            bubble.addEntity(UUID.randomUUID().toString(), new Point3f(5.0f + i * 0.01f, 5.0f, 5.0f), null);
        }

        var currentBounds = bubble.bounds();
        var currentCentroid = currentBounds.centroid();

        // Cluster centroid far outside bubble (Byzantine: fake clustering)
        var clusterCentroid = new Point3f(
            (float) currentCentroid.getX() + 100000.0f,
            (float) currentCentroid.getY() + 100000.0f,
            (float) currentCentroid.getZ() + 100000.0f
        );

        var proposal = new MoveProposal(
            UUID.randomUUID(),
            bubble.id(),
            new Point3f((float) currentCentroid.getX() + 0.1f,
                       (float) currentCentroid.getY() + 0.1f,
                       (float) currentCentroid.getZ() + 0.1f),
            clusterCentroid,
            DigestAlgorithm.DEFAULT.getOrigin(),
            clock.currentTimeMillis()
        );

        var result = proposal.validate(bubbleGrid);

        assertFalse(result.isValid(), "Should reject cluster centroid outside bubble");
        assertTrue(result.reason().contains("outside") || result.reason().contains("bounds"),
                   "Should explain bounds violation");
    }
}
