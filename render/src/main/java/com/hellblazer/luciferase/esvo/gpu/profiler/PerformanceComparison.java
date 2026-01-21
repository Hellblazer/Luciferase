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
 * Comparison between baseline and optimized performance metrics.
 *
 * @param baseline    Baseline performance metrics
 * @param optimized   Optimized performance metrics
 * @param improvement Improvement percentage (positive = faster)
 *
 * @author hal.hildebrand
 */
public record PerformanceComparison(
    PerformanceMetrics baseline,
    PerformanceMetrics optimized,
    double improvement
) {

    /**
     * Calculate speedup factor.
     *
     * @return speedup factor (e.g., 1.89 = 1.89x faster)
     */
    public double speedupFactor() {
        return optimized.speedupFactor(baseline);
    }

    /**
     * Check if optimized is actually faster than baseline.
     *
     * @return true if optimized is faster
     */
    public boolean isFaster() {
        return improvement > 0;
    }

    /**
     * Check if improvement meets target threshold.
     *
     * @param targetImprovement target improvement percentage
     * @return true if improvement meets or exceeds target
     */
    public boolean meetsTarget(double targetImprovement) {
        return improvement >= targetImprovement;
    }
}
