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

/**
 * Execution path decision for hybrid CPU/GPU tile rendering.
 *
 * <p>Three-way decision based on tile coherence and GPU saturation:
 * <ul>
 *   <li><b>GPU_BATCH</b> - High coherence (&gt;= 0.7), use batch SIMD kernel</li>
 *   <li><b>GPU_SINGLE</b> - Medium coherence (0.3-0.7), use single-ray GPU kernel</li>
 *   <li><b>CPU</b> - Low coherence (&lt; 0.3) or GPU saturated, use CPU traversal</li>
 * </ul>
 *
 * @see HybridTileDispatcher
 */
public enum HybridDecision {
    /**
     * Execute on GPU using batch kernel with SIMD factor 4.
     * Optimal for high-coherence tiles where rays traverse similar paths.
     */
    GPU_BATCH,

    /**
     * Execute on GPU using single-ray kernel.
     * Used for medium-coherence tiles where batch optimization isn't beneficial.
     */
    GPU_SINGLE,

    /**
     * Execute on CPU using traversal algorithm.
     * Preferred for very low coherence tiles or when GPU is saturated.
     */
    CPU;

    /**
     * Determines if this decision uses GPU execution.
     *
     * @return true if GPU_BATCH or GPU_SINGLE
     */
    public boolean usesGPU() {
        return this == GPU_BATCH || this == GPU_SINGLE;
    }

    /**
     * Determines if this decision uses CPU execution.
     *
     * @return true if CPU
     */
    public boolean usesCPU() {
        return this == CPU;
    }

    /**
     * Determines if this decision uses batch processing.
     *
     * @return true if GPU_BATCH
     */
    public boolean isBatch() {
        return this == GPU_BATCH;
    }
}
