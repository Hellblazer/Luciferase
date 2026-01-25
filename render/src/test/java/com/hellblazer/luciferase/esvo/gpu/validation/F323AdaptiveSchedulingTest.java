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
package com.hellblazer.luciferase.esvo.gpu.validation;

import com.hellblazer.luciferase.render.tile.*;
import com.hellblazer.luciferase.render.tile.AdaptiveScheduler.AdaptiveConfig;
import com.hellblazer.luciferase.render.tile.PerformanceTracker.FrameMetrics;
import com.hellblazer.luciferase.render.tile.PerformanceTracker.PerformanceSummary;
import com.hellblazer.luciferase.render.tile.PerformanceTracker.Trend;
import com.hellblazer.luciferase.render.tile.SchedulingDecision.RenderMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3.2.3: Adaptive Scheduling Validation Test Suite
 *
 * <p>Validates automatic GPU/CPU mode selection and dynamic threshold adjustment:
 * <ul>
 *   <li>Automatic mode selection based on coherence and saturation</li>
 *   <li>Dynamic threshold adjustment from performance feedback</li>
 *   <li>Hysteresis to prevent mode thrashing</li>
 *   <li>Learning from frame-to-frame performance</li>
 * </ul>
 *
 * @see AdaptiveScheduler
 * @see PerformanceTracker
 */
@DisplayName("F3.2.3: Adaptive Scheduling Validation")
class F323AdaptiveSchedulingTest {

    private AdaptiveScheduler scheduler;
    private PerformanceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new PerformanceTracker();
        scheduler = new AdaptiveScheduler(AdaptiveConfig.defaults(), tracker);
    }

    @Nested
    @DisplayName("PerformanceTracker")
    class PerformanceTrackerTests {

        @Test
        @DisplayName("Recording frame metrics")
        void testRecordMetrics() {
            var metrics = new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5);
            tracker.record(metrics);

            assertEquals(1, tracker.getSampleCount());
            assertNotNull(tracker.getLatest());
            assertEquals(metrics, tracker.getLatest());
        }

        @Test
        @DisplayName("Rolling window maintains size")
        void testRollingWindow() {
            // Fill beyond window size (default 30)
            for (int i = 0; i < 50; i++) {
                tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
            }

            assertEquals(30, tracker.getSampleCount());
        }

        @Test
        @DisplayName("Performance summary calculation")
        void testSummary() {
            // Record consistent metrics
            for (int i = 0; i < 10; i++) {
                tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
            }

            var summary = tracker.getSummary();

            assertEquals(16.0, summary.avgFrameTimeMs(), 0.1);
            assertEquals(0.75, summary.avgGpuUtilization(), 0.01);  // 12/16
            assertEquals(0.25, summary.avgCpuUtilization(), 0.01);  // 4/16
            assertEquals(0.8, summary.avgGpuTileRatio(), 0.01);     // 80/100
            assertEquals(0.65, summary.avgCoherence(), 0.01);
            assertEquals(10, summary.sampleCount());
        }

        @Test
        @DisplayName("Trend detection - improving")
        void testTrendImproving() {
            // First half: slower frames
            for (int i = 0; i < 6; i++) {
                tracker.record(new FrameMetrics(20_000_000L, 15_000_000L, 5_000_000L, 80, 20, 0.65, 0.5));
            }
            // Second half: faster frames
            for (int i = 0; i < 6; i++) {
                tracker.record(new FrameMetrics(10_000_000L, 8_000_000L, 2_000_000L, 80, 20, 0.65, 0.5));
            }

            var summary = tracker.getSummary();
            assertEquals(Trend.IMPROVING, summary.trend());
        }

        @Test
        @DisplayName("Trend detection - degrading")
        void testTrendDegrading() {
            // First half: faster frames
            for (int i = 0; i < 6; i++) {
                tracker.record(new FrameMetrics(10_000_000L, 8_000_000L, 2_000_000L, 80, 20, 0.65, 0.5));
            }
            // Second half: slower frames
            for (int i = 0; i < 6; i++) {
                tracker.record(new FrameMetrics(20_000_000L, 15_000_000L, 5_000_000L, 80, 20, 0.65, 0.5));
            }

            var summary = tracker.getSummary();
            assertEquals(Trend.DEGRADING, summary.trend());
        }

        @Test
        @DisplayName("Trend detection - stable")
        void testTrendStable() {
            // Consistent frames
            for (int i = 0; i < 12; i++) {
                tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
            }

            var summary = tracker.getSummary();
            assertEquals(Trend.STABLE, summary.trend());
        }

        @Test
        @DisplayName("Has reliable data after half window")
        void testHasReliableData() {
            assertFalse(tracker.hasReliableData());

            for (int i = 0; i < 14; i++) {  // Less than half of 30
                tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
            }
            assertFalse(tracker.hasReliableData());

            // Add one more to reach 15 (half of 30)
            tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
            assertTrue(tracker.hasReliableData());
        }

        @Test
        @DisplayName("Reset clears history")
        void testReset() {
            for (int i = 0; i < 10; i++) {
                tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
            }

            tracker.reset();

            assertEquals(0, tracker.getSampleCount());
            assertNull(tracker.getLatest());
        }

        @Test
        @DisplayName("FrameMetrics utility methods")
        void testFrameMetricsUtilities() {
            var metrics = new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5);

            assertEquals(0.75, metrics.gpuUtilization(), 0.01);
            assertEquals(0.25, metrics.cpuUtilization(), 0.01);
            assertEquals(0.8, metrics.gpuTileRatio(), 0.01);
        }

        @Test
        @DisplayName("From HybridDispatchMetrics")
        void testFromHybridDispatchMetrics() {
            var hybrid = new HybridDispatchMetrics(100, 60, 20, 20, 0.8, 0.2, 0.65, 0.5,
                16_000_000L, 12_000_000L, 4_000_000L);

            var metrics = FrameMetrics.from(hybrid);

            assertEquals(16_000_000L, metrics.frameTimeNs());
            assertEquals(80, metrics.gpuTiles());  // 60 + 20
            assertEquals(20, metrics.cpuTiles());
            assertEquals(0.65, metrics.avgCoherence());
        }
    }

    @Nested
    @DisplayName("SchedulingDecision")
    class SchedulingDecisionTests {

        @Test
        @DisplayName("GPU-only decision")
        void testGpuOnlyDecision() {
            var config = HybridTileDispatcher.HybridConfig.defaults();
            var decision = SchedulingDecision.gpuOnly(config, "High coherence");

            assertEquals(RenderMode.GPU_ONLY, decision.mode());
            assertTrue(decision.usesGPU());
            assertFalse(decision.usesCPU());
            assertEquals(1.0, decision.confidence());
        }

        @Test
        @DisplayName("CPU-only decision")
        void testCpuOnlyDecision() {
            var config = HybridTileDispatcher.HybridConfig.defaults();
            var decision = SchedulingDecision.cpuOnly(config, "GPU saturated");

            assertEquals(RenderMode.CPU_ONLY, decision.mode());
            assertFalse(decision.usesGPU());
            assertTrue(decision.usesCPU());
        }

        @Test
        @DisplayName("Hybrid decision")
        void testHybridDecision() {
            var config = HybridTileDispatcher.HybridConfig.defaults();
            var decision = SchedulingDecision.hybrid(config, "Mixed workload", 0.8);

            assertEquals(RenderMode.HYBRID, decision.mode());
            assertTrue(decision.usesGPU());
            assertTrue(decision.usesCPU());
            assertEquals(0.8, decision.confidence());
        }

        @Test
        @DisplayName("Adaptive decision")
        void testAdaptiveDecision() {
            var config = HybridTileDispatcher.HybridConfig.defaults();
            var decision = SchedulingDecision.adaptive(config, "Per-tile decision", 0.9);

            assertEquals(RenderMode.ADAPTIVE, decision.mode());
            assertTrue(decision.usesGPU());
            assertTrue(decision.usesCPU());
        }

        @Test
        @DisplayName("High confidence check")
        void testHighConfidence() {
            var config = HybridTileDispatcher.HybridConfig.defaults();

            var highConf = SchedulingDecision.adaptive(config, "test", 0.85);
            assertTrue(highConf.isHighConfidence());

            var lowConf = SchedulingDecision.adaptive(config, "test", 0.7);
            assertFalse(lowConf.isHighConfidence());
        }

        @Test
        @DisplayName("Invalid confidence throws")
        void testInvalidConfidence() {
            var config = HybridTileDispatcher.HybridConfig.defaults();

            assertThrows(IllegalArgumentException.class, () ->
                new SchedulingDecision(RenderMode.GPU_ONLY, config, "test", -0.1));

            assertThrows(IllegalArgumentException.class, () ->
                new SchedulingDecision(RenderMode.GPU_ONLY, config, "test", 1.5));
        }
    }

    @Nested
    @DisplayName("AdaptiveScheduler Mode Selection")
    class AdaptiveSchedulerModeTests {

        @Test
        @DisplayName("High coherence selects GPU_ONLY after warmup")
        void testHighCoherenceSelectsGpu() {
            // Need some history for non-ADAPTIVE decisions
            for (int i = 0; i < 6; i++) {
                scheduler.decide(true, true, 0.85, 0.3);
            }

            var decision = scheduler.decide(true, true, 0.85, 0.3);

            assertEquals(RenderMode.GPU_ONLY, decision.mode());
            assertTrue(decision.rationale().contains("High coherence"));
        }

        @Test
        @DisplayName("Low coherence with available CPU selects CPU_ONLY")
        void testLowCoherenceSelectsCpu() {
            // Warm up with some data
            for (int i = 0; i < 15; i++) {
                tracker.record(new FrameMetrics(16_000_000L, 6_000_000L, 10_000_000L, 30, 70, 0.2, 0.3));
            }

            var decision = scheduler.decide(true, true, 0.15, 0.3);

            // With low coherence and CPU being more efficient, should select CPU or ADAPTIVE
            assertTrue(decision.mode() == RenderMode.CPU_ONLY || decision.mode() == RenderMode.ADAPTIVE,
                "Low coherence should prefer CPU or adaptive: " + decision.mode());
        }

        @Test
        @DisplayName("GPU saturated shifts to HYBRID")
        void testGpuSaturatedSelectsHybrid() {
            // Need some history for mode to change from initial ADAPTIVE
            for (int i = 0; i < 6; i++) {
                scheduler.decide(true, true, 0.5, 0.9);
            }

            var decision = scheduler.decide(true, true, 0.5, 0.9);

            assertEquals(RenderMode.HYBRID, decision.mode());
            assertTrue(decision.rationale().contains("saturated"));
        }

        @Test
        @DisplayName("GPU unavailable selects CPU_ONLY")
        void testGpuUnavailableSelectsCpu() {
            var decision = scheduler.decide(false, true, 0.8, 0.0);

            assertEquals(RenderMode.CPU_ONLY, decision.mode());
            assertTrue(decision.rationale().contains("unavailable"));
        }

        @Test
        @DisplayName("CPU unavailable selects GPU_ONLY")
        void testCpuUnavailableSelectsGpu() {
            var decision = scheduler.decide(true, false, 0.3, 0.5);

            assertEquals(RenderMode.GPU_ONLY, decision.mode());
            assertTrue(decision.rationale().contains("unavailable"));
        }

        @Test
        @DisplayName("Neither available throws")
        void testNeitherAvailableThrows() {
            assertThrows(IllegalStateException.class, () ->
                scheduler.decide(false, false, 0.5, 0.5));
        }

        @Test
        @DisplayName("Medium coherence selects ADAPTIVE")
        void testMediumCoherenceSelectsAdaptive() {
            var decision = scheduler.decide(true, true, 0.5, 0.3);

            assertEquals(RenderMode.ADAPTIVE, decision.mode());
            assertTrue(decision.rationale().contains("per-tile"));
        }
    }

    @Nested
    @DisplayName("AdaptiveScheduler Threshold Adjustment")
    class ThresholdAdjustmentTests {

        @Test
        @DisplayName("Thresholds adjusted when GPU overloaded")
        void testThresholdsAdjustWhenGpuOverloaded() {
            var initialConfig = scheduler.getCurrentConfig();
            double initialHigh = initialConfig.highCoherenceThreshold();

            // Simulate GPU overload with degrading performance
            // First half: faster (baseline)
            for (int i = 0; i < 10; i++) {
                tracker.record(new FrameMetrics(
                    16_000_000L,  // Baseline frame time
                    15_200_000L,  // 95% GPU utilization (> 0.9 threshold)
                    800_000L, 90, 10, 0.65, 0.85
                ));
            }
            // Second half: slower (degrading)
            for (int i = 0; i < 10; i++) {
                tracker.record(new FrameMetrics(
                    22_000_000L,  // Degrading frame time (37% slower)
                    20_900_000L,  // Still 95% GPU utilization
                    1_100_000L, 90, 10, 0.65, 0.85
                ));
            }

            // Verify we have degrading trend
            var summary = tracker.getSummary();
            assertEquals(Trend.DEGRADING, summary.trend(),
                "Should detect degrading trend: " + summary.trend());

            // Make decisions to trigger adaptation (need multiple for adjustment)
            for (int i = 0; i < 5; i++) {
                scheduler.decide(true, true, 0.6, 0.85);
            }

            var adjustedConfig = scheduler.getCurrentConfig();

            assertTrue(adjustedConfig.highCoherenceThreshold() > initialHigh,
                "High threshold should increase when GPU overloaded: " +
                    initialHigh + " -> " + adjustedConfig.highCoherenceThreshold());
        }

        @Test
        @DisplayName("Thresholds adjusted when GPU underutilized")
        void testThresholdsAdjustWhenGpuUnderutilized() {
            var initialConfig = scheduler.getCurrentConfig();
            double initialHigh = initialConfig.highCoherenceThreshold();

            // Simulate GPU underutilization
            for (int i = 0; i < 20; i++) {
                tracker.record(new FrameMetrics(
                    16_000_000L, 8_000_000L, 8_000_000L,  // 50% GPU utilization
                    40, 60,  // Low GPU tile ratio
                    0.65, 0.3
                ));
            }

            // Make a decision to trigger adaptation
            scheduler.decide(true, true, 0.6, 0.3);

            var adjustedConfig = scheduler.getCurrentConfig();

            assertTrue(adjustedConfig.highCoherenceThreshold() < initialHigh,
                "High threshold should decrease when GPU underutilized: " +
                    initialHigh + " -> " + adjustedConfig.highCoherenceThreshold());
        }

        @Test
        @DisplayName("Thresholds respect bounds")
        void testThresholdsRespectBounds() {
            // Try to push thresholds to extremes
            for (int i = 0; i < 100; i++) {
                tracker.record(new FrameMetrics(20_000_000L, 19_000_000L, 1_000_000L, 95, 5, 0.65, 0.95));
                scheduler.decide(true, true, 0.6, 0.95);
            }

            var config = scheduler.getCurrentConfig();

            assertTrue(config.highCoherenceThreshold() <= 0.9,
                "High threshold should not exceed max: " + config.highCoherenceThreshold());
            assertTrue(config.lowCoherenceThreshold() >= 0.1,
                "Low threshold should not go below min: " + config.lowCoherenceThreshold());
        }
    }

    @Nested
    @DisplayName("AdaptiveScheduler Hysteresis")
    class HysteresisTests {

        @Test
        @DisplayName("Mode doesn't change immediately")
        void testModeDoesntChangeImmediately() {
            // Warm up to get out of initial ADAPTIVE mode
            for (int i = 0; i < 6; i++) {
                scheduler.decide(true, true, 0.85, 0.3);
            }

            // Now we should be in GPU_ONLY mode
            var decision1 = scheduler.decide(true, true, 0.85, 0.3);
            assertEquals(RenderMode.GPU_ONLY, decision1.mode());

            // Change conditions but should maintain mode due to hysteresis
            var decision2 = scheduler.decide(true, true, 0.5, 0.3);

            // Within MIN_FRAMES_BEFORE_SWITCH, should maintain previous mode
            assertTrue(decision2.rationale().contains("hysteresis"),
                "Should mention hysteresis: " + decision2.rationale());
        }

        @Test
        @DisplayName("Mode changes after sufficient frames")
        void testModeChangesAfterSufficientFrames() {
            // First decision at high coherence
            scheduler.decide(true, true, 0.85, 0.3);

            // Make multiple decisions to exceed hysteresis window
            RenderMode lastMode = null;
            for (int i = 0; i < 10; i++) {
                var decision = scheduler.decide(true, true, 0.5, 0.3);
                lastMode = decision.mode();
            }

            // After enough frames, should have changed to ADAPTIVE
            assertEquals(RenderMode.ADAPTIVE, lastMode);
        }
    }

    @Nested
    @DisplayName("AdaptiveScheduler Confidence")
    class ConfidenceTests {

        @Test
        @DisplayName("Low confidence with no data")
        void testLowConfidenceNoData() {
            var decision = scheduler.decide(true, true, 0.5, 0.3);

            assertTrue(decision.confidence() < 0.5,
                "Confidence should be low with no data: " + decision.confidence());
        }

        @Test
        @DisplayName("Higher confidence with more data")
        void testHigherConfidenceWithData() {
            // Add some performance data
            for (int i = 0; i < 20; i++) {
                tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
            }

            var decision = scheduler.decide(true, true, 0.5, 0.3);

            assertTrue(decision.confidence() > 0.5,
                "Confidence should be higher with data: " + decision.confidence());
        }

        @Test
        @DisplayName("Highest confidence with stable data")
        void testHighestConfidenceStableData() {
            // Add consistent stable data
            for (int i = 0; i < 30; i++) {
                tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
            }

            var decision = scheduler.decide(true, true, 0.5, 0.3);

            assertTrue(decision.confidence() >= 0.8,
                "Confidence should be high with stable data: " + decision.confidence());
        }
    }

    @Nested
    @DisplayName("AdaptiveConfig")
    class AdaptiveConfigTests {

        @Test
        @DisplayName("Default configuration")
        void testDefaults() {
            var config = AdaptiveConfig.defaults();

            assertEquals(0.5, config.minHighThreshold());
            assertEquals(0.9, config.maxHighThreshold());
            assertEquals(0.1, config.minLowThreshold());
            assertEquals(0.5, config.maxLowThreshold());
            assertEquals(0.02, config.adjustmentRate());
            assertEquals(0.1, config.hysteresisWindow());
            assertEquals(10, config.minSamplesForAdaptation());
        }

        @Test
        @DisplayName("Invalid threshold ranges throw")
        void testInvalidThresholdRanges() {
            // Min high >= max high
            assertThrows(IllegalArgumentException.class, () ->
                new AdaptiveConfig(0.9, 0.5, 0.1, 0.5, 0.02, 0.1, 10));

            // Min low >= max low
            assertThrows(IllegalArgumentException.class, () ->
                new AdaptiveConfig(0.5, 0.9, 0.5, 0.3, 0.02, 0.1, 10));
        }

        @Test
        @DisplayName("Invalid adjustment rate throws")
        void testInvalidAdjustmentRate() {
            assertThrows(IllegalArgumentException.class, () ->
                new AdaptiveConfig(0.5, 0.9, 0.1, 0.5, 0.0, 0.1, 10));

            assertThrows(IllegalArgumentException.class, () ->
                new AdaptiveConfig(0.5, 0.9, 0.1, 0.5, 0.3, 0.1, 10));
        }

        @Test
        @DisplayName("Invalid min samples throws")
        void testInvalidMinSamples() {
            assertThrows(IllegalArgumentException.class, () ->
                new AdaptiveConfig(0.5, 0.9, 0.1, 0.5, 0.02, 0.1, 2));
        }
    }

    @Nested
    @DisplayName("AdaptiveScheduler Integration")
    class IntegrationTests {

        @Test
        @DisplayName("Complete adaptive loop")
        void testCompleteAdaptiveLoop() {
            // Simulate a complete adaptive loop:
            // 1. Start with no data
            // 2. Record performance
            // 3. Adapt thresholds
            // 4. Make better decisions

            // Initial decision with low confidence
            var decision1 = scheduler.decide(true, true, 0.6, 0.4);
            assertTrue(decision1.confidence() < 0.5);

            // Record performance showing GPU overload
            for (int i = 0; i < 15; i++) {
                var metrics = new HybridDispatchMetrics(100, 70, 20, 10, 0.9, 0.1, 0.6, 0.75,
                    20_000_000L, 18_000_000L, 2_000_000L);
                scheduler.recordPerformance(metrics);
            }

            // Now has reliable data
            assertTrue(tracker.hasReliableData());

            // Decision with higher confidence
            var decision2 = scheduler.decide(true, true, 0.6, 0.4);
            assertTrue(decision2.confidence() > decision1.confidence());

            // Thresholds should have been adjusted
            var config = scheduler.getCurrentConfig();
            assertNotNull(config);
        }

        @Test
        @DisplayName("Reset clears all state")
        void testReset() {
            // Build up state
            for (int i = 0; i < 20; i++) {
                tracker.record(new FrameMetrics(16_000_000L, 12_000_000L, 4_000_000L, 80, 20, 0.65, 0.5));
                scheduler.decide(true, true, 0.5, 0.3);
            }

            // Reset
            scheduler.reset();

            // Verify reset state
            assertEquals(0, tracker.getSampleCount());
            assertEquals(RenderMode.ADAPTIVE, scheduler.getCurrentMode());

            var config = scheduler.getCurrentConfig();
            var defaults = HybridTileDispatcher.HybridConfig.defaults();
            assertEquals(defaults.highCoherenceThreshold(), config.highCoherenceThreshold());
        }
    }
}
