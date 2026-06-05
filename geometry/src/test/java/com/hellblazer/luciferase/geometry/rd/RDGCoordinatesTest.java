/**
 * Copyright (C) 2023 Hal Hildebrand. All rights reserved.
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.geometry.rd;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;

import java.util.Arrays;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Coverage for the UI-free RD coordinate kernel extracted from {@code portal.Tetrahedral} (RDR-006).
 * Validates that the extraction preserves the verified math (round-trip identity, metric-tensor dot,
 * neighbor shells) — i.e. the lift did not introduce a regression.
 *
 * @author hal.hildebrand
 */
class RDGCoordinatesTest {

    /**
     * toRDG(toCartesian(p)) == p for integer points — the round-trip identity that Luciferase-6oa's
     * Math.round fix established (C-style truncation broke it for unit-magnitude points).
     * Covers [-3, 3] exhaustively and spot-checks at ±1000 to verify the double-precision
     * intermediate arithmetic introduced in Luciferase-7wzml.82 holds at larger magnitudes
     * (float-only arithmetic broke the identity around |coord| > ~1000).
     */
    @Test
    void roundTripIdentityForIntegerPoints() {
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    var rdg = new Point3i(x, y, z);
                    var cart = RDGCoordinates.toCartesian(rdg);
                    var back = RDGCoordinates.toRDG(new Point3f((float) cart.x, (float) cart.y, (float) cart.z));
                    assertThat(back).as("round-trip for (%d,%d,%d)", x, y, z).isEqualTo(rdg);
                }
            }
        }

        // Spot-check at large magnitudes: float intermediate would lose precision here
        int[] large = { -1000, -100, -10, 10, 100, 1000 };
        for (int x : large) {
            for (int y : large) {
                for (int z : large) {
                    var rdg = new Point3i(x, y, z);
                    var cart = RDGCoordinates.toCartesian(rdg);
                    var back = RDGCoordinates.toRDG(new Point3f((float) cart.x, (float) cart.y, (float) cart.z));
                    assertThat(back).as("round-trip for (%d,%d,%d)", x, y, z).isEqualTo(rdg);
                }
            }
        }
    }

    /**
     * Luciferase-7wzml.82: toRDG must sum the (±x±y±z) terms in double precision, not float. This pins the
     * fix with an adversarial large-magnitude input where the OLD float-sum arithmetic rounds to a different
     * integer than the double-sum arithmetic. The test is self-proving: it computes both paths inline and
     * asserts (a) toRDG matches the double-precision reference and (b) the float-sum path genuinely diverges
     * (so the input actually witnesses the bug, not a vacuous pass). Removing the double-promotion in toRDG
     * makes assertion (a) fail.
     */
    @Test
    void toRDGSumsInDoublePrecisionAtLargeMagnitude() {
        // Float-exact integer inputs whose float-summed (±x±y±z) terms round to a different integer than
        // the double-summed terms (found by float32 simulation; the middle component diverges by 1).
        float cx = 13727664f, cy = -4524454f, cz = 22930328f;
        double k = RDGCoordinates.DIVIDE_ROOT_2;

        // Double-sum reference (what the fixed toRDG must produce):
        var expected = new Point3i((int) Math.round((-(double) cx + cy + cz) * k),
                                   (int) Math.round(((double) cx - cy + cz) * k),
                                   (int) Math.round(((double) cx + cy - cz) * k));
        // Old float-sum path: cx/cy/cz are float, so (-cx + cy + cz) is evaluated in float before scaling.
        var floatSum = new Point3i((int) Math.round((-cx + cy + cz) * k),
                                   (int) Math.round((cx - cy + cz) * k),
                                   (int) Math.round((cx + cy - cz) * k));

        var actual = RDGCoordinates.toRDG(new Point3f(cx, cy, cz));
        assertThat(actual).as("toRDG must use double-precision summation").isEqualTo(expected);
        assertThat(floatSum).as("input must be adversarial — float-sum arithmetic must diverge from double-sum")
                            .isNotEqualTo(expected);
    }

    /** Specifically the (1,0,0) case from the Luciferase-6oa audit. */
    @Test
    void roundTripUnitMagnitudePoint() {
        var rdg = new Point3i(1, 0, 0);
        var cart = RDGCoordinates.toCartesian(rdg);
        // toCartesian((1,0,0)) = (0, 1/√2, 1/√2)
        assertThat(cart.x).isCloseTo(0.0, within(1e-9));
        assertThat(cart.y).isCloseTo(RDGCoordinates.DIVIDE_ROOT_2, within(1e-9));
        assertThat(cart.z).isCloseTo(RDGCoordinates.DIVIDE_ROOT_2, within(1e-9));
        var back = RDGCoordinates.toRDG(new Point3f((float) cart.x, (float) cart.y, (float) cart.z));
        assertThat(back).isEqualTo(rdg);
    }

    /**
     * Metric-tensor dot (G_ii=1, G_ij=1/2). Verifies symmetry and a hand-computed value, guarding the
     * Luciferase-7jk coefficient fix.
     */
    @Test
    void dotIsSymmetricMetricTensor() {
        var u = new Vector3f(1, 2, 3);
        var v = new Vector3f(4, 5, 6);
        // Σ u_i v_i + (Σ_{i≠j} u_i v_j)/2
        float expected = (1*4 + 2*5 + 3*6)
                       + (1*5 + 2*4 + 1*6 + 3*4 + 2*6 + 3*5) / 2f;
        assertThat(RDGCoordinates.dot(u, v)).isEqualTo(expected);
        assertThat(RDGCoordinates.dot(u, v)).as("symmetry").isEqualTo(RDGCoordinates.dot(v, u));
    }

    @Test
    void euclideanNormAndL1() {
        assertThat(RDGCoordinates.l1(new Vector3f(1, -2, 3))).isEqualTo(6f);
        // norm of zero vector is zero
        assertThat(RDGCoordinates.euclideanNorm(new Vector3f(0, 0, 0))).isEqualTo(0f);
        // (1,1,1): x*(x+y+z)+y*(y+z)+z*z = 1*3+1*2+1 = 6 → norm = √6
        assertThat((double) RDGCoordinates.euclideanNorm(new Vector3f(1, 1, 1)))
                .isCloseTo(Math.sqrt(6), within(1e-5));
        // (1,2,3): 1*6+2*5+3*3 = 25 → norm = 5.0
        assertThat((double) RDGCoordinates.euclideanNorm(new Vector3f(1, 2, 3)))
                .isCloseTo(5.0, within(1e-5));
        // cross-check: euclideanNorm(v) == sqrt(dot(v,v)) for the metric tensor
        var v = new Vector3f(1, 2, 3);
        double normViaFormula = RDGCoordinates.euclideanNorm(v);
        double normViaDot = Math.sqrt(RDGCoordinates.dot(v, v));
        assertThat(normViaFormula).isCloseTo(normViaDot, within(1e-5));
    }

    @Test
    void crossIsAntisymmetric() {
        var u = new Point3f(1, 2, 3);
        var v = new Point3f(4, 5, 6);
        var uv = RDGCoordinates.cross(u, v);
        var vu = RDGCoordinates.cross(v, u);
        // cross(u,v) == -cross(v,u)
        assertThat(uv.x).isCloseTo(-vu.x, within(1e-5f));
        assertThat(uv.y).isCloseTo(-vu.y, within(1e-5f));
        assertThat(uv.z).isCloseTo(-vu.z, within(1e-5f));
        // cross(u,u) == 0
        var uu = RDGCoordinates.cross(u, u);
        assertThat(uu.x).isCloseTo(0f, within(1e-5f));
        assertThat(uu.y).isCloseTo(0f, within(1e-5f));
        assertThat(uu.z).isCloseTo(0f, within(1e-5f));
    }

    @Test
    void rotateVectorCCFullTurnIsIdentity() {
        var vec = new Vector3f(1, 2, 3);
        var axis = new Vector3f(0, 0, 1);
        var rotated = RDGCoordinates.rotateVectorCC(vec, axis, 2 * Math.PI);
        assertThat(rotated.x).isCloseTo(vec.x, within(1e-4f));
        assertThat(rotated.y).isCloseTo(vec.y, within(1e-4f));
        assertThat(rotated.z).isCloseTo(vec.z, within(1e-4f));
    }

    @Test
    void rotateVectorCCAboutZByHalfPi() {
        // (1,0,0) rotated +90° about +z → (0,1,0)
        var rotated = RDGCoordinates.rotateVectorCC(new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), Math.PI / 2);
        assertThat(rotated.x).isCloseTo(0f, within(1e-6f));
        assertThat(rotated.y).isCloseTo(1f, within(1e-6f));
        assertThat(rotated.z).isCloseTo(0f, within(1e-6f));
    }

    /**
     * All 12 face-connected neighbors of the FCC lattice sit at Cartesian distance 1.0
     * (the nearest-neighbor shell), and their offset set is disjoint from the 6 vertex-connected
     * neighbors which sit at distance √2.
     */
    @Test
    void faceConnectedNeighborsAreTwelveDistinct() {
        var origin = new Point3i(0, 0, 0);
        var n = RDGCoordinates.faceConnectedNeighbors(origin);
        assertThat(n).hasSize(12);
        var faceSet = new HashSet<>(Arrays.asList(n));
        assertThat(faceSet).as("all distinct").hasSize(12);

        // Each face-connected neighbor is at Cartesian distance 1.0 (FCC nearest-neighbor shell)
        for (var cell : n) {
            var c = RDGCoordinates.toCartesian(cell);
            double dist = Math.sqrt(c.x * c.x + c.y * c.y + c.z * c.z);
            assertThat(dist).as("face neighbor %s at distance 1.0", cell).isCloseTo(1.0, within(1e-9));
        }

        // Face-neighbor offsets must be disjoint from vertex-neighbor offsets (distance √2)
        var vertexSet = new HashSet<>(Arrays.asList(RDGCoordinates.vertexConnectedNeighbors(origin)));
        assertThat(faceSet).as("face and vertex neighbor sets are disjoint").doesNotContainAnyElementsOf(vertexSet);
    }

    /** The 6 vertex-connected neighbors must all sit at Cartesian distance √2 (Luciferase-xnf). */
    @Test
    void vertexConnectedNeighborsAreSixAtDistanceRoot2() {
        var n = RDGCoordinates.vertexConnectedNeighbors(new Point3i(0, 0, 0));
        assertThat(n).hasSize(6);
        assertThat(new HashSet<>(Arrays.asList(n))).hasSize(6);
        for (var cell : n) {
            var c = RDGCoordinates.toCartesian(cell);
            double dist = Math.sqrt(c.x * c.x + c.y * c.y + c.z * c.z);
            assertThat(dist).as("vertex neighbor %s at distance √2", cell).isCloseTo(Math.sqrt(2), within(1e-9));
        }
    }
}
