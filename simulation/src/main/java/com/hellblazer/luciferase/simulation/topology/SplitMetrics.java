/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.topology;

import java.util.Map;

/**
 * Diagnostic metrics for split operations.
 * <p>
 * Tracks split operation patterns to understand retry behavior and failure modes:
 * <ul>
 *   <li>Total split attempts initiated</li>
 *   <li>Successful vs failed splits</li>
 *   <li>Failure categorization by reason (KEY_COLLISION, NO_AVAILABLE_KEY, etc.)</li>
 *   <li>Average tree depth exhausted before failure</li>
 * </ul>
 * <p>
 * Used for diagnosing split retry patterns before implementing cooldown mechanisms.
 * <p>
 * Phase P1.1: Diagnostic Enhancement - Split Failure Metrics
 *
 * @param totalAttempts              total number of split operations initiated
 * @param successfulSplits           number of splits that completed successfully
 * @param failedSplits               number of splits that failed
 * @param failuresByReason           map of failure reason to count
 * @param avgLevelsExhaustedOnFailure average tree levels exhausted before failure (0.0 if no failures)
 * @author hal.hildebrand
 */
public record SplitMetrics(
    long totalAttempts,
    long successfulSplits,
    long failedSplits,
    Map<String, Long> failuresByReason,
    double avgLevelsExhaustedOnFailure
) {
}
