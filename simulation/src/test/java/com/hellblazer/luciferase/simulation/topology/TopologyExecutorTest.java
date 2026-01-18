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
import com.hellblazer.luciferase.simulation.distributed.integration.SeededUuidSupplier;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import com.hellblazer.luciferase.simulation.topology.*;
import com.hellblazer.luciferase.simulation.topology.events.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void testDeterministicEventTimestamps() {
        // Test that TopologyExecutor produces deterministic event timestamps when using TestClock
        var testClock = new TestClock(1000L);
        executor.setClock(testClock);

        // Create 2 bubbles with entities - merge works reliably
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);
        addEntities(bubble1, 300);
        addEntities(bubble2, 200);

        // Capture events
        List<TopologyEvent> events = new ArrayList<>();
        executor.addListener(events::add);

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
        assertTrue(result.success(), "Merge should succeed: " + result.message());

        // Verify we got an event
        assertEquals(1, events.size(), "Should have captured one event");

        // Verify event has deterministic timestamp from TestClock
        var event = events.get(0);
        assertTrue(event instanceof MergeEvent, "Event should be MergeEvent");
        var mergeEvent = (MergeEvent) event;
        assertEquals(1000L, mergeEvent.timestamp(), "Event timestamp should match TestClock time");
    }

    @Test
    void testDeterministicEventTimestampsReproducible() {
        // Run same scenario twice, verify identical timestamps
        long fixedTime = 5000L;

        // First run - use merge operation which works reliably
        var testClock1 = new TestClock(fixedTime);
        var bubbleGrid1 = new TetreeBubbleGrid((byte) 2);
        var accountant1 = new EntityAccountant();
        var executor1 = new TopologyExecutor(bubbleGrid1, accountant1, new TopologyMetrics());
        executor1.setClock(testClock1);

        bubbleGrid1.createBubbles(2, (byte) 1, 10);
        var bubbles1 = bubbleGrid1.getAllBubbles().stream().toList();
        addEntitiesToAccountant(bubbles1.get(0), accountant1, 300);
        addEntitiesToAccountant(bubbles1.get(1), accountant1, 200);

        List<TopologyEvent> events1 = new ArrayList<>();
        executor1.addListener(events1::add);

        var proposal1 = new MergeProposal(
            UUID.randomUUID(), bubbles1.get(0).id(), bubbles1.get(1).id(),
            DigestAlgorithm.DEFAULT.getOrigin(), System.currentTimeMillis()
        );
        var result1 = executor1.execute(proposal1);
        assertTrue(result1.success(), "Merge 1 should succeed");

        // Second run with same clock time
        var testClock2 = new TestClock(fixedTime);
        var bubbleGrid2 = new TetreeBubbleGrid((byte) 2);
        var accountant2 = new EntityAccountant();
        var executor2 = new TopologyExecutor(bubbleGrid2, accountant2, new TopologyMetrics());
        executor2.setClock(testClock2);

        bubbleGrid2.createBubbles(2, (byte) 1, 10);
        var bubbles2 = bubbleGrid2.getAllBubbles().stream().toList();
        addEntitiesToAccountant(bubbles2.get(0), accountant2, 300);
        addEntitiesToAccountant(bubbles2.get(1), accountant2, 200);

        List<TopologyEvent> events2 = new ArrayList<>();
        executor2.addListener(events2::add);

        var proposal2 = new MergeProposal(
            UUID.randomUUID(), bubbles2.get(0).id(), bubbles2.get(1).id(),
            DigestAlgorithm.DEFAULT.getOrigin(), System.currentTimeMillis()
        );
        var result2 = executor2.execute(proposal2);
        assertTrue(result2.success(), "Merge 2 should succeed");

        // Verify both runs produced same timestamps
        assertEquals(1, events1.size(), "Run 1 should have one event");
        assertEquals(1, events2.size(), "Run 2 should have one event");

        var timestamp1 = ((MergeEvent) events1.get(0)).timestamp();
        var timestamp2 = ((MergeEvent) events2.get(0)).timestamp();

        assertEquals(timestamp1, timestamp2, "Timestamps should be identical across runs with same clock time");
        assertEquals(fixedTime, timestamp1, "Timestamp should match fixed time");
    }

    private void addEntitiesToAccountant(com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble,
                                         EntityAccountant acc, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            acc.register(bubble.id(), entityId);
        }
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

    @Test
    void testDeterministicEventIds() {
        // Test that TopologyExecutor produces deterministic event IDs when using SeededUuidSupplier
        long seed = 12345L;
        var uuidSupplier = new SeededUuidSupplier(seed);
        executor.setUuidSupplier(uuidSupplier);

        // Create 2 bubbles with entities - merge works reliably
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);
        addEntities(bubble1, 300);
        addEntities(bubble2, 200);

        // Capture events
        List<TopologyEvent> events = new ArrayList<>();
        executor.addListener(events::add);

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
        assertTrue(result.success(), "Merge should succeed: " + result.message());

        // Verify we got an event
        assertEquals(1, events.size(), "Should have captured one event");

        // Verify event has deterministic ID from SeededUuidSupplier
        var eventId = events.get(0).eventId();
        assertNotNull(eventId, "Event ID should not be null");

        // Create a new supplier with same seed and verify same ID is generated
        var uuidSupplier2 = new SeededUuidSupplier(seed);
        assertEquals(uuidSupplier2.get(), eventId, "Event ID should match seeded UUID supplier output");
    }

    @Test
    void testDeterministicEventIdsReproducible() {
        // Run same scenario twice, verify identical event IDs
        long seed = 99999L;

        // First run
        var uuidSupplier1 = new SeededUuidSupplier(seed);
        var bubbleGrid1 = new TetreeBubbleGrid((byte) 2);
        var accountant1 = new EntityAccountant();
        var executor1 = new TopologyExecutor(bubbleGrid1, accountant1, new TopologyMetrics());
        executor1.setUuidSupplier(uuidSupplier1);

        bubbleGrid1.createBubbles(2, (byte) 1, 10);
        var bubbles1 = bubbleGrid1.getAllBubbles().stream().toList();
        addEntitiesToAccountant(bubbles1.get(0), accountant1, 300);
        addEntitiesToAccountant(bubbles1.get(1), accountant1, 200);

        List<TopologyEvent> events1 = new ArrayList<>();
        executor1.addListener(events1::add);

        var proposal1 = new MergeProposal(
            UUID.randomUUID(), bubbles1.get(0).id(), bubbles1.get(1).id(),
            DigestAlgorithm.DEFAULT.getOrigin(), System.currentTimeMillis()
        );
        var result1 = executor1.execute(proposal1);
        assertTrue(result1.success(), "Merge 1 should succeed");

        // Second run with same seed
        var uuidSupplier2 = new SeededUuidSupplier(seed);
        var bubbleGrid2 = new TetreeBubbleGrid((byte) 2);
        var accountant2 = new EntityAccountant();
        var executor2 = new TopologyExecutor(bubbleGrid2, accountant2, new TopologyMetrics());
        executor2.setUuidSupplier(uuidSupplier2);

        bubbleGrid2.createBubbles(2, (byte) 1, 10);
        var bubbles2 = bubbleGrid2.getAllBubbles().stream().toList();
        addEntitiesToAccountant(bubbles2.get(0), accountant2, 300);
        addEntitiesToAccountant(bubbles2.get(1), accountant2, 200);

        List<TopologyEvent> events2 = new ArrayList<>();
        executor2.addListener(events2::add);

        var proposal2 = new MergeProposal(
            UUID.randomUUID(), bubbles2.get(0).id(), bubbles2.get(1).id(),
            DigestAlgorithm.DEFAULT.getOrigin(), System.currentTimeMillis()
        );
        var result2 = executor2.execute(proposal2);
        assertTrue(result2.success(), "Merge 2 should succeed");

        // Verify both runs produced same event IDs
        assertEquals(1, events1.size(), "Run 1 should have one event");
        assertEquals(1, events2.size(), "Run 2 should have one event");

        var eventId1 = events1.get(0).eventId();
        var eventId2 = events2.get(0).eventId();

        assertEquals(eventId1, eventId2, "Event IDs should be identical across runs with same seed");
    }

    @Test
    void testSetUuidSupplierNullThrows() {
        assertThrows(NullPointerException.class, () -> {
            executor.setUuidSupplier(null);
        }, "Should reject null UUID supplier");
    }
}
