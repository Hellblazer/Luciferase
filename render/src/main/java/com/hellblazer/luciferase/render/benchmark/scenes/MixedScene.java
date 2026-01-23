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
import com.hellblazer.luciferase.render.benchmark.SceneGenerator;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Mixed-coherence test scene with configurable sky/geometry ratio.
 *
 * <p>Simulates a realistic rendering scenario where the upper portion of the frame
 * shows sky (parallel rays) and the lower portion shows complex geometry (divergent rays).
 * This is the PRIMARY TARGET for validating the 30% node reduction goal.
 *
 * <p><b>Default Configuration (60/40 split)</b>:
 * <ul>
 *   <li>Top 60% of frame: Sky (parallel rays)</li>
 *   <li>Bottom 40% of frame: Geometry (divergent rays)</li>
 * </ul>
 *
 * <p><b>Expected Behavior</b>:
 * <ul>
 *   <li>Average coherence: ~0.6</li>
 *   <li>Batch ratio: ~60%</li>
 *   <li>Node reduction: >= 30% (PRIMARY TARGET)</li>
 *   <li>60% tiles high-coherence, 40% low-coherence</li>
 *   <li>Combined node count <= 70% of global tree</li>
 * </ul>
 */
public class MixedScene extends SceneGenerator {

    private static final Vector3f SKY_DIRECTION = new Vector3f(0, 0, 1);  // Parallel rays for sky
    private static final Point3f GEOMETRY_CENTER = new Point3f(0.5f, 0.5f, 0.5f);  // Divergence point

    private final double skyRatio;  // Proportion of frame showing sky (0.0 to 1.0)

    /**
     * Create a mixed scene with specified frame dimensions and sky ratio.
     *
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @param skyRatio Proportion of frame (from top) showing sky (0.0 to 1.0)
     */
    public MixedScene(int frameWidth, int frameHeight, double skyRatio) {
        super(frameWidth, frameHeight);
        if (skyRatio < 0.0 || skyRatio > 1.0) {
            throw new IllegalArgumentException("skyRatio must be in [0.0, 1.0], got: " + skyRatio);
        }
        this.skyRatio = skyRatio;
    }

    @Override
    public Ray[] generateRays() {
        var rays = new Ray[totalRays];
        int skyHeightPixels = (int) (frameHeight * skyRatio);
        int index = 0;

        // Generate rays in row-major order (left-to-right, top-to-bottom)
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                if (y < skyHeightPixels) {
                    // Top portion: Sky (parallel rays)
                    rays[index++] = createRayAtPixel(x, y, SKY_DIRECTION);
                } else {
                    // Bottom portion: Geometry (divergent rays)
                    rays[index++] = createDivergentRay(x, y);
                }
            }
        }

        return rays;
    }

    @Override
    public double[] getExpectedCoherenceRange() {
        // Mixed scenes have moderate coherence (weighted by sky ratio)
        // Average coherence ≈ skyRatio * 1.0 + (1 - skyRatio) * 0.2
        double avgCoherence = skyRatio * 0.95 + (1.0 - skyRatio) * 0.25;
        return new double[]{avgCoherence - 0.2, avgCoherence + 0.2};
    }

    @Override
    public String getDescription() {
        return String.format("MixedScene: %dx%d frame, %.0f%% sky (parallel), %.0f%% geometry (divergent)",
                             frameWidth, frameHeight, skyRatio * 100, (1.0 - skyRatio) * 100);
    }

    /**
     * Get the configured sky ratio.
     */
    public double getSkyRatio() {
        return skyRatio;
    }

    /**
     * Create a divergent ray at the specified pixel position.
     */
    private Ray createDivergentRay(int x, int y) {
        // Map pixel to screen space
        float screenX = 2.0f * x / frameWidth - 1.0f;
        float screenY = 1.0f - 2.0f * y / frameHeight;

        // Ray origin at pixel position
        var origin = new Point3f(screenX, screenY, 0.0f);

        // Direction radiates outward from center
        var direction = new Vector3f(
            screenX - GEOMETRY_CENTER.x,
            screenY - GEOMETRY_CENTER.y,
            -GEOMETRY_CENTER.z
        );
        direction.normalize();

        return createRay(origin, direction);
    }
}
