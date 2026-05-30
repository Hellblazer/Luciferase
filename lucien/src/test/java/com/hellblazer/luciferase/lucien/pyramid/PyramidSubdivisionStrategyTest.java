/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.SubdivisionStrategy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PyramidIndex.createDefaultSubdivisionStrategy (Phase E, bead Luciferase-ioz).
 *
 * <p>The default strategy must:
 * <ul>
 *   <li>Return non-null at construction time (called from super-constructor).
 *   <li>Trigger child-descent when a node is over-threshold.
 *   <li>Retain all entities after subdivision.
 *   <li>Not silently defer all subdivisions (the Phase-A placeholder's behaviour).
 * </ul>
 */
class PyramidSubdivisionStrategyTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        // Default maxEntitiesPerNode=10; we'll use a small threshold index for tests
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    @Test
    void createDefaultSubdivisionStrategy_returnsNonNull() {
        // Called from super-constructor so index is non-null after construction
        var strategy = index.createDefaultSubdivisionStrategy();
        assertNotNull(strategy, "createDefaultSubdivisionStrategy must return non-null");
    }

    @Test
    void strategy_isNotTheDeferAllPlaceholder() {
        // Phase E must replace the Phase-A defer-all placeholder.
        // The real strategy should NOT return DEFER_SUBDIVISION unconditionally.
        // We verify it returns forceSubdivision or insertInParent when critically overloaded.
        var strategy = index.createDefaultSubdivisionStrategy();
        assertNotNull(strategy);

        // Build a context that is critically overloaded (size > maxEntitiesPerNode * 2)
        var key = PyramidKey.getRoot();
        var ctx = new SubdivisionStrategy.SubdivisionContext<PyramidKey, LongEntityID>(
            key,
            (byte) 0,
            25,    // currentNodeSize — critically overloaded (>10*2=20)
            10,    // maxEntitiesPerNode
            false, // isBulkOperation
            null,  // newEntityBounds
            List.of(),
            PyramidKey.MAX_PYRAMID_LEVEL
        );

        var result = strategy.determineStrategy(ctx);
        assertNotNull(result, "strategy must return a non-null result");
        // Must not be DEFER_SUBDIVISION unconditionally (the placeholder does this)
        assertNotEquals(SubdivisionStrategy.ControlFlow.DEFER_SUBDIVISION, result.decision,
                        "Real strategy must not always defer on critically overloaded node");
    }

    @Test
    void strategy_insertsInParentAtMaxDepth() {
        var strategy = index.createDefaultSubdivisionStrategy();
        var key = PyramidKey.getRoot();
        var ctx = new SubdivisionStrategy.SubdivisionContext<PyramidKey, LongEntityID>(
            key,
            PyramidKey.MAX_PYRAMID_LEVEL,   // at max depth
            20,
            10,
            false,
            null,
            List.of(),
            PyramidKey.MAX_PYRAMID_LEVEL
        );
        var result = strategy.determineStrategy(ctx);
        assertNotNull(result);
        assertEquals(SubdivisionStrategy.ControlFlow.INSERT_IN_PARENT, result.decision,
                     "At max depth, strategy should insert in parent");
    }

    @Test
    void insertingBeyondThreshold_doesNotThrow() {
        // Insert more than DEFAULT_MAX_ENTITIES_PER_NODE entities at the same location;
        // the strategy must not throw UnsupportedOperationException (the old placeholder).
        // We use a small index with maxEntitiesPerNode=3 to trigger subdivision quickly.
        var smallIndex = new PyramidIndex<LongEntityID, String>(
            new SequentialLongIDGenerator(), 3, (byte) 5);

        int h = Constants.lengthAtLevel((byte) 3);
        var pos = new Point3f(h, h, h);
        // Insert 6 entities — well above threshold
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 6; i++) {
                smallIndex.insert(pos, (byte) 3, "entity-" + i);
            }
        }, "Inserting beyond entity threshold must not throw UnsupportedOperationException");
    }

    @Test
    void strategy_detersSubdivisionForSmallNodeSize() {
        var strategy = index.createDefaultSubdivisionStrategy();
        var key = PyramidKey.getRoot();
        var ctx = new SubdivisionStrategy.SubdivisionContext<PyramidKey, LongEntityID>(
            key,
            (byte) 1,
            2,   // currentNodeSize — below minEntitiesForSplit (4 in balanced)
            10,
            false,
            null,
            List.of(),
            PyramidKey.MAX_PYRAMID_LEVEL
        );
        var result = strategy.determineStrategy(ctx);
        assertNotNull(result);
        // Should insert in parent or defer, not force subdivision for tiny nodes
        assertNotEquals(SubdivisionStrategy.ControlFlow.FORCE_SUBDIVISION, result.decision,
                        "Tiny node should not force subdivision");
    }
}
