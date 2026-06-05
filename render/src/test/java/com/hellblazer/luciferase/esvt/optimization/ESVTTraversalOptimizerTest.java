/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.optimization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTTraversalOptimizer.classifyDominantTetFace — verifies that all 6
 * axis-sign directions map to distinct, non-aliased face IDs (Luciferase-7wzml.166).
 *
 * <p>Face assignment convention (axis-sign → face id):
 * <ul>
 *   <li>+X → 0,  -X → 1  (X-dominant)</li>
 *   <li>+Y → 2,  -Y → 3  (Y-dominant)</li>
 *   <li>+Z → 2,  -Z → 3  (Z-dominant; shares Y faces deliberately, never aliases onto face 0)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class ESVTTraversalOptimizerTest {

    private ESVTTraversalOptimizer optimizer;
    private Method classifyMethod;

    @BeforeEach
    void setUp() throws Exception {
        optimizer = new ESVTTraversalOptimizer();
        classifyMethod = ESVTTraversalOptimizer.class.getDeclaredMethod("classifyDominantTetFace", Vector3f.class);
        classifyMethod.setAccessible(true);
    }

    private int classify(float x, float y, float z) throws Exception {
        return (int) classifyMethod.invoke(optimizer, new Vector3f(x, y, z));
    }

    // --- All 6 axis-sign directions ---

    @Test
    void testPositiveXDominant() throws Exception {
        assertEquals(0, classify(1.0f, 0.0f, 0.0f), "+X must map to face 0");
    }

    @Test
    void testNegativeXDominant() throws Exception {
        assertEquals(1, classify(-1.0f, 0.0f, 0.0f), "-X must map to face 1");
    }

    @Test
    void testPositiveYDominant() throws Exception {
        assertEquals(2, classify(0.0f, 1.0f, 0.0f), "+Y must map to face 2");
    }

    @Test
    void testNegativeYDominant() throws Exception {
        assertEquals(3, classify(0.0f, -1.0f, 0.0f), "-Y must map to face 3");
    }

    @Test
    void testPositiveZDominant() throws Exception {
        int face = classify(0.0f, 0.0f, 1.0f);
        assertNotEquals(0, face, "+Z must NOT alias onto face 0 (+X bucket)");
        assertNotEquals(1, face, "+Z must NOT alias onto face 1 (-X bucket)");
        assertEquals(2, face, "+Z must map to face 2");
    }

    @Test
    void testNegativeZDominant() throws Exception {
        int face = classify(0.0f, 0.0f, -1.0f);
        assertNotEquals(0, face, "-Z must NOT alias onto face 0 (+X bucket)");
        assertNotEquals(1, face, "-Z must NOT alias onto face 1 (-X bucket)");
        assertEquals(3, face, "-Z must map to face 3");
    }

    // --- Key constraint: +Z and -Z are distinct from the +X bucket ---

    @Test
    void testZAxisNotAliasedOntoPlusXBucket() throws Exception {
        int plusX  = classify(1.0f, 0.0f, 0.0f);
        int plusZ  = classify(0.0f, 0.0f, 1.0f);
        int minusZ = classify(0.0f, 0.0f, -1.0f);

        assertNotEquals(plusX, plusZ,  "+Z must not alias onto +X face (" + plusX + ")");
        assertNotEquals(plusX, minusZ, "-Z must not alias onto +X face (" + plusX + ")");
    }

    // --- All 6 directions must be total (valid face 0-3) ---

    @Test
    void testAllSixDirectionsReturnValidFace() throws Exception {
        float[][] axisDirections = {
            { 1,  0,  0},  // +X
            {-1,  0,  0},  // -X
            { 0,  1,  0},  // +Y
            { 0, -1,  0},  // -Y
            { 0,  0,  1},  // +Z
            { 0,  0, -1},  // -Z
        };

        for (float[] dir : axisDirections) {
            int face = classify(dir[0], dir[1], dir[2]);
            assertTrue(face >= 0 && face <= 3,
                "Direction (" + dir[0] + "," + dir[1] + "," + dir[2] + ") returned out-of-range face: " + face);
        }
    }

    // --- Z-dominant rays produce high tet face coherence (acceptance criterion 3) ---

    @Test
    void testZDominantRaySetProducesHighCoherence() {
        // All rays are +Z dominant — they should map to the same face and report high coherence
        var origins = new Vector3f[]{
            new Vector3f(0.0f, 0.0f, 0.0f),
            new Vector3f(0.01f, 0.0f, 0.0f),
            new Vector3f(0.02f, 0.0f, 0.0f),
            new Vector3f(0.0f, 0.01f, 0.0f),
        };
        var directions = new Vector3f[]{
            new Vector3f(0.0f, 0.0f, 1.0f),
            new Vector3f(0.0f, 0.0f, 1.0f),
            new Vector3f(0.01f, 0.0f, 1.0f),  // still Z-dominant
            new Vector3f(0.0f, 0.01f, 1.0f),  // still Z-dominant
        };

        var coherence = optimizer.analyzeRayCoherence(origins, directions);
        // Tet face coherence should be high since all directions bucket to the same Z face
        assertTrue(coherence.getTetFaceCoherence() >= 0.75f,
            "Z-dominant rays should yield high tet face coherence, got: " + coherence.getTetFaceCoherence());
    }

    // --- Non-trivial direction (diagonal, Z still dominant) ---

    @Test
    void testDiagonalZDominantNotAliasedOntoPlusX() throws Exception {
        // z > x, z > y → Z dominant
        int face = classify(0.3f, 0.2f, 0.9f);
        assertNotEquals(0, face, "Diagonal Z-dominant direction must not return face 0 (+X bucket)");
    }
}
