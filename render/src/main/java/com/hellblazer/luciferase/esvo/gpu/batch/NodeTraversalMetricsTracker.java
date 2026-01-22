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
package com.hellblazer.luciferase.esvo.gpu.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 4.2.2d: Node Traversal Metrics Tracker
 *
 * Tracks node access patterns to measure batch kernel efficiency.
 * Compares single-ray vs batch mode to quantify node reduction through cache sharing.
 *
 * Metrics tracked:
 * - Total node accesses per mode
 * - Unique nodes visited per mode
 * - Cache efficiency (total accesses / unique nodes)
 * - Node reduction percentage (batch vs single-ray)
 *
 * @author hal.hildebrand
 */
public class NodeTraversalMetricsTracker {
    private static final Logger log = LoggerFactory.getLogger(NodeTraversalMetricsTracker.class);

    // Single-ray baseline metrics
    private int singleRayTotalAccesses = 0;
    private int singleRayUniqueNodes = 0;

    // Batch mode metrics
    private int batchTotalAccesses = 0;
    private int batchUniqueNodes = 0;

    // Aggregation
    private int frameCount = 0;

    /**
     * Record single-ray kernel metrics.
     *
     * @param totalAccesses  Total node accesses during single-ray traversal
     * @param uniqueNodes    Unique nodes visited
     */
    public void recordSingleRayMetrics(int totalAccesses, int uniqueNodes) {
        this.singleRayTotalAccesses = totalAccesses;
        this.singleRayUniqueNodes = uniqueNodes;
    }

    /**
     * Record batch kernel metrics.
     *
     * @param totalAccesses  Total node accesses during batch traversal
     * @param uniqueNodes    Unique nodes visited
     */
    public void recordBatchMetrics(int totalAccesses, int uniqueNodes) {
        this.batchTotalAccesses = totalAccesses;
        this.batchUniqueNodes = uniqueNodes;
        frameCount++;
    }

    /**
     * Calculate node reduction percentage from single-ray to batch mode.
     *
     * Positive value = improvement (fewer accesses in batch mode)
     *
     * Formula: (singleRay - batch) / singleRay * 100
     *
     * @return node reduction percentage, or 0 if baseline unavailable
     */
    public double calculateNodeReductionPercent() {
        if (singleRayTotalAccesses == 0) {
            return 0.0;
        }

        return (singleRayTotalAccesses - batchTotalAccesses) * 100.0 / singleRayTotalAccesses;
    }

    /**
     * Calculate cache efficiency improvement.
     *
     * Efficiency = unique nodes / total accesses (lower = better, closer to 1.0 = fewer cache misses)
     * Improvement = batch efficiency / single-ray efficiency
     *
     * Returns improvement factor (>1.0 = batch has fewer cache misses, more efficient)
     *
     * @return efficiency improvement factor (>1.0 = batch is more efficient)
     */
    public double calculateEfficiencyImprovement() {
        if (singleRayTotalAccesses == 0 || batchTotalAccesses == 0) {
            return 1.0;
        }

        // Efficiency = unique nodes / total accesses (lower ratio = fewer cache misses)
        double singleRayEfficiency = (double) singleRayUniqueNodes / singleRayTotalAccesses;
        double batchEfficiency = (double) batchUniqueNodes / batchTotalAccesses;

        if (singleRayEfficiency == 0) {
            return 1.0;
        }

        // Improvement: batch efficiency / single-ray efficiency
        // > 1.0 means batch has better cache efficiency
        return batchEfficiency / singleRayEfficiency;
    }

    /**
     * Get comprehensive metrics report.
     *
     * @return formatted metrics string with all measurements
     */
    public String generateMetricsReport() {
        double nodeReduction = calculateNodeReductionPercent();
        double efficiencyImprovement = calculateEfficiencyImprovement();
        double singleRayEfficiency = singleRayTotalAccesses > 0 ?
            (double) singleRayTotalAccesses / Math.max(1, singleRayUniqueNodes) : 0;
        double batchEfficiency = batchTotalAccesses > 0 ?
            (double) batchTotalAccesses / Math.max(1, batchUniqueNodes) : 0;

        return String.format(
            "Node Traversal Metrics (frames: %d):\n" +
            "  Single-ray:  %,d accesses, %,d unique nodes (%.2fx cache efficiency)\n" +
            "  Batch mode:  %,d accesses, %,d unique nodes (%.2fx cache efficiency)\n" +
            "  Node reduction: %.1f%% (target: >=30%%)\n" +
            "  Efficiency improvement: %.2fx\n" +
            "  Status: %s",
            frameCount,
            singleRayTotalAccesses, singleRayUniqueNodes, singleRayEfficiency,
            batchTotalAccesses, batchUniqueNodes, batchEfficiency,
            nodeReduction,
            efficiencyImprovement,
            nodeReduction >= 30.0 ? "✓ PASS" : "✗ FAIL"
        );
    }

    /**
     * Check if batch mode meets performance target.
     *
     * Target: >= 30% node reduction
     *
     * @return true if target met
     */
    public boolean meetsNodeReductionTarget() {
        return calculateNodeReductionPercent() >= 30.0;
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        singleRayTotalAccesses = 0;
        singleRayUniqueNodes = 0;
        batchTotalAccesses = 0;
        batchUniqueNodes = 0;
        frameCount = 0;
    }

    /**
     * Log metrics summary to debug log.
     */
    public void logMetrics() {
        log.debug(generateMetricsReport());
    }

    /**
     * Get single-ray total accesses (for testing).
     */
    int getSingleRayTotalAccesses() {
        return singleRayTotalAccesses;
    }

    /**
     * Get single-ray unique nodes (for testing).
     */
    int getSingleRayUniqueNodes() {
        return singleRayUniqueNodes;
    }

    /**
     * Get batch total accesses (for testing).
     */
    int getBatchTotalAccesses() {
        return batchTotalAccesses;
    }

    /**
     * Get batch unique nodes (for testing).
     */
    int getBatchUniqueNodes() {
        return batchUniqueNodes;
    }
}
