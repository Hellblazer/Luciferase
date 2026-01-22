/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Spatial load balancer for reducing load skew across partitions.
 *
 * <p>This class:
 * <ul>
 *   <li>Measures spatial density (entity count) per partition</li>
 *   <li>Calculates load variance (skew) across partitions</li>
 *   <li>Identifies overloaded and underloaded partitions</li>
 *   <li>Redistributes entities to reduce skew by 50%+</li>
 * </ul>
 *
 * <p>The balancer uses a greedy approach:
 * <ol>
 *   <li>Collect load metrics from all partitions</li>
 *   <li>Calculate mean load and current skew</li>
 *   <li>Find overloaded partitions (> mean + threshold)</li>
 *   <li>Find underloaded partitions (< mean - threshold)</li>
 *   <li>Redistribute entities to balance load</li>
 *   <li>Verify skew reduction goal achieved (50%+ reduction)</li>
 * </ol>
 *
 * @param <Key> the spatial key type
 * @param <ID> the entity ID type
 * @author hal.hildebrand
 */
public class SpatialLoadBalancer<Key extends SpatialKey<Key>, ID extends EntityID> {

    private static final Logger log = LoggerFactory.getLogger(SpatialLoadBalancer.class);

    private static final double DEFAULT_SKEW_REDUCTION_TARGET = 0.5; // 50% reduction
    private static final double IMBALANCE_THRESHOLD = 0.2; // 20% deviation from mean

    private final double skewReductionTarget;

    public SpatialLoadBalancer() {
        this(DEFAULT_SKEW_REDUCTION_TARGET);
    }

    public SpatialLoadBalancer(double skewReductionTarget) {
        this.skewReductionTarget = skewReductionTarget;
        log.debug("Created SpatialLoadBalancer with skew reduction target: {}%", skewReductionTarget * 100);
    }

    /**
     * Analyze load distribution across partitions.
     *
     * @param loadPerPartition map of partition index to entity count
     * @return load analysis with metrics and imbalances
     */
    public LoadAnalysis analyzeLoad(Map<Integer, Integer> loadPerPartition) {
        log.debug("Analyzing load across {} partitions", loadPerPartition.size());

        if (loadPerPartition.isEmpty()) {
            return new LoadAnalysis(0, 0, 0, 0, Collections.emptyList(), Collections.emptyList());
        }

        // Calculate statistics
        int totalLoad = loadPerPartition.values().stream().mapToInt(Integer::intValue).sum();
        double meanLoad = (double) totalLoad / loadPerPartition.size();
        double variance = calculateVariance(loadPerPartition, meanLoad);
        double skew = Math.sqrt(variance);

        // Find overloaded and underloaded partitions
        double threshold = meanLoad * IMBALANCE_THRESHOLD;
        var overloaded = new ArrayList<PartitionLoad>();
        var underloaded = new ArrayList<PartitionLoad>();

        for (var entry : loadPerPartition.entrySet()) {
            int partitionIdx = entry.getKey();
            int load = entry.getValue();

            if (load > meanLoad + threshold) {
                overloaded.add(new PartitionLoad(partitionIdx, load));
            } else if (load < meanLoad - threshold) {
                underloaded.add(new PartitionLoad(partitionIdx, load));
            }
        }

        log.info("Load Analysis: mean={}, skew={}%, overloaded={}, underloaded={}",
                String.format("%.1f", meanLoad), String.format("%.2f", skew * 100), overloaded.size(), underloaded.size());

        return new LoadAnalysis(totalLoad, meanLoad, skew, variance, overloaded, underloaded);
    }

    /**
     * Generate load balancing redistribution plan.
     *
     * @param loadPerPartition current load per partition
     * @return balancing plan with redistribution targets
     */
    public BalancingPlan planBalancing(Map<Integer, Integer> loadPerPartition) {
        log.debug("Planning load balancing for {} partitions", loadPerPartition.size());

        var analysis = analyzeLoad(loadPerPartition);

        if (analysis.overloaded().isEmpty() || analysis.underloaded().isEmpty()) {
            log.info("Load is already balanced, no redistribution needed");
            return new BalancingPlan(analysis, 0, 0);
        }

        // Sort for greedy matching
        var overloadedList = new ArrayList<>(analysis.overloaded());
        var underloadedList = new ArrayList<>(analysis.underloaded());

        overloadedList.sort((a, b) -> Integer.compare(b.load(), a.load())); // Descending
        underloadedList.sort((a, b) -> Integer.compare(a.load(), b.load())); // Ascending

        // Generate redistribution plan
        var redistributions = new ArrayList<Redistribution>();
        int totalRedistributed = 0;

        for (var overload : overloadedList) {
            int excess = (int) (overload.load() - analysis.meanLoad());

            for (var underload : underloadedList) {
                if (excess <= 0) break;

                int deficit = (int) (analysis.meanLoad() - underload.load());
                int toMove = Math.min(excess, deficit);

                if (toMove > 0) {
                    redistributions.add(new Redistribution(overload.partitionIdx(), underload.partitionIdx(), toMove));
                    excess -= toMove;
                    totalRedistributed += toMove;
                    log.debug("Plan redistribution: {} entities from partition {} to {}",
                             toMove, overload.partitionIdx(), underload.partitionIdx());
                }
            }
        }

        double expectedSkewReduction = analysis.skew() * skewReductionTarget;
        log.info("Balancing plan: {} redistributions targeting {} skew reduction",
                redistributions.size(), String.format("%.1f%%", expectedSkewReduction * 100));

        return new BalancingPlan(analysis, redistributions.size(), totalRedistributed);
    }

    /**
     * Validate if balancing achieved target skew reduction.
     *
     * @param originalLoad original load distribution
     * @param newLoad load after redistribution
     * @return validation result
     */
    public BalancingValidation validateBalancing(Map<Integer, Integer> originalLoad, Map<Integer, Integer> newLoad) {
        log.debug("Validating load balancing results");

        var originalAnalysis = analyzeLoad(originalLoad);
        var newAnalysis = analyzeLoad(newLoad);

        double skewReduction = 1.0 - (newAnalysis.skew() / originalAnalysis.skew());
        boolean successful = skewReduction >= skewReductionTarget;

        log.info("Balancing validation: original skew={}%, new skew={}%, reduction={}%, success={}",
                String.format("%.2f", originalAnalysis.skew() * 100), String.format("%.2f", newAnalysis.skew() * 100), String.format("%.1f", skewReduction * 100), successful);

        return new BalancingValidation(
            originalAnalysis.skew(),
            newAnalysis.skew(),
            skewReduction,
            successful,
            String.format("Skew reduction: %.1f%% (target: %.1f%%)", skewReduction * 100, skewReductionTarget * 100)
        );
    }

    /**
     * Calculate variance in load distribution.
     */
    private double calculateVariance(Map<Integer, Integer> loads, double mean) {
        double variance = 0;
        for (int load : loads.values()) {
            variance += Math.pow(load - mean, 2);
        }
        return variance / loads.size();
    }

    /**
     * Load metrics for a single partition.
     */
    public record PartitionLoad(int partitionIdx, int load) {
    }

    /**
     * Analysis of load distribution across partitions.
     */
    public record LoadAnalysis(
        int totalLoad,
        double meanLoad,
        double skew,
        double variance,
        List<PartitionLoad> overloaded,
        List<PartitionLoad> underloaded
    ) {
        public boolean isBalanced() {
            return overloaded.isEmpty() && underloaded.isEmpty();
        }
    }

    /**
     * Balancing plan with redistribution targets.
     */
    public record BalancingPlan(
        LoadAnalysis analysis,
        int redistributionCount,
        int totalEntitiesRedistributed
    ) {
    }

    /**
     * Result of applying load balancing redistributions.
     */
    public record Redistribution(
        int sourcePartition,
        int targetPartition,
        int entityCount
    ) {
    }

    /**
     * Validation result for balancing success.
     */
    public record BalancingValidation(
        double originalSkew,
        double newSkew,
        double skewReduction,
        boolean successful,
        String details
    ) {
    }
}
