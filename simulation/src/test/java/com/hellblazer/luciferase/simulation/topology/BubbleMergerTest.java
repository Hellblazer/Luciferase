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

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.topology.BubbleMerger;
import com.hellblazer.luciferase.simulation.topology.MergeProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import org.mockito.Mockito;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

/**
 * Tests for BubbleMerger with duplicate detection.
 *
 * @author hal.hildebrand
 */
class BubbleMergerTest {
    private static final TestClock JC1KH_CLOCK = new TestClock(1_000L); // determinism mandate (Luciferase-jc1kh)

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics metrics;
    private BubbleMerger merger;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics = new TopologyMetrics();
        merger = new BubbleMerger(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
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
            JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
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
            JC1KH_CLOCK.currentTimeMillis()
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
            new BubbleMerger(null, accountant, OperationTracker.NOOP, metrics);
        }, "Should reject null bubble grid");
    }

    @Test
    void testConstructorNullAccountantThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleMerger(bubbleGrid, null, OperationTracker.NOOP, metrics);
        }, "Should reject null accountant");
    }

    @Test
    void testConstructorNullMetricsThrows() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleMerger(bubbleGrid, accountant, OperationTracker.NOOP, null);
        }, "Should reject null metrics");
    }

    /**
     * Verifies that a moveBetweenBubbles failure aborts the entire merge atomically —
     * no entities are stranded or lost.  After the partial-failure the accountant must
     * report that:
     * <ul>
     *   <li>Every entity is still in exactly one bubble (no orphans)</li>
     *   <li>bubble2 entity count == entities2Before (full rollback to source)</li>
     *   <li>bubble1 entity count == entities1Before (no net change)</li>
     *   <li>accountant.validate() passes</li>
     * </ul>
     */
    @Test
    void testPartialMergeFailureRollsBackNoEntitiesStranded() {
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        int entities1Before = 30;
        int entities2Before = 60;
        addEntities(bubble1, entities1Before);
        addEntities(bubble2, entities2Before);

        // Fail forward moves from bubble2→bubble1 after 20 successes.
        // Rollback moves (bubble1→bubble2) pass through to the delegate.
        var failingAccountant = new FailAfterNForwardMovesAccountant(accountant, 20, bubble2.id(), bubble1.id());
        var failingMerger = new BubbleMerger(bubbleGrid, failingAccountant, OperationTracker.NOOP, metrics);

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        var result = failingMerger.execute(proposal);

        // Merge must fail
        assertFalse(result.success(), "Merge must fail when a forward move fails");

        // No entities must be stranded — both bubbles should be at their pre-merge counts
        int b1After = accountant.entitiesInBubble(bubble1.id()).size();
        int b2After = accountant.entitiesInBubble(bubble2.id()).size();

        assertEquals(entities1Before, b1After,
                     "bubble1 entity count must be restored to pre-merge value after rollback");
        assertEquals(entities2Before, b2After,
                     "bubble2 entity count must be restored to pre-merge value after rollback — no entities stranded");
        assertEquals(entities1Before + entities2Before, b1After + b2After,
                     "Total entity count must be conserved after rollback");

        // Accountant must be fully consistent
        var validation = accountant.validate();
        assertTrue(validation.success(),
                   "Accountant must be consistent after failed merge rollback: " + validation.details());
    }

    /**
     * Verifies that conservation is checked against the snapshot captured at merge
     * start plus {@code entitiesMoved}, NOT via two separate live accountant reads
     * that could race with concurrent mutations.
     * <p>
     * Runs a normal successful merge and checks that the result reports the correct
     * snapshot-derived conservation figures.
     */
    @Test
    void testConservationDerivedFromSnapshotNotLiveReads() {
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 150);
        addEntities(bubble2, 100);

        int snapshotBefore = accountant.entitiesInBubble(bubble1.id()).size()
                           + accountant.entitiesInBubble(bubble2.id()).size();
        assertEquals(250, snapshotBefore);

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        var result = merger.execute(proposal);

        assertTrue(result.success(), "Merge should succeed: " + result.message());

        // entitiesBefore must reflect the snapshot captured at the start of the merge
        assertEquals(snapshotBefore, result.entitiesBefore(),
                     "entitiesBefore must equal the snapshot taken at start of merge");
        // entitiesAfter must equal the snapshot (no leak, no duplication)
        assertEquals(snapshotBefore, result.entitiesAfter(),
                     "entitiesAfter must equal entitiesBefore (conservation)");

        // Live counts must also agree
        int b1After = accountant.entitiesInBubble(bubble1.id()).size();
        int b2After = accountant.entitiesInBubble(bubble2.id()).size();
        assertEquals(snapshotBefore, b1After + b2After,
                     "Live entity counts must also sum to the snapshot");

        var validation = accountant.validate();
        assertTrue(validation.success(), "Accountant must be valid after merge");
    }

    /**
     * Verifies that when BOTH the forward merge move AND the corresponding rollback move
     * fail, the merger:
     * <ul>
     *   <li>does NOT throw</li>
     *   <li>returns a failure result</li>
     *   <li>continues the rollback loop for remaining entities (best-effort)</li>
     * </ul>
     * The orphaned-entity log.error is the diagnosability contract; we cannot assert
     * log output without a capturing appender, so we assert no-throw + failure result.
     */
    @Test
    void testRollbackMoveFailureLogsAndContinues() {
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 20);
        addEntities(bubble2, 60);

        // Fail forward moves from bubble2→bubble1 after 20 successes, AND fail rollbacks.
        var failBoth = new FailBothMovesAccountant(accountant, 20, bubble2.id(), bubble1.id());
        var failingMerger = new BubbleMerger(bubbleGrid, failBoth, OperationTracker.NOOP, metrics);

        var proposal = new MergeProposal(
            UUID.randomUUID(),
            bubble1.id(),
            bubble2.id(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        // Must NOT throw even though rollback moves also fail.
        var result = assertDoesNotThrow(() -> failingMerger.execute(proposal),
            "Merger must not throw when rollback moves fail");

        assertFalse(result.success(), "Merge must report failure when forward move fails");
    }

    /**
     * Bead Luciferase-7wzml.20 — BubbleMerger mutation-lock protocol.
     * <p>
     * A concurrent merge and a simulated migration on the SAME bubble pair must:
     * <ol>
     *   <li>Not deadlock (bounded by a 10-second timeout)</li>
     *   <li>Conserve entity count — no entity is lost or duplicated</li>
     * </ol>
     * The "migration" thread simulates {@code TetrahedralMigration.executeMigration} by
     * acquiring BOTH mutation locks in the same UUID.compareTo() order.  Both callers
     * use the same total order, so no lock-ordering cycle is possible.
     */
    @Test
    void concurrentMergeAndMigration_entityCountConserved() throws InterruptedException {
        // Two real bubbles
        var srcId = UUID.randomUUID();
        var dstId = UUID.randomUUID();
        var srcCtrl = new RealTimeController(srcId, "src");
        var dstCtrl = new RealTimeController(dstId, "dst");
        var srcBubble = new EnhancedBubble(srcId, (byte) 1, 16, srcCtrl);
        var dstBubble = new EnhancedBubble(dstId, (byte) 1, 16, dstCtrl);

        // Seed entities into srcBubble and register in the accountant
        int N = 20;
        var entityIds = new ArrayList<String>(N);
        for (int i = 0; i < N; i++) {
            var eid = UUID.randomUUID();
            entityIds.add(eid.toString());
            srcBubble.addEntity(eid.toString(), new Point3f(i, i, i), null);
            accountant.register(srcId, eid);
        }

        // Spy on the real grid so getBubbleById returns our real bubbles
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(2, (byte) 1, 10);

        var spyGrid = Mockito.spy(grid);
        doReturn(srcBubble).when(spyGrid).getBubbleById(srcId);
        doReturn(dstBubble).when(spyGrid).getBubbleById(dstId);

        // BubbleMerger using our spy grid + the shared accountant
        var concurrentMerger = new BubbleMerger(spyGrid, accountant, OperationTracker.NOOP, metrics);

        // Merge proposal: merge srcBubble into dstBubble
        var proposal = new MergeProposal(
            UUID.randomUUID(), srcId, dstId,
            com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis());

        // Concurrent: merge thread + simulated-migration thread, released simultaneously.
        // The migration thread acquires BOTH locks in UUID.compareTo() order (same order as
        // BubbleMerger.execute) then moves one entity src→dst under lock — exactly what
        // TetrahedralMigration.executeMigration does.
        var migratingEid = entityIds.get(0);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<Throwable>();
        var pool   = Executors.newFixedThreadPool(2);

        pool.submit(() -> {
            ready.countDown();
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try { concurrentMerger.execute(proposal); }
            catch (Throwable t) { errors.add(t); }
        });

        pool.submit(() -> {
            ready.countDown();
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try {
                // Simulate migration: acquire BOTH locks in UUID.compareTo() order, move entity under lock
                int cmp = srcId.compareTo(dstId);
                var firstLock  = cmp <= 0 ? srcBubble.getMutationLock() : dstBubble.getMutationLock();
                var secondLock = cmp <= 0 ? dstBubble.getMutationLock() : srcBubble.getMutationLock();
                firstLock.lock();
                try {
                    secondLock.lock();
                    try {
                        // Move entity if it still exists in srcBubble
                        var records = srcBubble.getAllEntityRecords();
                        var toMove = records.stream()
                                           .filter(r -> r.id().equals(migratingEid))
                                           .findFirst().orElse(null);
                        if (toMove != null) {
                            dstBubble.addEntity(toMove.id(), toMove.position(), toMove.content());
                            srcBubble.removeEntity(toMove.id());
                            accountant.moveBetweenBubbles(UUID.fromString(migratingEid), srcId, dstId);
                        }
                    } finally {
                        secondLock.unlock();
                    }
                } finally {
                    firstLock.unlock();
                }
            } catch (Throwable t) { errors.add(t); }
        });

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                   "Deadlock detected: threads did not finish within 10 seconds");

        assertTrue(errors.isEmpty(), "Concurrent threads threw: " + errors);

        // Conservation: total entities across both bubbles must equal N
        int srcCount = srcBubble.entityCount();
        int dstCount = dstBubble.entityCount();
        assertEquals(N, srcCount + dstCount,
                     "Entity count must be conserved (src=" + srcCount + " dst=" + dstCount + ")");
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

    /**
     * Delegating wrapper that fails forward moves (from {@code forwardFrom} to
     * {@code forwardTo}) after {@code failAfter} successes.  Rollback moves
     * (from {@code forwardTo} back to {@code forwardFrom}) always delegate faithfully
     * so the rollback path can clean up properly.
     */
    static final class FailAfterNForwardMovesAccountant extends EntityAccountant {

        private final EntityAccountant delegate;
        private final int              failAfter;
        private final UUID             forwardFrom;
        private final UUID             forwardTo;
        private final AtomicInteger    successCount = new AtomicInteger(0);

        FailAfterNForwardMovesAccountant(EntityAccountant delegate, int failAfter,
                                        UUID forwardFrom, UUID forwardTo) {
            this.delegate    = delegate;
            this.failAfter   = failAfter;
            this.forwardFrom = forwardFrom;
            this.forwardTo   = forwardTo;
        }

        @Override
        public boolean moveBetweenBubbles(UUID entityId, UUID fromBubble, UUID toBubble) {
            boolean isForward = forwardFrom.equals(fromBubble) && forwardTo.equals(toBubble);
            if (isForward && successCount.get() >= failAfter) {
                return false; // inject failure
            }
            boolean result = delegate.moveBetweenBubbles(entityId, fromBubble, toBubble);
            if (result && isForward) {
                successCount.incrementAndGet();
            }
            return result;
        }

        @Override
        public void register(UUID bubbleId, UUID entityId) {
            delegate.register(bubbleId, entityId);
        }

        @Override
        public java.util.Set<UUID> entitiesInBubble(UUID bubbleId) {
            return delegate.entitiesInBubble(bubbleId);
        }

        @Override
        public com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult validate() {
            return delegate.validate();
        }
    }

    /**
     * Delegating wrapper that fails forward moves (from bubble2 to bubble1) after
     * {@code failAfter} successes AND fails all rollback moves (from bubble1 back to
     * bubble2).  Used to exercise the "rollback itself fails" diagnostic path.
     */
    static final class FailBothMovesAccountant extends EntityAccountant {

        private final EntityAccountant delegate;
        private final int              failAfter;
        private final UUID             forwardFrom;
        private final UUID             forwardTo;
        private final AtomicInteger    successCount = new AtomicInteger(0);

        FailBothMovesAccountant(EntityAccountant delegate, int failAfter, UUID forwardFrom, UUID forwardTo) {
            this.delegate    = delegate;
            this.failAfter   = failAfter;
            this.forwardFrom = forwardFrom;
            this.forwardTo   = forwardTo;
        }

        @Override
        public boolean moveBetweenBubbles(UUID entityId, UUID fromBubble, UUID toBubble) {
            boolean isForward  = forwardFrom.equals(fromBubble) && forwardTo.equals(toBubble);
            boolean isRollback = forwardTo.equals(fromBubble) && forwardFrom.equals(toBubble);
            // Fail forward moves once threshold reached.
            if (isForward && successCount.get() >= failAfter) {
                return false;
            }
            // Fail rollback moves (simulates rollback-of-move failure).
            if (isRollback) {
                return false;
            }
            boolean result = delegate.moveBetweenBubbles(entityId, fromBubble, toBubble);
            if (result && isForward) {
                successCount.incrementAndGet();
            }
            return result;
        }

        @Override
        public void register(UUID bubbleId, UUID entityId) {
            delegate.register(bubbleId, entityId);
        }

        @Override
        public java.util.Set<UUID> entitiesInBubble(UUID bubbleId) {
            return delegate.entitiesInBubble(bubbleId);
        }

        @Override
        public com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult validate() {
            return delegate.validate();
        }
    }
}
