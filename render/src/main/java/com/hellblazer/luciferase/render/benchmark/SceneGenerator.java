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
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Abstract base class for test scene generators.
 *
 * <p>Scene generators create synthetic ray arrays for benchmarking tile-based
 * adaptive execution. Different scenes test different coherence patterns:
 * <ul>
 *   <li>High coherence: Sky-only scenes with parallel rays</li>
 *   <li>Low coherence: Complex geometry with divergent rays</li>
 *   <li>Mixed coherence: Combination of sky and geometry</li>
 *   <li>Cache validation: Multi-frame rendering with camera movement</li>
 *   <li>Stress testing: Large frame sizes (4K)</li>
 * </ul>
 *
 * @see SkyScene
 * @see GeometryScene
 * @see MixedScene
 */
public abstract class SceneGenerator {

    protected final int frameWidth;
    protected final int frameHeight;
    protected final int totalRays;

    /**
     * Create a scene generator for the given frame dimensions.
     *
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     */
    protected SceneGenerator(int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("Frame dimensions must be positive");
        }
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.totalRays = frameWidth * frameHeight;
    }

    /**
     * Generate rays for this scene.
     *
     * <p>Rays are ordered row-major (left-to-right, top-to-bottom) to match
     * pixel positions for tile partitioning.
     *
     * @return Ray array with one ray per pixel
     */
    public abstract Ray[] generateRays();

    /**
     * Get the expected coherence range for this scene.
     *
     * @return Expected coherence range [min, max]
     */
    public abstract double[] getExpectedCoherenceRange();

    /**
     * Get a description of this scene.
     *
     * @return Scene description
     */
    public abstract String getDescription();

    /**
     * Get frame width.
     */
    public int getFrameWidth() {
        return frameWidth;
    }

    /**
     * Get frame height.
     */
    public int getFrameHeight() {
        return frameHeight;
    }

    /**
     * Get total number of rays.
     */
    public int getTotalRays() {
        return totalRays;
    }

    /**
     * Create a ray from origin and direction.
     *
     * @param origin Ray origin
     * @param direction Ray direction (should be normalized)
     * @return New ray
     */
    protected Ray createRay(Point3f origin, Vector3f direction) {
        // Normalize direction to ensure consistent dot product results
        var normalized = new Vector3f(direction);
        normalized.normalize();
        return new Ray(origin, normalized);
    }

    /**
     * Create a ray from pixel coordinates with specified direction.
     *
     * @param x Pixel X coordinate
     * @param y Pixel Y coordinate
     * @param direction Ray direction
     * @return New ray with origin at pixel position
     */
    protected Ray createRayAtPixel(int x, int y, Vector3f direction) {
        // Map pixel to normalized screen space [-1, 1]
        float screenX = 2.0f * x / frameWidth - 1.0f;
        float screenY = 1.0f - 2.0f * y / frameHeight;  // Flip Y (top-to-bottom)

        var origin = new Point3f(screenX, screenY, 0.0f);
        return createRay(origin, direction);
    }
}
