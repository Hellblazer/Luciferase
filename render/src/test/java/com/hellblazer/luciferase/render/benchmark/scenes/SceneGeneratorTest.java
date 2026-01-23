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

package com.hellblazer.luciferase.render.benchmark.scenes;

import com.hellblazer.luciferase.esvo.gpu.beam.Ray;
import com.hellblazer.luciferase.render.benchmark.SimpleRayCoherenceAnalyzer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for all scene generators.
 * Validates that each scene produces rays with expected coherence properties.
 */
class SceneGeneratorTest {

    private static final int FRAME_WIDTH = 256;
    private static final int FRAME_HEIGHT = 256;
    private static final double EPSILON = 0.1;
    private final SimpleRayCoherenceAnalyzer analyzer = new SimpleRayCoherenceAnalyzer();

    // SkyScene Tests

    @Test
    void testSkySceneCoherence() {
        var scene = new SkyScene(FRAME_WIDTH, FRAME_HEIGHT);
        var rays = scene.generateRays();

        double coherence = analyzer.analyzeCoherence(rays, null);

        assertTrue(coherence >= 0.9, "Sky scene should have very high coherence (>= 0.9), got: " + coherence);
    }

    @Test
    void testSkySceneRayCount() {
        var scene = new SkyScene(FRAME_WIDTH, FRAME_HEIGHT);
        var rays = scene.generateRays();

        assertEquals(FRAME_WIDTH * FRAME_HEIGHT, rays.length,
                     "Sky scene should generate one ray per pixel");
    }

    // GeometryScene Tests

    @Test
    void testGeometrySceneCoherence() {
        var scene = new GeometryScene(FRAME_WIDTH, FRAME_HEIGHT);
        var rays = scene.generateRays();

        double coherence = analyzer.analyzeCoherence(rays, null);

        assertTrue(coherence <= 0.4, "Geometry scene should have low coherence (<= 0.4), got: " + coherence);
    }

    @Test
    void testGeometrySceneRayCount() {
        var scene = new GeometryScene(FRAME_WIDTH, FRAME_HEIGHT);
        var rays = scene.generateRays();

        assertEquals(FRAME_WIDTH * FRAME_HEIGHT, rays.length,
                     "Geometry scene should generate one ray per pixel");
    }

    // MixedScene Tests

    @Test
    void testMixedSceneCoherence60() {
        // 60% sky, 40% geometry (default)
        var scene = new MixedScene(FRAME_WIDTH, FRAME_HEIGHT, 0.6);
        var rays = scene.generateRays();

        double coherence = analyzer.analyzeCoherence(rays, null);

        // Expected: ~0.6 (between sky and geometry)
        assertTrue(coherence >= 0.45 && coherence <= 0.75,
                   "Mixed scene (60/40) should have moderate coherence, got: " + coherence);
    }

    @Test
    void testMixedSceneRayCount() {
        var scene = new MixedScene(FRAME_WIDTH, FRAME_HEIGHT, 0.6);
        var rays = scene.generateRays();

        assertEquals(FRAME_WIDTH * FRAME_HEIGHT, rays.length,
                     "Mixed scene should generate one ray per pixel");
    }

    // CameraMovementScene Tests

    @Test
    void testCameraMovementSceneFrameCount() {
        var scene = new CameraMovementScene(FRAME_WIDTH, FRAME_HEIGHT);
        var frames = scene.generateFrames();

        assertEquals(10, frames.size(), "Should generate 10 frames");
    }

    @Test
    void testCameraMovementSceneRaysPerFrame() {
        var scene = new CameraMovementScene(FRAME_WIDTH, FRAME_HEIGHT);
        var frames = scene.generateFrames();

        for (var frame : frames) {
            assertEquals(FRAME_WIDTH * FRAME_HEIGHT, frame.length,
                         "Each frame should have one ray per pixel");
        }
    }

    // LargeFrameScene Tests

    @Test
    void testLargeFrameSceneTileCount() {
        // 4K: 3840x2160 with 16x16 tiles = 240x135 = 32,400 tiles
        var scene = new LargeFrameScene(3840, 2160);
        var rays = scene.generateRays();

        assertEquals(3840 * 2160, rays.length, "4K should generate 3840x2160 rays");
    }

    @Test
    void testLargeFrameSceneRayValidation() {
        var scene = new LargeFrameScene(3840, 2160);
        var rays = scene.generateRays();

        assertNotNull(rays, "Rays should not be null");
        assertTrue(rays.length > 0, "Should have rays");

        // Validate no null rays
        for (var ray : rays) {
            assertNotNull(ray, "No ray should be null");
            assertNotNull(ray.origin(), "Ray origin should not be null");
            assertNotNull(ray.direction(), "Ray direction should not be null");
        }
    }
}
