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
 */
class NodeReductionComparatorTest {

    private NodeReductionComparator comparator;
    private static final int FRAME_SIZE = 64;  // 64x64 = 4096 rays for quick tests

    @BeforeEach
    void setUp() {
        var analyzer = new SimpleRayCoherenceAnalyzer();
        comparator = new NodeReductionComparator(analyzer);
    }

    @Test
    void testGlobalVsTiledComparison() {
        // Create high-coherence rays (all parallel)
        var rays = createParallelRays(FRAME_SIZE, FRAME_SIZE);
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, 16);

        var result = comparator.compare(rays, config, 0.7, FRAME_SIZE, FRAME_SIZE);

        // Verify basic result structure
        assertTrue(result.globalNodes() > 0, "Global tree should have nodes");
        assertTrue(result.tiledNodes() > 0, "Tiled approach should have nodes");
        assertTrue(result.reductionRatio() >= 0.0, "Reduction ratio should be non-negative");
    }

    @Test
    void testVirtualNodeCounting() {
        // Create low-coherence rays (divergent)
        var rays = createDivergentRays(FRAME_SIZE, FRAME_SIZE);
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, 16);

        // Use high threshold so all tiles are low-coherence
        var result = comparator.compare(rays, config, 0.9, FRAME_SIZE, FRAME_SIZE);

        // All tiles should be low-coherence (virtual nodes)
        assertEquals(config.totalTiles(), result.lowCoherenceTiles(),
                     "All tiles should be low-coherence with high threshold");

        // Tiled nodes should equal number of low-coherence tiles (1 virtual node each)
        assertTrue(result.tiledNodes() >= result.lowCoherenceTiles(),
                   "Tiled nodes should include at least 1 virtual node per low-coherence tile");
    }

    @Test
    void testReductionRatioCalculation() {
        // Create parallel rays (high coherence)
        var rays = createParallelRays(FRAME_SIZE, FRAME_SIZE);
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, 16);

        var result = comparator.compare(rays, config, 0.7, FRAME_SIZE, FRAME_SIZE);

        // Reduction ratio formula: 1 - (tiled / global)
        double expectedRatio = 1.0 - ((double) result.tiledNodes() / result.globalNodes());
        assertEquals(expectedRatio, result.reductionRatio(), 0.0001,
                     "Reduction ratio should match formula: 1 - (tiled / global)");
    }

    @Test
    void testHighCoherenceTileCount() {
        // Create parallel rays (high coherence)
        var rays = createParallelRays(FRAME_SIZE, FRAME_SIZE);
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, 16);

        // Use low threshold so all tiles are high-coherence
        var result = comparator.compare(rays, config, 0.5, FRAME_SIZE, FRAME_SIZE);

        // Most or all tiles should be high-coherence
        assertTrue(result.highCoherenceTiles() > 0,
                   "Parallel rays should produce high-coherence tiles");
        assertEquals(config.totalTiles(), result.highCoherenceTiles() + result.lowCoherenceTiles(),
                     "Total tiles should equal high + low coherence tiles");
    }

    @Test
    void testZeroNodeReduction() {
        // Create empty ray array
        var rays = new Ray[0];
        var config = TileConfiguration.from(FRAME_SIZE, FRAME_SIZE, 16);

        var result = comparator.compare(rays, config, 0.7, FRAME_SIZE, FRAME_SIZE);

        // Empty input should produce zero results
        assertEquals(0, result.globalNodes(), "Empty rays should have 0 global nodes");
        assertEquals(0, result.tiledNodes(), "Empty rays should have 0 tiled nodes");
        assertEquals(0.0, result.reductionRatio(), "Empty rays should have 0.0 reduction ratio");
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

                // Truly random direction for maximum divergence
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
