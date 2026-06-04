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

import com.hellblazer.luciferase.esvo.gpu.beam.Ray;
import com.hellblazer.luciferase.render.tile.TileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NodeReductionComparator.
 *
 * <p>Key invariants under test:
 * <ol>
 *   <li>tiledNodes is the SUM of actual per-tile BeamTree totalBeams() — never totalTiles, never globalNodes</li>
 *   <li>With N&gt;1 non-empty tiles, tiledNodes != globalNodes AND tiledNodes != totalTiles</li>
 *   <li>reductionRatio is computed from real measured node counts: 1 - (tiledNodes / globalNodes)</li>
 * </ol>
 */
class NodeReductionComparatorTest {

    private NodeReductionComparator comparator;
    // 32x32 = 1024 rays, 16-px tiles → 4 tiles per axis = 16 non-empty tiles
    private static final int FRAME_SIZE = 32;
    private static final int TILE_SIZE = 16;

    @BeforeEach
    void setUp() {
        var analyzer = new SimpleRayCoherenceAnalyzer();
        comparator = new NodeReductionComparator(analyzer);
    }

    /**
     * tiledNodes must be the sum of actual per-tile BeamTree totalBeams(), not totalTiles and not globalNodes.
     * With N&gt;1 high-coherence tiles, tiledNodes != globalNodes AND tiledNodes != totalTiles.
     */
    @Test
    void testTiledNodesIsActualPerTileBeamTreeSum() {
        var rays = createParallelRays(FRAME_SIZE, FRAME_SIZE);
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, TILE_SIZE);

        // Threshold below expected coherence so all tiles classified high-coherence
        var result = comparator.compare(rays, config, 0.1, FRAME_SIZE, FRAME_SIZE);

        int totalTiles = config.totalTiles();  // 16 tiles for 32x32 / 16

        assertTrue(result.globalNodes() > 0,
                   "Global BeamTree must have nodes");
        assertTrue(result.tiledNodes() > 0,
                   "Tiled sum must be positive");

        // The headline invariant: tiledNodes is NOT totalTiles and NOT globalNodes
        assertNotEquals(totalTiles, result.tiledNodes(),
                        "tiledNodes must be sum of BeamTree nodes, not a raw tile count");
        assertNotEquals(result.globalNodes(), result.tiledNodes(),
                        "tiledNodes must be sum of per-tile trees, not the global tree count");

        // Tile count partition: all non-empty tiles accounted for
        assertEquals(totalTiles, result.highCoherenceTiles() + result.lowCoherenceTiles(),
                     "highCoherenceTiles + lowCoherenceTiles must equal total non-empty tiles");
    }

    /**
     * reductionRatio must equal 1 - (tiledNodes / globalNodes) exactly.
     */
    @Test
    void testReductionRatioIsComputedFromRealNodeCounts() {
        var rays = createParallelRays(FRAME_SIZE, FRAME_SIZE);
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, TILE_SIZE);

        var result = comparator.compare(rays, config, 0.1, FRAME_SIZE, FRAME_SIZE);

        double expected = 1.0 - ((double) result.tiledNodes() / result.globalNodes());
        assertEquals(expected, result.reductionRatio(), 1e-9,
                     "reductionRatio must equal 1 - (tiledNodes / globalNodes)");
    }

    /**
     * Empty rays must produce all-zero result without building any trees.
     */
    @Test
    void testEmptyRaysProducesZeroResult() {
        var rays = new Ray[0];
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, TILE_SIZE);

        var result = comparator.compare(rays, config, 0.7, FRAME_SIZE, FRAME_SIZE);

        assertEquals(0, result.globalNodes(),        "Empty rays → 0 global nodes");
        assertEquals(0, result.tiledNodes(),         "Empty rays → 0 tiled nodes");
        assertEquals(0.0, result.reductionRatio(),   "Empty rays → 0.0 reduction ratio");
        assertEquals(0, result.highCoherenceTiles(), "Empty rays → 0 high-coherence tiles");
        assertEquals(0, result.lowCoherenceTiles(),  "Empty rays → 0 low-coherence tiles");
    }

    /**
     * Per-tile coherence classification must partition all non-empty tiles into
     * high or low coherence — the sum must equal the total tile count.
     */
    @Test
    void testTileCoherencePartitionIsExhaustive() {
        var parallelRays = createParallelRays(FRAME_SIZE, FRAME_SIZE);
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, TILE_SIZE);

        // Parallel rays have coherence ~1.0: threshold=0.0 → all tiles high-coherence
        var highResult = comparator.compare(parallelRays, config, 0.0, FRAME_SIZE, FRAME_SIZE);
        assertEquals(config.totalTiles(), highResult.highCoherenceTiles(),
                     "With threshold=0.0 all parallel-ray tiles should be high-coherence");
        assertEquals(0, highResult.lowCoherenceTiles(),
                     "With threshold=0.0 no tiles should be low-coherence");
        assertEquals(config.totalTiles(),
                     highResult.highCoherenceTiles() + highResult.lowCoherenceTiles(),
                     "high + low must equal total non-empty tiles");

        // Divergent rays with threshold=1.0 → all tiles below threshold (low-coherence)
        var divergentRays = createDivergentRays(FRAME_SIZE, FRAME_SIZE);
        var lowResult = comparator.compare(divergentRays, config, 1.0, FRAME_SIZE, FRAME_SIZE);
        assertEquals(config.totalTiles(), lowResult.lowCoherenceTiles(),
                     "With threshold=1.0 all divergent-ray tiles should be low-coherence");
        assertEquals(0, lowResult.highCoherenceTiles(),
                     "With threshold=1.0 no divergent-ray tiles should be high-coherence");
        assertEquals(config.totalTiles(),
                     lowResult.highCoherenceTiles() + lowResult.lowCoherenceTiles(),
                     "high + low must equal total non-empty tiles");
    }

    /**
     * Divergent rays must still produce a real tiledNodes sum (not totalTiles).
     */
    @Test
    void testDivergentRaysTiledNodesIsActualTreeSum() {
        var rays = createDivergentRays(FRAME_SIZE, FRAME_SIZE);
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, TILE_SIZE);

        var result = comparator.compare(rays, config, 0.9, FRAME_SIZE, FRAME_SIZE);

        int totalTiles = config.totalTiles();

        // tiledNodes is sum of actual BeamTree nodes, not the tile count
        assertNotEquals(totalTiles, result.tiledNodes(),
                        "tiledNodes must be actual BeamTree node sum, not raw tile count even for divergent rays");
        assertTrue(result.tiledNodes() > 0,
                   "Divergent rays still produce real BeamTree nodes per tile");
    }

    // Helper methods

    private Ray[] createParallelRays(int width, int height) {
        var rays = new Ray[width * height];
        var direction = new Vector3f(0, 0, 1);  // All rays parallel
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float screenX = 2.0f * x / width - 1.0f;
                float screenY = 1.0f - 2.0f * y / height;
                rays[index++] = new Ray(new Point3f(screenX, screenY, 0), direction);
            }
        }
        return rays;
    }

    private Ray[] createDivergentRays(int width, int height) {
        var rays = new Ray[width * height];
        var random = new java.util.Random(42);  // Fixed seed for reproducibility
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float screenX = 2.0f * x / width - 1.0f;
                float screenY = 1.0f - 2.0f * y / height;
                var origin = new Point3f(screenX, screenY, 0);

                var direction = new Vector3f(
                    random.nextFloat() * 2.0f - 1.0f,
                    random.nextFloat() * 2.0f - 1.0f,
                    random.nextFloat() * 2.0f - 1.0f
                );
                direction.normalize();

                rays[index++] = new Ray(origin, direction);
            }
        }
        return rays;
    }
}
