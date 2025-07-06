/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.performance.SpatialIndexCreationPerformanceTest;
import org.junit.jupiter.api.DisplayName;

/**
 * Tetree-specific creation performance tests
 *
 * @author hal.hildebrand
 */
@DisplayName("Tetree Creation Performance Tests")
public class TetreeCreationPerformanceTest
extends SpatialIndexCreationPerformanceTest<TetreeKey<?>, LongEntityID, String> {

    @Override
    protected SequentialLongIDGenerator createIDGenerator() {
        return new SequentialLongIDGenerator();
    }

    @Override
    protected SpatialIndex<TetreeKey<?>, LongEntityID, String> createSpatialIndex(VolumeBounds bounds, int maxDepth) {
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
