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

/**
 * Interface for optimizers in the optimization pipeline.
 *
 * <p>Optimizers transform sparse voxel data to improve performance characteristics
 * such as memory layout, cache efficiency, or GPU coalescing.
 *
 * @param <D> the type of sparse voxel data this optimizer works with
 * @author hal.hildebrand
 */
public interface Optimizer<D extends SparseVoxelData<? extends SparseVoxelNode>> {

    /**
     * Get the name of this optimizer.
     *
     * @return optimizer name
     */
    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * Optimize the input data.
     *
     * @param input the data to optimize
     * @return optimized data (may be same instance if no optimization needed)
     */
    D optimize(D input);

    /**
     * Estimate the improvement factor for the given input.
     *
     * <p>This is used for reporting. Return 1.0 if unknown.
     *
     * @param input the data to analyze
     * @return estimated improvement factor (e.g., 1.1 for 10% improvement)
     */
    default float estimateImprovement(D input) {
        return 1.0f;
    }

    /**
     * Check if this optimizer is applicable to the given input.
     *
     * @param input the data to check
     * @return true if this optimizer can process the input
     */
    default boolean isApplicable(D input) {
        return input != null && input.nodeCount() > 0;
    }
}
