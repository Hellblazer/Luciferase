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

import javax.vecmath.Vector3f;

/**
 * High-coherence test scene with all parallel rays.
 *
 * <p>Simulates a sky-only rendering scenario where all rays point in the same direction.
 * This represents the best-case scenario for tile-based adaptive execution, where batch
 * kernel utilization is maximized.
 *
 * <p><b>Expected Behavior</b>:
 * <ul>
 *   <li>Coherence score: >= 0.9 for all tiles</li>
 *   <li>Batch ratio: ~100%</li>
 *   <li>Node reduction: Maximum achievable (40-50%)</li>
 *   <li>All tiles routed to batch kernel</li>
 * </ul>
 */
public class SkyScene extends SceneGenerator {

    private static final Vector3f SKY_DIRECTION = new Vector3f(0, 0, 1);  // All rays parallel, pointing +Z

    /**
     * Create a sky scene with specified frame dimensions.
     *
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     */
    public SkyScene(int frameWidth, int frameHeight) {
        super(frameWidth, frameHeight);
    }

    @Override
    public Ray[] generateRays() {
        var rays = new Ray[totalRays];
        int index = 0;

        // Generate rays in row-major order (left-to-right, top-to-bottom)
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                // All rays have same direction (parallel)
                rays[index++] = createRayAtPixel(x, y, SKY_DIRECTION);
            }
        }

        return rays;
    }

    @Override
    public double[] getExpectedCoherenceRange() {
        // Sky scenes have maximum coherence (all parallel rays)
        return new double[]{0.9, 1.0};
    }

    @Override
    public String getDescription() {
        return String.format("SkyScene: %dx%d frame, all rays parallel (direction: %s)",
                             frameWidth, frameHeight, SKY_DIRECTION);
    }
}
