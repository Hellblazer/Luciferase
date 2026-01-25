/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.render.tile;

import com.hellblazer.luciferase.esvo.gpu.beam.Ray;

/**
 * Extended kernel executor interface supporting hybrid CPU/GPU rendering.
 *
 * <p>Adds CPU execution capability to the base KernelExecutor interface,
 * enabling optimal work distribution between CPU and GPU based on
 * tile coherence and workload characteristics.
 *
 * <p>Execution paths:
 * <ul>
 *   <li><b>GPU Batch</b> ({@link #executeBatch}) - High coherence tiles, SIMD-optimized</li>
 *   <li><b>GPU Single</b> ({@link #executeSingleRay}) - Medium coherence, per-ray GPU execution</li>
 *   <li><b>CPU</b> ({@link #executeCPU}) - Low coherence or when GPU is saturated</li>
 * </ul>
 *
 * @see KernelExecutor
 * @see HybridTileDispatcher
 */
public interface HybridKernelExecutor extends KernelExecutor {

    /**
     * Executes ray intersection on CPU. Used for tiles where CPU execution
     * is more efficient than GPU (very low coherence, complex traversal patterns,
     * or when GPU is saturated).
     *
     * @param rays       Global ray array
     * @param rayIndices Indices of rays to process
     */
    void executeCPU(Ray[] rays, int[] rayIndices);

    /**
     * Checks if CPU execution is available for this executor.
     *
     * @return true if CPU execution is supported
     */
    default boolean supportsCPU() {
        return true;
    }

    /**
     * Returns the estimated cost ratio of CPU to GPU execution.
     * A value of 2.0 means CPU is expected to be 2x slower than GPU.
     * Used by dispatcher for load balancing decisions.
     *
     * @return CPU/GPU cost ratio (>1.0 means GPU is faster)
     */
    default double getCPUGPURatio() {
        return 2.0; // Default: GPU is 2x faster
    }

    /**
     * Returns the current GPU saturation level (0.0 to 1.0).
     * When saturation is high, dispatcher may prefer CPU execution.
     *
     * @return GPU saturation level (0.0 = idle, 1.0 = fully saturated)
     */
    default double getGPUSaturation() {
        return 0.0; // Default: not saturated
    }
}
