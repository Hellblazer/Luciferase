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
package com.hellblazer.luciferase.esvo.dag.cache;

/**
 * Statistics for DAG cache performance.
 *
 * @param hitCount cache hits
 * @param missCount cache misses
 * @param evictionCount number of evictions
 * @param averageAccessTimeMs average access time in milliseconds
 * @author hal.hildebrand
 */
public record CacheStats(
    long hitCount,
    long missCount,
    long evictionCount,
    float averageAccessTimeMs
) {
    /**
     * Calculate hit rate as a ratio [0.0, 1.0].
     *
     * @return hit rate (hitCount / totalAccesses), 0.0 if no accesses
     */
    public float hitRate() {
        var totalAccesses = hitCount + missCount;
        if (totalAccesses == 0) {
            return 0.0f;
        }
        return (float) hitCount / totalAccesses;
    }
}
