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
package com.hellblazer.luciferase.render.tile;

import com.hellblazer.luciferase.render.tile.HybridTileDispatcher.HybridConfig;
import com.hellblazer.luciferase.render.tile.PerformanceTracker.PerformanceSummary;
import com.hellblazer.luciferase.render.tile.PerformanceTracker.Trend;
import com.hellblazer.luciferase.render.tile.SchedulingDecision.RenderMode;

/**
 * Adaptive scheduler that automatically selects GPU/CPU rendering mode
 * and dynamically adjusts execution thresholds based on runtime performance.
 *
 * <p>Key features:
 * <ul>
 *   <li>Automatic mode selection (GPU_ONLY, CPU_ONLY, HYBRID, ADAPTIVE)</li>
 *   <li>Dynamic threshold adjustment based on performance feedback</li>
 *   <li>Hysteresis to prevent mode thrashing</li>
 *   <li>Learning from frame-to-frame performance trends</li>
 * </ul>
 *
 * <p>The scheduler analyzes recent performance metrics and adjusts:
 * <ul>
 *   <li>High coherence threshold (default 0.7): raised if GPU is overloaded</li>
 *   <li>Low coherence threshold (default 0.3): lowered if CPU is underutilized</li>
 *   <li>GPU saturation threshold (default 0.8): adjusted based on actual saturation</li>
 * </ul>
 *
 * @see PerformanceTracker
 * @see SchedulingDecision
 * @see HybridTileDispatcher
 */
public class AdaptiveScheduler {

    /**
     * Configuration for adaptive scheduling behavior.
     */
    public record AdaptiveConfig(
        double minHighThreshold,    // Minimum for high coherence (default 0.5)
        double maxHighThreshold,    // Maximum for high coherence (default 0.9)
        double minLowThreshold,     // Minimum for low coherence (default 0.1)
        double maxLowThreshold,     // Maximum for low coherence (default 0.5)
        double adjustmentRate,      // How fast to adjust thresholds (default 0.02)
        double hysteresisWindow,    // % change needed to trigger mode switch (default 0.1)
        int minSamplesForAdaptation // Frames needed before adapting (default 10)
    ) {
        public static AdaptiveConfig defaults() {
            return new AdaptiveConfig(0.5, 0.9, 0.1, 0.5, 0.02, 0.1, 10);
        }

        public AdaptiveConfig {
            if (minHighThreshold >= maxHighThreshold) {
                throw new IllegalArgumentException("Min high threshold must be < max");
            }
            if (minLowThreshold >= maxLowThreshold) {
                throw new IllegalArgumentException("Min low threshold must be < max");
            }
            if (adjustmentRate <= 0 || adjustmentRate > 0.2) {
                throw new IllegalArgumentException("Adjustment rate must be in (0, 0.2]");
            }
            if (hysteresisWindow < 0 || hysteresisWindow > 0.5) {
                throw new IllegalArgumentException("Hysteresis window must be in [0, 0.5]");
            }
            if (minSamplesForAdaptation < 3) {
                throw new IllegalArgumentException("Min samples must be at least 3");
            }
        }
    }

    private final AdaptiveConfig adaptiveConfig;
    private final PerformanceTracker tracker;

    // Current thresholds (dynamically adjusted)
    private double currentHighThreshold;
    private double currentLowThreshold;
    private double currentSaturationThreshold;

    // State for hysteresis
    private RenderMode currentMode;
    private int framesInCurrentMode;
    private static final int MIN_FRAMES_BEFORE_SWITCH = 5;

    /**
     * Creates an adaptive scheduler with default configuration.
     */
    public AdaptiveScheduler() {
        this(AdaptiveConfig.defaults());
    }

    /**
     * Creates an adaptive scheduler with custom configuration.
     *
     * @param adaptiveConfig configuration for adaptive behavior
     */
    public AdaptiveScheduler(AdaptiveConfig adaptiveConfig) {
        this(adaptiveConfig, new PerformanceTracker());
    }

    /**
     * Creates an adaptive scheduler with custom configuration and tracker.
     *
     * @param adaptiveConfig configuration for adaptive behavior
     * @param tracker        performance tracker
     */
    public AdaptiveScheduler(AdaptiveConfig adaptiveConfig, PerformanceTracker tracker) {
        if (adaptiveConfig == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
        if (tracker == null) {
            throw new IllegalArgumentException("Tracker cannot be null");
        }

        this.adaptiveConfig = adaptiveConfig;
        this.tracker = tracker;

        // Initialize with default thresholds
        var defaults = HybridConfig.defaults();
        this.currentHighThreshold = defaults.highCoherenceThreshold();
        this.currentLowThreshold = defaults.lowCoherenceThreshold();
        this.currentSaturationThreshold = defaults.gpuSaturationThreshold();

        this.currentMode = RenderMode.ADAPTIVE;
        this.framesInCurrentMode = 0;
    }

    /**
     * Makes a scheduling decision for the next frame.
     *
     * @param gpuAvailable   whether GPU is available
     * @param cpuAvailable   whether CPU is available
     * @param estimatedCoherence estimated coherence for the frame (from scene analysis)
     * @param currentGpuSaturation current GPU saturation level
     * @return scheduling decision with adjusted thresholds
     */
    public SchedulingDecision decide(boolean gpuAvailable, boolean cpuAvailable,
                                      double estimatedCoherence, double currentGpuSaturation) {
        framesInCurrentMode++;

        var summary = tracker.getSummary();
        double confidence = calculateConfidence(summary);

        // Adapt thresholds based on performance feedback
        if (tracker.hasReliableData()) {
            adaptThresholds(summary);
        }

        // Create current config with adjusted thresholds
        var adjustedConfig = new HybridConfig(
            currentHighThreshold,
            currentLowThreshold,
            currentSaturationThreshold,
            HybridConfig.defaults().simdFactor()
        );

        // Select render mode
        RenderMode selectedMode;
        String rationale;

        if (!gpuAvailable && !cpuAvailable) {
            throw new IllegalStateException("Neither GPU nor CPU available");
        } else if (!gpuAvailable) {
            selectedMode = RenderMode.CPU_ONLY;
            rationale = "GPU unavailable, using CPU";
        } else if (!cpuAvailable) {
            selectedMode = RenderMode.GPU_ONLY;
            rationale = "CPU unavailable, using GPU";
        } else {
            // Both available - make intelligent decision
            var proposed = selectMode(estimatedCoherence, currentGpuSaturation, summary);
            selectedMode = proposed.mode;
            rationale = proposed.rationale;

            // Apply hysteresis to prevent thrashing
            if (selectedMode != currentMode && framesInCurrentMode < MIN_FRAMES_BEFORE_SWITCH) {
                selectedMode = currentMode;
                rationale = "Maintaining " + currentMode + " (hysteresis, " + framesInCurrentMode + " frames)";
            } else if (selectedMode != currentMode) {
                framesInCurrentMode = 0;
            }
        }

        currentMode = selectedMode;
        return new SchedulingDecision(selectedMode, adjustedConfig, rationale, confidence);
    }

    /**
     * Records frame performance for future adaptation.
     *
     * @param metrics frame dispatch metrics
     */
    public void recordPerformance(HybridDispatchMetrics metrics) {
        tracker.record(metrics);
    }

    /**
     * Gets the current adjusted thresholds.
     */
    public HybridConfig getCurrentConfig() {
        return new HybridConfig(
            currentHighThreshold,
            currentLowThreshold,
            currentSaturationThreshold,
            HybridConfig.defaults().simdFactor()
        );
    }

    /**
     * Gets the performance tracker.
     */
    public PerformanceTracker getTracker() {
        return tracker;
    }

    /**
     * Gets the current render mode.
     */
    public RenderMode getCurrentMode() {
        return currentMode;
    }

    /**
     * Resets the scheduler to initial state.
     */
    public void reset() {
        var defaults = HybridConfig.defaults();
        currentHighThreshold = defaults.highCoherenceThreshold();
        currentLowThreshold = defaults.lowCoherenceThreshold();
        currentSaturationThreshold = defaults.gpuSaturationThreshold();
        currentMode = RenderMode.ADAPTIVE;
        framesInCurrentMode = 0;
        tracker.reset();
    }

    // Private implementation

    private record ModeProposal(RenderMode mode, String rationale) {}

    private ModeProposal selectMode(double coherence, double saturation, PerformanceSummary summary) {
        // High GPU saturation -> prefer CPU or hybrid
        if (saturation >= currentSaturationThreshold) {
            if (coherence < currentLowThreshold) {
                return new ModeProposal(RenderMode.CPU_ONLY,
                    String.format("GPU saturated (%.0f%%), low coherence (%.2f) -> CPU",
                        saturation * 100, coherence));
            }
            return new ModeProposal(RenderMode.HYBRID,
                String.format("GPU saturated (%.0f%%), offloading to hybrid", saturation * 100));
        }

        // Very high coherence -> GPU batch is optimal
        if (coherence >= currentHighThreshold) {
            return new ModeProposal(RenderMode.GPU_ONLY,
                String.format("High coherence (%.2f >= %.2f) -> GPU batch optimal",
                    coherence, currentHighThreshold));
        }

        // Very low coherence -> CPU may be better
        if (coherence < currentLowThreshold) {
            // But check if CPU is actually faster
            if (summary.avgCpuUtilization() < summary.avgGpuUtilization() * 0.7) {
                return new ModeProposal(RenderMode.CPU_ONLY,
                    String.format("Low coherence (%.2f), CPU more efficient (%.0f%% vs %.0f%%)",
                        coherence, summary.avgCpuUtilization() * 100, summary.avgGpuUtilization() * 100));
            }
        }

        // Mixed coherence -> adaptive per-tile decision
        return new ModeProposal(RenderMode.ADAPTIVE,
            String.format("Mixed coherence (%.2f), using per-tile adaptive", coherence));
    }

    private void adaptThresholds(PerformanceSummary summary) {
        double rate = adaptiveConfig.adjustmentRate();

        // Adjust high threshold based on GPU utilization and trend
        if (summary.avgGpuUtilization() > 0.9 && summary.trend() == Trend.DEGRADING) {
            // GPU overloaded and getting worse -> raise threshold to send more to CPU
            currentHighThreshold = Math.min(
                currentHighThreshold + rate,
                adaptiveConfig.maxHighThreshold()
            );
        } else if (summary.avgGpuUtilization() < 0.6 && summary.avgGpuTileRatio() < 0.5) {
            // GPU underutilized -> lower threshold to use more GPU
            currentHighThreshold = Math.max(
                currentHighThreshold - rate,
                adaptiveConfig.minHighThreshold()
            );
        }

        // Adjust low threshold based on CPU utilization
        if (summary.avgCpuUtilization() > 0.8) {
            // CPU overloaded -> raise low threshold to use less CPU
            currentLowThreshold = Math.min(
                currentLowThreshold + rate,
                adaptiveConfig.maxLowThreshold()
            );
        } else if (summary.avgCpuUtilization() < 0.3 && summary.avgCoherence() < currentLowThreshold) {
            // CPU underutilized but coherence is low -> lower threshold to use more CPU
            currentLowThreshold = Math.max(
                currentLowThreshold - rate,
                adaptiveConfig.minLowThreshold()
            );
        }

        // Adjust GPU saturation threshold based on actual saturation
        if (summary.avgGpuSaturation() > currentSaturationThreshold && summary.trend() == Trend.DEGRADING) {
            // Often saturated -> lower threshold to trigger offload earlier
            currentSaturationThreshold = Math.max(
                currentSaturationThreshold - rate,
                0.5  // Don't go below 50%
            );
        } else if (summary.avgGpuSaturation() < currentSaturationThreshold * 0.5 && summary.trend() == Trend.IMPROVING) {
            // Rarely saturated -> raise threshold
            currentSaturationThreshold = Math.min(
                currentSaturationThreshold + rate,
                0.95  // Don't go above 95%
            );
        }

        // Ensure high > low threshold invariant
        if (currentHighThreshold <= currentLowThreshold + 0.1) {
            currentHighThreshold = currentLowThreshold + 0.1;
        }
    }

    private double calculateConfidence(PerformanceSummary summary) {
        int samples = summary.sampleCount();
        int minSamples = adaptiveConfig.minSamplesForAdaptation();

        if (samples == 0) {
            return 0.3;  // Low confidence with no data
        }

        // Scale confidence from 0.5 (at minSamples/2) to 1.0 (at minSamples*2)
        double sampleFactor = Math.min(1.0, (double) samples / (minSamples * 2));

        // Reduce confidence if trend is unstable
        double stabilityFactor = summary.trend() == Trend.STABLE ? 1.0 : 0.8;

        return 0.3 + 0.7 * sampleFactor * stabilityFactor;
    }
}
