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
package com.hellblazer.luciferase.esvo.gpu.beam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stream C: Beam Optimization Decision Gate
 *
 * Determines whether to enable beam optimization based on:
 * 1. Current latency vs target (need for optimization)
 * 2. Ray coherence (potential for benefit)
 *
 * Decision: Enable beam optimization if BOTH:
 * - latency > target (performance not yet at goal)
 * - coherence >= threshold (rays share enough traversal to benefit)
 *
 * @author hal.hildebrand
 */
public class BeamOptimizationGate {
    private static final Logger log = LoggerFactory.getLogger(BeamOptimizationGate.class);

    // Target: 100K rays in <100µs (10x improvement)
    private static final double DEFAULT_LATENCY_THRESHOLD_MICROSECONDS = 100.0;

    // Coherence threshold: >50% node sharing required for beam benefit
    private static final double DEFAULT_COHERENCE_THRESHOLD = 0.5;

    private final double latencyThreshold;
    private final double coherenceThreshold;

    private String lastDecisionReason;

    /**
     * Create gate with default thresholds.
     */
    public BeamOptimizationGate() {
        this(DEFAULT_LATENCY_THRESHOLD_MICROSECONDS, DEFAULT_COHERENCE_THRESHOLD);
    }

    /**
     * Create gate with custom thresholds.
     *
     * @param latencyThreshold target latency in microseconds
     * @param coherenceThreshold coherence threshold [0.0, 1.0]
     */
    public BeamOptimizationGate(double latencyThreshold, double coherenceThreshold) {
        if (latencyThreshold <= 0) {
            throw new IllegalArgumentException("Latency threshold must be positive");
        }
        if (coherenceThreshold < 0.0 || coherenceThreshold > 1.0) {
            throw new IllegalArgumentException("Coherence threshold must be in [0.0, 1.0]");
        }

        this.latencyThreshold = latencyThreshold;
        this.coherenceThreshold = coherenceThreshold;
    }

    /**
     * Decide whether to enable beam optimization.
     *
     * @param latency current latency in microseconds
     * @param coherence ray coherence score [0.0, 1.0]
     * @return true if beam optimization should be enabled
     * @throws IllegalArgumentException if inputs are invalid
     */
    public boolean shouldEnableBeam(double latency, double coherence) {
        // Validate inputs
        if (latency < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latency);
        }
        if (coherence < 0.0 || coherence > 1.0) {
            throw new IllegalArgumentException("Coherence must be in [0.0, 1.0]: " + coherence);
        }

        // Check conditions
        var latencyNeedsImprovement = latency > latencyThreshold;
        var coherenceSufficient = coherence >= coherenceThreshold;

        var shouldEnable = latencyNeedsImprovement && coherenceSufficient;

        // Record decision rationale
        if (shouldEnable) {
            lastDecisionReason = String.format(
                "Beam optimization ENABLED: latency=%.2fµs (target <%.2fµs), coherence=%.3f (threshold >=%.3f)",
                latency, latencyThreshold, coherence, coherenceThreshold
            );
        } else {
            if (!latencyNeedsImprovement) {
                lastDecisionReason = String.format(
                    "Beam optimization DISABLED: latency=%.2fµs already meets target <%.2fµs",
                    latency, latencyThreshold
                );
            } else {
                lastDecisionReason = String.format(
                    "Beam optimization DISABLED: coherence=%.3f below threshold >=%.3f (insufficient benefit)",
                    coherence, coherenceThreshold
                );
            }
        }

        log.debug(lastDecisionReason);

        return shouldEnable;
    }

    /**
     * Get the latency threshold.
     *
     * @return latency threshold in microseconds
     */
    public double getLatencyThreshold() {
        return latencyThreshold;
    }

    /**
     * Get the coherence threshold.
     *
     * @return coherence threshold [0.0, 1.0]
     */
    public double getCoherenceThreshold() {
        return coherenceThreshold;
    }

    /**
     * Get the rationale for the last decision.
     *
     * @return decision reason string, or null if no decision made yet
     */
    public String getLastDecisionReason() {
        return lastDecisionReason;
    }
}
