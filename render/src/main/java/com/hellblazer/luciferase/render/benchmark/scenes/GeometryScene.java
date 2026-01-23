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
 * Low-coherence test scene with divergent rays.
 *
 * <p>Simulates complex geometry rendering where rays diverge in all directions from
 * a central point. This represents the worst-case scenario for tile-based adaptive
 * execution, where most tiles fall below the coherence threshold and use single-ray kernel.
 *
 * <p><b>Expected Behavior</b>:
 * <ul>
 *   <li>Coherence score: <= 0.4 for all tiles</li>
 *   <li>Batch ratio: ~0%</li>
 *   <li>Node reduction: Minimal (0-10%)</li>
 *   <li>All tiles routed to single-ray kernel</li>
 *   <li>Total "nodes" = number of tiles (one virtual node per tile)</li>
 * </ul>
 */
public class GeometryScene extends SceneGenerator {

    private static final Point3f CENTER = new Point3f(0.5f, 0.5f, 0.5f);  // Central divergence point

    /**
     * Create a geometry scene with specified frame dimensions.
     *
     * @param frameWidth Frame width in pixels
     * @param frameHeight Frame height in pixels
     */
    public GeometryScene(int frameWidth, int frameHeight) {
        super(frameWidth, frameHeight);
    }

    @Override
    public Ray[] generateRays() {
        var rays = new Ray[totalRays];
        int index = 0;

        // Generate rays in row-major order (left-to-right, top-to-bottom)
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                // Map pixel to screen space
                float screenX = 2.0f * x / frameWidth - 1.0f;
                float screenY = 1.0f - 2.0f * y / frameHeight;

                // Ray origin at pixel position
                var origin = new Point3f(screenX, screenY, 0.0f);

                // Generate low-coherence rays using sin/cos with varied frequencies
                // This creates diverse ray directions that vary across the frame
                float angle1 = (float) (x * Math.PI / frameWidth);
                float angle2 = (float) (y * Math.PI / frameHeight);
                float angle3 = (float) ((x + y) * Math.PI / (frameWidth + frameHeight));

                var direction = new Vector3f(
                    (float) Math.sin(angle1) * (float) Math.cos(angle2),
                    (float) Math.cos(angle1) * (float) Math.sin(angle2),
                    (float) Math.sin(angle3) - 0.5f
                );
                direction.normalize();

                rays[index++] = createRay(origin, direction);
            }
        }

        return rays;
    }

    @Override
    public double[] getExpectedCoherenceRange() {
        // Geometry scenes have low coherence (divergent rays)
        return new double[]{0.0, 0.4};
    }

    @Override
    public String getDescription() {
        return String.format("GeometryScene: %dx%d frame, rays radiating from center point %s",
                             frameWidth, frameHeight, CENTER);
    }
}
