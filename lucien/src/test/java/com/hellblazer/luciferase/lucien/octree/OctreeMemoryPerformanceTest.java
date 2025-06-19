/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.performance.SpatialIndexMemoryPerformanceTest;
import org.junit.jupiter.api.DisplayName;

/**
 * Octree-specific memory performance tests
 *
 * @author hal.hildebrand
 */
@DisplayName("Octree Memory Performance Tests")
public class OctreeMemoryPerformanceTest extends SpatialIndexMemoryPerformanceTest<LongEntityID, String> {
    
    @Override
    protected String createTestContent(int entityIndex) {
        return "Entity-" + entityIndex;
    }
    
    @Override
    protected SpatialIndex<LongEntityID, String> createSpatialIndex(VolumeBounds bounds, int maxDepth) {
        return new Octree<>(createIDGenerator());
    }
    
    @Override
    protected String getImplementationName() {
        return "Octree";
    }
    
    @Override
    protected SequentialLongIDGenerator createIDGenerator() {
        return new SequentialLongIDGenerator();
    }
}