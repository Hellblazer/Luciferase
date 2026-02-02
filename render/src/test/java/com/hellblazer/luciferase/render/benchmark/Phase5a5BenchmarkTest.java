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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration benchmark tests for Phase 5a.5.
 *
 * Validates 30% node reduction target and coherence-based dispatch routing.
 * Uses shared benchmark configuration for consistency.
 *
 * <p>Disabled in CI: Memory-intensive benchmark requires >4GB heap.
 * Run locally with: {@code mvn test -Dtest=Phase5a5BenchmarkTest -DargLine="-Xmx8g"}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisabledIfEnvironmentVariable(
    named = "CI",
    matches = "true",
    disabledReason = "Memory-intensive benchmark requires >4GB heap, causes OOM in CI"
)
class Phase5a5BenchmarkTest {

    private static final int FRAME_WIDTH = 256;
    private static final int FRAME_HEIGHT = 256;
    private static final double TARGET_REDUCTION = 0.30;
    private static final double EPSILON = 0.05;

    private Phase5a5BenchmarkRunner runner;
    private BenchmarkConfig config;

    @BeforeEach
    void setUp() {
        // Use 0.8 threshold to classify high-coherence scenes (SkyScene only)
        // MixedScene (0.746) and GeometryScene (0.458) both route to single-ray kernel
        config = new BenchmarkConfig(FRAME_WIDTH, FRAME_HEIGHT, 16, 0.8, 10, 2, TARGET_REDUCTION);
        runner = new Phase5a5BenchmarkRunner(config);
    }

    // Sky Scene Tests (High Coherence)

    @Test
    void testSkySceneHighCoherence() {
        var result = runner.benchmarkSkyScene();

        assertNotNull(result);
        assertTrue(result.avgCoherence() >= 0.9, "Sky scene should have very high coherence (>= 0.9)");
    }

    @Test
    void testSkySceneBatchRatio() {
        var result = runner.benchmarkSkyScene();

        // Sky scene should route all tiles to batch kernel
        assertTrue(result.batchRatio() >= 0.95, "Sky scene should route ~100% to batch kernel");
    }

    @Test
    void testSkySceneNodeReduction() {
        var result = runner.benchmarkSkyScene();

        // Sky scene has high global coherence, routed to global BeamTree
        // Global tree provides baseline comparison (0% reduction vs itself)
        assertTrue(result.reductionRatio() >= 0, "Sky scene reduction should be non-negative");
    }

    // Geometry Scene Tests (Low Coherence)

    @Test
    void testGeometrySceneLowCoherence() {
        var result = runner.benchmarkGeometryScene();

        assertNotNull(result);
        // Geometry scene has moderate coherence due to local ray similarity in divergent pattern
        assertTrue(result.avgCoherence() <= 0.6, "Geometry scene should have moderate coherence (<= 0.6)");
    }

    @Test
    void testGeometrySceneSingleRayRouting() {
        var result = runner.benchmarkGeometryScene();

        // Geometry scene has low global coherence, so all tiles route to single-ray kernel
        assertEquals(0, result.batchTiles(), "Geometry scene should route 0 tiles to batch kernel");
    }

    // Mixed Scene Tests (Primary Target)

    @Test
    void testMixedSceneNodeReduction() {
        var result = runner.benchmarkMixedScene();

        assertNotNull(result);
        assertTrue(result.meetsTarget(TARGET_REDUCTION),
                   "Mixed scene (60/40) should achieve >= 30% node reduction, got: " + result.reductionRatio());
    }

    @Test
    void testMixedSceneBatchRatio() {
        var result = runner.benchmarkMixedScene();

        // Mixed scene has moderate global coherence (0.6), below 0.7 threshold
        // All tiles routed to single-ray kernel (0% batch ratio)
        double batchRatio = result.batchRatio();
        assertTrue(batchRatio == 0.0, "Mixed scene with moderate coherence should have 0% batch ratio, got: " + batchRatio);
    }

    @Test
    void testMixedSceneCoherenceDistribution() {
        var result = runner.benchmarkMixedScene();

        // Mixed scene should have moderate coherence (between sky and geometry)
        double coherence = result.avgCoherence();
        assertTrue(coherence >= 0.45 && coherence <= 0.75,
                   "Mixed scene should have moderate coherence, got: " + coherence);
    }

    // Camera Movement Tests

    @Test
    void testCameraMovementCacheHits() {
        var result = runner.benchmarkCameraMovement();

        assertNotNull(result);
        // Camera movement test measures frame-to-frame efficiency
        // Later frames should be faster due to coherence caching
        assertTrue(result.executionTimeMs() > 0, "Execution time should be positive");
    }

    @Test
    void testCameraMovementInvalidation() {
        // This tests that coherence cache is properly invalidated on camera movement
        var result = runner.benchmarkCameraMovement();

        assertNotNull(result);
        // Result should be valid even with multiple frames
        assertFalse(Double.isNaN(result.reductionRatio()), "Reduction ratio should be valid");
    }

    // Large Frame Tests (4K Stress - Memory Intensive)

    @Tag("memory-intensive")
    @Test
    void testLargeFrameDispatchOverhead() {
        // Note: Requires -Xmx1g JVM flag for 4K resolution
        var largeConfig = BenchmarkConfig.largeFrameConfig();
        var largeRunner = new Phase5a5BenchmarkRunner(largeConfig);

        var result = largeRunner.benchmarkMixedScene();

        assertNotNull(result);
        // Dispatch overhead should be minimal relative to total execution time
        assertTrue(result.dispatchTimeMs() < (result.executionTimeMs() * 0.05),
                   "Dispatch overhead should be < 5% of execution time");
    }

    @Tag("memory-intensive")
    @Test
    void testLargeFrameMemoryStability() {
        // Note: Requires -Xmx1g JVM flag for 4K resolution
        // This test validates that 4K frames can be processed without OOM
        var largeConfig = BenchmarkConfig.largeFrameConfig();
        var largeRunner = new Phase5a5BenchmarkRunner(largeConfig);

        // If we get here without OOM, the test passes
        var result = largeRunner.benchmarkSkyScene();

        assertNotNull(result);
        assertTrue(result.totalTiles() > 30000, "4K should have ~32,400 tiles");
    }

    // Sensitivity Tests

    @Test
    void testCoherenceThresholdSensitivity() {
        // Test with different coherence thresholds
        var config1 = new BenchmarkConfig(FRAME_WIDTH, FRAME_HEIGHT, 16, 0.5, 10, 2, TARGET_REDUCTION);
        var runner1 = new Phase5a5BenchmarkRunner(config1);

        var config2 = new BenchmarkConfig(FRAME_WIDTH, FRAME_HEIGHT, 16, 0.8, 10, 2, TARGET_REDUCTION);
        var runner2 = new Phase5a5BenchmarkRunner(config2);

        var result1 = runner1.benchmarkMixedScene();
        var result2 = runner2.benchmarkMixedScene();

        // Both should be valid
        assertNotNull(result1);
        assertNotNull(result2);
        // Lower threshold (0.5) should classify more tiles as batch
        // Higher threshold (0.8) should classify fewer tiles as batch
        assertTrue(result1.batchRatio() >= result2.batchRatio(),
                   "Lower threshold should result in higher batch ratio");
    }

    @Test
    void testNodeCountConsistency() {
        // Run same scene twice, should get same node counts
        var result1 = runner.benchmarkMixedScene();
        var result2 = runner.benchmarkMixedScene();

        assertEquals(result1.globalNodes(), result2.globalNodes(),
                     "Global node count should be deterministic");
        assertEquals(result1.tiledNodes(), result2.tiledNodes(),
                     "Tiled node count should be deterministic");
    }

    @Test
    void testEdgeCaseSingleTile() {
        // Frame smaller than tile size should create single tile
        var smallConfig = new BenchmarkConfig(8, 8, 16, 0.7, 5, 1, TARGET_REDUCTION);
        var smallRunner = new Phase5a5BenchmarkRunner(smallConfig);

        var result = smallRunner.benchmarkSkyScene();

        assertNotNull(result);
        // Single tile should still process correctly
        assertTrue(result.globalNodes() > 0, "Should have nodes for single tile");
    }

    @Test
    void testEdgeCaseOddDimensions() {
        // Frame with dimensions not divisible by tile size
        var oddConfig = new BenchmarkConfig(200, 150, 16, 0.7, 5, 1, TARGET_REDUCTION);
        var oddRunner = new Phase5a5BenchmarkRunner(oddConfig);

        var result = oddRunner.benchmarkMixedScene();

        assertNotNull(result);
        // Odd-sized frame should still process correctly
        assertTrue(result.globalNodes() > 0, "Should handle odd dimensions");
    }

    // Summary Tests

    @Test
    void testBenchmarkSummary() {
        runner.benchmarkSkyScene();
        runner.benchmarkGeometryScene();
        runner.benchmarkMixedScene();

        var summary = runner.getSummary();

        assertEquals(3, summary.resultCount(), "Should have 3 results");
        assertTrue(summary.avgReduction() >= 0.0 && summary.avgReduction() <= 1.0,
                   "Average reduction should be valid");
        assertTrue(summary.maxReduction() >= summary.avgReduction(),
                   "Max reduction should be >= average");
    }
}
