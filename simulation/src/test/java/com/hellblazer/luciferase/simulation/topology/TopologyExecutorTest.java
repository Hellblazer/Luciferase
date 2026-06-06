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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TopologyExecutor orchestration with snapshot/rollback.
 *
 * @author hal.hildebrand
 */
class TopologyExecutorTest {
    private static final TestClock JC1KH_CLOCK = new TestClock(1_000L); // determinism mandate (Luciferase-jc1kh)

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
        var centroid = bubble.centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
        );

        // Execute
        var result = executor.execute(proposal);

        // Bubble relocation is deferred (Luciferase-0frcy.123): the executor must NOT
        // report success for an unimplemented no-op move. It rolls back and reports failure.
        assertFalse(result.success(), "Move must report failure while relocation is unimplemented");
        assertTrue(result.message().contains("not yet implemented"),
                   "Failure message must state the operation is not yet implemented: " + result.message());

        // Entities are preserved across the rolled-back move.
        assertEquals(totalBefore, accountant.entitiesInBubble(bubble.id()).size(),
                     "Entities must be preserved after a rolled-back move");

        // Verify accountant validation passes
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    // ---- Luciferase-iveyb: concurrency, removed-bubble safety, split-event coverage ----

    /**
     * (c) execute(MoveProposal) on a bubble removed from the grid between proposal creation and execute()
     * must return a clean failure, never an NPE on getBubbleById()==null (TopologyExecutor 0frcy.48 path).
     */
    @Test
    void testRemovedBubbleMoveNoNpe() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 100);

        var c = bubble.bounds().centroid();
        var move = new MoveProposal(
            UUID.randomUUID(), bubble.id(),
            new Point3f((float) c.getX() + 0.1f, (float) c.getY() + 0.1f, (float) c.getZ() + 0.1f),
            new Point3f((float) c.getX() + 0.5f, (float) c.getY() + 0.5f, (float) c.getZ() + 0.5f),
            DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis());

        // The bubble disappears from the grid after the proposal references it.
        assertTrue(bubbleGrid.removeBubble(bubble.id()), "bubble must be removed from the grid");

        var result = assertDoesNotThrow(() -> executor.execute(move),
                                        "execute() on a removed bubble must not throw (no NPE on null bubble)");
        assertFalse(result.success(), "a move targeting a removed bubble must fail cleanly");
    }

    /**
     * (d) a successful split of a populated bubble must emit a SplitEvent whose entitiesMoved is positive.
     */
    @Test
    void testSplitEntitiesMovedPositive() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var captured = new java.util.concurrent.atomic.AtomicReference<SplitEvent>();
        executor.addListener(event -> {
            if (event instanceof SplitEvent se) {
                captured.set(se);
            }
        });

        var centroid = bubble.centroid();
        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), (float) centroid.getX());
        var proposal = new SplitProposal(UUID.randomUUID(), bubble.id(), splitPlane,
                                         DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis());

        var result = executor.execute(proposal);
        assertTrue(result.success(), "split should succeed: " + result.message());

        var event = captured.get();
        assertNotNull(event, "a SplitEvent must be emitted on a successful split");
        assertTrue(event.entitiesMoved() > 0,
                   "splitting a populated bubble must move a positive entity count, got " + event.entitiesMoved());
    }

    /**
     * (a) Two operations (split + merge) touching the SAME bubble submitted concurrently. execute() is guarded
     * by executionLock, so they serialize: the load-bearing safety property is that they run without exception
     * and WITHOUT corrupting entity accounting (no loss / no duplication), regardless of interleaving.
     *
     * <p>Note: contrary to the original finding's "exactly one success and one failure" assumption, BOTH
     * operations can legitimately succeed — split and merge both reuse {@code bubble1}'s id, so they compose
     * (split-then-merge or merge-then-split) rather than hard-conflict. The real invariant is serialized,
     * conservation-preserving execution, which is what this test pins.
     *
     * <p>(b) The executor wires NO consensus protocol — it executes already-approved proposals — so there is no
     * consensus call to verify here (documented invariant; TopologyExecutor exposes no consensus seam).
     */
    @Test
    void testConcurrentSplitMergeSameBubble() throws Exception {
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);
        addEntities(bubble1, 5100); // splittable
        addEntities(bubble2, 200);

        var centroid = bubble1.centroid();
        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), (float) centroid.getX());
        var split = new SplitProposal(UUID.randomUUID(), bubble1.id(), splitPlane,
                                      DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis());
        var merge = new MergeProposal(UUID.randomUUID(), bubble1.id(), bubble2.id(),
                                      DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis());

        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<TopologyExecutionResult>();
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

        Runnable runSplit = () -> {
            try { barrier.await(); results.add(executor.execute(split)); }
            catch (Throwable t) { errors.add(t); }
        };
        Runnable runMerge = () -> {
            try { barrier.await(); results.add(executor.execute(merge)); }
            catch (Throwable t) { errors.add(t); }
        };
        pool.submit(runSplit);
        pool.submit(runMerge);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "both operations must finish");

        assertTrue(errors.isEmpty(), "executionLock must serialize the operations without exceptions: " + errors);
        assertEquals(2, results.size(), "both operations must produce a result");
        // The load-bearing invariant: serialized execution must not corrupt entity accounting (no loss/dup),
        // whichever interleaving occurred.
        var validation = accountant.validate();
        assertTrue(validation.success(),
                   "entity accounting must remain valid after the serialized concurrent operations: "
                   + validation.details());
    }

    /**
     * (e) A degenerate SplitPlane positioned far outside the bubble (all entities on one side) must be handled
     * without exception or entity loss — the executor either splits with an empty side or fails cleanly, but
     * accounting stays conserved either way.
     */
    @Test
    void testDegenerateSplitPlaneHandled() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        // Plane offset far below every entity's x (entities have x = i*0.01 >= 0) → all entities one side.
        var degenerate = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), -1_000_000f);
        var proposal = new SplitProposal(UUID.randomUUID(), bubble.id(), degenerate,
                                         DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis());

        var result = assertDoesNotThrow(() -> executor.execute(proposal),
                                        "a degenerate split plane must not throw");
        assertNotNull(result, "execute must return a result for a degenerate plane");
        // Whether it succeeds (empty side) or fails cleanly, accounting must stay conserved (validate() checks
        // the global no-loss / no-duplication invariant).
        var validation = accountant.validate();
        assertTrue(validation.success(), "entity accounting must stay valid: " + validation.details());
    }

    @Test
    void testExecuteValidatesEntityConservation() {
        // Create bubble with entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        int totalBefore = getTotalEntityCount();

        // Create split proposal
        var centroid = bubble.centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
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
        var centroid = bubble.centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );

        var proposal1 = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        var proposal2 = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
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
            DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis()
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
            DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
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
            DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis()
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
            DigestAlgorithm.DEFAULT.getOrigin(), JC1KH_CLOCK.currentTimeMillis()
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

    /**
     * Verifies that when a split passes the BubbleSplitter but then fails the
     * executor-level validate(), rollback() restores the accountant distribution
     * to the pre-operation snapshot — no entities are orphaned on the removed
     * newBubbleId.
     *
     * <p>The accountant subclass makes validate() fail on the 2nd call: the
     * splitter's internal validate() is call #1 (passes), and the executor's
     * validate() at L325 is call #2 (fails). This exercises the previously-missing
     * snapshot-diff restore in rollback().
     */
    @Test
    void testRollbackRestoresAccountantDistributionAfterExecutorValidateFails() {
        // Build grid + entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Track entity IDs so we can verify location after rollback
        var entityIds = new ArrayList<UUID>();
        int entityCount = 5100;
        for (int i = 0; i < entityCount; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);
            bubble.addEntity(
                entityId.toString(),
                new Point3f(i * 0.001f, i * 0.001f, i * 0.001f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }

        // Capture pre-operation snapshot manually (same logic as takeSnapshot)
        var snapshotBefore = new java.util.HashMap<UUID, java.util.Set<UUID>>();
        for (var bId : accountant.getDistribution().keySet()) {
            snapshotBefore.put(bId, accountant.entitiesInBubble(bId));
        }

        // Replace accountant with one that fails on the 2nd validate() call so the
        // splitter's internal validate passes but the executor-level validate fails.
        var failingAccountant = new FailOnSecondValidateAccountant(accountant);

        var grid2 = bubbleGrid;  // same grid
        var executor2 = new TopologyExecutor(grid2, failingAccountant, metrics);

        // Proposal — valid split (>5000 entities, split plane through centroid)
        var centroid = bubble.centroid();
        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            (float) centroid.getX()
        );
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        // Execute — must fail at executor-level validate (not at splitter level)
        var result = executor2.execute(proposal);

        assertFalse(result.success(),
                    "Execute must return failure when executor-level validate fails");

        // Assertion 1: every entity resolves to a LIVE bubble (no orphan on removed newBubbleId)
        var liveBubbleIds = grid2.getAllBubbles().stream().map(b -> b.id()).collect(java.util.stream.Collectors.toSet());
        for (var entityId : entityIds) {
            var location = failingAccountant.getLocationOfEntity(entityId);
            assertNotNull(location,
                          "Entity " + entityId + " must have a bubble assignment after rollback");
            assertTrue(liveBubbleIds.contains(location),
                       "Entity " + entityId + " is assigned to removed bubble " + location
                       + " (not in live set " + liveBubbleIds + ")");
        }

        // Assertion 2: accountant distribution after rollback == pre-operation snapshot
        var distributionAfter = new java.util.HashMap<UUID, java.util.Set<UUID>>();
        for (var bId : failingAccountant.getDistribution().keySet()) {
            distributionAfter.put(bId, failingAccountant.entitiesInBubble(bId));
        }
        assertEquals(snapshotBefore.keySet(), distributionAfter.keySet(),
                     "Post-rollback bubble key set must match pre-operation snapshot");
        for (var entry : snapshotBefore.entrySet()) {
            assertEquals(entry.getValue(), distributionAfter.get(entry.getKey()),
                         "Entity set for bubble " + entry.getKey() + " must match snapshot after rollback");
        }
    }

    /**
     * Verifies that when a merge passes BubbleMerger but then fails the executor-level
     * validate(), rollback() restores ALL entities to their pre-operation bubbles.
     *
     * <p>Before the fix, rollback() only scanned bubbles absent from the snapshot
     * (transient bubbles). In a merge both bubble1 and bubble2 are snapshot keys, so
     * no transient bubble exists and the restore was a no-op — entities remained in
     * bubble1 and bubble2 was empty but still in the accountant. After the fix,
     * rollback() iterates the snapshot directly and reverse-moves every entity whose
     * current location differs.
     *
     * <p>BubbleMerger calls validate() once internally (call #1, passes).
     * TopologyExecutor's post-operation validate() is call #2 (forced to fail).
     */
    @Test
    void testRollbackRestoresAccountantDistributionAfterMergeValidateFails() {
        // Two bubbles with entities
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        // Track entity IDs so we can verify locations after rollback
        var entityIds1 = new ArrayList<UUID>();
        var entityIds2 = new ArrayList<UUID>();
        int count1 = 150;
        int count2 = 100;
        for (int i = 0; i < count1; i++) {
            var id = UUID.randomUUID();
            entityIds1.add(id);
            bubble1.addEntity(id.toString(), new Point3f(i * 0.01f, 0f, 0f), null);
            accountant.register(bubble1.id(), id);
        }
        for (int i = 0; i < count2; i++) {
            var id = UUID.randomUUID();
            entityIds2.add(id);
            bubble2.addEntity(id.toString(), new Point3f(0f, i * 0.01f, 0f), null);
            accountant.register(bubble2.id(), id);
        }

        // Capture pre-operation snapshot
        var snapshotBefore = new java.util.HashMap<UUID, java.util.Set<UUID>>();
        for (var bId : accountant.getDistribution().keySet()) {
            snapshotBefore.put(bId, accountant.entitiesInBubble(bId));
        }

        // Use FailOnSecondValidateAccountant: BubbleMerger's internal validate() is
        // call #1 (passes); executor-level validate() is call #2 (fails).
        var failingAccountant = new FailOnSecondValidateAccountant(accountant);
        var executor2 = new TopologyExecutor(bubbleGrid, failingAccountant, metrics);

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        var result = executor2.execute(proposal);

        assertFalse(result.success(),
                    "Execute must return failure when executor-level validate fails after merge");

        // Every entity originally in bubble1 must still be in bubble1
        for (var entityId : entityIds1) {
            var location = failingAccountant.getLocationOfEntity(entityId);
            assertEquals(bubble1.id(), location,
                         "Entity from bubble1 must be restored to bubble1 after rollback, got: " + location);
        }

        // Every entity originally in bubble2 must be restored to bubble2
        for (var entityId : entityIds2) {
            var location = failingAccountant.getLocationOfEntity(entityId);
            assertEquals(bubble2.id(), location,
                         "Entity from bubble2 must be restored to bubble2 after rollback, got: " + location);
        }

        // Full distribution must equal the pre-operation snapshot
        var distributionAfter = new java.util.HashMap<UUID, java.util.Set<UUID>>();
        for (var bId : failingAccountant.getDistribution().keySet()) {
            distributionAfter.put(bId, failingAccountant.entitiesInBubble(bId));
        }
        assertEquals(snapshotBefore.keySet(), distributionAfter.keySet(),
                     "Post-rollback bubble key set must match pre-operation snapshot");
        for (var entry : snapshotBefore.entrySet()) {
            assertEquals(entry.getValue(), distributionAfter.get(entry.getKey()),
                         "Entity set for bubble " + entry.getKey() + " must match snapshot after rollback");
        }
    }

    /**
     * EntityAccountant subclass that makes validate() fail on the 2nd call.
     * The first call (from BubbleSplitter's internal validate) returns a passing
     * result; the second call (from TopologyExecutor's executor-level validate)
     * returns a synthetic failure. This simulates a split that passes the splitter
     * but then fails the executor-level validate check.
     */
    static final class FailOnSecondValidateAccountant extends EntityAccountant {

        private final java.util.concurrent.atomic.AtomicInteger validateCalls =
            new java.util.concurrent.atomic.AtomicInteger(0);

        /**
         * Copy-constructs by replaying all registrations from an existing accountant.
         */
        FailOnSecondValidateAccountant(EntityAccountant source) {
            // Replicate registrations: for each bubble, for each entity, register here
            var distribution = source.getDistribution();
            for (var bubbleId : distribution.keySet()) {
                for (var entityId : source.entitiesInBubble(bubbleId)) {
                    register(bubbleId, entityId);
                }
            }
        }

        @Override
        public com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult validate() {
            int call = validateCalls.incrementAndGet();
            if (call >= 2) {
                // Simulate executor-level validate failure
                return new com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult(
                    false, 1,
                    java.util.List.of("Synthetic executor-level validate failure (call #" + call + ")")
                );
            }
            return super.validate();
        }
    }
}
