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
import com.hellblazer.luciferase.simulation.topology.events.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the wave-2 topology remediation beads
 * (Luciferase-0frcy.40-.48). Each test maps to a specific bead and fails
 * against the pre-fix source.
 *
 * @author hal.hildebrand
 */
class TopologyRemediationTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics  metrics;
    private TopologyExecutor executor;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics    = new TopologyMetrics();
        executor   = new TopologyExecutor(bubbleGrid, accountant, metrics);
    }

    // ---- .40: max-level source rejected (Bey refinement cannot go past level 21) ----
    //
    // With the Bey-refinement redesign (AC-2.5) the SplitPlane strategy is no longer
    // consulted for geometry; "degenerate plane" is not a meaningful failure mode.
    // The real hard guard is MAX_LEVEL: a bubble at level 21 cannot be subdivided further.
    // This replaces the old "degenerate plane" test with the current canonical failure path.

    @Test
    void maxLevelSourceFailsFast() {
        // Create a level-0 bubble and immediately promote it to a key at level 21
        // by registering a manually-constructed Tet key so the splitter's max-level
        // guard fires.
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 100);

        // Re-register the bubble at a level-21 key (same bubble, new key)
        var key21 = bubbleGrid.getKeyForBubble(bubble.id());

        // Build a max-level Tet from the existing key, then descend 21 levels from
        // the current level to reach a level-21 key.  Since this is a test for the
        // guard condition itself, use a direct splitter instantiation and pass a
        // proposal whose source is a bubble that the grid registers at level 21.
        // The simplest approach: swap the bubble's registration key directly.
        // Because TetreeBubbleGrid may not expose a re-register API, we use a
        // mock/stub-free variant: create a second grid with the bubble at level 21.
        var grid21 = new TetreeBubbleGrid((byte) 2);
        var maxLevelTet = buildMaxLevelTet();
        var maxKey = maxLevelTet.tmIndex();
        grid21.addBubble(bubble, maxKey);
        var acct21 = new EntityAccountant();
        acct21.register(bubble.id(), UUID.randomUUID());

        var splitter = new BubbleSplitter(grid21, acct21, new NoopTracker(), metrics);
        var proposal = new SplitProposal(UUID.randomUUID(), bubble.id(),
                                         new SplitPlane(new Point3f(1f, 0f, 0f), 0f),
                                         DigestAlgorithm.DEFAULT.getOrigin(), 0L);

        var result = splitter.execute(proposal);
        assertFalse(result.success(), "Source at MAX_LEVEL must fail fast — cannot subdivide further");
        // Entity conservation: failure must not move entities
        assertEquals(1, acct21.entitiesInBubble(bubble.id()).size(),
                     "Source bubble must retain all entities when MAX_LEVEL guard fires");
    }

    /** Returns a valid Tet at level 21 (max refinement). */
    private com.hellblazer.luciferase.lucien.tetree.Tet buildMaxLevelTet() {
        var t = new com.hellblazer.luciferase.lucien.tetree.Tet(0, 0, 0, (byte) 0, (byte) 0);
        for (int i = 0; i < 21; i++) {
            t = t.child(0);
        }
        return t;
    }

    // ---- .41: no entity leak; children added to grid, source removed ------
    //
    // After Bey-refinement split: source bubble is REMOVED, 1-8 non-empty children
    // are present in the grid.  Total entity count must be conserved.

    @Test
    void newBubbleInGridForSuccessfulSplit() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);
        int before = accountant.entitiesInBubble(bubble.id()).size();

        var proposal = splitProposal(bubble);
        var result = executor.executeInternal(proposal); // public execute(SplitProposal) fenced — AC-0

        assertTrue(result.success(), () -> "Split should succeed: " + result.message());
        assertEquals(before, getTotal(), "Entity conservation: total must equal pre-split count");

        // Source is REMOVED after a successful Bey split.
        assertNull(bubbleGrid.getBubbleById(bubble.id()),
                   "Source bubble must be removed from the grid after a successful Bey split");

        // At least one child bubble must exist in the grid with entities.
        // executor.executeInternal returns TopologyExecutionResult (no childBubbleIds field);
        // find child bubbles by scanning the accountant for non-empty, non-source IDs.
        var childIds = accountant.getDistribution().keySet().stream()
                                 .filter(id -> !id.equals(bubble.id()))
                                 .filter(id -> !accountant.entitiesInBubble(id).isEmpty())
                                 .toList();
        assertFalse(childIds.isEmpty(), "At least one non-empty child bubble must be created");
        for (var childId : childIds) {
            assertNotNull(bubbleGrid.getBubbleById(childId),
                          "Every non-empty child bubble must be present in the grid (no leak): " + childId);
        }
    }

    // ---- .42 / .45: split metrics not double-counted ----------------------

    @Test
    void splitSuccessRecordedExactlyOnce() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var result = executor.executeInternal(splitProposal(bubble));
        assertTrue(result.success(), () -> "Split should succeed: " + result.message());

        assertEquals(1L, metrics.getSplitMetrics().successfulSplits(),
                     "recordSplitSuccess must be invoked exactly once per successful split");
    }

    @Test
    void splitFailureRecordedExactlyOnce() {
        // Source bubble does not exist -> BubbleSplitter fails with SOURCE_BUBBLE_NOT_FOUND.
        var missing = UUID.randomUUID();
        var proposal = new SplitProposal(UUID.randomUUID(), missing,
                                         new SplitPlane(new Point3f(1f, 0f, 0f), 0f),
                                         DigestAlgorithm.DEFAULT.getOrigin(), 0L);
        executor.executeInternal(proposal); // public execute(SplitProposal) fenced — AC-0
        assertEquals(1L, metrics.getSplitMetrics().failedSplits(),
                     "recordSplitFailure must be invoked exactly once per failed split");
    }

    // ---- .46: SplitEvent.entitiesMoved is the real relocation count -------

    @Test
    void splitEventReportsActualEntitiesMoved() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        List<TopologyEvent> events = new ArrayList<>();
        executor.addListener(events::add);

        var result = executor.executeInternal(splitProposal(bubble));
        assertTrue(result.success(), () -> "Split should succeed: " + result.message());

        assertEquals(1, events.size());
        var split = assertInstanceOf(SplitEvent.class, events.get(0));
        assertTrue(split.entitiesMoved() > 0,
                   "entitiesMoved must reflect the real relocation count, not (after-before)=0");
        // Bey split: event must carry non-empty childBubbleIds (source removed, children present)
        assertFalse(split.childBubbleIds().isEmpty(),
                    "SplitEvent must carry at least one childBubbleId after a successful Bey split");
    }

    // ---- .70: merge relocation count is the real moved count, not (after-before) --------
    //
    // RDR-018 AC-4 fenced the public executor merge path, so the MergeEvent emitted by a
    // fenced merge carries entitiesMoved=0 (nothing moved). The "relocation count, not
    // (after-before)=0" pin for the EVENT plumbing is still covered on the split path by
    // splitEventReportsActualEntitiesMoved. Here we pin the same property at its source — the
    // merge MECHANISM (executeMerge) — which B-core (AC-2.5) will wrap and feed back into the
    // event once coverage-preserving merges return.

    @Test
    void mergeMechanismReportsActualEntitiesMoved() {
        bubbleGrid.createBubbles(2, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);
        addEntities(bubble1, 30);
        addEntities(bubble2, 20);

        var merger = new BubbleMerger(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
        var proposal = new MergeProposal(UUID.randomUUID(), bubble1.id(), bubble2.id(),
                                         DigestAlgorithm.DEFAULT.getOrigin(), 0L);
        var result = merger.executeMerge(proposal);
        assertTrue(result.success(), () -> "Merge mechanism should succeed: " + result.message());

        // 20 entities were relocated from bubble2 into bubble1. (after - before) == 0 by
        // conservation, so the field must come from the actual relocation count.
        assertEquals(20, result.entitiesMoved(),
                     "entitiesMoved must reflect the real relocation count, not (after-before)=0");
    }

    // ---- .47: events fire only after commit -------------------------------

    @Test
    void successEventNotFiredBeforeCommit() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        List<TopologyEvent> events = new ArrayList<>();
        executor.addListener(events::add);

        var result = executor.executeInternal(splitProposal(bubble));
        assertTrue(result.success());
        assertEquals(1, events.size());
        // On the committed happy path the event's success flag must be true.
        assertTrue(((SplitEvent) events.get(0)).success(),
                   "A committed split must emit success=true");
    }

    // ---- .48: no NPE on failed move proposal ------------------------------

    @Test
    void failedMoveDoesNotThrowNpe() {
        var missing = UUID.randomUUID();
        var proposal = new MoveProposal(UUID.randomUUID(), missing,
                                        new Point3f(1f, 1f, 1f), new Point3f(1f, 1f, 1f),
                                        DigestAlgorithm.DEFAULT.getOrigin(), 0L);

        List<TopologyEvent> events = new ArrayList<>();
        executor.addListener(events::add);

        var result = assertDoesNotThrow(() -> executor.execute(proposal),
                                        "Failed move (missing source bubble) must not NPE");
        assertFalse(result.success(), "Move on a missing bubble must fail");
        assertEquals(1, events.size());
        assertFalse(((MoveEvent) events.get(0)).success(), "Failed move must emit success=false");
    }

    // ---- .122: rollback records the actual registration key ---------------

    @Test
    void mergeRollbackRecordsActualRegistrationKey() {
        bubbleGrid.createBubbles(2, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);
        addEntities(bubble1, 30);
        addEntities(bubble2, 20);

        // The actual spatial-index registration key for bubble2, captured before the merge.
        var expectedKey = bubbleGrid.getKeyForBubble(bubble2.id());
        assertNotNull(expectedKey, "bubble2 must have a registration key");

        var capturing = new CapturingTracker();
        var merger = new BubbleMerger(bubbleGrid, accountant, capturing, metrics);

        var proposal = new MergeProposal(UUID.randomUUID(), bubble1.id(), bubble2.id(),
                                         DigestAlgorithm.DEFAULT.getOrigin(), 0L);
        // Exercise the merge MECHANISM directly — the public execute() is fenced (RDR-018 AC-4),
        // but the registration-key pin (.122) is a property of the mechanism's removeBubble record.
        var result = merger.executeMerge(proposal);
        assertTrue(result.success(), () -> "Merge should succeed: " + result.message());

        // The rollback record MUST use the actual registration key, not bounds().rootKey().
        assertEquals(bubble2.id(), capturing.removedBubbleId, "Removal must be recorded for bubble2");
        assertEquals(expectedKey, capturing.removedKey,
                     "Rollback must record the bubble's actual registration key (not bounds().rootKey())");
    }

    // ---- helpers ----------------------------------------------------------

    private SplitProposal splitProposal(EnhancedBubble bubble) {
        var centroid = bubble.centroid();
        var plane = new SplitPlane(new Point3f(1f, 0f, 0f), (float) centroid.getX());
        return new SplitProposal(UUID.randomUUID(), bubble.id(), plane,
                                 DigestAlgorithm.DEFAULT.getOrigin(), 0L);
    }

    private void addEntities(EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(bubble.id(), entityId);
        }
    }

    private int getTotal() {
        return accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Minimal operation tracker for direct-splitter tests. */
    private static final class NoopTracker implements OperationTracker {
        @Override public void recordBubbleAdded(UUID bubbleId) { }
        @Override public void recordBubbleRemoved(UUID bubbleId, EnhancedBubble snapshot,
                                                  com.hellblazer.luciferase.lucien.tetree.TetreeKey<?> key) { }
    }

    /** Tracker that captures the removal record for the .122 rollback-key assertion. */
    private static final class CapturingTracker implements OperationTracker {
        UUID removedBubbleId;
        com.hellblazer.luciferase.lucien.tetree.TetreeKey<?> removedKey;

        @Override public void recordBubbleAdded(UUID bubbleId) { }
        @Override public void recordBubbleRemoved(UUID bubbleId, EnhancedBubble snapshot,
                                                  com.hellblazer.luciferase.lucien.tetree.TetreeKey<?> key) {
            this.removedBubbleId = bubbleId;
            this.removedKey = key;
        }
    }
}
