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
package com.hellblazer.luciferase.esvo.gpu;

/**
 * GPU memory pressure levels for large scene management.
 *
 * <p>Pressure levels determine when to trigger eviction, streaming,
 * or graceful degradation to CPU rendering.
 *
 * <p>Default thresholds:
 * <ul>
 *   <li>NONE: 0-75% utilization</li>
 *   <li>MODERATE: 75-85% utilization</li>
 *   <li>HIGH: 85-95% utilization</li>
 *   <li>CRITICAL: 95%+ utilization</li>
 * </ul>
 *
 * @see GPUMemoryManager
 */
public enum GPUMemoryPressure {
    /**
     * No memory pressure. Normal operation. (0% - 75% utilization)
     */
    NONE(0.0),

    /**
     * Moderate pressure. Start evicting unused buffers. (75% - 85% utilization)
     */
    MODERATE(0.75),

    /**
     * High pressure. Aggressive eviction, defer non-critical allocations. (85% - 95% utilization)
     */
    HIGH(0.85),

    /**
     * Critical pressure. May need to fall back to CPU rendering. (95%+ utilization)
     */
    CRITICAL(0.95);

    private final double threshold;

    GPUMemoryPressure(double threshold) {
        this.threshold = threshold;
    }

    /**
     * Returns the utilization threshold for this pressure level.
     */
    public double getThreshold() {
        return threshold;
    }

    /**
     * Determines pressure level from utilization percentage.
     *
     * @param utilization current utilization (0.0 to 1.0)
     * @return appropriate pressure level
     */
    public static GPUMemoryPressure fromUtilization(double utilization) {
        if (utilization >= CRITICAL.threshold) {
            return CRITICAL;
        } else if (utilization >= HIGH.threshold) {
            return HIGH;
        } else if (utilization >= MODERATE.threshold) {
            return MODERATE;
        }
        return NONE;
    }

    /**
     * Determines pressure level from utilization with custom thresholds.
     *
     * @param utilization       current utilization (0.0 to 1.0)
     * @param moderateThreshold threshold for MODERATE (default 0.75)
     * @param highThreshold     threshold for HIGH (default 0.85)
     * @param criticalThreshold threshold for CRITICAL (default 0.95)
     * @return appropriate pressure level
     */
    public static GPUMemoryPressure fromUtilization(
        double utilization,
        double moderateThreshold,
        double highThreshold,
        double criticalThreshold
    ) {
        if (utilization >= criticalThreshold) {
            return CRITICAL;
        } else if (utilization >= highThreshold) {
            return HIGH;
        } else if (utilization >= moderateThreshold) {
            return MODERATE;
        }
        return NONE;
    }

    /**
     * Returns true if this pressure level should trigger eviction.
     */
    public boolean shouldEvict() {
        return this.ordinal() >= MODERATE.ordinal();
    }

    /**
     * Returns true if this pressure level should defer allocations.
     */
    public boolean shouldDeferAllocations() {
        return this.ordinal() >= HIGH.ordinal();
    }

    /**
     * Returns true if this pressure level should fall back to CPU.
     */
    public boolean shouldFallbackToCPU() {
        return this == CRITICAL;
    }
}
