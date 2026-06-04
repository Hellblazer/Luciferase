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

import com.hellblazer.luciferase.esvo.gpu.beam.BeamTreeBuilder;
import com.hellblazer.luciferase.esvo.gpu.beam.Ray;
import com.hellblazer.luciferase.render.tile.TileBasedDispatcher;
import com.hellblazer.luciferase.render.tile.TileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares node reduction between global and tile-based BeamTree approaches.
 *
 * <p><b>Node Reduction Definition</b>: The percentage reduction in total BeamTree nodes
 * when using tile-based adaptive execution versus a single global BeamTree.
 *
 * <p><b>Measurement Methodology</b>:
 * <ul>
 *   <li>Baseline (Global): Build ONE BeamTree for ALL rays in the frame</li>
 *   <li>Tiled (Adaptive): Build one BeamTree per non-empty tile, sum totalBeams() across all tiles</li>
 *   <li>Reduction: 1 - (tiled_nodes / global_nodes)</li>
 * </ul>
 *
 * @see BeamTreeBuilder
 * @see TileBasedDispatcher
 */
public class NodeReductionComparator {

    private final TileBasedDispatcher.CoherenceAnalyzer coherenceAnalyzer;

    public NodeReductionComparator(TileBasedDispatcher.CoherenceAnalyzer coherenceAnalyzer) {
        this.coherenceAnalyzer = coherenceAnalyzer;
    }

    /**
     * Comparison result between global and tiled approaches.
     *
     * @param globalNodes Number of nodes in single global BeamTree
     * @param tiledNodes Sum of actual per-tile BeamTree totalBeams() counts over all non-empty tiles
     * @param reductionRatio Node reduction: 1 - (tiledNodes / globalNodes)
     * @param highCoherenceTiles Number of tiles whose per-tile coherence meets the threshold
     * @param lowCoherenceTiles Number of tiles whose per-tile coherence is below the threshold
     */
    public record ComparisonResult(
        int globalNodes,
        int tiledNodes,
        double reductionRatio,
        int highCoherenceTiles,
        int lowCoherenceTiles
    ) {}

    /**
     * Compare global vs tiled BeamTree node counts.
     *
     * @param rays All rays in the frame
     * @param config Tile configuration
     * @param coherenceThreshold Threshold for high/low coherence classification
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @return Comparison result with node counts and reduction ratio
     */
    public ComparisonResult compare(Ray[] rays, TileConfiguration config, double coherenceThreshold,
                                     int frameWidth, int frameHeight) {
        if (rays == null || rays.length == 0) {
            return new ComparisonResult(0, 0, 0.0, 0, 0);
        }

        // Build global BeamTree from all rays (baseline)
        var globalTree = BeamTreeBuilder.from(rays).build();
        var globalStats = globalTree.getStatistics();
        int globalNodes = globalStats.totalBeams();

        // Build per-tile BeamTrees and sum their actual node counts
        var tileRayGroups = partitionRaysIntoTiles(rays, config, frameWidth, frameHeight);

        int tiledNodes = 0;
        int highCoherenceTiles = 0;
        int lowCoherenceTiles = 0;

        for (var group : tileRayGroups) {
            if (group.isEmpty()) {
                continue;
            }
            var tileRays = group.toArray(new Ray[0]);
            var tileTree = BeamTreeBuilder.from(tileRays).build();
            tiledNodes += tileTree.getStatistics().totalBeams();

            double tileCoherence = coherenceAnalyzer.analyzeCoherence(tileRays, null);
            if (tileCoherence >= coherenceThreshold) {
                highCoherenceTiles++;
            } else {
                lowCoherenceTiles++;
            }
        }

        // Calculate reduction ratio from real measurements
        double reductionRatio = globalNodes > 0 ? 1.0 - ((double) tiledNodes / globalNodes) : 0.0;

        return new ComparisonResult(globalNodes, tiledNodes, reductionRatio, highCoherenceTiles, lowCoherenceTiles);
    }

    /**
     * Partition rays into tiles based on pixel position.
     *
     * <p>Assumes rays are ordered row-major (left-to-right, top-to-bottom) matching pixel positions.
     *
     * @param rays All rays in the frame
     * @param config Tile configuration
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @return List of ray groups, one per tile
     */
    private List<List<Ray>> partitionRaysIntoTiles(Ray[] rays, TileConfiguration config,
                                                     int frameWidth, int frameHeight) {
        // Initialize tile groups
        var tileGroups = new ArrayList<List<Ray>>(config.totalTiles());
        for (int i = 0; i < config.totalTiles(); i++) {
            tileGroups.add(new ArrayList<>());
        }

        // Partition rays into tiles
        int rayIndex = 0;
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                if (rayIndex >= rays.length) {
                    break;
                }

                // Determine which tile this pixel belongs to
                int tileX = x / config.tileWidth();
                int tileY = y / config.tileHeight();
                int tileIndex = tileY * config.tilesX() + tileX;

                tileGroups.get(tileIndex).add(rays[rayIndex]);
                rayIndex++;
            }
        }

        return tileGroups;
    }
}
