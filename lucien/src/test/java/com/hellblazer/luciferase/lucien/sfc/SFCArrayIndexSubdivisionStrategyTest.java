/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.sfc;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Luciferase-5oruk: {@code SFCArrayIndex.createDefaultSubdivisionStrategy} returned {@code null}, so any caller of
 * {@code getSubdivisionStrategy().…} would NPE. It now returns a {@code NoOpSubdivisionStrategy}.
 *
 * @author hal.hildebrand
 */
class SFCArrayIndexSubdivisionStrategyTest {

    @Test
    void subdivisionStrategyIsNeverNullAndNpeSafe() {
        var index = new SFCArrayIndex<LongEntityID, String>(new SequentialLongIDGenerator());

        var strategy = index.getSubdivisionStrategy();
        assertNotNull(strategy, "getSubdivisionStrategy() must never return null (Luciferase-5oruk)");
        // Invoking accessors on the strategy must not NPE.
        assertDoesNotThrow(strategy::getLoadFactor, "NoOp strategy accessors must be safe to call");
        assertDoesNotThrow(strategy::getMinEntitiesForSplit, "NoOp strategy accessors must be safe to call");
    }
}
