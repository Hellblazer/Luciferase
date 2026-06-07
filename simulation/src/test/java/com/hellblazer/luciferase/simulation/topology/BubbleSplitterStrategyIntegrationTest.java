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
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BubbleSplitter with pluggable SplitPlaneStrategy.
 * <p>
 * With the Bey-refinement redesign (AC-2.5), the SplitPlaneStrategy is retained for
 * backward compatibility (the constructor still accepts one) but is NO LONGER consulted
 * for geometry — child keys come from {@link com.hellblazer.luciferase.lucien.tetree.Tet#geometricSubdivide()}
 * and containment from {@code contains12DOP}. Tests in this class validate:
 * <ul>
 *   <li>Default and custom constructors still compile and accept strategies</li>
 *   <li>Split succeeds regardless of which strategy is injected (strategy ignored for keys)</li>
 *   <li>Entity conservation holds for all constructor forms</li>
 *   <li>Null strategy still throws NPE (constructor guard)</li>
 *   <li>CyclicAxisStrategy itself still cycles correctly (strategy API is valid)</li>
 * </ul>
 * <p>
 * Tests that verified strategy.calculate() was INVOKED are removed — that contract
 * no longer holds after AC-2.5.
 * <p>
 * P2.3: Demo Integration - Strategy Pattern Integration
 *
 * @author hal.hildebrand
 */
class BubbleSplitterStrategyIntegrationTest {
    private static final TestClock JC1KH_CLOCK = new TestClock(1_000L); // determinism mandate (Luciferase-jc1kh)

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics metrics;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        metrics = new TopologyMetrics();
    }

    /**
     * Test 1: Default constructor (no strategy) succeeds; entity conservation holds.
     * <p>
     * Backward compatibility requirement: existing code should continue to work.
     * The Bey splitter ignores the strategy for geometry but the constructor remains valid.
     * FIX 6: assert entitiesRedistributed == before (no silent skip now that FIX 1 uses nearest-child).
     */
    @Test
    void testDefaultConstructor_SucceedsAndConservesEntities() {
        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);

        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);
        int before = accountant.entitiesInBubble(bubble.id()).size();

        var proposal = splitProposal(bubble);
        var result = splitter.execute(proposal);

        assertTrue(result.success(), "Default-constructor split should succeed: " + result.message());
        assertEquals(before, result.entitiesAfter(), "Entity conservation must hold");
        // FIX 2: all 8 children always kept
        assertEquals(8, result.childBubbleIds().size(), "Successful split must return exactly 8 children");
        // FIX 6: no entity silently dropped — entitiesRedistributed == before
        assertEquals(before, result.entitiesRedistributed(),
                     "entitiesRedistributed must equal source count (no silent skip)");
    }

    /**
     * Test 2: Custom X-axis strategy injected — split succeeds (strategy not consulted for geometry).
     * <p>
     * The strategy is accepted by the constructor and stored, but the Bey refinement path uses
     * geometricSubdivide() for keys and contains12DOP for containment — never strategy.calculate().
     */
    @Test
    void testCustomStrategy_AcceptedAndSplitSucceeds() {
        var xAxisStrategy = SplitPlaneStrategies.xAxis();
        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics, xAxisStrategy);

        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var result = splitter.execute(splitProposal(bubble));

        assertTrue(result.success(), "Split with custom X-axis strategy should succeed: " + result.message());
        assertEquals(8, result.childBubbleIds().size(), "Successful split must return exactly 8 children");
    }

    /**
     * Test 3: Two different strategies injected — BOTH splits succeed (strategy not consulted).
     * <p>
     * In the old plane-based splitter, degenerate Y-axis split on Y-clustered entities would
     * fail. With Bey refinement the strategy is irrelevant and both splits always use
     * geometricSubdivide(), so both succeed on identical entity distributions.
     */
    @Test
    void testDifferentStrategies_BothSucceedBeyRefinementIgnoresStrategy() {
        // Two bubbles with identical entity distribution
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbleList = new java.util.ArrayList<>(bubbleGrid.getAllBubbles());
        var bubble1 = bubbleList.get(0);
        var bubble2 = bubbleList.get(1);

        for (int i = 0; i < 5100; i++) {
            var id1 = UUID.randomUUID();
            var id2 = UUID.randomUUID();
            var position = new Point3f(i * 0.01f, 5.0f, 5.0f); // X varies, Y and Z fixed
            bubble1.addEntity(id1.toString(), position, null);
            accountant.register(bubble1.id(), id1);
            bubble2.addEntity(id2.toString(), position, null);
            accountant.register(bubble2.id(), id2);
        }

        var xAxisSplitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics,
                                               SplitPlaneStrategies.xAxis());
        var yAxisSplitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics,
                                               SplitPlaneStrategies.yAxis());

        var xResult = xAxisSplitter.execute(splitProposal(bubble1));
        var yResult = yAxisSplitter.execute(splitProposal(bubble2));

        // Both should succeed: Bey refinement ignores the injected strategy entirely
        assertTrue(xResult.success(), "X-axis strategy: split should succeed: " + xResult.message());
        assertTrue(yResult.success(), "Y-axis strategy: split should succeed (strategy ignored by Bey): " + yResult.message());

        // Entity conservation for both
        assertEquals(5100, xResult.entitiesAfter(), "X-axis split must conserve entities");
        assertEquals(5100, yResult.entitiesAfter(), "Y-axis split must conserve entities");
        // FIX 6: no entity silently dropped
        assertEquals(5100, xResult.entitiesRedistributed(),
                     "X-axis split: entitiesRedistributed must equal source count (no silent skip)");
        assertEquals(5100, yResult.entitiesRedistributed(),
                     "Y-axis split: entitiesRedistributed must equal source count (no silent skip)");
        // FIX 2: all 8 children always kept
        assertEquals(8, xResult.childBubbleIds().size(), "X-axis split: exactly 8 children");
        assertEquals(8, yResult.childBubbleIds().size(), "Y-axis split: exactly 8 children");
    }

    /**
     * Test 4: CyclicAxisStrategy itself cycles correctly (strategy API validation).
     * <p>
     * The strategy object is still valid; only its use inside BubbleSplitter.execute() changed.
     */
    @Test
    void testCyclicStrategy_CyclesAcrossMultipleCalls() {
        var cyclicStrategy = SplitPlaneStrategies.cyclic();

        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var axes = new java.util.ArrayList<SplitPlane.SplitAxis>();
        var allRecords = bubble.getAllEntityRecords();
        var bounds = bubble.bounds();

        // Call strategy directly to verify cycling (not via BubbleSplitter.execute())
        for (int i = 0; i < 5; i++) {
            var plane = cyclicStrategy.calculate(bounds, allRecords);
            axes.add(plane.axis());
        }

        assertEquals(SplitPlane.SplitAxis.X, axes.get(0), "First call should use X-axis");
        assertEquals(SplitPlane.SplitAxis.Y, axes.get(1), "Second call should use Y-axis");
        assertEquals(SplitPlane.SplitAxis.Z, axes.get(2), "Third call should use Z-axis");
        assertEquals(SplitPlane.SplitAxis.X, axes.get(3), "Fourth call should cycle back to X-axis");
        assertEquals(SplitPlane.SplitAxis.Y, axes.get(4), "Fifth call should use Y-axis");
    }

    /**
     * Test 5: Backward-compatible constructor preserves entity conservation.
     */
    @Test
    void testBackwardCompatibility_ExistingConstructorWorks() {
        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);

        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var bounds = bubble.bounds();
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            SplitPlane.alongLongestAxis(bounds),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        assertTrue(result.success(), "Backward-compatible split should succeed");
        assertEquals(5100, result.entitiesAfter(), "Entity conservation should work");
        // FIX 6: no entity silently dropped
        assertEquals(5100, result.entitiesRedistributed(),
                     "entitiesRedistributed must equal source count (no silent skip)");
        // FIX 2: all 8 children always kept
        assertEquals(8, result.childBubbleIds().size(), "Successful split must return exactly 8 children");
    }

    /**
     * Test 6: Null strategy throws NullPointerException (constructor guard).
     */
    @Test
    void testNullStrategy_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics, null);
        }, "Null strategy should be rejected");
    }

    // ---- helpers ----

    private SplitProposal splitProposal(EnhancedBubble bubble) {
        var centroid = bubble.centroid();
        return new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            SplitPlane.xAxis((float) centroid.getX()),
            DigestAlgorithm.DEFAULT.getOrigin(),
            JC1KH_CLOCK.currentTimeMillis()
        );
    }

    private void addEntities(EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(entityId.toString(), new Point3f(i * 0.01f, i * 0.01f, i * 0.01f), null);
            accountant.register(bubble.id(), entityId);
        }
    }
}
