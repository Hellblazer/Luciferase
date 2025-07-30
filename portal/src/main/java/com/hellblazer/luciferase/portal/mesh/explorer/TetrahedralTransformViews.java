/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;

import javax.vecmath.Point3i;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages tetrahedral meshviews based on transformations and reference meshes for efficient rendering. This class
 * creates and caches the 6 reference meshes (one for each characteristic tetrahedron type S0-S5) and provides
 * transformation calculations to position, scale, and orient instances as needed.
 *
 * @author hal.hildebrand
 */
public class TetrahedralTransformViews {
    private final TriangleMesh[]            referenceMeshes = new TriangleMesh[6];
    private final Map<TetreeKey<?>, Affine> transformCache  = new HashMap<>();

    /**
     * Initialize the transform manager with reference meshes and materials.
     */
    public TetrahedralTransformViews() {
        // Create reference mesh for each tetrahedron type
        for (int type = 0; type < 6; type++) {
            // Get the standard simplex vertices for this type from Constants
            Point3i[] vertices = Constants.SIMPLEX_STANDARD[type];

            // Create unit-sized reference mesh at origin
            TriangleMesh mesh = createUnitTetrahedronMesh(vertices);
            referenceMeshes[type] = mesh;
        }
    }

    /**
     * Clear the transform cache. Useful when switching between different visualizations or when memory needs to be
     * freed.
     */
    public void clearTransformCache() {
        transformCache.clear();
    }

    /**
     * Get statistics about the current state of the manager.
     *
     * @return A map containing statistics
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("referenceMeshCount", referenceMeshes.length);
        stats.put("transformCacheSize", transformCache.size());
        return stats;
    }

    public MeshView of(Tet tet) {
        TriangleMesh referenceMesh = referenceMeshes[(int) tet.type()];

        if (referenceMesh == null) {
            System.err.println("ERROR: No reference mesh for type " + tet.type());
            return null;
        }

        // Create mesh view (this is lightweight - just references the mesh)
        MeshView meshView = new MeshView(referenceMesh);

        // Calculate and apply transform
        Affine transform = calculateTransform(tet);
        meshView.getTransforms().add(transform);
        return meshView;
    }

    /**
     * Calculate the transform needed to position and scale a tetrahedron.
     *
     * @param tet The tetrahedron
     * @return The affine transform
     */
    private Affine calculateTransform(Tet tet) {
        // Check cache first
        var cacheKey = tet.tmIndex();
        Affine cached = transformCache.get(cacheKey);
        if (cached != null) {
            return new Affine(cached); // Return copy
        }

        // Get the actual position and scale from the Tet
        Point3i anchor = tet.anchor();
        int edgeLength = tet.length();

        // Create transform
        Affine transform = new Affine();

        // Scale from unit cube to actual size and translate to position
        transform.appendScale(edgeLength, edgeLength, edgeLength);
        transform.appendTranslation(anchor.x, anchor.y, anchor.z);

        // Cache the transform
        transformCache.put(cacheKey, new Affine(transform));

        return transform;
    }

    /**
     * Create a unit tetrahedron mesh with vertices in standard positions.
     *
     * @param vertices The vertices of the tetrahedron
     * @return A TriangleMesh representing the tetrahedron
     */
    private TriangleMesh createUnitTetrahedronMesh(Point3i[] vertices) {
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices as floats (unit cube coordinates)
        for (Point3i v : vertices) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }

        // Add texture coordinates
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Add faces with correct winding for outward normals
        // Fixed: reversed winding order to produce outward-pointing normals
        mesh.getFaces().addAll(0, 0, 1, 1, 2, 2,  // Face 0-1-2 (was 0-2-1)
                               0, 0, 3, 3, 1, 1,  // Face 0-3-1 (was 0-1-3)
                               0, 0, 2, 2, 3, 3,  // Face 0-2-3 (was 0-3-2)
                               1, 1, 3, 3, 2, 2   // Face 1-3-2 (was 1-2-3)
                              );

        return mesh;
    }

    /**
     * Get type-specific rotation if needed. This handles the different orientations of types 1-5 relative to type 0.
     *
     * @param type The tetrahedron type (0-5)
     * @return The rotation transform, or null if no rotation is needed
     */
    private Affine getTypeSpecificRotation(int type) {
        Affine rotation = new Affine();

        switch (type) {
            case 0:
                // S0 is reference - no rotation needed
                return null;

            case 1:
                // S1: 120° rotation around (1,1,1) axis
                rotation.appendRotation(120, 0.5, 0.5, 0.5, 1, 1, 1);
                break;

            case 2:
                // S2: 90° rotation around Z axis
                rotation.appendRotation(90, 0.5, 0.5, 0.5, 0, 0, 1);
                break;

            case 3:
                // S3: 240° rotation around (1,1,1) axis
                rotation.appendRotation(240, 0.5, 0.5, 0.5, 1, 1, 1);
                break;

            case 4:
                // S4: 90° rotation around X axis
                rotation.appendRotation(90, 0.5, 0.5, 0.5, 1, 0, 0);
                break;

            case 5:
                // S5: -120° rotation around (1,1,1) axis
                rotation.appendRotation(-120, 0.5, 0.5, 0.5, 1, 1, 1);
                break;
        }

        return rotation;
    }
}
