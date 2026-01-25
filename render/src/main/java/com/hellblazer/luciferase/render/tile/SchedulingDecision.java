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

/**
 * Result of adaptive scheduling decision-making.
 *
 * <p>Contains the selected render mode, adjusted thresholds, and rationale
 * for the decision. Used by AdaptiveScheduler to communicate decisions
 * to the rendering pipeline.
 *
 * @param mode              selected render mode
 * @param adjustedConfig    adjusted thresholds for this frame
 * @param rationale         explanation of why this decision was made
 * @param confidence        confidence level (0.0 to 1.0) based on data quality
 */
public record SchedulingDecision(
    RenderMode mode,
    HybridConfig adjustedConfig,
    String rationale,
    double confidence
) {
    /**
     * Render mode for the frame.
     */
    public enum RenderMode {
        /**
         * Pure GPU rendering - all work on GPU.
         * Selected when: GPU not saturated, high coherence, or limited CPU.
         */
        GPU_ONLY,

        /**
         * Pure CPU rendering - all work on CPU.
         * Selected when: GPU saturated, very low coherence, or GPU unavailable.
         */
        CPU_ONLY,

        /**
         * Hybrid rendering - work split between CPU and GPU.
         * Selected when: Mixed coherence, moderate GPU saturation.
         */
        HYBRID,

        /**
         * Adaptive mode - per-tile decisions using HybridTileDispatcher.
         * Selected when: Coherence varies significantly across tiles.
         */
        ADAPTIVE
    }

    /**
     * Validates the decision.
     */
    public SchedulingDecision {
        if (mode == null) {
            throw new IllegalArgumentException("Mode cannot be null");
        }
        if (adjustedConfig == null) {
            throw new IllegalArgumentException("Adjusted config cannot be null");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be in [0, 1]");
        }
    }

    /**
     * Creates a GPU-only decision with high confidence.
     */
    public static SchedulingDecision gpuOnly(HybridConfig config, String rationale) {
        return new SchedulingDecision(RenderMode.GPU_ONLY, config, rationale, 1.0);
    }

    /**
     * Creates a CPU-only decision with high confidence.
     */
    public static SchedulingDecision cpuOnly(HybridConfig config, String rationale) {
        return new SchedulingDecision(RenderMode.CPU_ONLY, config, rationale, 1.0);
    }

    /**
     * Creates a hybrid decision with specified confidence.
     */
    public static SchedulingDecision hybrid(HybridConfig config, String rationale, double confidence) {
        return new SchedulingDecision(RenderMode.HYBRID, config, rationale, confidence);
    }

    /**
     * Creates an adaptive decision with specified confidence.
     */
    public static SchedulingDecision adaptive(HybridConfig config, String rationale, double confidence) {
        return new SchedulingDecision(RenderMode.ADAPTIVE, config, rationale, confidence);
    }

    /**
     * Checks if the decision uses GPU.
     */
    public boolean usesGPU() {
        return mode == RenderMode.GPU_ONLY || mode == RenderMode.HYBRID || mode == RenderMode.ADAPTIVE;
    }

    /**
     * Checks if the decision uses CPU.
     */
    public boolean usesCPU() {
        return mode == RenderMode.CPU_ONLY || mode == RenderMode.HYBRID || mode == RenderMode.ADAPTIVE;
    }

    /**
     * Checks if decision has high confidence (>= 0.8).
     */
    public boolean isHighConfidence() {
        return confidence >= 0.8;
    }
}
