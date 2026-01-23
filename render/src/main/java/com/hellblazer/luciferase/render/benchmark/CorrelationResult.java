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

/**
 * Results of coherence-speed correlation analysis.
 *
 * @param pearsonR Pearson correlation coefficient [-1, 1]
 * @param pearsonPValue P-value for Pearson correlation (significance)
 * @param spearmanRho Spearman rank correlation [-1, 1] (non-parametric)
 * @param spearmanPValue P-value for Spearman correlation
 * @param avgTimeHigh Average execution time for high-coherence tiles (>= 0.7)
 * @param avgTimeMedium Average execution time for medium-coherence tiles [0.3, 0.7)
 * @param avgTimeLow Average execution time for low-coherence tiles (< 0.3)
 * @param sampleCount Total number of data points analyzed
 */
public record CorrelationResult(
    double pearsonR,
    double pearsonPValue,
    double spearmanRho,
    double spearmanPValue,
    double avgTimeHigh,
    double avgTimeMedium,
    double avgTimeLow,
    int sampleCount
) {
    /**
     * Create empty result (for error cases).
     */
    public static CorrelationResult empty() {
        return new CorrelationResult(Double.NaN, 1.0, Double.NaN, 1.0, 0.0, 0.0, 0.0, 0);
    }

    /**
     * Get recommended correlation metric.
     *
     * Returns Pearson if p < 0.05, otherwise Spearman.
     */
    public double getRecommendedCorrelation() {
        if (pearsonPValue < 0.05) {
            return pearsonR;
        } else {
            return spearmanRho;
        }
    }

    /**
     * Get recommended p-value.
     */
    public double getRecommendedPValue() {
        if (pearsonPValue < 0.05) {
            return pearsonPValue;
        } else {
            return spearmanPValue;
        }
    }

    /**
     * Check if correlation is statistically significant.
     */
    public boolean isSignificant() {
        double r = getRecommendedCorrelation();
        double p = getRecommendedPValue();
        return !Double.isNaN(r) && p < 0.05 && Math.abs(r) >= 0.5;
    }

    /**
     * Get correlation interpretation.
     */
    public String getInterpretation() {
        double r = getRecommendedCorrelation();

        if (Double.isNaN(r)) {
            return "INVALID: No valid correlation computed";
        }

        if (Math.abs(r) < 0.3) {
            return "WEAK: Coherence has weak correlation with execution time";
        } else if (Math.abs(r) < 0.5) {
            return "MODERATE: Coherence has moderate correlation with execution time";
        } else if (Math.abs(r) < 0.7) {
            return "STRONG: Coherence has strong correlation with execution time";
        } else {
            return "VERY_STRONG: Coherence has very strong correlation with execution time";
        }
    }

    /**
     * Check trend: HIGH band faster than MEDIUM faster than LOW.
     */
    public boolean validateTrend() {
        // Faster = lower time, so HIGH < MEDIUM < LOW
        return avgTimeHigh <= avgTimeMedium && avgTimeMedium <= avgTimeLow;
    }

    /**
     * Get trend strength (percentage improvement from LOW to HIGH).
     */
    public double getTrendStrength() {
        if (avgTimeLow == 0) {
            return 0.0;
        }
        return (avgTimeLow - avgTimeHigh) / avgTimeLow * 100.0;
    }
}
