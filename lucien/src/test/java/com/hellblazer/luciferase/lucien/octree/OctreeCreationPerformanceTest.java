/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.performance.SpatialIndexCreationPerformanceTest;
import org.junit.jupiter.api.DisplayName;

/**
 * Octree-specific creation performance tests
 *
 * @author hal.hildebrand
 */
@DisplayName("Octree Creation Performance Tests")
public class OctreeCreationPerformanceTest
extends SpatialIndexCreationPerformanceTest<MortonKey, LongEntityID, String> {

    @Override
    protected SequentialLongIDGenerator createIDGenerator() {
        return new SequentialLongIDGenerator();
    }

    @Override
    protected SpatialIndex<MortonKey, LongEntityID, String> createSpatialIndex(VolumeBounds bounds, int maxDepth) {
        return new Octree<>(createIDGenerator());
    }

    @Override
    protected String createTestContent(int entityIndex) {
        return "Entity-" + entityIndex;
    }

    @Override
    protected String getImplementationName() {
        return "Octree";
    }
}
