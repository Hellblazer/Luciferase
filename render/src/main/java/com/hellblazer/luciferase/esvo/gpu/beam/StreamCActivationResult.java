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

import java.util.Objects;

/**
 * Phase 4.2 P2: Stream C Activation Result Report
 *
 * Formats the Stream C activation decision with complete context:
 * - Performance metrics (Streams A+B)
 * - Ray coherence analysis
 * - Decision and rationale
 * - Activation instructions
 * - Performance target comparison
 *
 * @author hal.hildebrand
 */
public class StreamCActivationResult {

    private final StreamCActivationDecision.Decision decision;
    private final PerformanceMetrics optimized;
    private final CoherenceMetrics coherence;
    private final String rationale;

    /**
     * Create activation result.
     *
     * @param decision  activation decision
     * @param optimized optimized performance metrics (Streams A+B)
     * @param coherence ray coherence metrics
     * @param rationale decision rationale
     */
    public StreamCActivationResult(StreamCActivationDecision.Decision decision,
                                   PerformanceMetrics optimized,
                                   CoherenceMetrics coherence,
                                   String rationale) {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (optimized == null) {
            throw new IllegalArgumentException("optimized metrics must not be null");
        }
        if (coherence == null) {
            throw new IllegalArgumentException("coherence metrics must not be null");
        }
        if (rationale == null) {
            throw new IllegalArgumentException("rationale must not be null");
        }
        this.decision = decision;
        this.optimized = optimized;
        this.coherence = coherence;
        this.rationale = rationale;
    }

    /**
     * Generate comprehensive decision report.
     *
     * Report includes:
     * - Performance metrics section
     * - Ray coherence analysis
     * - Decision with rationale
     * - Activation instructions
     * - Performance target comparison
     *
     * @return formatted decision report
     */
    public String generateDecisionReport() {
        var report = new StringBuilder();

        // Header
        report.append("═══════════════════════════════════════════════════════════════\n");
        report.append("        Stream C Activation Decision Report\n");
        report.append("═══════════════════════════════════════════════════════════════\n");
        report.append("\n");

        // Performance Metrics
        report.append("Performance Metrics (Streams A+B):\n");
        report.append("  Latency: ").append(String.format("%.0f", optimized.latencyMicroseconds()))
              .append(" µs for ").append(String.format("%,d", optimized.rayCount())).append(" rays\n");
        report.append("  throughput: ").append(String.format("%.2f", optimized.throughputRaysPerMicrosecond() * 1000))
              .append(" Kray/ms\n");
        report.append("  GPU occupancy: ").append(String.format("%.1f", optimized.gpuOccupancyPercent()))
              .append("%\n");
        report.append("  Cache Hit Rate: ").append(String.format("%.1f", optimized.cacheHitRate() * 100))
              .append("%\n");
        report.append("\n");

        // Ray Coherence Analysis
        report.append("Ray Coherence Analysis:\n");
        report.append("  Coherence Score: ").append(String.format("%.2f", coherence.coherenceScore()))
              .append(" (0.0-1.0 scale, >0.5 beneficial for beam)\n");
        report.append("  Upper-Level Sharing: ").append(String.format("%.1f", coherence.upperLevelSharingPercent() * 100))
              .append("% (nodes shared at depth < 4)\n");
        report.append("  Unique Nodes Visited: ").append(String.format("%,d", coherence.uniqueNodesVisited())).append("\n");
        report.append("  Cache Reuse Factor: ").append(String.format("%.2f", coherence.cacheReuseFactor()))
              .append("x\n");
        report.append("\n");

        // Decision
        report.append("Decision: ").append(decision.name()).append("\n");
        report.append("─────────────────────────────────────────────────────────────\n");
        report.append(rationale).append("\n");
        report.append("\n");

        // Recommendation (activation instructions)
        report.append("Recommendation:\n");
        report.append(getActivationInstructions());
        report.append("\n");

        // Impact (if enabled)
        if (decision == StreamCActivationDecision.Decision.ENABLE_BEAM) {
            report.append("Expected Impact if Enabled:\n");
            report.append("  - Node Reduction: 30% fewer nodes traversed\n");
            report.append("  - Throughput Gain: +30% rays/second (estimated)\n");
            report.append("  - Activation: Batch kernel (rayTraverseDAGBatch) enabled\n");
            report.append("  - Next Step: Phase 4.2.2 batch kernel enhancement\n");
            report.append("\n");
        }

        // Performance Target Status
        report.append("Performance Target Status:\n");
        var target10x = 5000.0; // 100K rays in <5ms = 5000µs
        var targetFactor = target10x / optimized.latencyMicroseconds();
        report.append("  10x GPU Speedup Goal: 100K rays <5ms (5000µs)\n");
        report.append("  Current Performance: ").append(String.format("%.0f", optimized.latencyMicroseconds()))
              .append("µs (").append(String.format("%.1f", targetFactor))
              .append("x better than target)\n");

        if (targetFactor >= 1.0) {
            report.append("  Status: ✅ TARGET MET\n");
        } else {
            report.append("  Status: ⚠ NEEDS IMPROVEMENT (")
                  .append(String.format("%.0f", optimized.latencyMicroseconds() - target10x))
                  .append("µs over target)\n");
        }

        report.append("\n");
        report.append("═══════════════════════════════════════════════════════════════\n");

        return report.toString();
    }

    /**
     * Get activation instructions based on decision.
     */
    private String getActivationInstructions() {
        return switch (decision) {
            case SKIP_BEAM -> """
                ✅ SUCCESS: Streams A+B achieved target latency.
                   No additional optimization needed.

                   Next Steps:
                   - Document Phase 3 GPU acceleration success
                   - Declare 10x speedup target status
                   - Prepare for Phase 5 (production deployment)
                   - Update performance documentation with final metrics
                """;

            case ENABLE_BEAM -> """
                ⚡ ENABLING: Ray coherence >0.5 detected.
                   Activating batch kernel (rayTraverseDAGBatch).

                   Next Steps:
                   - Proceed to Phase 4.2.2: Batch kernel optimization
                   - Implement cooperative traversal in batch kernel
                   - Validate identical results vs single-ray kernel
                   - Measure 30% node reduction target
                   - Add comprehensive batch kernel tests
                """;

            case INVESTIGATE_ALTERNATIVES -> """
                🔍 INVESTIGATE: Low coherence (<0.5), beam won't help.
                   Consider alternative optimizations:

                   Options:
                   1. Memory bandwidth profiling (measure actual GB/s utilization)
                   2. Vendor-specific shader optimizations (per-GPU tuning)
                   3. Alternative ray batching strategies (temporal coherence)
                   4. Workgroup size fine-tuning (beyond GPUAutoTuner defaults)
                   5. Traversal algorithm alternatives (packet traversal)

                   Next Steps:
                   - Profile memory bandwidth utilization
                   - Analyze GPU vendor-specific bottlenecks
                   - Consider Phase 5 future optimization work item
                """;
        };
    }

    /**
     * Get the decision.
     */
    public StreamCActivationDecision.Decision getDecision() {
        return decision;
    }

    /**
     * Get optimized metrics.
     */
    public PerformanceMetrics getOptimized() {
        return optimized;
    }

    /**
     * Get coherence metrics.
     */
    public CoherenceMetrics getCoherence() {
        return coherence;
    }

    /**
     * Get decision rationale.
     */
    public String getRationale() {
        return rationale;
    }
}
