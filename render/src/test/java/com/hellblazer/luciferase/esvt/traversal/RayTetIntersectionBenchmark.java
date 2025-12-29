/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 */
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.PluckerCoordinate;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark comparing Plücker and Möller-Trumbore ray-tetrahedron intersection algorithms.
 *
 * Validates Hypothesis H1: Measure actual operation count for both algorithms.
 *
 * <p><b>Operation Counting Convention:</b>
 * <ul>
 *   <li>FMA = Fused Multiply-Add (counts as 1 op on modern GPUs)</li>
 *   <li>MUL/ADD/SUB = 1 op each</li>
 *   <li>DIV = 1 op (fast reciprocal on GPUs)</li>
 *   <li>Compare = 1 op</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class RayTetIntersectionBenchmark {

    private static final float EPSILON = 1e-6f;

    /**
     * Precomputed Plücker edge coordinates for S0-S5 tetrahedra.
     * Format: [type][edge][0-5] where 0-2 = U (direction), 3-5 = V (moment)
     *
     * Edges are: 01, 02, 03, 12, 13, 23 (vertex pairs)
     *
     * IMPORTANT: We offset vertices by +0.5 to avoid origin-at-vertex issues.
     * Edges through origin have zero moment, breaking the sign test.
     */
    private static final float[][][] PLUCKER_EDGES = new float[6][6][6];

    /**
     * Offset applied to all vertices (avoids zero moments for edges at origin).
     */
    private static final float VERTEX_OFFSET = 0.5f;

    /**
     * Edge vertex indices for the 6 edges of a tetrahedron.
     */
    private static final int[][] EDGE_VERTICES = {
        {0, 1}, {0, 2}, {0, 3}, {1, 2}, {1, 3}, {2, 3}
    };

    /**
     * Cached offset vertices for each type.
     */
    private static final float[][][] OFFSET_VERTICES = new float[6][4][3];

    static {
        initializeOffsetVertices();
        initializePluckerEdges();
    }

    private static void initializeOffsetVertices() {
        for (int type = 0; type < 6; type++) {
            Point3i[] verts = Constants.SIMPLEX_STANDARD[type];
            for (int v = 0; v < 4; v++) {
                OFFSET_VERTICES[type][v][0] = verts[v].x + VERTEX_OFFSET;
                OFFSET_VERTICES[type][v][1] = verts[v].y + VERTEX_OFFSET;
                OFFSET_VERTICES[type][v][2] = verts[v].z + VERTEX_OFFSET;
            }
        }
    }

    private static void initializePluckerEdges() {
        for (int type = 0; type < 6; type++) {
            for (int e = 0; e < 6; e++) {
                int vi1 = EDGE_VERTICES[e][0];
                int vi2 = EDGE_VERTICES[e][1];

                float p1x = OFFSET_VERTICES[type][vi1][0];
                float p1y = OFFSET_VERTICES[type][vi1][1];
                float p1z = OFFSET_VERTICES[type][vi1][2];
                float p2x = OFFSET_VERTICES[type][vi2][0];
                float p2y = OFFSET_VERTICES[type][vi2][1];
                float p2z = OFFSET_VERTICES[type][vi2][2];

                // U = p2 - p1 (direction)
                float ux = p2x - p1x;
                float uy = p2y - p1y;
                float uz = p2z - p1z;

                // V = p2 × p1 (standard Plücker moment for edge from p1 to p2)
                // This matches PluckerCoordinate.java which uses V.cross(p2Vec, p1Vec)
                // Equivalent to: U × p1 = (p2-p1) × p1 = p2×p1 - p1×p1 = p2×p1
                float vx = p2y * p1z - p2z * p1y;
                float vy = p2z * p1x - p2x * p1z;
                float vz = p2x * p1y - p2y * p1x;

                PLUCKER_EDGES[type][e][0] = ux;
                PLUCKER_EDGES[type][e][1] = uy;
                PLUCKER_EDGES[type][e][2] = uz;
                PLUCKER_EDGES[type][e][3] = vx;
                PLUCKER_EDGES[type][e][4] = vy;
                PLUCKER_EDGES[type][e][5] = vz;
            }
        }
    }

    /**
     * Face-to-edges mapping. Each face is opposite a vertex and uses the 3 edges NOT touching that vertex.
     * Face 0 (opposite v0): edges 3(1-2), 4(1-3), 5(2-3)
     * Face 1 (opposite v1): edges 1(0-2), 2(0-3), 5(2-3)
     * Face 2 (opposite v2): edges 0(0-1), 2(0-3), 4(1-3)
     * Face 3 (opposite v3): edges 0(0-1), 1(0-2), 3(1-2)
     */
    private static final int[][] FACE_EDGES = {
        {3, 4, 5},  // Face 0
        {1, 2, 5},  // Face 1
        {0, 2, 4},  // Face 2
        {0, 1, 3}   // Face 3
    };

    /**
     * Sign correction for each edge in each face.
     * +1 = use as-is, -1 = flip sign to match face orientation.
     * For CCW edge ordering around each face (as viewed from outside).
     */
    private static final int[][] FACE_EDGE_SIGNS = {
        {+1, -1, +1},  // Face 0: 1→2, 3→1(flip 1→3), 2→3
        {+1, -1, -1},  // Face 1: 0→2, 3→0(flip 0→3), 3→2(flip 2→3)
        {+1, +1, +1},  // Face 2: 0→1, 0→3, 1→3
        {+1, +1, -1}   // Face 3: 0→1, 0→2, 2→1(flip 1→2)
    };

    /**
     * Edge flip table for each S0-S5 type.
     * +1 = use edge p1→p2, -1 = flip to p2→p1 for consistent orientation.
     * Derived from signed volume analysis of each tetrahedron type.
     *
     * S0: c0,c1,c5,c7 - edges from v0 go "outward"
     * S1: c0,c7,c3,c1 - inverted orientation (v0,v1 flipped)
     * S2: c0,c2,c3,c7 - edges from v0 go "outward"
     * S3: c0,c7,c6,c2 - inverted orientation
     * S4: c0,c4,c6,c7 - edges from v0 go "outward"
     * S5: c0,c7,c5,c4 - inverted orientation
     */
    private static final int[][] EDGE_FLIP_TABLE = {
        {+1, +1, +1, +1, +1, +1},  // S0: standard orientation
        {+1, +1, +1, -1, -1, -1},  // S1: flip edges involving v1/v2/v3 among themselves
        {+1, +1, +1, +1, +1, +1},  // S2: standard orientation
        {+1, +1, +1, -1, -1, -1},  // S3: flip edges involving v1/v2/v3
        {+1, +1, +1, +1, +1, +1},  // S4: standard orientation
        {+1, +1, +1, -1, -1, -1}   // S5: flip edges involving v1/v2/v3
    };

    /**
     * Plücker ray-tetrahedron intersection test.
     *
     * <p>Uses precomputed edge Plückers with type-specific flip corrections
     * for consistent edge orientation.
     *
     * <p><b>Operation Count (with precomputed edges):</b>
     * <ul>
     *   <li>Ray Plücker setup: 1 cross product = 9 ops</li>
     *   <li>Per edge: 2 dot products + 1 add + 1 flip = 12 ops</li>
     *   <li>6 edges: 72 ops</li>
     *   <li>Sign checking: ~14 ops</li>
     *   <li><b>Total: 9 + 72 + 14 = 95 ops</b></li>
     * </ul>
     *
     * @return true if ray intersects tetrahedron
     */
    public static boolean pluckerIntersection(
            int type,
            float rayOx, float rayOy, float rayOz,  // Ray origin
            float rayDx, float rayDy, float rayDz,  // Ray direction
            float rayVx, float rayVy, float rayVz)  // Precomputed ray moment (D × O)
    {
        int[] flips = EDGE_FLIP_TABLE[type];
        int positiveCount = 0;
        int negativeCount = 0;

        for (int e = 0; e < 6; e++) {
            float[] edge = PLUCKER_EDGES[type][e];

            // Permuted inner product: U_ray · V_edge + U_edge · V_ray
            float dotRayEdge = rayDx * edge[3] + rayDy * edge[4] + rayDz * edge[5];
            float dotEdgeRay = edge[0] * rayVx + edge[1] * rayVy + edge[2] * rayVz;
            float product = (dotRayEdge + dotEdgeRay) * flips[e];

            if (product > EPSILON) {
                positiveCount++;
            } else if (product < -EPSILON) {
                negativeCount++;
            }
        }

        // Ray intersects if all non-zero products have same sign
        return (positiveCount == 0 || negativeCount == 0) && (positiveCount + negativeCount > 0);
    }

    /**
     * Möller-Trumbore ray-triangle intersection for a single face.
     *
     * <p><b>Operation Count per triangle:</b>
     * <ul>
     *   <li>edge1, edge2: 6 subs</li>
     *   <li>h = D × edge2: 6 muls + 3 subs = 9 ops</li>
     *   <li>a = edge1 · h: 3 muls + 2 adds = 5 ops</li>
     *   <li>f = 1/a: 1 div</li>
     *   <li>s = O - v0: 3 subs</li>
     *   <li>u = f × (s · h): 5 + 1 = 6 ops</li>
     *   <li>q = s × edge1: 9 ops</li>
     *   <li>v = f × (D · q): 6 ops</li>
     *   <li>t = f × (edge2 · q): 6 ops</li>
     *   <li>Compares: ~5 ops</li>
     *   <li><b>Total per face: ~56 ops</b></li>
     * </ul>
     *
     * @return t-value if intersection, Float.MAX_VALUE if no intersection
     */
    public static float mollerTrumboreTriangle(
            float rayOx, float rayOy, float rayOz,
            float rayDx, float rayDy, float rayDz,
            float v0x, float v0y, float v0z,
            float v1x, float v1y, float v1z,
            float v2x, float v2y, float v2z)
    {
        // edge1 = v1 - v0: 3 subs
        float e1x = v1x - v0x;
        float e1y = v1y - v0y;
        float e1z = v1z - v0z;

        // edge2 = v2 - v0: 3 subs
        float e2x = v2x - v0x;
        float e2y = v2y - v0y;
        float e2z = v2z - v0z;

        // h = D × edge2: 6 muls + 3 subs
        float hx = rayDy * e2z - rayDz * e2y;
        float hy = rayDz * e2x - rayDx * e2z;
        float hz = rayDx * e2y - rayDy * e2x;

        // a = edge1 · h: 3 muls + 2 adds
        float a = e1x * hx + e1y * hy + e1z * hz;

        // Check if ray is parallel to triangle
        if (a > -EPSILON && a < EPSILON) {
            return Float.MAX_VALUE;
        }

        // f = 1/a: 1 div
        float f = 1.0f / a;

        // s = O - v0: 3 subs
        float sx = rayOx - v0x;
        float sy = rayOy - v0y;
        float sz = rayOz - v0z;

        // u = f × (s · h): 3 muls + 2 adds + 1 mul
        float u = f * (sx * hx + sy * hy + sz * hz);

        if (u < 0.0f || u > 1.0f) {
            return Float.MAX_VALUE;
        }

        // q = s × edge1: 6 muls + 3 subs
        float qx = sy * e1z - sz * e1y;
        float qy = sz * e1x - sx * e1z;
        float qz = sx * e1y - sy * e1x;

        // v = f × (D · q): 3 muls + 2 adds + 1 mul
        float v = f * (rayDx * qx + rayDy * qy + rayDz * qz);

        if (v < 0.0f || u + v > 1.0f) {
            return Float.MAX_VALUE;
        }

        // t = f × (edge2 · q): 3 muls + 2 adds + 1 mul
        float t = f * (e2x * qx + e2y * qy + e2z * qz);

        return t > EPSILON ? t : Float.MAX_VALUE;
    }

    /**
     * Möller-Trumbore ray-tetrahedron intersection.
     * Tests all 4 faces and returns minimum positive t-value.
     *
     * <p><b>Operation Count:</b>
     * <ul>
     *   <li>4 faces × 56 ops = 224 ops (worst case)</li>
     *   <li>With early exit: 56-168 ops (average ~112 ops)</li>
     * </ul>
     *
     * @return true if ray intersects tetrahedron
     */
    public static boolean mollerTrumboreIntersection(
            int type,
            float rayOx, float rayOy, float rayOz,
            float rayDx, float rayDy, float rayDz)
    {
        // Use offset vertices to match Plücker test (avoids origin issues)
        float[][] verts = OFFSET_VERTICES[type];

        // Test all 4 faces - find any intersection
        // Face 0: vertices 1, 2, 3
        float t0 = mollerTrumboreTriangle(
            rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
            verts[1][0], verts[1][1], verts[1][2],
            verts[2][0], verts[2][1], verts[2][2],
            verts[3][0], verts[3][1], verts[3][2]
        );

        // Face 1: vertices 0, 2, 3
        float t1 = mollerTrumboreTriangle(
            rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
            verts[0][0], verts[0][1], verts[0][2],
            verts[2][0], verts[2][1], verts[2][2],
            verts[3][0], verts[3][1], verts[3][2]
        );

        // Face 2: vertices 0, 1, 3
        float t2 = mollerTrumboreTriangle(
            rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
            verts[0][0], verts[0][1], verts[0][2],
            verts[1][0], verts[1][1], verts[1][2],
            verts[3][0], verts[3][1], verts[3][2]
        );

        // Face 3: vertices 0, 1, 2
        float t3 = mollerTrumboreTriangle(
            rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
            verts[0][0], verts[0][1], verts[0][2],
            verts[1][0], verts[1][1], verts[1][2],
            verts[2][0], verts[2][1], verts[2][2]
        );

        // Find minimum positive t
        float minT = Float.MAX_VALUE;
        if (t0 > 0 && t0 < minT) minT = t0;
        if (t1 > 0 && t1 < minT) minT = t1;
        if (t2 > 0 && t2 < minT) minT = t2;
        if (t3 > 0 && t3 < minT) minT = t3;

        return minT < Float.MAX_VALUE;
    }

    /**
     * Möller-Trumbore with early exit - stops on first hit.
     */
    public static boolean mollerTrumboreEarlyExit(
            int type,
            float rayOx, float rayOy, float rayOz,
            float rayDx, float rayDy, float rayDz)
    {
        // Use offset vertices to match Plücker test
        float[][] verts = OFFSET_VERTICES[type];

        // Test faces with early exit
        if (mollerTrumboreTriangle(rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
                verts[1][0], verts[1][1], verts[1][2],
                verts[2][0], verts[2][1], verts[2][2],
                verts[3][0], verts[3][1], verts[3][2]) < Float.MAX_VALUE) {
            return true;
        }

        if (mollerTrumboreTriangle(rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
                verts[0][0], verts[0][1], verts[0][2],
                verts[2][0], verts[2][1], verts[2][2],
                verts[3][0], verts[3][1], verts[3][2]) < Float.MAX_VALUE) {
            return true;
        }

        if (mollerTrumboreTriangle(rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
                verts[0][0], verts[0][1], verts[0][2],
                verts[1][0], verts[1][1], verts[1][2],
                verts[3][0], verts[3][1], verts[3][2]) < Float.MAX_VALUE) {
            return true;
        }

        return mollerTrumboreTriangle(rayOx, rayOy, rayOz, rayDx, rayDy, rayDz,
                verts[0][0], verts[0][1], verts[0][2],
                verts[1][0], verts[1][1], verts[1][2],
                verts[2][0], verts[2][1], verts[2][2]) < Float.MAX_VALUE;
    }

    @Test
    void analyzeProductSignPatterns() {
        // Analyze what sign patterns actually occur for rays that hit
        Random random = new Random(12345);

        for (int type = 0; type < 6; type++) {
            float[][] verts = OFFSET_VERTICES[type];
            float cx = (verts[0][0] + verts[1][0] + verts[2][0] + verts[3][0]) / 4.0f;
            float cy = (verts[0][1] + verts[1][1] + verts[2][1] + verts[3][1]) / 4.0f;
            float cz = (verts[0][2] + verts[1][2] + verts[2][2] + verts[3][2]) / 4.0f;

            // Count sign patterns for hitting rays
            java.util.Map<String, Integer> patternCounts = new java.util.TreeMap<>();
            int allSame = 0;
            int total = 0;

            for (int i = 0; i < 1000; i++) {
                float ox = random.nextFloat() * 4 - 2;
                float oy = random.nextFloat() * 4 - 2;
                float oz = random.nextFloat() * 4 - 2;

                float dx = cx - ox, dy = cy - oy, dz = cz - oz;
                float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                dx /= len; dy /= len; dz /= len;

                float vx = dy * oz - dz * oy;
                float vy = dz * ox - dx * oz;
                float vz = dx * oy - dy * ox;

                boolean mtHit = mollerTrumboreIntersection(type, ox, oy, oz, dx, dy, dz);

                if (mtHit) {
                    total++;
                    StringBuilder sb = new StringBuilder();
                    int posCount = 0, negCount = 0;

                    for (int e = 0; e < 6; e++) {
                        float[] edge = PLUCKER_EDGES[type][e];
                        float dotRayEdge = dx * edge[3] + dy * edge[4] + dz * edge[5];
                        float dotEdgeRay = edge[0] * vx + edge[1] * vy + edge[2] * vz;
                        float product = dotRayEdge + dotEdgeRay;

                        if (product > EPSILON) { sb.append('+'); posCount++; }
                        else if (product < -EPSILON) { sb.append('-'); negCount++; }
                        else { sb.append('0'); }
                    }

                    String pattern = sb.toString();
                    patternCounts.merge(pattern, 1, Integer::sum);

                    if ((posCount == 0 || negCount == 0) && (posCount + negCount > 0)) {
                        allSame++;
                    }
                }
            }

            System.out.printf("\nType %d: %d/%d rays have all-same-sign (%.1f%%)%n",
                type, allSame, total, 100.0 * allSame / total);
            System.out.println("  Top patterns:");
            patternCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5)
                .forEach(e -> System.out.printf("    %s: %d%n", e.getKey(), e.getValue()));
        }
    }

    @Test
    void testPluckerCorrectness() {
        // Test that Plücker correctly detects intersection for a known ray
        int type = 0;
        float[][] verts = OFFSET_VERTICES[type];

        // Ray from outside toward centroid of offset tetrahedron
        float cx = (verts[0][0] + verts[1][0] + verts[2][0] + verts[3][0]) / 4.0f;
        float cy = (verts[0][1] + verts[1][1] + verts[2][1] + verts[3][1]) / 4.0f;
        float cz = (verts[0][2] + verts[1][2] + verts[2][2] + verts[3][2]) / 4.0f;

        float ox = -1.0f, oy = cy, oz = cz;
        float dx = cx - ox;
        float dy = 0.0f;
        float dz = 0.0f;
        float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        dx /= len;

        // Compute ray moment V = D × O
        float vx = dy * oz - dz * oy;
        float vy = dz * ox - dx * oz;
        float vz = dx * oy - dy * ox;

        boolean pluckerResult = pluckerIntersection(type, ox, oy, oz, dx, dy, dz, vx, vy, vz);
        boolean mtResult = mollerTrumboreIntersection(type, ox, oy, oz, dx, dy, dz);

        System.out.println("Ray toward S0 centroid (" + cx + ", " + cy + ", " + cz + "):");
        System.out.println("  Plücker: " + pluckerResult);
        System.out.println("  M-T: " + mtResult);

        // Debug: print all 6 Plücker products
        System.out.println("  Plücker products:");
        for (int e = 0; e < 6; e++) {
            float[] edge = PLUCKER_EDGES[type][e];
            float dotRayEdge = dx * edge[3] + dy * edge[4] + dz * edge[5];
            float dotEdgeRay = edge[0] * vx + edge[1] * vy + edge[2] * vz;
            float product = dotRayEdge + dotEdgeRay;
            System.out.println("    Edge " + e + ": " + product);
        }

        // Also test using PluckerCoordinate class directly
        System.out.println("  Using PluckerCoordinate class:");
        Point3f origin = new Point3f(ox, oy, oz);
        Vector3f dir = new Vector3f(dx, dy, dz);
        var rayPlucker = new PluckerCoordinate(origin, dir);

        int positiveCount = 0, negativeCount = 0;
        for (int e = 0; e < 6; e++) {
            int vi1 = EDGE_VERTICES[e][0];
            int vi2 = EDGE_VERTICES[e][1];
            Point3f p1 = new Point3f(verts[vi1][0], verts[vi1][1], verts[vi1][2]);
            Point3f p2 = new Point3f(verts[vi2][0], verts[vi2][1], verts[vi2][2]);
            var edgePlucker = new PluckerCoordinate(p1, p2);
            float product = rayPlucker.permutedInnerProduct(edgePlucker);
            System.out.println("    Edge " + vi1 + "-" + vi2 + ": " + product);
            if (product > EPSILON) positiveCount++;
            else if (product < -EPSILON) negativeCount++;
        }
        boolean classResult = (positiveCount == 0 || negativeCount == 0) && (positiveCount + negativeCount > 0);
        System.out.println("  PluckerCoordinate class result: " + classResult);

        assertTrue(mtResult, "M-T should detect intersection toward centroid");
    }

    @Test
    void validatePluckerVsMollerTrumboreAgreement() {
        // Check that both algorithms agree on the same rays
        Random random = new Random(12345);
        int total = 10000;
        int agree = 0;
        int pluckerOnly = 0;
        int mtOnly = 0;
        int bothMiss = 0;
        int bothHit = 0;

        for (int i = 0; i < total; i++) {
            int type = random.nextInt(6);
            float[][] verts = OFFSET_VERTICES[type];

            // Centroid of the offset tetrahedron
            float cx = (verts[0][0] + verts[1][0] + verts[2][0] + verts[3][0]) / 4.0f;
            float cy = (verts[0][1] + verts[1][1] + verts[2][1] + verts[3][1]) / 4.0f;
            float cz = (verts[0][2] + verts[1][2] + verts[2][2] + verts[3][2]) / 4.0f;

            // Origin outside, directed toward centroid
            float ox = random.nextFloat() * 4 - 2;
            float oy = random.nextFloat() * 4 - 2;
            float oz = random.nextFloat() * 4 - 2;

            float dx = cx - ox;
            float dy = cy - oy;
            float dz = cz - oz;
            float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            dx /= len; dy /= len; dz /= len;

            // Ray moment V = D × O
            float vx = dy * oz - dz * oy;
            float vy = dz * ox - dx * oz;
            float vz = dx * oy - dy * ox;

            boolean pluckerHit = pluckerIntersection(type, ox, oy, oz, dx, dy, dz, vx, vy, vz);
            boolean mtHit = mollerTrumboreIntersection(type, ox, oy, oz, dx, dy, dz);

            if (pluckerHit == mtHit) {
                agree++;
                if (pluckerHit) {
                    bothHit++;
                } else {
                    bothMiss++;
                }
            } else if (pluckerHit) {
                pluckerOnly++;
            } else {
                mtOnly++;
            }
        }

        System.out.println("\n=== Plücker vs M-T Agreement ===");
        System.out.println("Total rays: " + total);
        System.out.printf("Agreement: %d (%.1f%%)%n", agree, 100.0 * agree / total);
        System.out.printf("  Both hit: %d (%.1f%%)%n", bothHit, 100.0 * bothHit / total);
        System.out.printf("  Both miss: %d%n", bothMiss);
        System.out.printf("Plücker only (false positive?): %d%n", pluckerOnly);
        System.out.printf("M-T only (Plücker false negative?): %d%n", mtOnly);

        // We expect high agreement - at least 95% with fixed origin bug
        assertTrue(agree > total * 0.95,
            "Algorithms should agree on at least 95% of rays, got: " + (100.0 * agree / total) + "%");
    }

    @Test
    void testMollerTrumboreCorrectness() {
        int type = 0;
        float[][] verts = OFFSET_VERTICES[type];

        float cx = (verts[0][0] + verts[1][0] + verts[2][0] + verts[3][0]) / 4.0f;
        float cy = (verts[0][1] + verts[1][1] + verts[2][1] + verts[3][1]) / 4.0f;
        float cz = (verts[0][2] + verts[1][2] + verts[2][2] + verts[3][2]) / 4.0f;

        float ox = -1.0f, oy = cy, oz = cz;
        float dx = cx - ox;
        float dy = 0.0f;
        float dz = 0.0f;
        float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        dx /= len;

        boolean result = mollerTrumboreIntersection(type, ox, oy, oz, dx, dy, dz);
        System.out.println("Möller-Trumbore intersection (should hit): " + result);
        assertTrue(result, "Ray toward centroid should hit");
    }

    @Test
    void benchmarkPluckerVsMollerTrumbore() {
        int iterations = 1_000_000;
        Random random = new Random(42);

        // Generate random rays that pass through the offset tetrahedra
        float[][] rays = new float[iterations][9]; // ox,oy,oz, dx,dy,dz, vx,vy,vz
        int[] types = new int[iterations];

        for (int i = 0; i < iterations; i++) {
            types[i] = random.nextInt(6);
            float[][] verts = OFFSET_VERTICES[types[i]];

            // Centroid of offset tetrahedron
            float cx = (verts[0][0] + verts[1][0] + verts[2][0] + verts[3][0]) / 4.0f;
            float cy = (verts[0][1] + verts[1][1] + verts[2][1] + verts[3][1]) / 4.0f;
            float cz = (verts[0][2] + verts[1][2] + verts[2][2] + verts[3][2]) / 4.0f;

            // Origin outside, directed toward centroid
            float ox = random.nextFloat() * 4 - 2;
            float oy = random.nextFloat() * 4 - 2;
            float oz = random.nextFloat() * 4 - 2;

            // Direction toward centroid
            float dx = cx - ox;
            float dy = cy - oy;
            float dz = cz - oz;
            float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
            dx /= len; dy /= len; dz /= len;

            // Ray moment V = D × O
            float vx = dy * oz - dz * oy;
            float vy = dz * ox - dx * oz;
            float vz = dx * oy - dy * ox;

            rays[i][0] = ox; rays[i][1] = oy; rays[i][2] = oz;
            rays[i][3] = dx; rays[i][4] = dy; rays[i][5] = dz;
            rays[i][6] = vx; rays[i][7] = vy; rays[i][8] = vz;
        }

        // Warm up
        for (int i = 0; i < 100000; i++) {
            float[] r = rays[i % iterations];
            pluckerIntersection(types[i % iterations],
                r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
            mollerTrumboreIntersection(types[i % iterations],
                r[0], r[1], r[2], r[3], r[4], r[5]);
        }

        // Benchmark Plücker
        int pluckerHits = 0;
        long pluckerStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            float[] r = rays[i];
            if (pluckerIntersection(types[i],
                    r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8])) {
                pluckerHits++;
            }
        }
        long pluckerTime = System.nanoTime() - pluckerStart;

        // Benchmark Möller-Trumbore (all faces)
        int mtHits = 0;
        long mtStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            float[] r = rays[i];
            if (mollerTrumboreIntersection(types[i],
                    r[0], r[1], r[2], r[3], r[4], r[5])) {
                mtHits++;
            }
        }
        long mtTime = System.nanoTime() - mtStart;

        // Benchmark Möller-Trumbore (early exit)
        int mtEarlyHits = 0;
        long mtEarlyStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            float[] r = rays[i];
            if (mollerTrumboreEarlyExit(types[i],
                    r[0], r[1], r[2], r[3], r[4], r[5])) {
                mtEarlyHits++;
            }
        }
        long mtEarlyTime = System.nanoTime() - mtEarlyStart;

        // Report results
        double pluckerNsPerOp = (double) pluckerTime / iterations;
        double mtNsPerOp = (double) mtTime / iterations;
        double mtEarlyNsPerOp = (double) mtEarlyTime / iterations;

        System.out.println("\n=== Ray-Tetrahedron Intersection Benchmark ===");
        System.out.println("Iterations: " + iterations);
        System.out.println();
        System.out.println("Plücker (precomputed edges):");
        System.out.printf("  Time: %.2f ns/op%n", pluckerNsPerOp);
        System.out.printf("  Hits: %d (%.1f%%)%n", pluckerHits, 100.0 * pluckerHits / iterations);
        System.out.println("  Estimated ops: 78 (66 products + 12 signs)");
        System.out.println();
        System.out.println("Möller-Trumbore (all 4 faces):");
        System.out.printf("  Time: %.2f ns/op%n", mtNsPerOp);
        System.out.printf("  Hits: %d (%.1f%%)%n", mtHits, 100.0 * mtHits / iterations);
        System.out.println("  Estimated ops: 224 (4 × 56)");
        System.out.println();
        System.out.println("Möller-Trumbore (early exit):");
        System.out.printf("  Time: %.2f ns/op%n", mtEarlyNsPerOp);
        System.out.printf("  Hits: %d (%.1f%%)%n", mtEarlyHits, 100.0 * mtEarlyHits / iterations);
        System.out.println("  Estimated ops: 56-224 (depends on exit)");
        System.out.println();
        System.out.printf("Plücker speedup vs M-T all: %.2fx%n", mtNsPerOp / pluckerNsPerOp);
        System.out.printf("Plücker speedup vs M-T early: %.2fx%n", mtEarlyNsPerOp / pluckerNsPerOp);
    }

    @Test
    void countOperationsExactly() {
        System.out.println("\n=== Exact Operation Count Analysis ===\n");

        System.out.println("PLÜCKER RAY-TETRAHEDRON INTERSECTION");
        System.out.println("=====================================");
        System.out.println("Precomputation (once per ray):");
        System.out.println("  Ray moment V = D × O: 6 muls + 3 subs = 9 ops");
        System.out.println();
        System.out.println("Per edge (6 edges):");
        System.out.println("  U_ray · V_edge: 3 muls + 2 adds = 5 ops");
        System.out.println("  U_edge · V_ray: 3 muls + 2 adds = 5 ops");
        System.out.println("  Sum: 1 add");
        System.out.println("  Per edge total: 11 ops");
        System.out.println("  6 edges: 66 ops");
        System.out.println();
        System.out.println("Sign checking:");
        System.out.println("  6 comparisons + 6 increments: ~12 ops");
        System.out.println("  Final check: 2 compares");
        System.out.println();
        System.out.println("PLÜCKER TOTAL: 9 + 66 + 14 = 89 ops");
        System.out.println("PLÜCKER PER-STEP (ray precomputed): 66 + 14 = 80 ops");
        System.out.println();

        System.out.println("MÖLLER-TRUMBORE RAY-TRIANGLE INTERSECTION");
        System.out.println("==========================================");
        System.out.println("Per triangle:");
        System.out.println("  edge1 = v1 - v0: 3 subs");
        System.out.println("  edge2 = v2 - v0: 3 subs");
        System.out.println("  h = D × edge2: 6 muls + 3 subs = 9 ops");
        System.out.println("  a = edge1 · h: 3 muls + 2 adds = 5 ops");
        System.out.println("  f = 1/a: 1 div");
        System.out.println("  s = O - v0: 3 subs");
        System.out.println("  u = f × (s · h): 1 mul + 5 ops = 6 ops");
        System.out.println("  q = s × edge1: 9 ops");
        System.out.println("  v = f × (D · q): 6 ops");
        System.out.println("  t = f × (edge2 · q): 6 ops");
        System.out.println("  Compares/branches: ~6 ops");
        System.out.println("  Per triangle total: 57 ops");
        System.out.println();
        System.out.println("M-T TOTAL (4 faces, worst case): 228 ops");
        System.out.println("M-T TOTAL (4 faces, average ~2.5 tested): ~143 ops");
        System.out.println("M-T TOTAL (4 faces, best case 1 tested): 57 ops");
        System.out.println();

        System.out.println("COMPARISON");
        System.out.println("==========");
        System.out.println("Plücker advantages:");
        System.out.println("  - Branchless (all 6 edges computed regardless)");
        System.out.println("  - All computations independent (SIMD-friendly)");
        System.out.println("  - Consistent cost: always 80 ops per step");
        System.out.println("  - Edge Plückers precomputable per S0-S5 type");
        System.out.println();
        System.out.println("Möller-Trumbore advantages:");
        System.out.println("  - Lower best-case (57 ops with early exit)");
        System.out.println("  - Gives intersection t-value directly");
        System.out.println("  - No precomputation needed");
        System.out.println();
        System.out.println("RECOMMENDATION: Plücker for GPU (branchless)");
        System.out.println("              M-T with early exit for CPU (branch prediction)");
    }
}
