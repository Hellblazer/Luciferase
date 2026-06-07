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
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pinning test for the RDR-018 AC-4 (F4) coverage fence on {@link BubbleMerger#execute}.
 * <p>
 * <b>What it pins.</b> An arbitrary two-bubble merge removes bubble2 from the grid while
 * keeping bubble1's key, leaving bubble2's tetrahedral region untiled — a partition coverage
 * hole through which an escaping entity would later be silently dropped (RDR-018 F4). Under the
 * Option-B interim there is no coverage-preserving merge available (the inverse-Bey
 * sibling-collapse merge lands with B-core / AC-2.5), so the merger MUST hard-fence:
 * <ol>
 *   <li>{@code execute} returns failure (no exception, no no-op-pretending-success);</li>
 *   <li>NO mutation occurs — both bubbles remain in the grid (coverage intact) and every
 *       entity stays in its original bubble;</li>
 *   <li>the failure message names the coverage hole so the rejection is diagnosable.</li>
 * </ol>
 * This is the AC-4 deliverable: it confirms the chosen behaviour actually closes the coverage
 * hole (by never opening it), not merely relabels it.
 *
 * @author hal.hildebrand
 */
class BubbleMergerCoverageFenceTest {

    private static final TestClock CLOCK = new TestClock(1_000L);

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics  metrics;
    private BubbleMerger     merger;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics    = new TopologyMetrics();
        merger     = new BubbleMerger(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
    }

    @Test
    void execute_rejectsArbitraryTwoBubbleMerge_failLoud() {
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 120);
        addEntities(bubble2, 80);

        var proposal = new MergeProposal(UUID.randomUUID(), bubble1.id(), bubble2.id(),
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());

        var result = merger.execute(proposal);

        // (1) fail-loud: rejected, not a silent success and not a thrown exception
        assertFalse(result.success(), "Arbitrary two-bubble merge must be rejected under the AC-4 coverage fence");
        // (3) diagnosable: the message must name the coverage hole / the fence
        assertTrue(result.message().toLowerCase().contains("coverage")
                   || result.message().toLowerCase().contains("untiled"),
                   "Rejection message must name the coverage hole: " + result.message());
        assertTrue(result.message().contains("AC-4"), "Rejection message should reference RDR-018 AC-4: " + result.message());
    }

    @Test
    void execute_doesNotPunchCoverageHole_bothBubblesRemainAndEntitiesUnmoved() {
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 120);
        addEntities(bubble2, 80);

        int b1Before = accountant.entitiesInBubble(bubble1.id()).size();
        int b2Before = accountant.entitiesInBubble(bubble2.id()).size();
        int bubbleCountBefore = bubbleGrid.getAllBubbles().size();

        merger.execute(new MergeProposal(UUID.randomUUID(), bubble1.id(), bubble2.id(),
                                         DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis()));

        // (2a) bubble2 was NOT removed — its tetrahedral region stays tiled (no coverage hole)
        assertNotNull(bubbleGrid.getBubbleById(bubble2.id()),
                      "bubble2 must NOT be removed — removing it would untile its region (coverage hole)");
        assertNotNull(bubbleGrid.getBubbleById(bubble1.id()), "bubble1 must remain");
        assertEquals(bubbleCountBefore, bubbleGrid.getAllBubbles().size(),
                     "Bubble count must be unchanged — no bubble removed by a fenced merge");

        // (2b) no entity was moved — both bubbles retain exactly their pre-merge entities
        assertEquals(b1Before, accountant.entitiesInBubble(bubble1.id()).size(),
                     "bubble1 entity set must be unchanged by a fenced merge");
        assertEquals(b2Before, accountant.entitiesInBubble(bubble2.id()).size(),
                     "bubble2 entity set must be unchanged by a fenced merge");

        // accountant stays globally consistent
        assertTrue(accountant.validate().success(), "Accountant must remain consistent after a fenced merge");
    }

    @Test
    void execute_rejectsBeforeFence_whenBubbleMissing() {
        // The "not found" precondition still wins over the coverage fence so callers get the
        // precise reason for a malformed proposal.
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var existing = bubbleGrid.getAllBubbles().iterator().next();

        var result = merger.execute(new MergeProposal(UUID.randomUUID(), existing.id(), UUID.randomUUID(),
                                                      DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis()));

        assertFalse(result.success());
        assertTrue(result.message().contains("not found"),
                   "Missing-bubble proposal must report 'not found', not the coverage fence: " + result.message());

        // Symmetric case: bubble1 missing must also report "not found" before the fence.
        var result2 = merger.execute(new MergeProposal(UUID.randomUUID(), UUID.randomUUID(), existing.id(),
                                                       DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis()));
        assertFalse(result2.success());
        assertTrue(result2.message().contains("not found"),
                   "Missing bubble1 must report 'not found', not the coverage fence: " + result2.message());
    }

    private void addEntities(EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(bubble.id(), entityId);
        }
    }
}
