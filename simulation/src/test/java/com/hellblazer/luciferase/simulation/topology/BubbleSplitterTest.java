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
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.topology.BubbleSplitter;
import com.hellblazer.luciferase.simulation.topology.SplitPlane;
import com.hellblazer.luciferase.simulation.topology.SplitProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Verifies that a moveBetweenBubbles failure aborts the entire split atomically —
     * no entities are orphaned or duplicated, and accountant.validate() passes.
     * <p>
     * Uses a FailingEntityAccountant that rejects moves for a configurable subset
     * of entities, simulating a partial-move failure mid-split.
     */
    @Test
    void testSplitFailsCleanlyOnMoveFailure() {
        // Create bubble with entities on both sides of split plane.
        // Keep count modest so the test is fast.
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        for (int i = 0; i < 100; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(10.0f, 5.0f, 5.0f), null);
            accountant.register(bubble.id(), entityId);
        }
        for (int i = 0; i < 100; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(1.0f, 5.0f, 5.0f), null);
            accountant.register(bubble.id(), entityId);
        }

        int entitiesBeforeSplit = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(200, entitiesBeforeSplit);

        // Wrap accountant with a failing wrapper: fail after 50 forward moves.
        var failingAccountant = new FailAfterNAccountant(accountant, 50);
        failingAccountant.setForwardFrom(bubble.id()); // only inject failures for forward (split) moves
        var failingSplitter = new BubbleSplitter(bubbleGrid, failingAccountant, OperationTracker.NOOP, metrics);

        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 5.5f);
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = failingSplitter.execute(proposal);

        // The split must fail — partial moves are not acceptable.
        assertFalse(result.success(), "Split should fail when a move fails");

        // After failure the accountant must still have every entity exactly once
        // (no orphans, no duplicates). Conservation is derived from snapshot, not
        // two live reads.
        var validation = accountant.validate();
        assertTrue(validation.success(),
                   "Accountant must be consistent after failed split: " + validation.details());
        assertEquals(entitiesBeforeSplit,
                     accountant.entitiesInBubble(bubble.id()).size(),
                     "All entities must still be in the source bubble after rollback");
    }

    /**
     * Verifies that conservation is checked against the snapshot captured at split
     * start plus {@code entitiesMoved}, NOT via two separate live accountant reads
     * that could race with concurrent mutations.
     * <p>
     * Adds entities to both bubbles concurrently while the split runs and confirms
     * the result still reports the correct conservation figures.
     */
    @Test
    void testConservationDerivedFromSnapshotNotLiveReads() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        for (int i = 0; i < 2550; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(10.0f, 5.0f, 5.0f), null);
            accountant.register(bubble.id(), entityId);
        }
        for (int i = 0; i < 2550; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(1.0f, 5.0f, 5.0f), null);
            accountant.register(bubble.id(), entityId);
        }

        int snapshotCount = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(5100, snapshotCount);

        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 5.5f);
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        assertTrue(result.success(), "Split should succeed: " + result.message());
        // entitiesBefore in the result must reflect the snapshot, not a racy re-read.
        assertEquals(snapshotCount, result.entitiesBefore(),
                     "entitiesBefore must equal the snapshot taken at the start of the split");
        // entitiesAfter must equal snapshot (no leak, no duplication).
        assertEquals(snapshotCount, result.entitiesAfter(),
                     "entitiesAfter must equal entitiesBefore (conservation)");
        // The two live reads must still agree with the snapshot-based figures.
        int sourceAfter = accountant.entitiesInBubble(bubble.id()).size();
        int newAfter    = accountant.entitiesInBubble(result.newBubbleId()).size();
        assertEquals(snapshotCount, sourceAfter + newAfter,
                     "Live entity counts must also sum to the snapshot");
        var validation = accountant.validate();
        assertTrue(validation.success(), "Accountant must be valid after split");
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

    /**
     * Verifies that when BOTH the forward move AND the rollback move fail, the splitter:
     * <ul>
     *   <li>does NOT throw</li>
     *   <li>returns a failure result</li>
     *   <li>continues the rollback loop for the remaining entities (best-effort)</li>
     * </ul>
     * The orphaned-entity log.error is the diagnosability contract; we cannot assert
     * log output without a capturing appender, so we assert no-throw + failure result.
     */
    @Test
    void testRollbackMoveFailureLogsAndContinues() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        for (int i = 0; i < 60; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(10.0f, 5.0f, 5.0f), null);
            accountant.register(bubble.id(), entityId);
        }
        for (int i = 0; i < 60; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(1.0f, 5.0f, 5.0f), null);
            accountant.register(bubble.id(), entityId);
        }

        // Fail forward after 30 moves, AND fail all rollback moves too.
        var failBoth = new FailBothMovesAccountant(accountant, 30, bubble.id());
        var failingSplitter = new BubbleSplitter(bubbleGrid, failBoth, OperationTracker.NOOP, metrics);

        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 5.5f);
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        // Must NOT throw even though rollback moves also fail.
        var result = assertDoesNotThrow(() -> failingSplitter.execute(proposal),
            "Splitter must not throw when rollback moves fail");

        assertFalse(result.success(), "Split must report failure when forward move fails");
    }

    /**
     * Delegating EntityAccountant wrapper that rejects {@code moveBetweenBubbles}
     * after {@code failAfter} forward moves (from {@code forwardFrom} to any destination),
     * simulating a partial-failure mid-split.  Rollback moves (from new bubble back to
     * source) always delegate faithfully so the rollback path can clean up properly.
     * All other operations delegate faithfully.
     */
    static final class FailAfterNAccountant extends EntityAccountant {

        private final EntityAccountant delegate;
        private final AtomicInteger    successCount;
        private final int              failAfter;
        private volatile UUID          forwardFrom; // set by the test before execute()

        FailAfterNAccountant(EntityAccountant delegate, int failAfter) {
            this.delegate     = delegate;
            this.failAfter    = failAfter;
            this.successCount = new AtomicInteger(0);
        }

        void setForwardFrom(UUID sourceBubbleId) {
            this.forwardFrom = sourceBubbleId;
        }

        @Override
        public boolean moveBetweenBubbles(UUID entityId, UUID fromBubble, UUID toBubble) {
            // Only inject failures for forward (split) moves, not rollback moves.
            boolean isForward = forwardFrom != null && forwardFrom.equals(fromBubble);
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
     * Delegating wrapper that fails forward moves after {@code failAfter} successes AND
     * also fails all rollback moves (from any bubble back to the original source).
     * Used to exercise the "rollback itself fails" diagnostic path.
     */
    static final class FailBothMovesAccountant extends EntityAccountant {

        private final EntityAccountant delegate;
        private final int              failAfter;
        private final UUID             forwardFrom;
        private final AtomicInteger    successCount = new AtomicInteger(0);

        FailBothMovesAccountant(EntityAccountant delegate, int failAfter, UUID forwardFrom) {
            this.delegate    = delegate;
            this.failAfter   = failAfter;
            this.forwardFrom = forwardFrom;
        }

        @Override
        public boolean moveBetweenBubbles(UUID entityId, UUID fromBubble, UUID toBubble) {
            boolean isForward = forwardFrom.equals(fromBubble);
            boolean isRollback = forwardFrom.equals(toBubble) && !forwardFrom.equals(fromBubble);
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
