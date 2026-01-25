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

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.beam.BeamTree;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;

import java.util.ArrayList;
import java.util.List;

/**
 * Hybrid tile dispatcher with 3-way decision: GPU batch, GPU single, or CPU.
 *
 * <p>Extends tile-based dispatch with optimal work distribution between CPU and GPU
 * based on tile coherence and GPU saturation levels.
 *
 * <p>Decision thresholds:
 * <ul>
 *   <li>Coherence &gt;= 0.7: GPU batch (SIMD factor 4)</li>
 *   <li>Coherence 0.3-0.7: GPU single-ray</li>
 *   <li>Coherence &lt; 0.3 OR GPU saturated: CPU traversal</li>
 * </ul>
 *
 * <p>When GPU saturation exceeds 0.8, dispatcher shifts more work to CPU
 * regardless of coherence to maintain throughput.
 *
 * @see HybridKernelExecutor
 * @see HybridDecision
 */
public class HybridTileDispatcher {

    /**
     * Functional interface for analyzing ray coherence.
     */
    @FunctionalInterface
    public interface CoherenceAnalyzer {
        double analyzeCoherence(Ray[] rays, DAGOctreeData dag);
    }

    /**
     * Functional interface for building BeamTree instances.
     */
    @FunctionalInterface
    public interface BeamTreeFactory {
        BeamTree buildBeamTree(Ray[] rays, int[] rayIndices, DAGOctreeData dag, double coherenceScore);
    }

    /**
     * Configuration for hybrid dispatch thresholds.
     */
    public record HybridConfig(
        double highCoherenceThreshold,  // >= this: GPU batch (default 0.7)
        double lowCoherenceThreshold,   // < this: CPU (default 0.3)
        double gpuSaturationThreshold,  // >= this: prefer CPU (default 0.8)
        int simdFactor                  // rays per work item for batch (default 4)
    ) {
        public static HybridConfig defaults() {
            return new HybridConfig(0.7, 0.3, 0.8, 4);
        }

        public HybridConfig {
            if (highCoherenceThreshold < lowCoherenceThreshold) {
                throw new IllegalArgumentException("High threshold must be >= low threshold");
            }
            if (highCoherenceThreshold < 0.0 || highCoherenceThreshold > 1.0) {
                throw new IllegalArgumentException("High threshold must be in [0, 1]");
            }
            if (lowCoherenceThreshold < 0.0 || lowCoherenceThreshold > 1.0) {
                throw new IllegalArgumentException("Low threshold must be in [0, 1]");
            }
            if (gpuSaturationThreshold < 0.0 || gpuSaturationThreshold > 1.0) {
                throw new IllegalArgumentException("GPU saturation threshold must be in [0, 1]");
            }
            if (simdFactor < 1) {
                throw new IllegalArgumentException("SIMD factor must be at least 1");
            }
        }
    }

    private final TileConfiguration tileConfig;
    private final HybridConfig hybridConfig;
    private final CoherenceAnalyzer coherenceAnalyzer;
    private final BeamTreeFactory beamTreeFactory;
    private final TileCoherenceMap coherenceMap;

    /**
     * Creates a hybrid dispatcher with default configuration.
     */
    public HybridTileDispatcher(TileConfiguration tileConfig,
                                 CoherenceAnalyzer coherenceAnalyzer,
                                 BeamTreeFactory beamTreeFactory) {
        this(tileConfig, HybridConfig.defaults(), coherenceAnalyzer, beamTreeFactory);
    }

    /**
     * Creates a hybrid dispatcher with custom configuration.
     */
    public HybridTileDispatcher(TileConfiguration tileConfig,
                                 HybridConfig hybridConfig,
                                 CoherenceAnalyzer coherenceAnalyzer,
                                 BeamTreeFactory beamTreeFactory) {
        if (tileConfig == null) {
            throw new IllegalArgumentException("Tile config cannot be null");
        }
        if (hybridConfig == null) {
            throw new IllegalArgumentException("Hybrid config cannot be null");
        }
        if (coherenceAnalyzer == null) {
            throw new IllegalArgumentException("Coherence analyzer cannot be null");
        }
        if (beamTreeFactory == null) {
            throw new IllegalArgumentException("Beam tree factory cannot be null");
        }

        this.tileConfig = tileConfig;
        this.hybridConfig = hybridConfig;
        this.coherenceAnalyzer = coherenceAnalyzer;
        this.beamTreeFactory = beamTreeFactory;
        this.coherenceMap = new TileCoherenceMap(tileConfig, 0.5);
    }

    /**
     * Dispatches frame using hybrid CPU/GPU execution.
     *
     * @param rays        global ray array
     * @param frameWidth  frame width in pixels
     * @param frameHeight frame height in pixels
     * @param dag         DAG data for traversal
     * @param executor    hybrid kernel executor
     * @return hybrid dispatch metrics
     */
    public HybridDispatchMetrics dispatchFrame(Ray[] rays, int frameWidth, int frameHeight,
                                                DAGOctreeData dag, HybridKernelExecutor executor) {
        long startTime = System.nanoTime();
        long gpuTimeStart = 0, gpuTimeEnd = 0;
        long cpuTimeStart = 0, cpuTimeEnd = 0;

        // Phase 1: Partition into tiles
        var tiles = partitionIntoTiles(rays, frameWidth, frameHeight);

        // Phase 2: Measure coherence per tile
        updateTileCoherence(tiles, rays, frameWidth, frameHeight, dag);

        // Get GPU saturation for decision making
        double gpuSaturation = executor.getGPUSaturation();

        // Phase 3: Execute tiles with 3-way decision
        int gpuBatchCount = 0;
        int gpuSingleCount = 0;
        int cpuCount = 0;
        double totalCoherence = 0.0;

        // Separate lists for different execution paths
        var gpuBatchTiles = new ArrayList<Tile>();
        var gpuSingleTiles = new ArrayList<Tile>();
        var cpuTiles = new ArrayList<Tile>();

        for (var tile : tiles) {
            var decision = makeDecision(tile.coherenceScore(), gpuSaturation, executor.supportsCPU());
            totalCoherence += tile.coherenceScore();

            switch (decision) {
                case GPU_BATCH -> {
                    gpuBatchCount++;
                    gpuBatchTiles.add(tile);
                }
                case GPU_SINGLE -> {
                    gpuSingleCount++;
                    gpuSingleTiles.add(tile);
                }
                case CPU -> {
                    cpuCount++;
                    cpuTiles.add(tile);
                }
            }
        }

        // Execute GPU tiles first (batch, then single)
        gpuTimeStart = System.nanoTime();
        for (var tile : gpuBatchTiles) {
            executeTileGPUBatch(tile, rays, frameWidth, frameHeight, dag, executor);
        }
        for (var tile : gpuSingleTiles) {
            executeTileGPUSingle(tile, rays, frameWidth, frameHeight, executor);
        }
        gpuTimeEnd = System.nanoTime();

        // Execute CPU tiles
        cpuTimeStart = System.nanoTime();
        for (var tile : cpuTiles) {
            executeTileCPU(tile, rays, frameWidth, frameHeight, executor);
        }
        cpuTimeEnd = System.nanoTime();

        long endTime = System.nanoTime();

        // Calculate metrics
        int totalTiles = tiles.size();
        double avgCoherence = totalTiles > 0 ? totalCoherence / totalTiles : 0.0;
        double gpuRatio = totalTiles > 0 ? (double) (gpuBatchCount + gpuSingleCount) / totalTiles : 0.0;
        double cpuRatio = totalTiles > 0 ? (double) cpuCount / totalTiles : 0.0;

        return new HybridDispatchMetrics(
            totalTiles,
            gpuBatchCount,
            gpuSingleCount,
            cpuCount,
            gpuRatio,
            cpuRatio,
            avgCoherence,
            gpuSaturation,
            endTime - startTime,
            gpuTimeEnd - gpuTimeStart,
            cpuTimeEnd - cpuTimeStart
        );
    }

    /**
     * Makes a 3-way execution decision based on coherence and GPU saturation.
     */
    public HybridDecision makeDecision(double coherence, double gpuSaturation, boolean cpuSupported) {
        // If GPU is saturated and CPU is available, prefer CPU for low-medium coherence
        if (gpuSaturation >= hybridConfig.gpuSaturationThreshold() && cpuSupported) {
            if (coherence < hybridConfig.highCoherenceThreshold()) {
                return HybridDecision.CPU;
            }
        }

        // Standard coherence-based decision
        if (coherence >= hybridConfig.highCoherenceThreshold()) {
            return HybridDecision.GPU_BATCH;
        } else if (coherence >= hybridConfig.lowCoherenceThreshold()) {
            return HybridDecision.GPU_SINGLE;
        } else {
            // Low coherence: prefer CPU if available
            return cpuSupported ? HybridDecision.CPU : HybridDecision.GPU_SINGLE;
        }
    }

    /**
     * Returns the hybrid configuration.
     */
    public HybridConfig getConfig() {
        return hybridConfig;
    }

    /**
     * Returns the tile configuration.
     */
    public TileConfiguration getTileConfig() {
        return tileConfig;
    }

    // Private implementation methods

    private List<Tile> partitionIntoTiles(Ray[] rays, int frameWidth, int frameHeight) {
        var tiles = new ArrayList<Tile>();

        for (int tileY = 0; tileY < tileConfig.tilesY(); tileY++) {
            for (int tileX = 0; tileX < tileConfig.tilesX(); tileX++) {
                int pixelX = tileX * tileConfig.tileWidth();
                int pixelY = tileY * tileConfig.tileHeight();

                int actualWidth = Math.min(tileConfig.tileWidth(), frameWidth - pixelX);
                int actualHeight = Math.min(tileConfig.tileHeight(), frameHeight - pixelY);

                int rayStartIndex = pixelY * frameWidth + pixelX;
                int rayCount = actualWidth * actualHeight;

                var tile = new Tile(tileX, tileY, rayStartIndex, rayCount, 0.5);
                tiles.add(tile);
            }
        }

        return tiles;
    }

    private void updateTileCoherence(List<Tile> tiles, Ray[] rays, int frameWidth, int frameHeight, DAGOctreeData dag) {
        for (int i = 0; i < tiles.size(); i++) {
            var tile = tiles.get(i);
            var tileRays = extractTileRays(tile, rays, frameWidth, frameHeight);
            double coherenceScore = coherenceAnalyzer.analyzeCoherence(tileRays, dag);

            var updatedTile = new Tile(tile.tileX(), tile.tileY(), tile.rayStartIndex(),
                                        tile.rayCount(), coherenceScore);
            tiles.set(i, updatedTile);
            coherenceMap.updateTileCoherence(tile.tileX(), tile.tileY(), coherenceScore);
        }
    }

    private Ray[] extractTileRays(Tile tile, Ray[] rays, int frameWidth, int frameHeight) {
        var tileRays = new Ray[tile.rayCount()];
        int destIndex = 0;

        int pixelX = tile.tileX() * tileConfig.tileWidth();
        int pixelY = tile.tileY() * tileConfig.tileHeight();
        int actualWidth = Math.min(tileConfig.tileWidth(), frameWidth - pixelX);
        int actualHeight = tile.rayCount() / actualWidth;

        for (int dy = 0; dy < actualHeight; dy++) {
            int scanlineStart = (pixelY + dy) * frameWidth + pixelX;
            System.arraycopy(rays, scanlineStart, tileRays, destIndex, actualWidth);
            destIndex += actualWidth;
        }

        return tileRays;
    }

    private int[] extractTileRayIndices(Tile tile, int frameWidth, int frameHeight) {
        var indices = new int[tile.rayCount()];
        int destIndex = 0;

        int pixelX = tile.tileX() * tileConfig.tileWidth();
        int pixelY = tile.tileY() * tileConfig.tileHeight();
        int actualWidth = Math.min(tileConfig.tileWidth(), frameWidth - pixelX);
        int actualHeight = tile.rayCount() / actualWidth;

        for (int dy = 0; dy < actualHeight; dy++) {
            int scanlineStart = (pixelY + dy) * frameWidth + pixelX;
            for (int dx = 0; dx < actualWidth; dx++) {
                indices[destIndex++] = scanlineStart + dx;
            }
        }

        return indices;
    }

    private void executeTileGPUBatch(Tile tile, Ray[] rays, int frameWidth, int frameHeight,
                                      DAGOctreeData dag, HybridKernelExecutor executor) {
        var rayIndices = extractTileRayIndices(tile, frameWidth, frameHeight);
        // Build BeamTree for batch optimization
        beamTreeFactory.buildBeamTree(rays, rayIndices, dag, tile.coherenceScore());
        executor.executeBatch(rays, rayIndices, hybridConfig.simdFactor());
    }

    private void executeTileGPUSingle(Tile tile, Ray[] rays, int frameWidth, int frameHeight,
                                       HybridKernelExecutor executor) {
        var rayIndices = extractTileRayIndices(tile, frameWidth, frameHeight);
        executor.executeSingleRay(rays, rayIndices);
    }

    private void executeTileCPU(Tile tile, Ray[] rays, int frameWidth, int frameHeight,
                                 HybridKernelExecutor executor) {
        var rayIndices = extractTileRayIndices(tile, frameWidth, frameHeight);
        executor.executeCPU(rays, rayIndices);
    }
}
