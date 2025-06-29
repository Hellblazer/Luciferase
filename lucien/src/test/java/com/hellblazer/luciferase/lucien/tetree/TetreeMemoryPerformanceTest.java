/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.performance.SpatialIndexMemoryPerformanceTest;
import org.junit.jupiter.api.DisplayName;

/**
 * Tetree-specific memory performance tests
 *
 * @author hal.hildebrand
 */
@DisplayName("Tetree Memory Performance Tests")
public class TetreeMemoryPerformanceTest
extends SpatialIndexMemoryPerformanceTest<BaseTetreeKey<?>, LongEntityID, String> {

    @Override
    protected SequentialLongIDGenerator createIDGenerator() {
        return new SequentialLongIDGenerator();
    }

    @Override
    protected SpatialIndex<BaseTetreeKey<?>, LongEntityID, String> createSpatialIndex(VolumeBounds bounds,
                                                                                      int maxDepth) {
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
