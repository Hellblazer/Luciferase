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

import java.util.*;

/**
 * Report summarizing all optimization steps.
 *
 * <p>Provides a comprehensive view of the optimization process including
 * individual step results, total time, and overall improvement.
 *
 * @author hal.hildebrand
 */
public record OptimizationReport(
    List<OptimizationStep> steps,
    float totalTimeMs,
    float overallImprovement,
    Map<String, Object> summary
) {
    /**
     * Compact constructor that creates defensive copies.
     */
    public OptimizationReport(List<OptimizationStep> steps, float totalTimeMs,
                             float overallImprovement, Map<String, Object> summary) {
        this.steps = steps != null ? new ArrayList<>(steps) : List.of();
        this.totalTimeMs = totalTimeMs;
        this.overallImprovement = overallImprovement;
        this.summary = summary != null ? new HashMap<>(summary) : Map.of();
    }

    /**
     * Get optimization steps as an unmodifiable list.
     */
    public List<OptimizationStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * Get summary as an unmodifiable map.
     */
    public Map<String, Object> getSummary() {
        return Collections.unmodifiableMap(summary);
    }

    /**
     * Get the number of steps.
     */
    public int stepCount() {
        return steps.size();
    }

    /**
     * Check if any step had an error.
     */
    public boolean hasErrors() {
        return steps.stream().anyMatch(OptimizationStep::hasError);
    }

    /**
     * Get the most effective optimizer name.
     */
    public Optional<String> mostEffectiveOptimizer() {
        return steps.stream()
            .max(Comparator.comparing(OptimizationStep::improvementFactor))
            .map(OptimizationStep::optimizerName);
    }

    /**
     * Get average improvement per step.
     */
    public float averageImprovement() {
        if (steps.isEmpty()) {
            return 1.0f;
        }
        return (float) steps.stream()
            .mapToDouble(OptimizationStep::improvementFactor)
            .average()
            .orElse(1.0);
    }

    @Override
    public String toString() {
        return String.format("OptimizationReport[steps=%d, time=%.1fms, improvement=%.2fx]",
            steps.size(), totalTimeMs, overallImprovement);
    }
}
