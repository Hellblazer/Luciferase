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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for coherence-speed correlation analysis.
 *
 * Validates that the statistical analyzer correctly computes correlation
 * and identifies trends in the data.
 */
class CoherenceCorrelationTest {

    private CoherenceCorrelationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new CoherenceCorrelationAnalyzer();
    }

    @Test
    void testPearsonCorrelationComputation() {
        var records = createPerfectPositiveCorrelation();

        var result = analyzer.analyze(records);

        assertNotNull(result);
        assertTrue(result.pearsonR() > 0.8, "Perfect positive correlation should be > 0.8");
        assertTrue(result.sampleCount() > 0, "Should have samples");
    }

    @Test
    void testHighCoherenceFaster() {
        var records = new ArrayList<TileExecutionRecord>();

        // High-coherence tiles: faster execution
        for (int i = 0; i < 10; i++) {
            records.add(new TileExecutionRecord(0, i, 0, 0.85, 10.0 + i, 256, true));
        }

        // Low-coherence tiles: slower execution
        for (int i = 0; i < 10; i++) {
            records.add(new TileExecutionRecord(0, i, 1, 0.25, 25.0 + i, 256, false));
        }

        var result = analyzer.analyze(records);

        assertTrue(result.avgTimeHigh() < result.avgTimeLow(),
                   "High-coherence tiles should be faster than low-coherence");
    }

    @Test
    void testLowCoherenceSlower() {
        var records = new ArrayList<TileExecutionRecord>();

        // Medium-coherence: intermediate speed
        for (int i = 0; i < 10; i++) {
            records.add(new TileExecutionRecord(0, i, 0, 0.50, 17.5, 256, false));
        }

        // Low-coherence: slowest
        for (int i = 0; i < 10; i++) {
            records.add(new TileExecutionRecord(0, i, 1, 0.20, 30.0, 256, false));
        }

        var result = analyzer.analyze(records);

        assertTrue(result.avgTimeMedium() < result.avgTimeLow(),
                   "Medium-coherence should be faster than low-coherence");
    }

    @Test
    void testSpearmanFallback() {
        // Create non-normal distribution (with outliers)
        var records = new ArrayList<TileExecutionRecord>();

        // Normal data
        for (int i = 0; i < 8; i++) {
            records.add(new TileExecutionRecord(0, i, 0, 0.7 + i * 0.02, 10.0 + i, 256, true));
        }

        // Outliers
        records.add(new TileExecutionRecord(0, 8, 0, 0.95, 1000.0, 256, true));
        records.add(new TileExecutionRecord(0, 9, 0, 0.05, 5.0, 256, false));

        var result = analyzer.analyze(records);

        assertNotNull(result.spearmanRho());
        // Spearman should be more robust to outliers
        assertTrue(!Double.isNaN(result.spearmanRho()), "Spearman should be computable");
    }

    @Test
    void testStatisticalSignificance() {
        var records = createPerfectPositiveCorrelation();

        var result = analyzer.analyze(records);

        // With 100 perfect points, correlation should be highly significant
        assertTrue(result.pearsonPValue() < 0.05,
                   "Perfect correlation should be statistically significant");
        assertTrue(result.isSignificant(),
                   "Result should be marked as significant");
    }

    @Test
    void testTrendValidation() {
        var records = new ArrayList<TileExecutionRecord>();

        // Create clear trend: HIGH < MEDIUM < LOW
        for (int i = 0; i < 10; i++) {
            records.add(new TileExecutionRecord(0, i, 0, 0.80, 10.0, 256, true));      // HIGH: 10ms
            records.add(new TileExecutionRecord(0, i, 0, 0.50, 20.0, 256, false));     // MEDIUM: 20ms
            records.add(new TileExecutionRecord(0, i, 0, 0.20, 30.0, 256, false));     // LOW: 30ms
        }

        var result = analyzer.analyze(records);

        assertTrue(result.validateTrend(), "Should validate trend");
        assertTrue(result.getTrendStrength() > 0, "Trend strength should be positive");
    }

    @Test
    void testEmptyInput() {
        var result = analyzer.analyze(new ArrayList<>());

        assertNotNull(result);
        assertEquals(0, result.sampleCount());
    }

    // Helper: Create perfect positive correlation dataset

    private List<TileExecutionRecord> createPerfectPositiveCorrelation() {
        var records = new ArrayList<TileExecutionRecord>();

        // Create 100 points with perfect positive correlation
        // Higher coherence -> lower execution time
        for (int i = 0; i < 100; i++) {
            double coherence = i / 100.0;  // 0.00 to 0.99
            double execTime = 50.0 - (coherence * 40.0);  // 50ms to 10ms
            records.add(new TileExecutionRecord(0, i % 16, i / 16, coherence, execTime, 256, coherence >= 0.7));
        }

        return records;
    }
}
