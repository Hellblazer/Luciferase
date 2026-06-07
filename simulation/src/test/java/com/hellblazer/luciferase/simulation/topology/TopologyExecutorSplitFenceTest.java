/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pinning test for the RDR-018 AC-0 (gate S4) split fence on {@link TopologyExecutor#execute}.
 * <p>
 * <b>What it pins.</b> The current {@code BubbleSplitter} is a plane-based logical split that is
 * geometrically incompatible with the key-as-geometry migration partition and self-defeating under
 * live migration (RDR-018 F2): it inserts an L+1 child the router cannot see, mis-routing or
 * dropping entities. Split is not on the live tick path (F1). Until B-core (AC-2.5) redesigns the
 * splitter as true Bey refinement, the executor MUST fail-loud at the public boundary rather than
 * drive a broken operation — a <em>documented failure result</em>, NOT an exception and NOT a
 * silent no-op. Mirrors the RDR-012 D2 boundary-pinning pattern for the unconsumed split path.
 * <p>
 * Asserts: (1) {@code execute(SplitProposal)} returns failure (no throw); (2) the message documents
 * the deferral to B-core; (3) NO mutation — bubble count unchanged, no new bubble, entities
 * unmoved; (4) {@code execute(null)} still throws NPE (the null contract is unchanged).
 *
 * @author hal.hildebrand
 */
class TopologyExecutorSplitFenceTest {

    private static final TestClock CLOCK = new TestClock(1_000L);

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

    @Test
    void execute_rejectsSplitProposal_documentedFailureNotExceptionNotNoOp() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100); // would be splittable under a working splitter

        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();
        int totalBefore = accountant.entitiesInBubble(bubble.id()).size();

        var centroid = bubble.centroid();
        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), (float) centroid.getX());
        var proposal = new SplitProposal(UUID.randomUUID(), bubble.id(), splitPlane,
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());

        // (1) documented failure, not an exception
        var result = assertDoesNotThrow(() -> executor.execute(proposal),
                                        "A fenced SplitProposal must return a result, not throw");
        assertFalse(result.success(), "SplitProposal must be rejected under the AC-0 fence");

        // (2) message documents the deferral
        assertTrue(result.message().contains("AC-0"), "Message should reference RDR-018 AC-0: " + result.message());
        assertTrue(result.message().toLowerCase().contains("split"),
                   "Message should name the rejected split: " + result.message());

        // (3) NOT a silent no-op-that-mutates: nothing changed in the grid or the accountant
        assertEquals(bubbleCountBefore, bubbleGrid.getAllBubbles().size(),
                     "A fenced split must not add a bubble");
        assertEquals(totalBefore, accountant.entitiesInBubble(bubble.id()).size(),
                     "A fenced split must not move any entity");
        assertEquals(totalBefore, result.entitiesBefore(), "Reported entitiesBefore must reflect conserved total");
        assertEquals(totalBefore, result.entitiesAfter(), "Reported entitiesAfter must equal entitiesBefore (conserved)");
        assertTrue(accountant.validate().success(), "Accountant must remain consistent after a fenced split");

        // Metric choice (pinned explicitly): a fenced split is rejected at the boundary BEFORE the
        // splitter runs, so it records NEITHER a success NOR a categorised failure — the WARN log is
        // the only signal. A dedicated fence/rejection counter is Stage-2 observability scope
        // (deferred, tracked on Luciferase-xtyki), not built here.
        assertEquals(0L, metrics.getSplitMetrics().successfulSplits(),
                     "A fenced split must not record a successful split");
        assertEquals(0L, metrics.getSplitMetrics().failedSplits(),
                     "A fenced split records no categorised failure (fence, not attempt) — Stage-2 observability deferred");
    }

    @Test
    void execute_nullProposal_stillThrows() {
        assertThrows(NullPointerException.class, () -> executor.execute(null),
                     "The null-proposal contract is unchanged by the AC-0 fence");
    }

    private void addEntities(com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(bubble.id(), entityId);
        }
    }
}
