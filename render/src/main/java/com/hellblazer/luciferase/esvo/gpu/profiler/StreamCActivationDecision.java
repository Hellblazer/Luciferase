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
package com.hellblazer.luciferase.esvo.gpu.profiler;

import com.hellblazer.luciferase.esvo.gpu.report.MultiVendorConsistencyReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 P2: Stream C Beam Optimization Activation Decision Engine
 *
 * Generates activation recommendations for Stream C (beam optimization) based on:
 * - Coherence threshold: ≥0.5 required, 0.5-0.7 marginal, ≥0.7 good
 * - Latency target: <500µs may skip optimization
 * - GPU occupancy: ≥60% required for effective beam optimization
 * - Vendor consistency: <20% variance preferred
 *
 * Decision States:
 * - ENABLE: Full activation of beam optimization
 * - ENABLE_CONDITIONAL: Enable only on high-coherence scenes (>0.7)
 * - SKIP: Disable beam optimization (not beneficial)
 * - MONITOR: Enable with performance monitoring
 *
 * @author hal.hildebrand
 */
public class StreamCActivationDecision {

    // Decision thresholds
    private static final double COHERENCE_MINIMUM = 0.5;      // Below this: SKIP
    private static final double COHERENCE_MARGINAL = 0.7;     // Below this: CONDITIONAL
    private static final double LATENCY_TARGET_MICROS = 500.0; // Below this: may skip
    private static final double OCCUPANCY_MINIMUM = 0.60;     // Below this: SKIP (as percentage 0.0-1.0)
    private static final double VENDOR_CONSISTENCY_TARGET = 90.0; // Target consistency percentage

    /**
     * Activation state enumeration
     */
    public enum ActivationState {
        /** Full activation - all criteria met */
        ENABLE,

        /** Conditional activation - enable only on high-coherence scenes */
        ENABLE_CONDITIONAL,

        /** Skip optimization - not beneficial for this hardware/scene */
        SKIP,

        /** Enable with monitoring - watch for performance regressions */
        MONITOR
    }

    /**
     * Generate Stream C activation recommendation.
     *
     * @param baselineReport   P1 baseline performance metrics
     * @param optimizedReport  P4 optimized performance metrics (Streams A+B)
     * @param consistencyReport P3 multi-vendor consistency report
     * @return activation recommendation with rationale
     */
    public ActivationRecommendation decide(
        PerformanceReport baselineReport,
        PerformanceReport optimizedReport,
        MultiVendorConsistencyReport consistencyReport
    ) {
        var metrics = new HashMap<String, Float>();
        var warnings = new ArrayList<String>();
        var recommendations = new ArrayList<String>();

        // Extract baseline metrics
        var coherence = baselineReport.coherence();
        var baselineMetrics = baselineReport.baseline();
        var optimizedMetrics = baselineReport.optimized();

        // Validate input data
        if (coherence == null) {
            return createSkipDecision(
                "coherence metrics unavailable - cannot evaluate beam optimization viability",
                metrics, warnings, recommendations
            );
        }

        var coherenceScore = coherence.coherenceScore();
        var latency = baselineMetrics.latencyMicroseconds();
        var occupancy = baselineMetrics.gpuOccupancyPercent() / 100.0; // Convert to 0.0-1.0

        // Store key metrics
        metrics.put("coherence", (float) coherenceScore);
        metrics.put("latency", (float) latency);
        metrics.put("occupancy", (float) (occupancy * 100.0));
        metrics.put("consistency", (float) consistencyReport.getOverallConsistency());

        // Validate metric sanity
        if (latency <= 0.0 || coherenceScore < 0.0 || coherenceScore > 1.0) {
            return createSkipDecision(
                "invalid metrics detected - cannot make reliable decision",
                metrics, warnings, recommendations
            );
        }

        // Check vendor consistency
        var vendorConsistent = consistencyReport.meetsTarget(VENDOR_CONSISTENCY_TARGET);
        if (!vendorConsistent || consistencyReport.hasFailures()) {
            warnings.add("Vendor consistency below target or has failures - monitor cross-vendor performance");
        }

        // BLOCKER 1: Check GPU occupancy (must meet minimum)
        if (occupancy < OCCUPANCY_MINIMUM) {
            return createSkipDecision(
                String.format("GPU occupancy too low (%.0f%% < %.0f%% threshold) - insufficient GPU utilization for beam optimization",
                    occupancy * 100.0, OCCUPANCY_MINIMUM * 100.0),
                metrics, warnings, recommendations
            );
        }

        // BLOCKER 2: Check coherence threshold
        if (coherenceScore < COHERENCE_MINIMUM) {
            return createSkipDecision(
                String.format("low coherence (%.2f < %.1f threshold) - insufficient spatial locality for beam optimization",
                    coherenceScore, COHERENCE_MINIMUM),
                metrics, warnings, recommendations
            );
        }

        // EARLY EXIT: Check if latency target already met
        if (latency < LATENCY_TARGET_MICROS) {
            return createSkipDecision(
                String.format("latency target already met (%.0fµs < %.0fµs threshold) - beam optimization not needed",
                    latency, LATENCY_TARGET_MICROS),
                metrics, warnings, recommendations
            );
        }

        // At this point: occupancy OK, coherence OK, latency needs improvement
        // Decide based on coherence strength and vendor consistency

        // Check if optimized report shows dramatic improvement
        var hasImprovement = optimizedReport != null &&
                           optimizedReport.optimized() != null &&
                           optimizedReport.optimized().latencyMicroseconds() < LATENCY_TARGET_MICROS;

        if (hasImprovement) {
            // Dramatic improvement already achieved - monitor further optimization
            recommendations.add("Streams A+B already achieved dramatic improvement");
            recommendations.add("Enable Stream C with performance monitoring");
            recommendations.add("Watch for diminishing returns on further optimization");

            return new ActivationRecommendation(
                ActivationState.MONITOR,
                String.format("Enable Stream C with monitoring - latency improved to %.0fµs, coherence=%.2f supports beam optimization",
                    optimizedReport.optimized().latencyMicroseconds(), coherenceScore),
                metrics, warnings, recommendations
            );
        }

        // Marginal coherence: conditional activation
        if (coherenceScore < COHERENCE_MARGINAL) {
            recommendations.add("Enable beam optimization only on high-coherence scenes (>0.7)");
            recommendations.add("Use dynamic coherence detection to activate conditionally");
            recommendations.add("Profile per-scene coherence in production workloads");

            return new ActivationRecommendation(
                ActivationState.ENABLE_CONDITIONAL,
                String.format("marginal coherence (%.2f) - enable conditionally on high-coherence scenes only",
                    coherenceScore),
                metrics, warnings, recommendations
            );
        }

        // Good coherence: full activation
        recommendations.add("Enable all Stream C beam optimizations (ray coherence, beam trees)");
        recommendations.add("Monitor performance across different scene types");

        if (!vendorConsistent) {
            recommendations.add("Pay special attention to vendor-specific performance");
        }

        var rationale = String.format(
            "high coherence (%.2f ≥ %.1f), latency needs improvement (%.0fµs), good occupancy (%.0f%%) - proceed with beam optimization",
            coherenceScore, COHERENCE_MARGINAL, latency, occupancy * 100.0
        );

        return new ActivationRecommendation(
            ActivationState.ENABLE,
            rationale,
            metrics, warnings, recommendations
        );
    }

    /**
     * Create a SKIP decision with given rationale.
     */
    private ActivationRecommendation createSkipDecision(
        String rationale,
        Map<String, Float> metrics,
        List<String> warnings,
        List<String> recommendations
    ) {
        recommendations.add("Skip Stream C beam optimization for this configuration");
        recommendations.add("Focus on Streams A+B optimizations instead");

        return new ActivationRecommendation(
            ActivationState.SKIP,
            rationale,
            metrics, warnings, recommendations
        );
    }
}
