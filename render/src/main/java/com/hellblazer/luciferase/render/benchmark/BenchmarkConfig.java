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
 * Configuration for Phase 5a.5 benchmark runs.
 *
 * @param frameWidth Target frame width in pixels
 * @param frameHeight Target frame height in pixels
 * @param tileSize Tile size in pixels (typically 16 or 32)
 * @param coherenceThreshold Threshold for high-coherence classification (typically 0.7)
 * @param iterations Number of iterations per scene
 * @param warmupIterations Number of warmup iterations before measurement
 * @param targetNodeReduction Target node reduction ratio (e.g., 0.30 for 30%)
 */
public record BenchmarkConfig(
    int frameWidth,
    int frameHeight,
    int tileSize,
    double coherenceThreshold,
    int iterations,
    int warmupIterations,
    double targetNodeReduction
) {
    /**
     * Default benchmark configuration (256x256 frames, 30% target).
     */
    public static BenchmarkConfig defaultConfig() {
        return new BenchmarkConfig(
            256,      // frameWidth
            256,      // frameHeight
            16,       // tileSize
            0.7,      // coherenceThreshold
            100,      // iterations
            10,       // warmupIterations
            0.30      // targetNodeReduction (30%)
        );
    }

    /**
     * Configuration for 4K stress testing (memory-intensive).
     */
    public static BenchmarkConfig largeFrameConfig() {
        return new BenchmarkConfig(
            3840,     // frameWidth (4K)
            2160,     // frameHeight (4K)
            16,       // tileSize
            0.7,      // coherenceThreshold
            5,        // iterations (fewer due to memory)
            1,        // warmupIterations
            0.30      // targetNodeReduction
        );
    }

    /**
     * Configuration for detailed coherence analysis.
     */
    public static BenchmarkConfig coherenceAnalysisConfig() {
        return new BenchmarkConfig(
            256,      // frameWidth
            256,      // frameHeight
            16,       // tileSize
            0.7,      // coherenceThreshold
            100,      // iterations (many for statistical validity)
            20,       // warmupIterations
            0.30      // targetNodeReduction
        );
    }

    /**
     * Validate configuration constraints.
     */
    public BenchmarkConfig {
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("Frame dimensions must be positive");
        }
        if (tileSize <= 0) {
            throw new IllegalArgumentException("Tile size must be positive");
        }
        if (coherenceThreshold < 0.0 || coherenceThreshold > 1.0) {
            throw new IllegalArgumentException("Coherence threshold must be in [0, 1]");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("Iterations must be positive");
        }
        if (warmupIterations < 0) {
            throw new IllegalArgumentException("Warmup iterations must be non-negative");
        }
        if (targetNodeReduction < 0.0 || targetNodeReduction > 1.0) {
            throw new IllegalArgumentException("Target node reduction must be in [0, 1]");
        }
    }

    /**
     * Get total tiles for this configuration.
     */
    public int totalTiles() {
        int tilesX = (frameWidth + tileSize - 1) / tileSize;
        int tilesY = (frameHeight + tileSize - 1) / tileSize;
        return tilesX * tilesY;
    }

    /**
     * Get total rays for this configuration.
     */
    public int totalRays() {
        return frameWidth * frameHeight;
    }
}
