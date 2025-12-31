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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Result of a single optimization step.
 *
 * <p>Captures the name of the optimizer, execution time, improvement factor,
 * and any additional details about the optimization performed.
 *
 * @author hal.hildebrand
 */
public record OptimizationStep(
    String optimizerName,
    float executionTimeMs,
    float improvementFactor,
    Map<String, Object> details
) {
    /**
     * Compact constructor that creates a defensive copy of details.
     */
    public OptimizationStep(String optimizerName, float executionTimeMs,
                           float improvementFactor, Map<String, Object> details) {
        this.optimizerName = optimizerName;
        this.executionTimeMs = executionTimeMs;
        this.improvementFactor = improvementFactor;
        this.details = details != null ? new HashMap<>(details) : Map.of();
    }

    /**
     * Get step details as an unmodifiable map.
     */
    public Map<String, Object> getDetails() {
        return Collections.unmodifiableMap(details);
    }

    /**
     * Check if this step had an error.
     */
    public boolean hasError() {
        return details.containsKey("error");
    }

    /**
     * Get the error message if present.
     */
    public String getErrorMessage() {
        var error = details.get("error");
        return error != null ? error.toString() : null;
    }

    @Override
    public String toString() {
        return String.format("OptimizationStep[%s: %.1fms, %.2fx%s]",
            optimizerName, executionTimeMs, improvementFactor,
            hasError() ? " (ERROR)" : "");
    }
}
