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

import com.hellblazer.luciferase.esvo.gpu.beam.BeamTree;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamTreeBuilder;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;
import com.hellblazer.luciferase.render.benchmark.scenes.*;
import com.hellblazer.luciferase.render.tile.TileConfiguration;
import com.hellblazer.luciferase.render.tile.TileBasedDispatcher;

import java.util.*;

/**
 * Orchestrates benchmark runs for all test scenarios.
 *
 * Responsible for:
 * 1. Creating test scenes
 * 2. Running node reduction comparisons
 * 3. Collecting execution metrics
 * 4. Aggregating results
 */
public class Phase5a5BenchmarkRunner {

    private final BenchmarkConfig config;
    private final NodeReductionComparator comparator;
    private final SimpleRayCoherenceAnalyzer coherenceAnalyzer;
    private final List<BenchmarkResult> results = new ArrayList<>();

    public Phase5a5BenchmarkRunner(BenchmarkConfig config) {
        this.config = config;
        this.coherenceAnalyzer = new SimpleRayCoherenceAnalyzer();
        this.comparator = new NodeReductionComparator(coherenceAnalyzer);
    }

    /**
     * Run benchmark for Sky scene.
     */
    public BenchmarkResult benchmarkSkyScene() {
        var scene = new SkyScene(config.frameWidth(), config.frameHeight());
        return runBenchmark("SkyScene", scene);
    }

    /**
     * Run benchmark for Geometry scene.
     */
    public BenchmarkResult benchmarkGeometryScene() {
        var scene = new GeometryScene(config.frameWidth(), config.frameHeight());
        return runBenchmark("GeometryScene", scene);
    }

    /**
     * Run benchmark for Mixed scene (primary target for 30% reduction).
     */
    public BenchmarkResult benchmarkMixedScene() {
        var scene = new MixedScene(config.frameWidth(), config.frameHeight(), 0.6);
        return runBenchmark("MixedScene", scene);
    }

    /**
     * Run benchmark for Camera Movement scene.
     */
    public BenchmarkResult benchmarkCameraMovement() {
        var scene = new CameraMovementScene(config.frameWidth(), config.frameHeight());
        var frames = scene.generateFrames();
        var tileConfig = TileConfiguration.from(config.frameWidth(), config.frameHeight(), config.tileSize());

        // Warm up
        for (int i = 0; i < Math.min(config.warmupIterations(), frames.size()); i++) {
            comparator.compare(frames.get(i), tileConfig, config.coherenceThreshold(),
                             config.frameWidth(), config.frameHeight());
        }

        // Measure
        var startTime = System.nanoTime();
        double totalReduction = 0.0;
        for (int i = config.warmupIterations(); i < frames.size(); i++) {
            var result = comparator.compare(frames.get(i), tileConfig, config.coherenceThreshold(),
                                           config.frameWidth(), config.frameHeight());
            totalReduction += result.reductionRatio();
        }
        var endTime = System.nanoTime();

        int measuredFrames = Math.max(0, frames.size() - config.warmupIterations());
        double avgReduction = measuredFrames > 0 ? totalReduction / measuredFrames : 0.0;
        double execTimeMs = (endTime - startTime) / 1_000_000.0;

        var result = new BenchmarkResult(
            "CameraMovementScene",
            0,  // Global nodes not measured (frame-based)
            0,  // Tiled nodes not measured (frame-based)
            avgReduction,
            0,  // Batch tiles averaged
            0,  // Single-ray tiles averaged
            0.6,  // Average coherence (estimated)
            0.0,  // Dispatch time averaged
            execTimeMs
        );

        results.add(result);
        return result;
    }

    /**
     * Get all collected results.
     */
    public List<BenchmarkResult> getResults() {
        return new ArrayList<>(results);
    }

    /**
     * Get results for specific scene.
     */
    public Optional<BenchmarkResult> getResult(String sceneName) {
        return results.stream()
                      .filter(r -> r.sceneName().equals(sceneName))
                      .findFirst();
    }

    /**
     * Check if 30% reduction target was met in any result.
     */
    public boolean targetMet() {
        return results.stream()
                      .anyMatch(r -> r.meetsTarget(config.targetNodeReduction()));
    }

    /**
     * Get summary statistics across all results.
     */
    public BenchmarkSummary getSummary() {
        if (results.isEmpty()) {
            return new BenchmarkSummary(0, 0.0, 0.0, 0.0);
        }

        double avgReduction = results.stream()
                                     .mapToDouble(BenchmarkResult::reductionRatio)
                                     .average()
                                     .orElse(0.0);

        double maxReduction = results.stream()
                                     .mapToDouble(BenchmarkResult::reductionRatio)
                                     .max()
                                     .orElse(0.0);

        double totalTime = results.stream()
                                  .mapToDouble(BenchmarkResult::executionTimeMs)
                                  .sum();

        return new BenchmarkSummary(results.size(), avgReduction, maxReduction, totalTime);
    }

    // Private helper

    private BenchmarkResult runBenchmark(String sceneName, SceneGenerator scene) {
        var rays = scene.generateRays();
        var tileConfig = TileConfiguration.from(this.config.frameWidth(), this.config.frameHeight(), this.config.tileSize());

        // Warm up
        for (int i = 0; i < this.config.warmupIterations(); i++) {
            comparator.compare(rays, tileConfig, this.config.coherenceThreshold(),
                             this.config.frameWidth(), this.config.frameHeight());
        }

        // Measure
        var startTime = System.nanoTime();
        int globalNodes = 0;
        int tiledNodes = 0;
        int batchTiles = 0;
        int singleRayTiles = 0;

        for (int i = 0; i < this.config.iterations(); i++) {
            var result = comparator.compare(rays, tileConfig, this.config.coherenceThreshold(),
                                           this.config.frameWidth(), this.config.frameHeight());
            if (i == 0) {
                // Only measure nodes on first iteration
                globalNodes = result.globalNodes();
                tiledNodes = result.tiledNodes();
                batchTiles = result.highCoherenceTiles();
                singleRayTiles = result.lowCoherenceTiles();
            }
        }

        var endTime = System.nanoTime();
        double execTimeMs = (endTime - startTime) / 1_000_000.0;
        double reductionRatio = globalNodes > 0 ? 1.0 - ((double) tiledNodes / globalNodes) : 0.0;
        double avgCoherence = coherenceAnalyzer.analyzeCoherence(rays, null);

        var result = new BenchmarkResult(
            sceneName,
            globalNodes,
            tiledNodes,
            reductionRatio,
            batchTiles,
            singleRayTiles,
            avgCoherence,
            0.0,  // Dispatch time (would need instrumentation)
            execTimeMs
        );

        results.add(result);
        return result;
    }

    /**
     * Summary of all benchmark runs.
     */
    public record BenchmarkSummary(
        int resultCount,
        double avgReduction,
        double maxReduction,
        double totalTimeMs
    ) {}
}
