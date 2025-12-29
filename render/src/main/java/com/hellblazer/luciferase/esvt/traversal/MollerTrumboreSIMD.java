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
package com.hellblazer.luciferase.esvt.traversal;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-optimized Möller-Trumbore ray-tetrahedron intersection.
 *
 * <p>Tests all 4 tetrahedron faces in parallel using Java Vector API.
 * This provides ~2-4x speedup on CPUs with AVX2/NEON support.
 *
 * <p><b>Key Optimization:</b> Instead of testing 4 triangles sequentially,
 * we pack all 4 face tests into SIMD lanes and compute in parallel.
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>Java 22+ with --add-modules jdk.incubator.vector</li>
 *   <li>CPU with SIMD support (AVX2, AVX-512, or ARM NEON)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class MollerTrumboreSIMD {

    // Use 128-bit species (4 floats) - works on all SIMD platforms
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_128;
    private static final int LANES = SPECIES.length(); // 4 for 128-bit

    private static final float EPSILON = 1e-7f;

    /**
     * Result of SIMD tetrahedron intersection.
     */
    public static final class TetrahedronResult {
        public boolean hit;
        public float tEntry;
        public float tExit;
        public int entryFace;
        public int exitFace;

        public void reset() {
            hit = false;
            tEntry = Float.MAX_VALUE;
            tExit = -Float.MAX_VALUE;
            entryFace = -1;
            exitFace = -1;
        }
    }

    // Preallocated arrays for SIMD operations (4 faces x 3 components)
    private final float[] edge1x = new float[4];
    private final float[] edge1y = new float[4];
    private final float[] edge1z = new float[4];
    private final float[] edge2x = new float[4];
    private final float[] edge2y = new float[4];
    private final float[] edge2z = new float[4];
    private final float[] v0x = new float[4];
    private final float[] v0y = new float[4];
    private final float[] v0z = new float[4];
    private final float[] tResults = new float[4];
    private final boolean[] hitMask = new boolean[4];

    private MollerTrumboreSIMD() {
    }

    public static MollerTrumboreSIMD create() {
        return new MollerTrumboreSIMD();
    }

    /**
     * Test ray-tetrahedron intersection using SIMD (all 4 faces in parallel).
     *
     * @param rayOx Ray origin X
     * @param rayOy Ray origin Y
     * @param rayOz Ray origin Z
     * @param rayDx Ray direction X
     * @param rayDy Ray direction Y
     * @param rayDz Ray direction Z
     * @param v0x Vertex 0 X
     * @param v0y Vertex 0 Y
     * @param v0z Vertex 0 Z
     * @param v1x Vertex 1 X
     * @param v1y Vertex 1 Y
     * @param v1z Vertex 1 Z
     * @param v2x Vertex 2 X
     * @param v2y Vertex 2 Y
     * @param v2z Vertex 2 Z
     * @param v3x Vertex 3 X
     * @param v3y Vertex 3 Y
     * @param v3z Vertex 3 Z
     * @param result Result to fill
     * @return true if ray intersects tetrahedron
     */
    public boolean intersectTetrahedron(
            float rayOx, float rayOy, float rayOz,
            float rayDx, float rayDy, float rayDz,
            float v0x, float v0y, float v0z,
            float v1x, float v1y, float v1z,
            float v2x, float v2y, float v2z,
            float v3x, float v3y, float v3z,
            TetrahedronResult result) {

        result.reset();

        // Setup edge vectors for all 4 faces
        // Face 0: v1, v2, v3 (opposite v0)
        // Face 1: v0, v2, v3 (opposite v1)
        // Face 2: v0, v1, v3 (opposite v2)
        // Face 3: v0, v1, v2 (opposite v3)

        // First vertex of each triangle (v0 array for the algorithm)
        this.v0x[0] = v1x; this.v0y[0] = v1y; this.v0z[0] = v1z; // Face 0
        this.v0x[1] = v0x; this.v0y[1] = v0y; this.v0z[1] = v0z; // Face 1
        this.v0x[2] = v0x; this.v0y[2] = v0y; this.v0z[2] = v0z; // Face 2
        this.v0x[3] = v0x; this.v0y[3] = v0y; this.v0z[3] = v0z; // Face 3

        // Edge 1 for each face (v1 - v0 in triangle terms)
        edge1x[0] = v2x - v1x; edge1y[0] = v2y - v1y; edge1z[0] = v2z - v1z; // Face 0
        edge1x[1] = v2x - v0x; edge1y[1] = v2y - v0y; edge1z[1] = v2z - v0z; // Face 1
        edge1x[2] = v1x - v0x; edge1y[2] = v1y - v0y; edge1z[2] = v1z - v0z; // Face 2
        edge1x[3] = v1x - v0x; edge1y[3] = v1y - v0y; edge1z[3] = v1z - v0z; // Face 3

        // Edge 2 for each face (v2 - v0 in triangle terms)
        edge2x[0] = v3x - v1x; edge2y[0] = v3y - v1y; edge2z[0] = v3z - v1z; // Face 0
        edge2x[1] = v3x - v0x; edge2y[1] = v3y - v0y; edge2z[1] = v3z - v0z; // Face 1
        edge2x[2] = v3x - v0x; edge2y[2] = v3y - v0y; edge2z[2] = v3z - v0z; // Face 2
        edge2x[3] = v2x - v0x; edge2y[3] = v2y - v0y; edge2z[3] = v2z - v0z; // Face 3

        // SIMD Möller-Trumbore for all 4 faces
        var e1x = FloatVector.fromArray(SPECIES, edge1x, 0);
        var e1y = FloatVector.fromArray(SPECIES, edge1y, 0);
        var e1z = FloatVector.fromArray(SPECIES, edge1z, 0);
        var e2x = FloatVector.fromArray(SPECIES, edge2x, 0);
        var e2y = FloatVector.fromArray(SPECIES, edge2y, 0);
        var e2z = FloatVector.fromArray(SPECIES, edge2z, 0);
        var fv0x = FloatVector.fromArray(SPECIES, this.v0x, 0);
        var fv0y = FloatVector.fromArray(SPECIES, this.v0y, 0);
        var fv0z = FloatVector.fromArray(SPECIES, this.v0z, 0);

        // Broadcast ray direction
        var rdx = FloatVector.broadcast(SPECIES, rayDx);
        var rdy = FloatVector.broadcast(SPECIES, rayDy);
        var rdz = FloatVector.broadcast(SPECIES, rayDz);

        // h = rayDir × edge2
        var hx = rdy.mul(e2z).sub(rdz.mul(e2y));
        var hy = rdz.mul(e2x).sub(rdx.mul(e2z));
        var hz = rdx.mul(e2y).sub(rdy.mul(e2x));

        // a = edge1 · h (determinant)
        var a = e1x.mul(hx).add(e1y.mul(hy)).add(e1z.mul(hz));

        // Check for parallel rays (|a| < epsilon)
        var epsVec = FloatVector.broadcast(SPECIES, EPSILON);
        var negEps = FloatVector.broadcast(SPECIES, -EPSILON);
        var notParallel = a.compare(VectorOperators.LT, negEps).or(a.compare(VectorOperators.GT, epsVec));

        // f = 1 / a
        var one = FloatVector.broadcast(SPECIES, 1.0f);
        var f = one.div(a);

        // s = rayOrigin - v0
        var rox = FloatVector.broadcast(SPECIES, rayOx);
        var roy = FloatVector.broadcast(SPECIES, rayOy);
        var roz = FloatVector.broadcast(SPECIES, rayOz);
        var sx = rox.sub(fv0x);
        var sy = roy.sub(fv0y);
        var sz = roz.sub(fv0z);

        // u = f * (s · h)
        var u = f.mul(sx.mul(hx).add(sy.mul(hy)).add(sz.mul(hz)));

        // Check u in [0, 1]
        var zero = FloatVector.broadcast(SPECIES, 0.0f);
        var validU = u.compare(VectorOperators.LT, zero).not().and(
            u.compare(VectorOperators.LT, one).or(u.compare(VectorOperators.EQ, one)));

        // q = s × edge1
        var qx = sy.mul(e1z).sub(sz.mul(e1y));
        var qy = sz.mul(e1x).sub(sx.mul(e1z));
        var qz = sx.mul(e1y).sub(sy.mul(e1x));

        // v = f * (rayDir · q)
        var v = f.mul(rdx.mul(qx).add(rdy.mul(qy)).add(rdz.mul(qz)));

        // Check v >= 0 and u + v <= 1
        var uPlusV = u.add(v);
        var validV = v.compare(VectorOperators.LT, zero).not().and(
            uPlusV.compare(VectorOperators.LT, one).or(uPlusV.compare(VectorOperators.EQ, one)));

        // t = f * (edge2 · q)
        var t = f.mul(e2x.mul(qx).add(e2y.mul(qy)).add(e2z.mul(qz)));

        // Check t > epsilon
        var validT = t.compare(VectorOperators.GT, epsVec);

        // Combine all validity checks
        var validHit = notParallel.and(validU).and(validV).and(validT);

        // Extract results
        t.intoArray(tResults, 0);
        for (int i = 0; i < 4; i++) {
            hitMask[i] = validHit.laneIsSet(i);
        }

        // Find entry (min t) and exit (max t)
        float minT = Float.MAX_VALUE;
        float maxT = -Float.MAX_VALUE;
        int entryFace = -1;
        int exitFace = -1;

        for (int i = 0; i < 4; i++) {
            if (hitMask[i]) {
                float ti = tResults[i];
                if (ti < minT) {
                    minT = ti;
                    entryFace = i;
                }
                if (ti > maxT) {
                    maxT = ti;
                    exitFace = i;
                }
            }
        }

        // Valid hit requires entry and exit
        if (entryFace >= 0 && exitFace >= 0 && minT < maxT) {
            result.hit = true;
            result.tEntry = minT;
            result.tExit = maxT;
            result.entryFace = entryFace;
            result.exitFace = exitFace;
            return true;
        }

        // Special case: ray origin inside (only exit found)
        if (entryFace < 0 && exitFace >= 0 && maxT > 0) {
            result.hit = true;
            result.tEntry = 0;
            result.tExit = maxT;
            result.entryFace = -1;
            result.exitFace = exitFace;
            return true;
        }

        return false;
    }

    /**
     * Test 4 rays against 1 triangle in parallel (SIMD across rays).
     *
     * @return hits mask (bit i set if ray i hit)
     */
    public int intersect4RaysTriangle(
            float[] rayOx, float[] rayOy, float[] rayOz,
            float[] rayDx, float[] rayDy, float[] rayDz,
            float tv0x, float tv0y, float tv0z,
            float tv1x, float tv1y, float tv1z,
            float tv2x, float tv2y, float tv2z,
            float[] tOut) {

        // Edge vectors (same for all rays)
        float e1x = tv1x - tv0x, e1y = tv1y - tv0y, e1z = tv1z - tv0z;
        float e2x = tv2x - tv0x, e2y = tv2y - tv0y, e2z = tv2z - tv0z;

        // Broadcast edges
        var ve1x = FloatVector.broadcast(SPECIES, e1x);
        var ve1y = FloatVector.broadcast(SPECIES, e1y);
        var ve1z = FloatVector.broadcast(SPECIES, e1z);
        var ve2x = FloatVector.broadcast(SPECIES, e2x);
        var ve2y = FloatVector.broadcast(SPECIES, e2y);
        var ve2z = FloatVector.broadcast(SPECIES, e2z);

        // Load ray directions (different for each ray)
        var vrdx = FloatVector.fromArray(SPECIES, rayDx, 0);
        var vrdy = FloatVector.fromArray(SPECIES, rayDy, 0);
        var vrdz = FloatVector.fromArray(SPECIES, rayDz, 0);

        // h = rayDir × edge2
        var hx = vrdy.mul(ve2z).sub(vrdz.mul(ve2y));
        var hy = vrdz.mul(ve2x).sub(vrdx.mul(ve2z));
        var hz = vrdx.mul(ve2y).sub(vrdy.mul(ve2x));

        // a = edge1 · h
        var a = ve1x.mul(hx).add(ve1y.mul(hy)).add(ve1z.mul(hz));

        // Check parallel
        var eps = FloatVector.broadcast(SPECIES, EPSILON);
        var negEps = FloatVector.broadcast(SPECIES, -EPSILON);
        var notParallel = a.compare(VectorOperators.LT, negEps).or(a.compare(VectorOperators.GT, eps));

        // f = 1/a
        var one = FloatVector.broadcast(SPECIES, 1.0f);
        var f = one.div(a);

        // Load ray origins
        var vrox = FloatVector.fromArray(SPECIES, rayOx, 0);
        var vroy = FloatVector.fromArray(SPECIES, rayOy, 0);
        var vroz = FloatVector.fromArray(SPECIES, rayOz, 0);

        // s = rayOrigin - v0
        var vtv0x = FloatVector.broadcast(SPECIES, tv0x);
        var vtv0y = FloatVector.broadcast(SPECIES, tv0y);
        var vtv0z = FloatVector.broadcast(SPECIES, tv0z);
        var sx = vrox.sub(vtv0x);
        var sy = vroy.sub(vtv0y);
        var sz = vroz.sub(vtv0z);

        // u = f * (s · h)
        var u = f.mul(sx.mul(hx).add(sy.mul(hy)).add(sz.mul(hz)));

        // Check u in [0,1]
        var zero = FloatVector.broadcast(SPECIES, 0.0f);
        var validU = u.compare(VectorOperators.GE, zero).and(u.compare(VectorOperators.LE, one));

        // q = s × edge1
        var qx = sy.mul(ve1z).sub(sz.mul(ve1y));
        var qy = sz.mul(ve1x).sub(sx.mul(ve1z));
        var qz = sx.mul(ve1y).sub(sy.mul(ve1x));

        // v = f * (rayDir · q)
        var v = f.mul(vrdx.mul(qx).add(vrdy.mul(qy)).add(vrdz.mul(qz)));

        // Check v >= 0 and u+v <= 1
        var uPlusV = u.add(v);
        var validV = v.compare(VectorOperators.GE, zero).and(uPlusV.compare(VectorOperators.LE, one));

        // t = f * (edge2 · q)
        var t = f.mul(ve2x.mul(qx).add(ve2y.mul(qy)).add(ve2z.mul(qz)));

        // Check t > epsilon
        var validT = t.compare(VectorOperators.GT, eps);

        // Combined validity
        var valid = notParallel.and(validU).and(validV).and(validT);

        // Store results
        t.intoArray(tOut, 0);

        // Return hit mask
        return (int) valid.toLong();
    }

    /**
     * Test 4 rays against the same tetrahedron in parallel.
     * True SIMD implementation - processes 4 rays at once.
     *
     * @param rayOx Ray origin X values [4]
     * @param rayOy Ray origin Y values [4]
     * @param rayOz Ray origin Z values [4]
     * @param rayDx Ray direction X values [4]
     * @param rayDy Ray direction Y values [4]
     * @param rayDz Ray direction Z values [4]
     * @param v0-v3 Tetrahedron vertices
     * @param results Array of 4 results
     * @return Number of hits (0-4)
     */
    public int intersect4RaysTet(
            float[] rayOx, float[] rayOy, float[] rayOz,
            float[] rayDx, float[] rayDy, float[] rayDz,
            float v0x, float v0y, float v0z,
            float v1x, float v1y, float v1z,
            float v2x, float v2y, float v2z,
            float v3x, float v3y, float v3z,
            TetrahedronResult[] results) {

        // Reset results
        for (int i = 0; i < 4; i++) {
            results[i].reset();
        }

        float[] tOut = new float[4];
        float[] minT = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] maxT = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int[] entryFace = {-1, -1, -1, -1};
        int[] exitFace = {-1, -1, -1, -1};

        // Face 0: v1, v2, v3
        int hits0 = intersect4RaysTriangle(rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
                v1x, v1y, v1z, v2x, v2y, v2z, v3x, v3y, v3z, tOut);
        for (int i = 0; i < 4; i++) {
            if ((hits0 & (1 << i)) != 0) {
                if (tOut[i] < minT[i]) { minT[i] = tOut[i]; entryFace[i] = 0; }
                if (tOut[i] > maxT[i]) { maxT[i] = tOut[i]; exitFace[i] = 0; }
            }
        }

        // Face 1: v0, v2, v3
        int hits1 = intersect4RaysTriangle(rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
                v0x, v0y, v0z, v2x, v2y, v2z, v3x, v3y, v3z, tOut);
        for (int i = 0; i < 4; i++) {
            if ((hits1 & (1 << i)) != 0) {
                if (tOut[i] < minT[i]) { minT[i] = tOut[i]; entryFace[i] = 1; }
                if (tOut[i] > maxT[i]) { maxT[i] = tOut[i]; exitFace[i] = 1; }
            }
        }

        // Face 2: v0, v1, v3
        int hits2 = intersect4RaysTriangle(rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
                v0x, v0y, v0z, v1x, v1y, v1z, v3x, v3y, v3z, tOut);
        for (int i = 0; i < 4; i++) {
            if ((hits2 & (1 << i)) != 0) {
                if (tOut[i] < minT[i]) { minT[i] = tOut[i]; entryFace[i] = 2; }
                if (tOut[i] > maxT[i]) { maxT[i] = tOut[i]; exitFace[i] = 2; }
            }
        }

        // Face 3: v0, v1, v2
        int hits3 = intersect4RaysTriangle(rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
                v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, tOut);
        for (int i = 0; i < 4; i++) {
            if ((hits3 & (1 << i)) != 0) {
                if (tOut[i] < minT[i]) { minT[i] = tOut[i]; entryFace[i] = 3; }
                if (tOut[i] > maxT[i]) { maxT[i] = tOut[i]; exitFace[i] = 3; }
            }
        }

        // Finalize results
        int hitCount = 0;
        for (int i = 0; i < 4; i++) {
            if (entryFace[i] >= 0 && exitFace[i] >= 0 && minT[i] < maxT[i]) {
                results[i].hit = true;
                results[i].tEntry = minT[i];
                results[i].tExit = maxT[i];
                results[i].entryFace = entryFace[i];
                results[i].exitFace = exitFace[i];
                hitCount++;
            } else if (entryFace[i] < 0 && exitFace[i] >= 0 && maxT[i] > 0) {
                results[i].hit = true;
                results[i].tEntry = 0;
                results[i].tExit = maxT[i];
                results[i].entryFace = -1;
                results[i].exitFace = exitFace[i];
                hitCount++;
            }
        }

        return hitCount;
    }

    /**
     * Check if SIMD is available on this platform.
     */
    public static boolean isAvailable() {
        try {
            // Try to use a vector operation
            var test = FloatVector.broadcast(SPECIES, 1.0f);
            return test.reduceLanes(VectorOperators.ADD) == 4.0f;
        } catch (Throwable t) {
            return false;
        }
    }
}
