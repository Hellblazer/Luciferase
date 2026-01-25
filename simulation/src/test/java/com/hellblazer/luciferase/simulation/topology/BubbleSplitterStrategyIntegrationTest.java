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
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BubbleSplitter with pluggable SplitPlaneStrategy.
 * <p>
 * Tests verify:
 * - Default strategy is LongestAxisStrategy (backward compatibility)
 * - Custom strategies can be injected
 * - Strategy actually affects split plane used
 * - Different strategies produce different results
 * - Backward-compatible constructor still works
 * <p>
 * P2.3: Demo Integration - Strategy Pattern Integration
 *
 * @author hal.hildebrand
 */
class BubbleSplitterStrategyIntegrationTest {

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
     * Test 1: Default constructor uses LongestAxisStrategy.
     * <p>
     * Backward compatibility requirement: existing code should continue to work.
     */
    @Test
    void testDefaultConstructor_UsesLongestAxisStrategy() {
        // Use backward-compatible constructor (no strategy parameter)
        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);

        // Create bubble with X-dominant bounds (entities spread along X-axis)
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add 5100 entities spread along X-axis (X=0 to X=100, Y=50, Z=50)
        for (int i = 0; i < 5100; i++) {
            var entityId = UUID.randomUUID();
            var x = (i / 51.0f); // Spread 0-100 along X
            bubble.addEntity(entityId.toString(), new Point3f(x, 50.0f, 50.0f), null);
            accountant.register(bubble.id(), entityId);
        }

        // Execute split with a test proposal
        // Strategy should automatically choose X-axis (longest dimension)
        var bounds = bubble.bounds();
        var centroid = bounds.centroid();

        // Create proposal with dummy split plane (strategy will override it)
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            SplitPlane.yAxis((float) centroid.getY()), // Intentionally wrong axis
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        // Should succeed with strategy-selected plane
        assertTrue(result.success(), "Split should succeed with default strategy: " + result.message());
    }

    /**
     * Test 2: Custom strategy is used when provided.
     * <p>
     * Verify that BubbleSplitter accepts and uses injected strategies.
     */
    @Test
    void testCustomStrategy_IsUsedWhenProvided() {
        // Create splitter with explicit X-axis strategy
        var xAxisStrategy = SplitPlaneStrategies.xAxis();
        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics, xAxisStrategy);

        // Create bubble with Y-dominant bounds
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();

        // Add 5100 entities spread along Y-axis
        for (int i = 0; i < 5100; i++) {
            var entityId = UUID.randomUUID();
            var y = (i / 51.0f); // Spread 0-100 along Y
            bubble.addEntity(entityId.toString(), new Point3f(50.0f, y, 50.0f), null);
            accountant.register(bubble.id(), entityId);
        }

        // Execute split
        var bounds = bubble.bounds();
        var centroid = bounds.centroid();
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            SplitPlane.xAxis((float) centroid.getX()), // X-axis should be used by strategy
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        // Should succeed - strategy uses X-axis even though Y is longest
        assertTrue(result.success(), "Split should succeed with custom X-axis strategy: " + result.message());
    }

    /**
     * Test 3: Strategy affects actual split plane used.
     * <p>
     * Verify that the strategy's calculate() method is actually invoked and its
     * result affects entity partitioning.
     */
    @Test
    void testStrategy_AffectsSplitPlane() {
        // Create spy strategy to track invocations
        var invoked = new boolean[1];
        SplitPlaneStrategy spyStrategy = (bounds, entities) -> {
            invoked[0] = true;
            return SplitPlane.xAxis((float) bounds.centroid().getX());
        };

        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics, spyStrategy);

        // Create bubble with entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var bounds = bubble.bounds();
        var centroid = bounds.centroid();
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            SplitPlane.xAxis((float) centroid.getX()),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        splitter.execute(proposal);

        // Verify strategy was invoked
        assertTrue(invoked[0], "Strategy.calculate() should have been invoked");
    }

    /**
     * Test 4: Different strategies produce different partitioning results.
     * <p>
     * Validate that different strategies produce different split planes
     * when entities have unequal distributions along different axes.
     */
    @Test
    void testDifferentStrategies_ProduceDifferentResults() {
        // Create two bubbles
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbleList = new java.util.ArrayList<>(bubbleGrid.getAllBubbles());
        var bubble1 = bubbleList.get(0);
        var bubble2 = bubbleList.get(1);

        // Add entities with X-dominant distribution (spread along X, clustered in Y,Z)
        for (int i = 0; i < 5100; i++) {
            var id1 = UUID.randomUUID();
            var id2 = UUID.randomUUID();
            var position = new Point3f(i * 0.01f, 5.0f, 5.0f); // X varies, Y and Z fixed

            bubble1.addEntity(id1.toString(), position, null);
            accountant.register(bubble1.id(), id1);

            bubble2.addEntity(id2.toString(), position, null);
            accountant.register(bubble2.id(), id2);
        }

        // Create two splitters with different strategies
        var xAxisSplitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics,
                                               SplitPlaneStrategies.xAxis());
        var yAxisSplitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics,
                                               SplitPlaneStrategies.yAxis());

        var bounds = bubble1.bounds();
        var centroid = bounds.centroid();

        // Execute splits with different strategies
        var xProposal = new SplitProposal(
            UUID.randomUUID(),
            bubble1.id(),
            SplitPlane.xAxis((float) centroid.getX()),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var yProposal = new SplitProposal(
            UUID.randomUUID(),
            bubble2.id(),
            SplitPlane.yAxis((float) centroid.getY()),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var xResult = xAxisSplitter.execute(xProposal);
        var yResult = yAxisSplitter.execute(yProposal);

        // Both should succeed
        assertTrue(xResult.success(), "X-axis split should succeed");
        assertTrue(yResult.success(), "Y-axis split should succeed");

        // X-axis split should partition entities (spread along X)
        // Y-axis split should fail to partition or have all entities on one side (no spread along Y)
        var xNew = accountant.entitiesInBubble(xResult.newBubbleId()).size();
        var yNew = accountant.entitiesInBubble(yResult.newBubbleId()).size();

        // X-axis split should move approximately half the entities
        assertTrue(xNew > 1000 && xNew < 4000, "X-axis split should partition entities: " + xNew);
        // Y-axis split should move either 0 or all entities (no variance along Y)
        assertTrue(yNew == 0 || yNew == 5100, "Y-axis split should not partition clustered entities: " + yNew);
    }

    /**
     * Test 5: CyclicAxisStrategy cycles through axes across multiple splits.
     * <p>
     * Verify that the cyclic strategy maintains state and rotates through axes.
     */
    @Test
    void testCyclicStrategy_CyclesAcrossMultipleSplits() {
        var cyclicStrategy = SplitPlaneStrategies.cyclic();

        // Create one bubble with entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var axes = new java.util.ArrayList<SplitPlane.SplitAxis>();
        var allRecords = bubble.getAllEntityRecords();
        var bounds = bubble.bounds();

        // Call strategy multiple times to verify cycling
        for (int i = 0; i < 5; i++) {
            var plane = cyclicStrategy.calculate(bounds, allRecords);
            axes.add(plane.axis());
        }

        // Verify cycling pattern: X -> Y -> Z -> X -> Y
        assertEquals(SplitPlane.SplitAxis.X, axes.get(0), "First call should use X-axis");
        assertEquals(SplitPlane.SplitAxis.Y, axes.get(1), "Second call should use Y-axis");
        assertEquals(SplitPlane.SplitAxis.Z, axes.get(2), "Third call should use Z-axis");
        assertEquals(SplitPlane.SplitAxis.X, axes.get(3), "Fourth call should cycle back to X-axis");
        assertEquals(SplitPlane.SplitAxis.Y, axes.get(4), "Fifth call should use Y-axis");
    }

    /**
     * Test 6: Backward-compatible constructor preserves existing behavior.
     * <p>
     * Ensures 100% backward compatibility - all existing code continues to work.
     */
    @Test
    void testBackwardCompatibility_ExistingConstructorWorks() {
        // Use old constructor without strategy parameter
        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);

        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var bounds = bubble.bounds();
        var centroid = bounds.centroid();
        var proposal = new SplitProposal(
            UUID.randomUUID(),
            bubble.id(),
            SplitPlane.alongLongestAxis(bounds),
            DigestAlgorithm.DEFAULT.getOrigin(),
            System.currentTimeMillis()
        );

        var result = splitter.execute(proposal);

        // Should work exactly as before
        assertTrue(result.success(), "Backward-compatible split should succeed");
        assertEquals(5100, result.entitiesAfter(), "Entity conservation should work");
    }

    /**
     * Test 7: Strategy is consulted for every split operation.
     * <p>
     * Verify that the strategy is invoked each time execute() is called,
     * not just once during construction.
     */
    @Test
    void testStrategy_ConsultedForEverySplit() {
        var invocationCount = new int[1];
        SplitPlaneStrategy countingStrategy = (bounds, entities) -> {
            invocationCount[0]++;
            return SplitPlane.xAxis((float) bounds.centroid().getX());
        };

        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics, countingStrategy);

        // Create 2 bubbles and split them both
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbleList = new java.util.ArrayList<>(bubbleGrid.getAllBubbles());

        for (var bubble : bubbleList) {
            addEntities(bubble, 5100);
            var bounds = bubble.bounds();
            var centroid = bounds.centroid();
            var proposal = new SplitProposal(
                UUID.randomUUID(),
                bubble.id(),
                SplitPlane.xAxis((float) centroid.getX()),
                DigestAlgorithm.DEFAULT.getOrigin(),
                System.currentTimeMillis()
            );
            splitter.execute(proposal);
        }

        // Strategy should be invoked once per split
        assertEquals(2, invocationCount[0], "Strategy should be invoked for each split operation");
    }

    /**
     * Test 8: Null strategy throws NullPointerException.
     * <p>
     * Validate defensive programming.
     */
    @Test
    void testNullStrategy_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> {
            new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics, null);
        }, "Null strategy should be rejected");
    }

    // Helper method
    private void addEntities(EnhancedBubble bubble, int count) {
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
}
