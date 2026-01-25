/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.Map;

/**
 * Metrics collected during Phase 4.5 E2E validation tests.
 * <p>
 * Records timing information, phase durations, message counts, and consistency
 * validation results for fault tolerance scenarios.
 *
 * @param detectionLatencyMs Time from fault injection to detection (ms)
 * @param recoveryTimeMs Time from detection to recovery completion (ms)
 * @param totalTimeMs Total scenario execution time (ms)
 * @param phaseTimes Duration of each recovery phase (DETECTING, REDISTRIBUTING, REBALANCING, VALIDATING)
 * @param messageCount Total number of messages exchanged during recovery
 * @param ghostLayerConsistent Whether ghost layer remained consistent during recovery
 */
public record Phase45TestMetrics(
    long detectionLatencyMs,
    long recoveryTimeMs,
    long totalTimeMs,
    Map<String, Long> phaseTimes,
    int messageCount,
    boolean ghostLayerConsistent
) {
    /**
     * Create metrics with default values for initial state.
     *
     * @return default metrics (all zeros, empty phase map, consistent=true)
     */
    public static Phase45TestMetrics defaults() {
        return new Phase45TestMetrics(0, 0, 0, Map.of(), 0, true);
    }
}
