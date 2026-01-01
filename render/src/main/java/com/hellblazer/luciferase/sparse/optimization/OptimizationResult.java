/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sparse.optimization;

import com.hellblazer.luciferase.sparse.core.SparseVoxelData;
import com.hellblazer.luciferase.sparse.core.SparseVoxelNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Result of running the optimization pipeline.
 *
 * <p>Contains the optimized data, a detailed report of all optimization steps,
 * and performance metrics.
 *
 * @param <D> the type of sparse voxel data
 * @author hal.hildebrand
 */
public record OptimizationResult<D extends SparseVoxelData<? extends SparseVoxelNode>>(
    D optimizedData,
    OptimizationReport report,
    Map<String, Float> performanceMetrics
) {
    /**
     * Compact constructor that creates a defensive copy of metrics.
     */
    public OptimizationResult(D optimizedData, OptimizationReport report,
                             Map<String, Float> performanceMetrics) {
        this.optimizedData = optimizedData;
        this.report = report;
        this.performanceMetrics = performanceMetrics != null
            ? new HashMap<>(performanceMetrics)
            : Map.of();
    }

    /**
     * Get performance metrics as an unmodifiable map.
     */
    public Map<String, Float> getPerformanceMetrics() {
        return Collections.unmodifiableMap(performanceMetrics);
    }

    /**
     * Get a specific performance metric.
     */
    public float getMetric(String name, float defaultValue) {
        return performanceMetrics.getOrDefault(name, defaultValue);
    }

    /**
     * Check if optimization was successful (no errors and improvement >= 1.0).
     */
    public boolean isSuccessful() {
        return optimizedData != null
            && report != null
            && !report.hasErrors()
            && report.overallImprovement() >= 1.0f;
    }

    @Override
    public String toString() {
        return String.format("OptimizationResult[data=%s, %s]",
            optimizedData != null ? optimizedData.nodeCount() + " nodes" : "null",
            report);
    }
}
