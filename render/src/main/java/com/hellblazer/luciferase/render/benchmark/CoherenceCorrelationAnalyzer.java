/*
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.render.benchmark;

import com.hellblazer.luciferase.render.benchmark.TileExecutionRecord.CoherenceBand;

import java.util.*;

/**
 * Statistical analyzer for coherence-speed correlation.
 *
 * Computes Pearson correlation coefficient between coherence scores and
 * execution times. Also provides Spearman rank correlation as a non-parametric
 * alternative if Pearson assumptions are violated.
 *
 * <p><b>Hypothesis</b>: High-coherence tiles execute faster than low-coherence
 * tiles because batch kernel amortizes memory access and leverages SIMD parallelism.
 *
 * <p><b>Success Criteria</b>:
 * <ul>
 *   <li>Pearson r >= 0.5 (moderate positive correlation)</li>
 *   <li>p-value < 0.05 (statistically significant)</li>
 *   <li>Clear trend: HIGH band faster than MEDIUM faster than LOW</li>
 * </ul>
 */
public class CoherenceCorrelationAnalyzer {

    /**
     * Analyze coherence-speed correlation from tile execution records.
     */
    public CorrelationResult analyze(List<TileExecutionRecord> records) {
        if (records.isEmpty()) {
            return CorrelationResult.empty();
        }

        // Extract arrays for correlation computation
        double[] coherenceScores = new double[records.size()];
        double[] executionTimes = new double[records.size()];

        for (int i = 0; i < records.size(); i++) {
            coherenceScores[i] = records.get(i).coherenceScore();
            executionTimes[i] = records.get(i).executionTimeMs();
        }

        // Compute Pearson correlation
        double pearsonR = computePearsonCorrelation(coherenceScores, executionTimes);
        double pValue = computePValue(records.size(), pearsonR);

        // Compute Spearman correlation as backup
        double spearmanRho = computeSpearmanCorrelation(coherenceScores, executionTimes);

        // Analyze by coherence band
        Map<CoherenceBand, List<Double>> timesByBand = groupByCoherenceBand(records);
        double avgTimeHigh = computeAverage(timesByBand.get(CoherenceBand.HIGH));
        double avgTimeMedium = computeAverage(timesByBand.get(CoherenceBand.MEDIUM));
        double avgTimeLow = computeAverage(timesByBand.get(CoherenceBand.LOW));

        return new CorrelationResult(
            pearsonR,
            pValue,
            spearmanRho,
            computePValue(records.size(), spearmanRho),
            avgTimeHigh,
            avgTimeMedium,
            avgTimeLow,
            records.size()
        );
    }

    /**
     * Compute Pearson correlation coefficient.
     *
     * @param x First variable (coherence scores)
     * @param y Second variable (execution times)
     * @return Pearson r in [-1, 1]
     */
    private double computePearsonCorrelation(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) {
            return Double.NaN;
        }

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        int n = x.length;

        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }

        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

        return denominator != 0 ? numerator / denominator : Double.NaN;
    }

    /**
     * Compute Spearman rank correlation (non-parametric alternative).
     */
    private double computeSpearmanCorrelation(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) {
            return Double.NaN;
        }

        // Convert to ranks
        double[] xRanks = toRanks(x);
        double[] yRanks = toRanks(y);

        // Compute Pearson correlation on ranks
        return computePearsonCorrelation(xRanks, yRanks);
    }

    /**
     * Convert values to ranks [1, n].
     */
    private double[] toRanks(double[] values) {
        Integer[] indices = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            indices[i] = i;
        }

        // Sort indices by values
        Arrays.sort(indices, Comparator.comparingDouble(i -> values[i]));

        double[] ranks = new double[values.length];
        for (int i = 0; i < indices.length; i++) {
            ranks[indices[i]] = i + 1.0;  // Ranks are 1-based
        }

        return ranks;
    }

    /**
     * Compute p-value for correlation significance (simplified t-test).
     */
    private double computePValue(int n, double r) {
        if (Double.isNaN(r) || n < 3) {
            return 1.0;
        }

        // t = r * sqrt(n - 2) / sqrt(1 - r^2)
        double rSquared = r * r;
        if (rSquared >= 1.0) {
            return r == 1.0 ? 0.0 : 1.0;
        }

        double t = r * Math.sqrt(n - 2) / Math.sqrt(1 - rSquared);
        double tAbs = Math.abs(t);

        // Simplified p-value: very rough approximation
        // For more accuracy, would need t-distribution CDF
        if (tAbs < 1.96) {
            return Math.max(0.05, 1.0 - (tAbs / 2.0));  // Rough approximation
        } else {
            return Math.min(0.001, 0.05 / tAbs);
        }
    }

    /**
     * Group execution times by coherence band.
     */
    private Map<CoherenceBand, List<Double>> groupByCoherenceBand(List<TileExecutionRecord> records) {
        Map<CoherenceBand, List<Double>> groups = new EnumMap<>(CoherenceBand.class);
        groups.put(CoherenceBand.LOW, new ArrayList<>());
        groups.put(CoherenceBand.MEDIUM, new ArrayList<>());
        groups.put(CoherenceBand.HIGH, new ArrayList<>());

        for (var record : records) {
            CoherenceBand band = record.getCoherenceBand();
            groups.get(band).add(record.executionTimeMs());
        }

        return groups;
    }

    /**
     * Compute average of non-empty list.
     */
    private double computeAverage(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
