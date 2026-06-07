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
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BubbleSplitter Bey-refinement entity redistribution.
 * <p>
 * Updated for AC-2.5: source bubble is REMOVED after split; entities distributed across
 * up to 8 Bey children by exact contains12DOP containment.
 *
 * @author hal.hildebrand
 */
class BubbleSplitterTest {
    private static final TestClock JC1KH_CLOCK = new TestClock(1_000L); // determinism mandate (Luciferase-jc1kh)

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
        // Create bubble with >5000 entities placed at child centroids for guaranteed containment
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble    = bubbleGrid.getAllBubbles().iterator().next();
        addEntitiesAtChildCentroids(bubble, 5100);

        int entitiesBefore = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(5100, entitiesBefore, "Should have 5100 entities before split");

        var result = splitter.execute(dummyProposal(bubble));

        assertTrue(result.success(), "Bey split should succeed: " + result.message());
        // FIX 2: all 8 children always kept on success
        assertEquals(8, result.childBubbleIds().size(), "Successful split must return exactly 8 children");
        assertEquals(5100, result.entitiesBefore(), "Entities before should be 5100");
        assertEquals(5100, result.entitiesAfter(), "Entities after should be 5100 (conservation)");

        // Source bubble REMOVED by Bey split
        assertNull(bubbleGrid.getBubbleById(bubble.id()), "Source bubble must be removed after Bey split");

        // All entities distributed across children
        int childTotal = result.childBubbleIds().stream()
                               .mapToInt(id -> accountant.entitiesInBubble(id).size())
                               .sum();
        assertEquals(5100, childTotal, "Total entities conserved across all children");

        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testSplitPartitionsEntitiesByContainment() {
        // Bey split partitions entities by contains12DOP, not a plane.
        // Place entities at the centroids of the 8 Bey children and verify each
        // lands in its correct child bubble.
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble    = bubbleGrid.getAllBubbles().iterator().next();
        var parentKey = bubbleGrid.getKeyForBubble(bubble.id());
        var parentTet = Tet.tetrahedron(parentKey);
        var children  = parentTet.geometricSubdivide();

        // Place exactly 100 entities per child (800 total, 8 children)
        for (int ci = 0; ci < 8; ci++) {
            var child = children[ci];
            var verts = child.coordinates();
            float cx  = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4.0f;
            float cy  = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4.0f;
            float cz  = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4.0f;
            for (int i = 0; i < 100; i++) {
                var id = UUID.randomUUID();
                bubble.addEntity(id.toString(), new Point3f(cx, cy, cz), null);
                accountant.register(bubble.id(), id);
            }
        }

        var result = splitter.execute(dummyProposal(bubble));

        assertTrue(result.success(), "Split should succeed");
        // FIX 2: all 8 children always kept on success
        assertEquals(8, result.childBubbleIds().size(), "Successful split must return exactly 8 children");
        // Source gone
        assertNull(bubbleGrid.getBubbleById(bubble.id()), "Source must be removed");
        // All 800 redistributed
        int childTotal = result.childBubbleIds().stream()
                               .mapToInt(id -> accountant.entitiesInBubble(id).size())
                               .sum();
        assertEquals(800, childTotal, "All 800 entities redistributed");
        // FIX 6: entitiesRedistributed == 800 (no silent skip)
        assertEquals(800, result.entitiesRedistributed(),
                     "entitiesRedistributed must equal source count (no silent skip)");
        // Conservation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
    }

    @Test
    void testSplitRejectsNonexistentBubble() {
        var result = splitter.execute(new SplitProposal(
            UUID.randomUUID(),
            UUID.randomUUID(), // Non-existent bubble
            new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), 0.0f),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        ));

        assertFalse(result.success(), "Should reject non-existent bubble");
        assertTrue(result.message().contains("not found") || result.message().contains("Source bubble"),
                   "Should mention source not found; got: " + result.message());
        assertTrue(result.childBubbleIds().isEmpty(), "childBubbleIds must be empty on failure");
    }

    @Test
    void testSplitFailsOnEmptyBubble() {
        // Create bubble with no entities — Bey split fails: no entities redistributed (0 moved)
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Capture source key before split attempt
        var sourceKey = bubbleGrid.getKeyForBubble(bubble.id());

        var result = splitter.execute(dummyProposal(bubble));

        assertFalse(result.success(), "Should fail for empty bubble (no entities redistributed)");
        assertTrue(result.childBubbleIds().isEmpty(), "childBubbleIds must be empty on failure");

        // Source bubble must still be in grid after the abort
        assertNotNull(bubbleGrid.getBubbleById(bubble.id()),
                      "Source bubble must still be in grid after empty-bubble abort");
        assertTrue(bubbleGrid.containsBubble(sourceKey),
                   "Source key must still be in grid after empty-bubble abort");
    }

    @Test
    void testSplitValidatesEntityConservation() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble    = bubbleGrid.getAllBubbles().iterator().next();
        addEntitiesAtChildCentroids(bubble, 5100);

        int entitiesBefore = accountant.entitiesInBubble(bubble.id()).size();

        var result = splitter.execute(dummyProposal(bubble));

        assertTrue(result.success(), "Split should succeed");
        assertEquals(entitiesBefore, result.entitiesAfter(), "Total entities must be conserved");

        var validation = accountant.validate();
        assertTrue(validation.success(), "Entity validation should pass");
        assertEquals(0, validation.errorCount(), "Should have no validation errors");
    }

    @Test
    void testSplitNullProposalThrows() {
        assertThrows(NullPointerException.class, () -> splitter.execute(null),
                     "Should reject null proposal");
    }

    /**
     * Verifies that a moveBetweenBubbles failure aborts the entire split atomically.
     * All 8 child bubbles are created before the move loop; on failure they are removed.
     * FIX 4: also asserts that NONE of the 8 expected child keys remain in the grid.
     */
    @Test
    void testSplitFailsCleanlyOnMoveFailure() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble    = bubbleGrid.getAllBubbles().iterator().next();

        // Capture expected child keys BEFORE the split attempt (FIX 4)
        var parentKey    = bubbleGrid.getKeyForBubble(bubble.id());
        var parentTet    = Tet.tetrahedron(parentKey);
        var childTets    = parentTet.geometricSubdivide();
        var expectedChildKeys = new ArrayList<TetreeKey<?>>(8);
        for (var child : childTets) {
            expectedChildKeys.add(child.tmIndex());
        }
        assertEquals(8, expectedChildKeys.size(), "Must have 8 expected child keys");

        addEntitiesAtChildCentroids(bubble, 200);

        int entitiesBeforeSplit = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(200, entitiesBeforeSplit);

        var failingAccountant = new FailAfterNAccountant(accountant, 50);
        failingAccountant.setForwardFrom(bubble.id());
        var failingSplitter = new BubbleSplitter(bubbleGrid, failingAccountant, OperationTracker.NOOP, metrics);

        var result = failingSplitter.execute(dummyProposal(bubble));

        assertFalse(result.success(), "Split should fail when a move fails");

        // Accountant must still be valid
        var validation = accountant.validate();
        assertTrue(validation.success(),
                   "Accountant must be consistent after failed split: " + validation.details());
        // Source bubble must still be in the grid (rollback restores it)
        assertNotNull(bubbleGrid.getBubbleById(bubble.id()),
                      "Source bubble must still be accessible after failed split");

        // FIX 4: NONE of the 8 child keys must remain in the grid after rollback
        for (var childKey : expectedChildKeys) {
            assertFalse(bubbleGrid.containsBubble(childKey),
                        "Child key " + childKey + " must NOT be in grid after failed split rollback");
        }
        // FIX 4: Source key must still be in the grid (parent survives failed split)
        assertTrue(bubbleGrid.containsBubble(parentKey),
                   "Parent key must still be in grid after failed split");
    }

    /**
     * Verifies that conservation is checked against the pre-split snapshot.
     */
    @Test
    void testConservationDerivedFromSnapshotNotLiveReads() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntitiesAtChildCentroids(bubble, 5100);

        int snapshotCount = accountant.entitiesInBubble(bubble.id()).size();
        assertEquals(5100, snapshotCount);

        var result = splitter.execute(dummyProposal(bubble));

        assertTrue(result.success(), "Split should succeed: " + result.message());
        assertEquals(snapshotCount, result.entitiesBefore(),
                     "entitiesBefore must equal the snapshot taken at the start of the split");
        assertEquals(snapshotCount, result.entitiesAfter(),
                     "entitiesAfter must equal entitiesBefore (conservation)");

        // Sum of live child entity counts must equal snapshot
        int childTotal = result.childBubbleIds().stream()
                               .mapToInt(id -> accountant.entitiesInBubble(id).size())
                               .sum();
        assertEquals(snapshotCount, childTotal,
                     "Live child entity counts must sum to snapshot");

        var validation = accountant.validate();
        assertTrue(validation.success(), "Accountant must be valid after split");
    }

    @Test
    void testConstructorNullBubbleGridThrows() {
        assertThrows(NullPointerException.class, () ->
            new BubbleSplitter(null, accountant, OperationTracker.NOOP, metrics),
            "Should reject null bubble grid");
    }

    @Test
    void testConstructorNullAccountantThrows() {
        assertThrows(NullPointerException.class, () ->
            new BubbleSplitter(bubbleGrid, null, OperationTracker.NOOP, metrics),
            "Should reject null accountant");
    }

    @Test
    void testConstructorNullMetricsThrows() {
        assertThrows(NullPointerException.class, () ->
            new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, null),
            "Should reject null metrics");
    }

    /**
     * Verifies that when BOTH the forward move AND the rollback move fail, the splitter
     * does NOT throw, returns failure, and continues best-effort rollback.
     * FIX 4: also asserts that NONE of the 8 expected child keys remain in the grid.
     */
    @Test
    void testRollbackMoveFailureLogsAndContinues() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Capture expected child keys BEFORE the split attempt (FIX 4)
        var parentKey    = bubbleGrid.getKeyForBubble(bubble.id());
        var parentTet    = Tet.tetrahedron(parentKey);
        var childTets    = parentTet.geometricSubdivide();
        var expectedChildKeys = new ArrayList<TetreeKey<?>>(8);
        for (var child : childTets) {
            expectedChildKeys.add(child.tmIndex());
        }

        addEntitiesAtChildCentroids(bubble, 120);

        var failBoth = new FailBothMovesAccountant(accountant, 30, bubble.id());
        var failingSplitter = new BubbleSplitter(bubbleGrid, failBoth, OperationTracker.NOOP, metrics);

        var result = assertDoesNotThrow(() -> failingSplitter.execute(dummyProposal(bubble)),
            "Splitter must not throw when rollback moves fail");

        assertFalse(result.success(), "Split must report failure when forward move fails");

        // FIX 4: NONE of the 8 child keys must remain in the grid after rollback
        for (var childKey : expectedChildKeys) {
            assertFalse(bubbleGrid.containsBubble(childKey),
                        "Child key " + childKey + " must NOT be in grid after failed split rollback");
        }
        // FIX 4: Source key must still be present in the grid
        assertTrue(bubbleGrid.containsBubble(parentKey),
                   "Parent key must still be in grid after failed split");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Add entities placed at the centroids of the 8 Bey children of the bubble's parent Tet.
     * This guarantees each entity is exactly contained by one child (interior point).
     */
    private void addEntitiesAtChildCentroids(EnhancedBubble bubble, int count) {
        var parentKey = bubbleGrid.getKeyForBubble(bubble.id());
        var parentTet = Tet.tetrahedron(parentKey);
        var children  = parentTet.geometricSubdivide();

        for (int i = 0; i < count; i++) {
            var child = children[i % 8];
            var verts = child.coordinates();
            float cx  = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4.0f;
            float cy  = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4.0f;
            float cz  = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4.0f;
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(cx, cy, cz), null);
            accountant.register(bubble.id(), entityId);
        }
    }

    /** A dummy split proposal; the plane is not consulted by the Bey-refinement splitter. */
    private SplitProposal dummyProposal(EnhancedBubble bubble) {
        return new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            new SplitPlane(new Point3f(1f, 0f, 0f), 0f),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );
    }

    /** Old helper kept for testSplitFailsCleanlyOnMoveFailure (adds generic positions). */
    private void addEntities(EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(bubble.id(), entityId);
        }
    }

    /**
     * Delegating EntityAccountant wrapper that rejects {@code moveBetweenBubbles}
     * after {@code failAfter} forward moves (from {@code forwardFrom} to any destination),
     * simulating a partial-failure mid-split.
     */
    static final class FailAfterNAccountant extends EntityAccountant {

        private final EntityAccountant delegate;
        private final AtomicInteger    successCount;
        private final int              failAfter;
        private volatile UUID          forwardFrom;

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
            boolean isForward = forwardFrom != null && forwardFrom.equals(fromBubble);
            if (isForward && successCount.get() >= failAfter) {
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

    /**
     * Delegating wrapper that fails forward moves after {@code failAfter} successes AND
     * also fails all rollback moves (from any bubble back to the original source).
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
            boolean isForward  = forwardFrom.equals(fromBubble);
            boolean isRollback = forwardFrom.equals(toBubble) && !forwardFrom.equals(fromBubble);
            if (isForward && successCount.get() >= failAfter) {
                return false;
            }
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
