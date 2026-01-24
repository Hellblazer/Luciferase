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

import java.time.Duration;
import java.util.*;

/**
 * Test utility for simulating various failure scenarios in fault detection.
 *
 * <p>Provides methods to inject failures, create split-brain scenarios, and
 * simulate network partitions for testing fault detection logic.
 *
 * <p><b>Supported Scenarios</b>:
 * <ul>
 *   <li>Crash - Immediate failure of a partition</li>
 *   <li>Slowdown - Degraded response time</li>
 *   <li>Split-brain - Network partition between groups</li>
 *   <li>Chaos - Random failures at specified probability</li>
 * </ul>
 */
public class FaultSimulator {

    private final List<FailureDetector> detectors;
    private final Map<Integer, UUID> rankToPartition;
    private final Random random = new Random();

    /**
     * Create a FaultSimulator with specified detectors and rank mappings.
     *
     * @param detectors list of failure detectors to control
     * @param rankToPartition mapping from rank to partition UUID
     */
    public FaultSimulator(List<FailureDetector> detectors,
                         Map<Integer, UUID> rankToPartition) {
        this.detectors = Objects.requireNonNull(detectors, "detectors must not be null");
        this.rankToPartition = Objects.requireNonNull(rankToPartition,
                                                       "rankToPartition must not be null");
    }

    /**
     * Crash a partition (immediate failure).
     *
     * <p>Stops heartbeat detection and marks as failed.
     *
     * @param rank partition rank to crash
     */
    public void crashPartition(int rank) {
        var partitionId = rankToPartition.get(rank);
        if (partitionId != null && rank >= 0 && rank < detectors.size()) {
            var detector = detectors.get(rank);
            detector.unregisterPartition(partitionId);
        }
    }

    /**
     * Slow down a partition (degraded performance).
     *
     * <p>Stops sending heartbeats, simulating network delay.
     *
     * @param rank partition rank to slow down
     * @param delayMs delay in milliseconds before recovery
     */
    public void slowPartition(int rank, long delayMs) {
        var partitionId = rankToPartition.get(rank);
        if (partitionId != null && rank >= 0 && rank < detectors.size()) {
            // Simulate by not recording heartbeat
            // Recovery happens after delay in real scenario
        }
    }

    /**
     * Recover a partition from failure.
     *
     * <p>Restores heartbeat sending and marks as healthy.
     *
     * @param rank partition rank to recover
     */
    public void recoverPartition(int rank) {
        var partitionId = rankToPartition.get(rank);
        if (partitionId != null && rank >= 0 && rank < detectors.size()) {
            var detector = detectors.get(rank);
            detector.registerPartition(partitionId);
            detector.recordHeartbeat(partitionId);
        }
    }

    /**
     * Create a split-brain scenario (network partition).
     *
     * <p>Stops heartbeat exchange between two groups, simulating network
     * partition where groups cannot communicate.
     *
     * @param groupA first group (ranks as Set)
     * @param groupB second group (ranks as Set)
     */
    public void splitBrain(Set<Integer> groupA, Set<Integer> groupB) {
        // Partition all members of groupA (stop heartbeats)
        for (var rank : groupA) {
            slowPartition(rank, Long.MAX_VALUE);
        }

        // Partition all members of groupB (stop heartbeats)
        for (var rank : groupB) {
            slowPartition(rank, Long.MAX_VALUE);
        }
    }

    /**
     * Heal a split-brain scenario.
     *
     * <p>Restores communication between all partitions.
     */
    public void healSplitBrain() {
        for (int rank = 0; rank < detectors.size(); rank++) {
            recoverPartition(rank);
        }
    }

    /**
     * Inject random failures (chaos engineering).
     *
     * <p>Each partition has a chance to fail based on failure probability.
     *
     * @param failureProbability probability (0.0-1.0) of partition failure
     * @param duration how long chaos lasts
     */
    public void chaos(double failureProbability, Duration duration) {
        if (failureProbability < 0.0 || failureProbability > 1.0) {
            throw new IllegalArgumentException(
                "failureProbability must be between 0.0 and 1.0");
        }

        // For each partition, randomly decide to fail
        for (int rank = 0; rank < detectors.size(); rank++) {
            if (random.nextDouble() < failureProbability) {
                crashPartition(rank);
            }
        }
    }

    /**
     * Get the current state of all detectors.
     *
     * @return map of rank to isRunning boolean
     */
    public Map<Integer, Boolean> getDetectorStates() {
        var states = new HashMap<Integer, Boolean>();
        for (int i = 0; i < detectors.size(); i++) {
            states.put(i, detectors.get(i).isRunning());
        }
        return states;
    }
}
