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
package com.hellblazer.luciferase.esvo.gpu.profiler;

/**
 * Configuration for GPU performance profiling.
 *
 * @param rayCount            Number of rays to trace
 * @param enableCache         Enable shared memory cache (Stream A)
 * @param workgroupSize       Workgroup size for GPU kernel
 * @param maxTraversalDepth   Maximum DAG traversal depth
 * @param iterations          Number of measurement iterations for averaging
 *
 * @author hal.hildebrand
 */
public record ProfilerConfig(
    int rayCount,
    boolean enableCache,
    int workgroupSize,
    int maxTraversalDepth,
    int iterations
) {

    /**
     * Default configuration for baseline profiling.
     */
    public static ProfilerConfig baseline(int rayCount) {
        return new ProfilerConfig(
            rayCount,
            false,      // No cache for baseline
            64,         // Default workgroup size
            16,         // Default traversal depth
            5           // 5 iterations for averaging
        );
    }

    /**
     * Default configuration for optimized profiling (Stream A+B).
     */
    public static ProfilerConfig optimized(int rayCount) {
        return new ProfilerConfig(
            rayCount,
            true,       // Cache enabled (Stream A)
            64,         // Will be tuned by GPUAutoTuner (Stream B)
            16,         // Default traversal depth
            5           // 5 iterations for averaging
        );
    }
}
