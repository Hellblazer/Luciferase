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

    // ---- .40: degenerate one-sided plane rejected -------------------------

    @Test
    void degenerateOneSidedPlaneFailsFast() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        // Strategy is recomputed in execute(); we drive a degenerate partition by
        // forcing a plane whose normal puts EVERY entity on the positive side.
        // Use a strategy that returns a plane far below all positions so signed
        // distance >= 0 for all of them.
        var degenerateStrategy = (SplitPlaneStrategy) (bounds, records) ->
            new SplitPlane(new Point3f(1f, 0f, 0f), -1_000_000f, SplitPlane.SplitAxis.X);

        var splitter = new BubbleSplitter(bubbleGrid, accountant, new NoopTracker(), metrics, degenerateStrategy);

        var proposal = new SplitProposal(UUID.randomUUID(), bubble.id(),
                                         new SplitPlane(new Point3f(1f, 0f, 0f), 0f),
                                         DigestAlgorithm.DEFAULT.getOrigin(), 0L);

        var result = splitter.execute(proposal);
        assertFalse(result.success(), "Degenerate one-sided split plane must fail fast");
        assertEquals(5100, accountant.entitiesInBubble(bubble.id()).size(),
                     "Source bubble must retain all entities on a degenerate-plane rejection");
    }

    // ---- .41: no entity leak; grid bubble added before moves --------------

    @Test
    void newBubbleInGridForSuccessfulSplit() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);
        int before = accountant.entitiesInBubble(bubble.id()).size();

        var proposal = splitProposal(bubble);
        var result = executor.execute(proposal);

        assertTrue(result.success(), () -> "Split should succeed: " + result.message());
        assertEquals(before, getTotal(), "Conservation across the whole grid");
        // Every entity recorded in the accountant must live in a bubble that exists in the grid.
        var newBubbleId = findNewBubbleId(bubble.id());
        assertNotNull(newBubbleId, "A new bubble must have been created");
        assertNotNull(bubbleGrid.getBubbleById(newBubbleId),
                      "New bubble holding migrated entities must be present in the grid (no leak)");
    }

    // ---- .42 / .45: split metrics not double-counted ----------------------

    @Test
    void splitSuccessRecordedExactlyOnce() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var result = executor.execute(splitProposal(bubble));
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
        executor.execute(proposal);
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

        var result = executor.execute(splitProposal(bubble));
        assertTrue(result.success(), () -> "Split should succeed: " + result.message());

        assertEquals(1, events.size());
        var split = assertInstanceOf(SplitEvent.class, events.get(0));
        assertTrue(split.entitiesMoved() > 0,
                   "entitiesMoved must reflect the real relocation count, not (after-before)=0");
    }

    // ---- .70: MergeEvent.entitiesMoved is the real relocation count --------

    @Test
    void mergeEventReportsActualEntitiesMoved() {
        bubbleGrid.createBubbles(2, (byte) 2, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);
        addEntities(bubble1, 30);
        addEntities(bubble2, 20);

        List<TopologyEvent> events = new ArrayList<>();
        executor.addListener(events::add);

        var proposal = new MergeProposal(UUID.randomUUID(), bubble1.id(), bubble2.id(),
                                         DigestAlgorithm.DEFAULT.getOrigin(), 0L);
        var result = executor.execute(proposal);
        assertTrue(result.success(), () -> "Merge should succeed: " + result.message());

        assertEquals(1, events.size());
        var merge = assertInstanceOf(MergeEvent.class, events.get(0));
        // 20 entities were relocated from bubble2 into bubble1. (after - before) == 0
        // by conservation, so the field must come from the merger's relocation count.
        assertEquals(20, merge.entitiesMoved(),
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

        var result = executor.execute(splitProposal(bubble));
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
        var result = merger.execute(proposal);
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

    private UUID findNewBubbleId(UUID source) {
        for (var id : accountant.getDistribution().keySet()) {
            if (!id.equals(source) && !accountant.entitiesInBubble(id).isEmpty()) {
                return id;
            }
        }
        return null;
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
