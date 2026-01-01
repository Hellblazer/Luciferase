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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Statistics for a single optimizer's performance over multiple executions.
 *
 * <p>Thread-safe class that tracks execution count, total time, and cumulative
 * improvement factor.
 *
 * @author hal.hildebrand
 */
public class OptimizerStats {

    private static final int IMPROVEMENT_SCALE = 1000;

    private final AtomicLong totalExecutions = new AtomicLong(0);
    private final AtomicLong totalExecutionTimeNs = new AtomicLong(0);
    private final AtomicLong totalImprovementScaled = new AtomicLong(0);

    /**
     * Record an optimizer execution.
     *
     * @param executionTimeNs execution time in nanoseconds
     * @param improvement improvement factor (e.g., 1.1 for 10% improvement)
     */
    public void recordExecution(long executionTimeNs, float improvement) {
        totalExecutions.incrementAndGet();
        totalExecutionTimeNs.addAndGet(executionTimeNs);
        totalImprovementScaled.addAndGet((long) (improvement * IMPROVEMENT_SCALE));
    }

    /**
     * Get total number of executions.
     */
    public long getTotalExecutions() {
        return totalExecutions.get();
    }

    /**
     * Get total execution time in nanoseconds.
     */
    public long getTotalExecutionTimeNs() {
        return totalExecutionTimeNs.get();
    }

    /**
     * Get average execution time in milliseconds.
     */
    public float getAverageExecutionTimeMs() {
        var executions = totalExecutions.get();
        return executions > 0
            ? (float) totalExecutionTimeNs.get() / (executions * 1_000_000)
            : 0.0f;
    }

    /**
     * Get average improvement factor.
     */
    public float getAverageImprovement() {
        var executions = totalExecutions.get();
        return executions > 0
            ? (float) totalImprovementScaled.get() / (executions * IMPROVEMENT_SCALE)
            : 1.0f;
    }

    /**
     * Reset all statistics.
     */
    public void reset() {
        totalExecutions.set(0);
        totalExecutionTimeNs.set(0);
        totalImprovementScaled.set(0);
    }

    @Override
    public String toString() {
        return String.format("OptimizerStats[executions=%d, avgTime=%.1fms, avgImprovement=%.2fx]",
            getTotalExecutions(), getAverageExecutionTimeMs(), getAverageImprovement());
    }
}
