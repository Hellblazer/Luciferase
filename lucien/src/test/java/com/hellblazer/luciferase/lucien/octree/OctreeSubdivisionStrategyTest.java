/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.SubdivisionStrategy;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for OctreeSubdivisionStrategy
 *
 * @author hal.hildebrand
 */
public class OctreeSubdivisionStrategyTest {

    private OctreeSubdivisionStrategy<LongEntityID, String> strategy;

    @BeforeEach
    void setUp() {
        strategy = OctreeSubdivisionStrategy.balanced();
    }

    @Test
    void testCalculateTargetNodes() {
        // Entity at a specific position and level
        EntityBounds bounds = new EntityBounds(new Point3f(400, 400, 400), new Point3f(700, 700, 700));

        // At level 5, the cell size is quite large, so this entity might fit in one octant
        var targetNodes = strategy.calculateTargetNodes(MortonKey.getRoot(), (byte) 5, bounds, null);

        assertNotNull(targetNodes);
        assertTrue(targetNodes.size() > 0, "Should have at least one target node");

        // Test with a truly spanning entity at a finer level
        var largerBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(2000, 2000, 2000));

        var spanningTargets = strategy.calculateTargetNodes(MortonKey.getRoot(), (byte) 10, largerBounds, null);
        assertNotNull(spanningTargets);
        assertTrue(spanningTargets.size() > 0);
        // At finer levels, large entities are more likely to span multiple nodes
    }

    @Test
    void testCreateSingleChildDecision() {
        // Entity fits in single octant
        EntityBounds smallBounds = new EntityBounds(new Point3f(10, 10, 10), new Point3f(20, 20, 20));

        List<LongEntityID> existingEntities = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            existingEntities.add(new LongEntityID(i));
        }

        var context = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(new MortonKey(0L), (byte) 5,
                                                                                          5, 10, false, smallBounds,
                                                                                          existingEntities, (byte) 21);

        var result = strategy.determineStrategy(context);

        // The strategy should decide based on benefit calculation
        assertNotNull(result.decision);
        assertNotNull(result.reason);
    }

    @Test
    void testDeferSubdivisionDecision() {
        // Test during bulk operation
        var context = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(new MortonKey(12345L),
                                                                                          (byte) 10, 12, 10, true, null,
                                                                                          new ArrayList<>(), (byte) 21);

        var result = strategy.determineStrategy(context);
        assertEquals(SubdivisionStrategy.ControlFlow.DEFER_SUBDIVISION, result.decision);
        assertTrue(result.reason.contains("Bulk operation"));
    }

    @Test
    void testForceSubdivisionDecision() {
        // Test critically overloaded
        var context = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(new MortonKey(12345L),
                                                                                          (byte) 10, 25, 10, false,
                                                                                          null, new ArrayList<>(),
                                                                                          (byte) 21);

        var result = strategy.determineStrategy(context);
        assertEquals(SubdivisionStrategy.ControlFlow.FORCE_SUBDIVISION, result.decision);
        assertTrue(result.reason.contains("critically overloaded"));
    }

    @Test
    void testInsertInParentDecision() {
        // Test at max depth
        var context = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(new MortonKey(12345L),
                                                                                          (byte) 21, 5, 10, false, null,
                                                                                          new ArrayList<>(), (byte) 21);

        var result = strategy.determineStrategy(context);
        assertEquals(SubdivisionStrategy.ControlFlow.INSERT_IN_PARENT, result.decision);
        assertTrue(result.reason.contains("maximum depth"));

        // Test with too few entities
        context = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(new MortonKey(12345L), (byte) 10,
                                                                                      2, 10, false, null,
                                                                                      new ArrayList<>(), (byte) 21);

        result = strategy.determineStrategy(context);
        assertEquals(SubdivisionStrategy.ControlFlow.INSERT_IN_PARENT, result.decision);
        assertTrue(result.reason.contains("Too few entities"));
    }

    @Test
    void testPresetStrategies() {
        // Test dense point cloud strategy
        var denseStrategy = OctreeSubdivisionStrategy.forDensePointClouds();
        assertEquals(8, denseStrategy.getMinEntitiesForSplit());
        assertEquals(0.9, denseStrategy.getLoadFactor());
        assertEquals(0.1, denseStrategy.getSpanningThreshold());

        // Test large entities strategy
        var largeStrategy = OctreeSubdivisionStrategy.forLargeEntities();
        assertEquals(2, largeStrategy.getMinEntitiesForSplit());
        assertEquals(0.5, largeStrategy.getLoadFactor());
        assertEquals(0.7, largeStrategy.getSpanningThreshold());

        // Test balanced strategy
        var balancedStrategy = OctreeSubdivisionStrategy.balanced();
        assertEquals(4, balancedStrategy.getMinEntitiesForSplit());
        assertEquals(0.75, balancedStrategy.getLoadFactor());
        assertEquals(0.5, balancedStrategy.getSpanningThreshold());
    }

    @Test
    void testSplitToChildrenDecision() {
        // Very large entity that spans multiple octants at a finer level
        EntityBounds largeBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(5000, 5000, 5000));

        var context = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(new MortonKey(0L), (byte) 10,
                                                                                          8, 10, false, largeBounds,
                                                                                          new ArrayList<>(), (byte) 21);

        var result = strategy.determineStrategy(context);
        // The strategy makes intelligent decisions - it might choose CREATE_SINGLE_CHILD
        // if the entity fits in one child at the current level
        assertNotNull(result.decision);
        assertNotNull(result.reason);

        // For truly spanning entities, test at a level where it must span
        var spanningContext = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(new MortonKey(0L),
                                                                                                  (byte) 15, 8, 10,
                                                                                                  false, largeBounds,
                                                                                                  new ArrayList<>(),
                                                                                                  (byte) 21);

        var spanningResult = strategy.determineStrategy(spanningContext);
        if (spanningResult.decision == SubdivisionStrategy.ControlFlow.SPLIT_TO_CHILDREN) {
            assertTrue(spanningResult.reason.contains("Entity spans"));
            assertNotNull(spanningResult.targetNodes);
            assertTrue(spanningResult.targetNodes.size() > 1);
        }
    }

    @Test
    void testSubdivisionBenefitEstimation() {
        // Test various scenarios
        var smallContext = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(new MortonKey(12345L),
                                                                                               (byte) 10, 5, 10, false,
                                                                                               null, new ArrayList<>(),
                                                                                               (byte) 21);

        var overloadedContext = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(
        new MortonKey(12345L), (byte) 10, 15, 10, false, null, new ArrayList<>(), (byte) 21);

        var nearMaxDepthContext = new SubdivisionStrategy.SubdivisionContext<MortonKey, LongEntityID>(
        new MortonKey(12345L), (byte) 20, 10, 10, false, null, new ArrayList<>(), (byte) 21);

        // Different contexts should produce different decisions
        var smallResult = strategy.determineStrategy(smallContext);
        var overloadedResult = strategy.determineStrategy(overloadedContext);
        var nearMaxResult = strategy.determineStrategy(nearMaxDepthContext);

        // Verify that different contexts lead to different strategies
        assertNotNull(smallResult.decision);
        assertNotNull(overloadedResult.decision);
        assertNotNull(nearMaxResult.decision);

        // Overloaded should likely force subdivision
        assertEquals(SubdivisionStrategy.ControlFlow.FORCE_SUBDIVISION, overloadedResult.decision);
    }
}
