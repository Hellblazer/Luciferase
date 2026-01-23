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
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.render.tile.TileBasedDispatcher;

import javax.vecmath.Vector3f;

/**
 * Spatial-only coherence analyzer for Ray[] arrays.
 * Implements TileBasedDispatcher.CoherenceAnalyzer interface.
 *
 * <p><b>Design Decision (DD-1)</b>: Uses direction similarity only, without DAG traversal.
 * This approach matches the spatial-only aspect of BeamTreeBuilder.computeCoherence() and
 * avoids the type incompatibility with RayCoherenceAnalyzer (which uses ESVORay[]).
 *
 * <p><b>Algorithm</b>: Average dot product between reference ray direction and all other rays.
 * Returns value in [0.0, 1.0] where 1.0 = perfectly coherent (parallel rays).
 *
 * <p><b>Rationale for Spatial-Only Analysis</b>:
 * <ul>
 *   <li>Type Compatibility: TileBasedDispatcher.CoherenceAnalyzer expects Ray[], not ESVORay[]</li>
 *   <li>Performance: Avoids DAG traversal overhead, which is unnecessary for tile-level coherence</li>
 *   <li>Simplicity: Direction similarity alone is sufficient for tile partitioning decisions</li>
 *   <li>Precedent: BeamTreeBuilder.computeCoherence() uses similar directional analysis</li>
 * </ul>
 *
 * @see TileBasedDispatcher.CoherenceAnalyzer
 * @see com.hellblazer.luciferase.esvo.gpu.beam.BeamTreeBuilder#computeCoherence(int[])
 */
public class SimpleRayCoherenceAnalyzer implements TileBasedDispatcher.CoherenceAnalyzer {

    /**
     * Analyzes coherence of a ray array using direction-only similarity.
     *
     * <p>For a set of rays, this computes the average absolute dot product
     * between the first ray's direction and all other ray directions. Higher
     * values indicate more coherent (parallel) rays.
     *
     * @param rays Ray array to analyze (from beam package, not ESVORay)
     * @param dag DAG octree data (ignored - spatial-only analysis doesn't require DAG)
     * @return Coherence score in [0.0, 1.0] where:
     *         - 1.0 = perfectly parallel rays
     *         - 0.0 = perpendicular rays
     *         - 0.5 = default for single ray (conservative estimate)
     */
    @Override
    public double analyzeCoherence(Ray[] rays, DAGOctreeData dag) {
        // DAG parameter ignored - this is spatial-only analysis

        if (rays == null || rays.length == 0) {
            // No rays: return neutral coherence
            return 0.5;
        }

        if (rays.length == 1) {
            // Single ray: default to moderate coherence (conservative)
            return 0.5;
        }

        // Use first ray as reference (normalize for correct dot product)
        var refDir = new Vector3f(rays[0].direction());
        refDir.normalize();

        // Compute average directional similarity
        double totalSimilarity = 0.0;
        for (int i = 1; i < rays.length; i++) {
            var direction = new Vector3f(rays[i].direction());
            direction.normalize();

            // Directional similarity: absolute value of dot product
            // abs() handles both forward and backward parallel rays
            totalSimilarity += Math.abs(refDir.dot(direction));
        }

        // Return average similarity
        return totalSimilarity / (rays.length - 1);
    }
}
