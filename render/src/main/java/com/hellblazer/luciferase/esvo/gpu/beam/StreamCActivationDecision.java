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

import com.hellblazer.luciferase.esvo.gpu.profiler.CoherenceMetrics;
import com.hellblazer.luciferase.esvo.gpu.profiler.PerformanceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Phase 4.2 P2: Stream C Activation Decision Logic
 *
 * Determines whether to activate beam optimization based on P1 performance metrics.
 *
 * Decision Tree:
 * 1. IF latency < 500µs → SKIP_BEAM (target met, success!)
 * 2. ELSE IF coherence >= 0.5 → ENABLE_BEAM (coherent rays, activate batch kernel)
 * 3. ELSE → INVESTIGATE_ALTERNATIVES (low coherence, beam won't help)
 *
 * @author hal.hildebrand
 */
public class StreamCActivationDecision {
    private static final Logger log = LoggerFactory.getLogger(StreamCActivationDecision.class);

    // Decision thresholds
    private static final double DEFAULT_LATENCY_TARGET_MICROSECONDS = 500.0;
    private static final double DEFAULT_COHERENCE_THRESHOLD = 0.5;

    private final double latencyTarget;
    private final double coherenceThreshold;

    private String lastRationale;

    /**
     * Create decision logic with default thresholds.
     *
     * Defaults:
     * - Latency target: 500µs for 100K rays
     * - Coherence threshold: 0.5 (50% node sharing)
     */
    public StreamCActivationDecision() {
        this(DEFAULT_LATENCY_TARGET_MICROSECONDS, DEFAULT_COHERENCE_THRESHOLD);
    }

    /**
     * Create decision logic with custom thresholds.
     *
     * @param latencyTarget       target latency in microseconds
     * @param coherenceThreshold  coherence threshold [0.0, 1.0]
     */
    public StreamCActivationDecision(double latencyTarget, double coherenceThreshold) {
        if (latencyTarget <= 0) {
            throw new IllegalArgumentException("latencyTarget must be positive: " + latencyTarget);
        }
        if (coherenceThreshold < 0.0 || coherenceThreshold > 1.0) {
            throw new IllegalArgumentException("coherenceThreshold must be in [0.0, 1.0]: " + coherenceThreshold);
        }

        this.latencyTarget = latencyTarget;
        this.coherenceThreshold = coherenceThreshold;

        log.debug("Stream C decision thresholds: latency <{}µs, coherence >={}",
                  latencyTarget, coherenceThreshold);
    }

    /**
     * Evaluate performance metrics and decide on Stream C activation.
     *
     * Decision priority:
     * 1. Check latency first (if target met, skip optimization regardless of coherence)
     * 2. Then check coherence (if sufficient, enable beam optimization)
     * 3. Otherwise, investigate alternatives
     *
     * @param optimized optimized performance metrics (Streams A+B)
     * @param coherence ray coherence metrics
     * @return decision enum value
     * @throws IllegalArgumentException if inputs are null
     */
    public Decision evaluate(PerformanceMetrics optimized, CoherenceMetrics coherence) {
        if (optimized == null) {
            throw new IllegalArgumentException("optimized metrics must not be null");
        }
        if (coherence == null) {
            throw new IllegalArgumentException("coherence metrics must not be null");
        }

        var latency = optimized.latencyMicroseconds();
        var coherenceScore = coherence.coherenceScore();

        log.debug("Evaluating Stream C activation: latency={}µs, coherence={:.3f}",
                  latency, coherenceScore);

        // Decision 1: Check if target already met
        if (latency <= latencyTarget) {
            lastRationale = String.format(
                "Target met: latency %.2fµs < %.2fµs target. " +
                "Streams A+B achieved performance goal, no additional optimization needed.",
                latency, latencyTarget
            );
            log.info("Decision: SKIP_BEAM - {}", lastRationale);
            return Decision.SKIP_BEAM;
        }

        // Decision 2: Check coherence for beam optimization viability
        if (coherenceScore >= coherenceThreshold) {
            lastRationale = String.format(
                "High coherence detected (score %.3f >= %.3f threshold). " +
                "Rays share %.1f%% of upper-level nodes. " +
                "Will enable batch kernel (expected 30%% node reduction).",
                coherenceScore, coherenceThreshold,
                coherence.upperLevelSharingPercent() * 100.0
            );
            log.info("Decision: ENABLE_BEAM - {}", lastRationale);
            return Decision.ENABLE_BEAM;
        }

        // Decision 3: Low coherence, beam won't help
        lastRationale = String.format(
            "Low coherence: score %.3f < %.3f threshold. " +
            "Beam optimization requires coherent rays for benefit. " +
            "Consider alternative optimizations: memory bandwidth tuning, " +
            "vendor-specific shader optimizations, or alternative ray batching strategies.",
            coherenceScore, coherenceThreshold
        );
        log.info("Decision: INVESTIGATE_ALTERNATIVES - {}", lastRationale);
        return Decision.INVESTIGATE_ALTERNATIVES;
    }

    /**
     * Get the rationale for the last decision.
     *
     * @return decision rationale string, or null if no decision made yet
     */
    public String getRationale() {
        return lastRationale;
    }

    /**
     * Get the latency target threshold.
     *
     * @return latency target in microseconds
     */
    public double getLatencyTarget() {
        return latencyTarget;
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
     * Stream C activation decision enum.
     */
    public enum Decision {
        /**
         * Skip beam optimization - target already met by Streams A+B.
         * No additional work needed, declare Phase 4 success.
         */
        SKIP_BEAM,

        /**
         * Enable beam optimization - high coherence detected.
         * Activate batch kernel for cooperative traversal.
         * Expected impact: 30% node reduction, +30% throughput.
         */
        ENABLE_BEAM,

        /**
         * Investigate alternative optimizations - low coherence.
         * Beam optimization won't help with current ray patterns.
         * Consider: memory bandwidth profiling, shader specialization,
         * alternative batching strategies.
         */
        INVESTIGATE_ALTERNATIVES
    }
}
