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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimpleRayCoherenceAnalyzer.
 */
class SimpleRayCoherenceAnalyzerTest {

    private SimpleRayCoherenceAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SimpleRayCoherenceAnalyzer();
    }

    @Test
    void testParallelRaysMaxCoherence() {
        // All rays pointing in same direction
        var rays = new Ray[10];
        var direction = new Vector3f(0, 0, 1);
        for (int i = 0; i < rays.length; i++) {
            rays[i] = new Ray(new Point3f(i, 0, 0), direction);
        }

        double coherence = analyzer.analyzeCoherence(rays, null);

        // Parallel rays should have coherence = 1.0
        assertEquals(1.0, coherence, 0.01, "Parallel rays should have coherence ~1.0");
    }

    @Test
    void testPerpendicularRaysMinCoherence() {
        // Rays pointing in perpendicular directions
        var rays = new Ray[2];
        rays[0] = new Ray(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));  // X-axis
        rays[1] = new Ray(new Point3f(0, 0, 0), new Vector3f(0, 1, 0));  // Y-axis

        double coherence = analyzer.analyzeCoherence(rays, null);

        // Perpendicular rays: dot product = 0
        assertEquals(0.0, coherence, 0.01, "Perpendicular rays should have coherence ~0.0");
    }

    @Test
    void testMixedDirectionsModerateCoherence() {
        // Mix of parallel and slightly divergent rays
        var rays = new Ray[5];
        rays[0] = new Ray(new Point3f(0, 0, 0), new Vector3f(0, 0, 1));     // Reference
        rays[1] = new Ray(new Point3f(1, 0, 0), new Vector3f(0, 0, 1));     // Parallel
        rays[2] = new Ray(new Point3f(2, 0, 0), new Vector3f(0.1f, 0, 1));  // Slightly off
        rays[3] = new Ray(new Point3f(3, 0, 0), new Vector3f(0.2f, 0, 1));  // More off
        rays[4] = new Ray(new Point3f(4, 0, 0), new Vector3f(0.3f, 0, 1));  // Even more off

        double coherence = analyzer.analyzeCoherence(rays, null);

        // Should be moderate coherence (between 0 and 1)
        assertTrue(coherence > 0.5 && coherence < 1.0,
                   "Mixed directions should have moderate coherence: " + coherence);
    }

    @Test
    void testEmptyRaysArray() {
        var rays = new Ray[0];

        double coherence = analyzer.analyzeCoherence(rays, null);

        // Empty array should return neutral coherence
        assertEquals(0.5, coherence, 0.01, "Empty ray array should return neutral coherence");
    }

    @Test
    void testSingleRayDefaultCoherence() {
        var rays = new Ray[1];
        rays[0] = new Ray(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));

        double coherence = analyzer.analyzeCoherence(rays, null);

        // Single ray: conservative default
        assertEquals(0.5, coherence, 0.01, "Single ray should return default coherence of 0.5");
    }

    @Test
    void testNullRaysArray() {
        double coherence = analyzer.analyzeCoherence(null, null);

        // Null array should return neutral coherence
        assertEquals(0.5, coherence, 0.01, "Null ray array should return neutral coherence");
    }
}
