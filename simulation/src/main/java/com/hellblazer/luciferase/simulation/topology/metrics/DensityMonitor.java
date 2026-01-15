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
package com.hellblazer.luciferase.simulation.topology.metrics;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors entity density per bubble with hysteresis state machine.
 * <p>
 * Tracks entity counts and applies split/merge threshold detection with
 * 10% hysteresis to prevent oscillation. Uses state machine to manage
 * transitions between NORMAL, APPROACHING_SPLIT, NEEDS_SPLIT,
 * APPROACHING_MERGE, and NEEDS_MERGE states.
 * <p>
 * <b>Split Threshold</b>: Configurable (default 5000 entities)
 * <ul>
 *   <li>APPROACHING_SPLIT: >90% of threshold (e.g., >4500)</li>
 *   <li>NEEDS_SPLIT: >100% of threshold (e.g., >5000)</li>
 *   <li>Clear NEEDS_SPLIT: <90% of threshold (hysteresis)</li>
 * </ul>
 * <p>
 * <b>Merge Threshold</b>: Configurable (default 500 entities)
 * <ul>
 *   <li>APPROACHING_MERGE: <110% of threshold (e.g., <550)</li>
 *   <li>NEEDS_MERGE: <100% of threshold (e.g., <500)</li>
 *   <li>Clear NEEDS_MERGE: >110% of threshold (hysteresis)</li>
 * </ul>
 * <p>
 * Thread-safe: All operations use concurrent data structures.
 *
 * @author hal.hildebrand
 */
public class DensityMonitor {

    private final int                                splitThreshold;
    private final int                                mergeThreshold;
    private final float                              approachingSplitThreshold; // 90% of split
    private final float                              approachingMergeThreshold; // 110% of merge
    private final ConcurrentHashMap<UUID, Integer>   entityCounts;
    private final ConcurrentHashMap<UUID, DensityState> states;

    /**
     * Creates a density monitor with specified thresholds.
     *
     * @param splitThreshold entity count above which split is recommended
     * @param mergeThreshold entity count below which merge is recommended
     * @throws IllegalArgumentException if thresholds invalid
     */
    public DensityMonitor(int splitThreshold, int mergeThreshold) {
        if (splitThreshold <= 0) {
            throw new IllegalArgumentException("Split threshold must be positive: " + splitThreshold);
        }
        if (mergeThreshold <= 0) {
            throw new IllegalArgumentException("Merge threshold must be positive: " + mergeThreshold);
        }
        if (mergeThreshold >= splitThreshold) {
            throw new IllegalArgumentException(
                "Merge threshold (" + mergeThreshold + ") must be less than split threshold (" + splitThreshold + ")");
        }

        this.splitThreshold = splitThreshold;
        this.mergeThreshold = mergeThreshold;
        this.approachingSplitThreshold = splitThreshold * 0.9f;
        this.approachingMergeThreshold = mergeThreshold * 1.1f;
        this.entityCounts = new ConcurrentHashMap<>();
        this.states = new ConcurrentHashMap<>();
    }

    /**
     * Updates density tracking with current entity distribution.
     * <p>
     * Processes all bubbles in the distribution and updates their states
     * according to the hysteresis state machine.
     *
     * @param distribution map of bubble ID to entity count
     */
    public void update(Map<UUID, Integer> distribution) {
        for (var entry : distribution.entrySet()) {
            var bubbleId = entry.getKey();
            var count = entry.getValue();

            entityCounts.put(bubbleId, count);
            updateState(bubbleId, count);
        }
    }

    /**
     * Updates the state for a single bubble based on entity count.
     *
     * @param bubbleId    the bubble identifier
     * @param entityCount current entity count
     */
    private void updateState(UUID bubbleId, int entityCount) {
        var currentState = states.getOrDefault(bubbleId, DensityState.NORMAL);
        var newState = computeNextState(currentState, entityCount);

        if (newState != currentState) {
            states.put(bubbleId, newState);
        }
    }

    /**
     * Computes the next state based on current state and entity count.
     * Implements hysteresis state machine transitions.
     *
     * @param currentState current density state
     * @param entityCount  current entity count
     * @return next state
     */
    private DensityState computeNextState(DensityState currentState, int entityCount) {
        return switch (currentState) {
            case NORMAL -> {
                if (entityCount > splitThreshold) {
                    yield DensityState.NEEDS_SPLIT;
                } else if (entityCount > approachingSplitThreshold) {
                    yield DensityState.APPROACHING_SPLIT;
                } else if (entityCount < mergeThreshold) {
                    yield DensityState.NEEDS_MERGE;
                } else if (entityCount < approachingMergeThreshold) {
                    yield DensityState.APPROACHING_MERGE;
                } else {
                    yield DensityState.NORMAL;
                }
            }
            case APPROACHING_SPLIT -> {
                if (entityCount > splitThreshold) {
                    yield DensityState.NEEDS_SPLIT;
                } else if (entityCount < mergeThreshold) {
                    yield DensityState.NEEDS_MERGE;
                } else if (entityCount < approachingMergeThreshold) {
                    yield DensityState.APPROACHING_MERGE;
                } else if (entityCount > approachingSplitThreshold) {
                    yield DensityState.APPROACHING_SPLIT;
                } else {
                    yield DensityState.NORMAL;
                }
            }
            case NEEDS_SPLIT -> {
                // Hysteresis: require drop to 90% of threshold to clear
                if (entityCount < approachingSplitThreshold) {
                    yield DensityState.APPROACHING_SPLIT;
                } else {
                    yield DensityState.NEEDS_SPLIT;
                }
            }
            case APPROACHING_MERGE -> {
                if (entityCount < mergeThreshold) {
                    yield DensityState.NEEDS_MERGE;
                } else if (entityCount > splitThreshold) {
                    yield DensityState.NEEDS_SPLIT;
                } else if (entityCount > approachingSplitThreshold) {
                    yield DensityState.APPROACHING_SPLIT;
                } else if (entityCount < approachingMergeThreshold) {
                    yield DensityState.APPROACHING_MERGE;
                } else {
                    yield DensityState.NORMAL;
                }
            }
            case NEEDS_MERGE -> {
                // Hysteresis: require rise to 110% of threshold to clear
                if (entityCount > approachingMergeThreshold) {
                    yield DensityState.APPROACHING_MERGE;
                } else {
                    yield DensityState.NEEDS_MERGE;
                }
            }
        };
    }

    /**
     * Gets the current density state for a bubble.
     *
     * @param bubbleId the bubble identifier
     * @return current density state (NORMAL if never tracked)
     */
    public DensityState getState(UUID bubbleId) {
        return states.getOrDefault(bubbleId, DensityState.NORMAL);
    }

    /**
     * Checks if a bubble needs splitting.
     *
     * @param bubbleId the bubble identifier
     * @return true if in NEEDS_SPLIT state
     */
    public boolean needsSplit(UUID bubbleId) {
        return getState(bubbleId) == DensityState.NEEDS_SPLIT;
    }

    /**
     * Checks if a bubble needs merging.
     *
     * @param bubbleId the bubble identifier
     * @return true if in NEEDS_MERGE state
     */
    public boolean needsMerge(UUID bubbleId) {
        return getState(bubbleId) == DensityState.NEEDS_MERGE;
    }

    /**
     * Gets the split ratio for a bubble (entityCount / splitThreshold).
     *
     * @param bubbleId the bubble identifier
     * @return ratio relative to split threshold (1.0 = at threshold, >1.0 = over)
     */
    public float getSplitRatio(UUID bubbleId) {
        var count = entityCounts.getOrDefault(bubbleId, 0);
        return (float) count / splitThreshold;
    }

    /**
     * Gets the merge ratio for a bubble (entityCount / mergeThreshold).
     *
     * @param bubbleId the bubble identifier
     * @return ratio relative to merge threshold (1.0 = at threshold, <1.0 = under)
     */
    public float getMergeRatio(UUID bubbleId) {
        var count = entityCounts.getOrDefault(bubbleId, 0);
        return (float) count / mergeThreshold;
    }

    /**
     * Gets the current entity count for a bubble.
     *
     * @param bubbleId the bubble identifier
     * @return entity count (0 if never tracked)
     */
    public int getEntityCount(UUID bubbleId) {
        return entityCounts.getOrDefault(bubbleId, 0);
    }

    /**
     * Clears all tracked state.
     */
    public void reset() {
        entityCounts.clear();
        states.clear();
    }
}
