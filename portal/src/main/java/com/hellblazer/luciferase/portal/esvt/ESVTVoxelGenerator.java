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
package com.hellblazer.luciferase.portal.esvt;

import com.hellblazer.luciferase.geometry.Point3i;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Voxel generator for ESVT that generates shapes contained within the S0 tetrahedron.
 * Unlike the cubic grid generator, this ensures all voxels fit within the tetrahedral volume.
 *
 * <p>S0 tetrahedron uses cube vertices 0, 1, 3, 7 (from Tet.java):
 * <ul>
 *   <li>v0 = (0, 0, 0) - cube origin</li>
 *   <li>v1 = (1, 0, 0) - +X face</li>
 *   <li>v2 = (1, 1, 0) - +X+Y face (cube vertex 3)</li>
 *   <li>v3 = (1, 1, 1) - far diagonal corner (cube vertex 7)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTVoxelGenerator {

    // S0 tetrahedron vertices (unit cube scale)
    // S0 uses cube vertices 0, 1, 3, 7 (from Tet.java coordinates())
    private static final Point3f V0 = new Point3f(0, 0, 0);  // Cube vertex 0
    private static final Point3f V1 = new Point3f(1, 0, 0);  // Cube vertex 1
    private static final Point3f V2 = new Point3f(1, 1, 0);  // Cube vertex 3 (NOT 1,0,1!)
    private static final Point3f V3 = new Point3f(1, 1, 1);  // Cube vertex 7

    /**
     * Shape types for ESVT generation.
     */
    public enum Shape {
        /** Sphere inscribed within S0 tetrahedron */
        SPHERE,
        /** Full S0 tetrahedron filled with voxels */
        TETRAHEDRON,
        /** Torus inscribed within S0 */
        TORUS
    }

    /**
     * Generate voxels for the specified shape at given resolution,
     * contained within the S0 tetrahedron.
     *
     * @param shape Shape type to generate
     * @param resolution Grid resolution (voxels per dimension)
     * @return List of voxel coordinates
     */
    public List<Point3i> generate(Shape shape, int resolution) {
        return switch (shape) {
            case SPHERE -> generateInscribedSphere(resolution);
            case TETRAHEDRON -> generateTetrahedron(resolution);
            case TORUS -> generateInscribedTorus(resolution);
        };
    }

    /**
     * Generate a sphere inscribed within the S0 tetrahedron.
     * The sphere is centered at the incenter with radius = inradius.
     *
     * @param resolution Grid resolution
     * @return Voxel coordinates forming an inscribed sphere
     */
    public List<Point3i> generateInscribedSphere(int resolution) {
        var voxels = new ArrayList<Point3i>();

        // Calculate incenter and inradius for S0 tetrahedron
        var incenter = calculateIncenter();
        var inradius = calculateInradius();

        // Scale to resolution
        float scale = resolution;
        float cx = incenter.x * scale;
        float cy = incenter.y * scale;
        float cz = incenter.z * scale;
        float r = inradius * scale * 0.95f; // 95% to avoid edge cases
        float rSquared = r * r;

        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    // Check if inside sphere
                    float dx = x + 0.5f - cx;
                    float dy = y + 0.5f - cy;
                    float dz = z + 0.5f - cz;
                    float distSquared = dx * dx + dy * dy + dz * dz;

                    if (distSquared <= rSquared) {
                        // Also verify it's inside S0 (should be, but check for edge cases)
                        float nx = (x + 0.5f) / scale;
                        float ny = (y + 0.5f) / scale;
                        float nz = (z + 0.5f) / scale;

                        if (isInsideS0(nx, ny, nz)) {
                            voxels.add(new Point3i(x, y, z));
                        }
                    }
                }
            }
        }

        return voxels;
    }

    /**
     * Generate voxels filling the S0 tetrahedron.
     *
     * @param resolution Grid resolution
     * @return Voxel coordinates forming S0 tetrahedron
     */
    public List<Point3i> generateTetrahedron(int resolution) {
        var voxels = new ArrayList<Point3i>();
        float scale = resolution;

        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    // Normalize to unit cube and check if inside S0
                    float nx = (x + 0.5f) / scale;
                    float ny = (y + 0.5f) / scale;
                    float nz = (z + 0.5f) / scale;

                    if (isInsideS0(nx, ny, nz)) {
                        voxels.add(new Point3i(x, y, z));
                    }
                }
            }
        }

        return voxels;
    }

    /**
     * Generate a torus inscribed within S0 tetrahedron.
     *
     * @param resolution Grid resolution
     * @return Voxel coordinates forming an inscribed torus
     */
    public List<Point3i> generateInscribedTorus(int resolution) {
        var voxels = new ArrayList<Point3i>();

        var incenter = calculateIncenter();
        var inradius = calculateInradius();

        float scale = resolution;
        float cx = incenter.x * scale;
        float cy = incenter.y * scale;
        float cz = incenter.z * scale;

        // Torus parameters - fit within inscribed sphere
        float majorRadius = inradius * scale * 0.5f;
        float minorRadius = inradius * scale * 0.25f;
        float minorRadiusSquared = minorRadius * minorRadius;

        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    float dx = x + 0.5f - cx;
                    float dy = y + 0.5f - cy;
                    float dz = z + 0.5f - cz;

                    // Distance from center to XZ plane circle
                    float distToCenter = (float) Math.sqrt(dx * dx + dz * dz);
                    float distToMajor = distToCenter - majorRadius;

                    // Distance from major radius circle
                    float torusDistSquared = distToMajor * distToMajor + dy * dy;

                    if (torusDistSquared <= minorRadiusSquared) {
                        // Verify inside S0
                        float nx = (x + 0.5f) / scale;
                        float ny = (y + 0.5f) / scale;
                        float nz = (z + 0.5f) / scale;

                        if (isInsideS0(nx, ny, nz)) {
                            voxels.add(new Point3i(x, y, z));
                        }
                    }
                }
            }
        }

        return voxels;
    }

    /**
     * Calculate the incenter of S0 tetrahedron.
     * Incenter = weighted average of vertices by opposite face areas.
     */
    private Point3f calculateIncenter() {
        // Calculate face areas
        float a0 = triangleArea(V1, V2, V3); // Face opposite V0
        float a1 = triangleArea(V0, V2, V3); // Face opposite V1
        float a2 = triangleArea(V0, V1, V3); // Face opposite V2
        float a3 = triangleArea(V0, V1, V2); // Face opposite V3

        float total = a0 + a1 + a2 + a3;

        // Incenter = (a0*V0 + a1*V1 + a2*V2 + a3*V3) / total
        float ix = (a0 * V0.x + a1 * V1.x + a2 * V2.x + a3 * V3.x) / total;
        float iy = (a0 * V0.y + a1 * V1.y + a2 * V2.y + a3 * V3.y) / total;
        float iz = (a0 * V0.z + a1 * V1.z + a2 * V2.z + a3 * V3.z) / total;

        return new Point3f(ix, iy, iz);
    }

    /**
     * Calculate the inradius of S0 tetrahedron.
     * Inradius = 3 * Volume / Surface Area
     */
    private float calculateInradius() {
        float volume = tetrahedronVolume(V0, V1, V2, V3);
        float surfaceArea = triangleArea(V0, V1, V2) + triangleArea(V0, V1, V3) +
                           triangleArea(V0, V2, V3) + triangleArea(V1, V2, V3);
        return 3.0f * volume / surfaceArea;
    }

    /**
     * Calculate triangle area using cross product.
     */
    private float triangleArea(Point3f a, Point3f b, Point3f c) {
        var ab = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
        var ac = new Vector3f(c.x - a.x, c.y - a.y, c.z - a.z);

        // Cross product
        var cross = new Vector3f();
        cross.cross(ab, ac);

        return cross.length() / 2.0f;
    }

    /**
     * Calculate tetrahedron volume using scalar triple product.
     */
    private float tetrahedronVolume(Point3f a, Point3f b, Point3f c, Point3f d) {
        var ab = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
        var ac = new Vector3f(c.x - a.x, c.y - a.y, c.z - a.z);
        var ad = new Vector3f(d.x - a.x, d.y - a.y, d.z - a.z);

        // Cross product ab x ac
        var cross = new Vector3f();
        cross.cross(ab, ac);

        // Dot with ad
        return Math.abs(cross.dot(ad)) / 6.0f;
    }

    /**
     * Test if a point is inside the S0 tetrahedron using barycentric coordinates.
     * A point is inside if all barycentric coordinates are non-negative.
     */
    private boolean isInsideS0(float px, float py, float pz) {
        // Use same-side test for each face
        // Point is inside if it's on the same side of all faces as the opposite vertex

        var p = new Point3f(px, py, pz);

        // For each face, check if p is on same side as opposite vertex
        return sameSide(p, V0, V1, V2, V3) &&
               sameSide(p, V1, V0, V2, V3) &&
               sameSide(p, V2, V0, V1, V3) &&
               sameSide(p, V3, V0, V1, V2);
    }

    /**
     * Check if point p is on the same side of plane (v1,v2,v3) as point v0.
     */
    private boolean sameSide(Point3f p, Point3f v0, Point3f v1, Point3f v2, Point3f v3) {
        var normal = planeNormal(v1, v2, v3);

        // Vector from v1 to v0
        var toV0 = new Vector3f(v0.x - v1.x, v0.y - v1.y, v0.z - v1.z);
        // Vector from v1 to p
        var toP = new Vector3f(p.x - v1.x, p.y - v1.y, p.z - v1.z);

        float dotV0 = normal.dot(toV0);
        float dotP = normal.dot(toP);

        // Same side if signs match (or p is on the plane)
        return dotV0 * dotP >= -1e-6f;
    }

    /**
     * Calculate plane normal from three points.
     */
    private Vector3f planeNormal(Point3f a, Point3f b, Point3f c) {
        var ab = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
        var ac = new Vector3f(c.x - a.x, c.y - a.y, c.z - a.z);

        var normal = new Vector3f();
        normal.cross(ab, ac);
        if (normal.length() > 0) {
            normal.normalize();
        }
        return normal;
    }

    /**
     * Get the incenter of S0 (for external use).
     */
    public Point3f getS0Incenter() {
        return calculateIncenter();
    }

    /**
     * Get the inradius of S0 (for external use).
     */
    public float getS0Inradius() {
        return calculateInradius();
    }
}
