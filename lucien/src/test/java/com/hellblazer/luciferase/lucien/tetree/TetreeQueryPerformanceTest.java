/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.performance.SpatialIndexQueryPerformanceTest;
import org.junit.jupiter.api.DisplayName;

/**
 * Tetree-specific query performance tests
 *
 * @author hal.hildebrand
 */
@DisplayName("Tetree Query Performance Tests")
public class TetreeQueryPerformanceTest
extends SpatialIndexQueryPerformanceTest<BaseTetreeKey<? extends BaseTetreeKey>, LongEntityID, String> {

    @Override
    protected SequentialLongIDGenerator createIDGenerator() {
        return new SequentialLongIDGenerator();
    }

    @Override
    protected SpatialIndex<BaseTetreeKey<? extends BaseTetreeKey>, LongEntityID, String> createSpatialIndex(
    VolumeBounds bounds, int maxDepth) {
        return new Tetree<>(createIDGenerator());
    }

    @Override
    protected String createTestContent(int entityIndex) {
        return "Entity-" + entityIndex;
    }

    @Override
    protected String getImplementationName() {
        return "Tetree";
    }
}
