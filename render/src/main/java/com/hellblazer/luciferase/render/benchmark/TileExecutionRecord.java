/*
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.render.benchmark;

/**
 * Single per-tile execution record for statistical analysis.
 *
 * Used to collect data points for coherence-speed correlation analysis.
 *
 * @param frameIndex Which frame this record came from
 * @param tileX Horizontal tile coordinate
 * @param tileY Vertical tile coordinate
 * @param coherenceScore Measured coherence score [0.0, 1.0]
 * @param executionTimeMs Execution time in milliseconds
 * @param rayCount Number of rays in this tile
 * @param isBatchKernel True if executed with batch kernel, false if single-ray
 */
public record TileExecutionRecord(
    int frameIndex,
    int tileX,
    int tileY,
    double coherenceScore,
    double executionTimeMs,
    int rayCount,
    boolean isBatchKernel
) {
    /**
     * Validate record invariants.
     */
    public TileExecutionRecord {
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frameIndex must be non-negative");
        }
        if (tileX < 0 || tileY < 0) {
            throw new IllegalArgumentException("Tile coordinates must be non-negative");
        }
        if (coherenceScore < 0.0 || coherenceScore > 1.0) {
            throw new IllegalArgumentException("Coherence must be in [0, 1]");
        }
        if (executionTimeMs < 0) {
            throw new IllegalArgumentException("Execution time must be non-negative");
        }
        if (rayCount <= 0) {
            throw new IllegalArgumentException("Ray count must be positive");
        }
    }

    /**
     * Classify coherence into band.
     */
    public CoherenceBand getCoherenceBand() {
        if (coherenceScore < 0.3) {
            return CoherenceBand.LOW;
        } else if (coherenceScore < 0.7) {
            return CoherenceBand.MEDIUM;
        } else {
            return CoherenceBand.HIGH;
        }
    }

    /**
     * Coherence classification bands.
     */
    public enum CoherenceBand {
        LOW,      // [0.0, 0.3) - Single-ray kernel
        MEDIUM,   // [0.3, 0.7) - Borderline
        HIGH      // [0.7, 1.0] - Batch kernel
    }
}
