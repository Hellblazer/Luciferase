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

/**
 * Phase 4.1 P1: GPU Performance Validation Report Generator
 *
 * Generates human-readable markdown reports comparing baseline vs optimized GPU performance.
 * Includes analysis of Stream C activation decision based on latency and coherence metrics.
 *
 * @author hal.hildebrand
 */
public class PerformanceReport {
    private final PerformanceMetrics baseline;
    private final PerformanceMetrics optimized;
    private final CoherenceMetrics coherence;

    // Decision thresholds for Stream C activation
    private static final double LATENCY_TARGET_MICROS = 500.0;  // If < 500µs, target nearly met
    private static final double COHERENCE_THRESHOLD = 0.5;      // Minimum coherence for beam optimization

    /**
     * Create report without coherence metrics.
     *
     * @param baseline  baseline performance metrics
     * @param optimized optimized performance metrics (Streams A+B)
     */
    public PerformanceReport(PerformanceMetrics baseline, PerformanceMetrics optimized) {
        this(baseline, optimized, null);
    }

    /**
     * Create report with coherence metrics for Stream C decision.
     *
     * @param baseline  baseline performance metrics
     * @param optimized optimized performance metrics (Streams A+B)
     * @param coherence coherence metrics for beam optimization decision
     */
    public PerformanceReport(PerformanceMetrics baseline, PerformanceMetrics optimized, CoherenceMetrics coherence) {
        this.baseline = baseline;
        this.optimized = optimized;
        this.coherence = coherence;
    }

    /**
     * Generate human-readable markdown performance report.
     *
     * @return formatted markdown report
     */
    public String generateReport() {
        var sb = new StringBuilder();

        // Header
        sb.append("GPU Performance Validation Report\n");
        sb.append("════════════════════════════════\n\n");

        // Baseline section
        sb.append("Baseline (Phase 2 - DAG Kernel Only):\n");
        sb.append(formatMetrics(baseline));
        sb.append("\n");

        // Optimized section
        sb.append("Optimized (Streams A+B):\n");
        sb.append(formatMetrics(optimized));
        sb.append("\n");

        // Improvement section
        sb.append("Improvement:\n");
        sb.append(formatImprovement());
        sb.append("\n");

        // Target status section
        sb.append("Target Status:\n");
        sb.append(formatTargetStatus());
        sb.append("\n");

        // Stream C decision (if coherence metrics available)
        if (coherence != null) {
            sb.append("Stream C Activation Decision:\n");
            sb.append(formatStreamCDecision());
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Format performance metrics as human-readable text.
     */
    private String formatMetrics(PerformanceMetrics metrics) {
        var rayCountFormatted = formatRayCount(metrics.rayCount());
        return String.format("  %s: %.0fµs (%.1f Kray/ms, %.1f%% occupancy, depth=%d, cache=%.1f%%)",
                             rayCountFormatted,
                             metrics.latencyMicroseconds(),
                             metrics.throughputRaysPerMicrosecond(),
                             metrics.gpuOccupancyPercent(),
                             metrics.averageTraversalDepth(),
                             metrics.cacheHitRate() * 100.0f);
    }

    /**
     * Format ray count with appropriate units.
     */
    private String formatRayCount(int rayCount) {
        if (rayCount >= 1_000_000) {
            return String.format("%dM rays", rayCount / 1_000_000);
        } else if (rayCount >= 1_000) {
            return String.format("%dK rays", rayCount / 1_000);
        } else {
            return String.format("%d rays", rayCount);
        }
    }

    /**
     * Format improvement section showing speedup.
     */
    private String formatImprovement() {
        var improvement = optimized.compareToBaseline(baseline);
        var speedup = optimized.speedupFactor(baseline);
        var rayCountFormatted = formatRayCount(optimized.rayCount());

        return String.format("  %s: %.0f%% faster (%.2fx speedup)",
                             rayCountFormatted,
                             improvement,
                             speedup);
    }

    /**
     * Format target status section.
     */
    private String formatTargetStatus() {
        var sb = new StringBuilder();

        // 10x target for 100K rays: <5ms = 5000µs
        var targetLatency = 5000.0 * (optimized.rayCount() / 100_000.0);
        var currentLatency = optimized.latencyMicroseconds();

        sb.append(String.format("  10x target: %s <5ms = %.0fµs\n",
                                formatRayCount(optimized.rayCount()),
                                targetLatency));
        sb.append(String.format("  Current: %.0fµs ", currentLatency));

        if (currentLatency < targetLatency) {
            var factor = targetLatency / currentLatency;
            sb.append(String.format("(%.1fx better than target!)\n", factor));
        } else {
            var gap = currentLatency / targetLatency;
            sb.append(String.format("(%.1fx of target, need %.1fx more improvement)\n", gap, gap));
        }

        return sb.toString();
    }

    /**
     * Format Stream C activation decision section.
     */
    private String formatStreamCDecision() {
        var sb = new StringBuilder();

        var latency = optimized.latencyMicroseconds();
        var coherenceScore = coherence.coherenceScore();

        sb.append(String.format("  Coherence: %.2f (%s %.1f threshold)\n",
                                coherenceScore,
                                coherenceScore >= COHERENCE_THRESHOLD ? ">" : "<",
                                COHERENCE_THRESHOLD));

        sb.append(String.format("  Latency: %.0fµs (%s %.0fµs threshold)\n",
                                latency,
                                latency >= LATENCY_TARGET_MICROS ? ">" : "<",
                                LATENCY_TARGET_MICROS));

        sb.append(String.format("  Recommendation: %s\n", getRecommendation()));

        return sb.toString();
    }

    /**
     * Get Stream C activation recommendation.
     *
     * @return recommendation string
     */
    public String getRecommendation() {
        var latency = optimized.latencyMicroseconds();

        // If coherence metrics not available, can't make beam decision based on coherence
        if (coherence == null) {
            // Just check latency target
            if (latency < LATENCY_TARGET_MICROS) {
                return "SKIP - target nearly met, beam optimization not needed";
            }
            return "UNKNOWN - coherence metrics not available";
        }

        var coherenceScore = coherence.coherenceScore();

        // If already fast enough, skip beam optimization
        if (latency < LATENCY_TARGET_MICROS) {
            return "SKIP - target nearly met, beam optimization not needed";
        }

        // Check coherence threshold
        if (coherenceScore < COHERENCE_THRESHOLD) {
            return String.format("SKIP - insufficient coherence (%.2f < %.1f threshold)",
                                 coherenceScore, COHERENCE_THRESHOLD);
        }

        // Both conditions met: proceed with beam optimization
        return String.format("ENABLE - proceed with beam optimization (coherence=%.2f, latency=%.0fµs)",
                             coherenceScore, latency);
    }

    /**
     * Check if optimized performance meets target threshold.
     *
     * @param targetLatencyMicros target latency threshold
     * @return true if optimized latency is below target
     */
    public boolean meetsTarget(double targetLatencyMicros) {
        return optimized.latencyMicroseconds() < targetLatencyMicros;
    }

    /**
     * Get speedup factor (baseline / optimized).
     *
     * @return speedup factor
     */
    public double getSpeedupFactor() {
        return optimized.speedupFactor(baseline);
    }

    /**
     * Get baseline metrics.
     *
     * @return baseline metrics
     */
    public PerformanceMetrics baseline() {
        return baseline;
    }

    /**
     * Get optimized metrics.
     *
     * @return optimized metrics
     */
    public PerformanceMetrics optimized() {
        return optimized;
    }

    /**
     * Get coherence metrics.
     *
     * @return coherence metrics (may be null)
     */
    public CoherenceMetrics coherence() {
        return coherence;
    }
}
