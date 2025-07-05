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
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;

import javax.vecmath.Point3i;
import java.util.HashMap;
import java.util.Map;

/**
 * Transform-based Tetree visualization that uses JavaFX transforms efficiently. Creates only 6 reference meshes (one
 * for each characteristic tetrahedron type S0-S5) and uses transforms to position, scale, and orient instances as
 * needed.
 *
 * This approach significantly reduces memory usage and improves performance compared to creating individual mesh
 * instances for each tetrahedron.
 *
 * @param <ID>      The entity identifier type
 * @param <Content> The entity content type
 * @author hal.hildebrand
 */
public class TransformBasedTetreeVisualization<ID extends EntityID, Content> {

    // Reference meshes for each tetrahedron type (S0-S5)
    private final Map<Integer, TriangleMesh> referenceMeshes = new HashMap<>();

    // Materials for each type
    private final Map<Integer, Material> typeMaterials = new HashMap<>();

    // Scene root
    private final Group sceneRoot = new Group();

    // Cache of transforms for reuse
    private final Map<String, Affine> transformCache = new HashMap<>();

    /**
     * Initialize the transform-based visualization.
     */
    public TransformBasedTetreeVisualization() {
        initializeReferenceMeshes();
        initializeMaterials();
    }

    /**
     * Add a tetrahedron instance to the scene using transforms.
     *
     * @param tet     The tetrahedron to visualize
     * @param opacity The opacity for the tetrahedron
     * @return The transformed mesh view
     */
    public MeshView addTetrahedronInstance(Tet tet, double opacity) {
        // Get reference mesh for this type
        TriangleMesh referenceMesh = referenceMeshes.get((int) tet.type());

        if (referenceMesh == null) {
            System.err.println("ERROR: No reference mesh for type " + tet.type());
            return null;
        }

        // Create mesh view (this is lightweight - just references the mesh)
        MeshView meshView = new MeshView(referenceMesh);

        // Apply material
        PhongMaterial material = new PhongMaterial();
        Color baseColor = getColorForType(tet.type());
        material.setDiffuseColor(baseColor.deriveColor(0, 1, 1, opacity));
        material.setSpecularColor(baseColor.brighter());
        meshView.setMaterial(material);
        meshView.setOpacity(opacity);

        // Calculate and apply transform
        Affine transform = calculateTransform(tet);
        meshView.getTransforms().add(transform);

        // Add to scene
        sceneRoot.getChildren().add(meshView);

        return meshView;
    }

    /**
     * Clear all tetrahedra from the scene.
     */
    public void clear() {
        sceneRoot.getChildren().clear();
        // Keep reference meshes and materials - they can be reused
    }

    /**
     * Demonstrate the efficiency of this approach.
     */
    public void demonstrateUsage(Tetree<ID, Content> tetree) {
        // Clear existing visualization
        clear();

        // Add all tetrahedra from the tetree
        System.out.println("Tetree has " + tetree.nodes().count() + " nodes");
        tetree.nodes().forEach(node -> {
            TetreeKey<? extends TetreeKey> key = node.sfcIndex();
            Tet tet = Tet.tetrahedron(key);

            System.out.println(
            "Adding tet: type=" + tet.type() + ", level=" + tet.l() + ", pos=(" + tet.x() + "," + tet.y() + ","
            + tet.z() + ")");

            // All tetrahedra share the same 6 reference meshes
            // Only the transforms are different
            addTetrahedronInstance(tet, 0.3);
        });

        // Print verification info
        int tetCount = sceneRoot.getChildren().size();
        System.out.println("\n=== Transform-Based Rendering Active ===");
        System.out.println("Created " + tetCount + " tetrahedra using only 6 reference meshes");
        System.out.println("Reference meshes: " + referenceMeshes.size());
        System.out.println("Transform cache size: " + transformCache.size());
        if (tetCount > 0) {
            System.out.println("Memory saved: ~" + ((tetCount - 6) * 100 / tetCount) + "%");
        } else {
            System.out.println("No tetrahedra to display - add some entities first!");
        }
        System.out.println("=====================================\n");
    }

    /**
     * Get the scene root.
     */
    public Group getSceneRoot() {
        return sceneRoot;
    }

    /**
     * Calculate the transform needed to position and scale a tetrahedron.
     *
     * @param tet The tetrahedron
     * @return The affine transform
     */
    private Affine calculateTransform(Tet tet) {
        // Check cache first
        String cacheKey = tet.tmIndex().toString();
        Affine cached = transformCache.get(cacheKey);
        if (cached != null) {
            return new Affine(cached); // Return copy
        }

        // Get the actual position and scale from the Tet
        Point3i anchor = tet.coordinates()[0];
        int edgeLength = tet.length();

        // Debug: Show actual positions
        if (transformCache.size() < 5) { // Only log first few
            System.out.println(
            "Transform for tet type=" + tet.type() + ": anchor=(" + anchor.x + "," + anchor.y + "," + anchor.z
            + "), edgeLength=" + edgeLength);
        }

        // Create transform
        Affine transform = new Affine();

        // For types other than S0, apply rotation first
        if (tet.type() != 0) {
            Affine typeRotation = getTypeSpecificRotation(tet.type());
            if (typeRotation != null) {
                transform = typeRotation;
            }
        }

        // Scale from unit cube to actual size and translate to position
        // This matches how TetreeVisualization creates its meshes
        transform.appendScale(edgeLength, edgeLength, edgeLength);
        transform.appendTranslation(anchor.x, anchor.y, anchor.z);

        // Cache the transform
        transformCache.put(cacheKey, new Affine(transform));

        return transform;
    }

    /**
     * Create a unit tetrahedron mesh with vertices in standard positions.
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
        // These reference tetrahedra use the t8code vertex ordering
        mesh.getFaces().addAll(0, 0, 2, 2, 1, 1,  // Face 0-2-1
                               0, 0, 1, 1, 3, 3,  // Face 0-1-3
                               0, 0, 3, 3, 2, 2,  // Face 0-3-2
                               1, 1, 2, 2, 3, 3   // Face 1-2-3
                              );

        return mesh;
    }

    /**
     * Get color for tetrahedron type.
     */
    private Color getColorForType(int type) {
        Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN };
        return colors[type % 6];
    }

    /**
     * Get type-specific rotation if needed. This handles the different orientations of types 1-5 relative to type 0.
     *
     * Note: With t8code vertex ordering, the rotational relationships between types may be different
     * than with SIMPLEX_STANDARD. These rotations may need adjustment based on actual geometry.
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

    /**
     * Create materials for each tetrahedron type.
     */
    private void initializeMaterials() {
        Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN };

        for (int i = 0; i < 6; i++) {
            PhongMaterial material = new PhongMaterial(colors[i].deriveColor(0, 1, 1, 0.3));
            material.setSpecularColor(colors[i].brighter());
            typeMaterials.put(i, material);
        }
    }

    /**
     * Create the 6 reference meshes for characteristic tetrahedra. These are unit tetrahedra at the origin that will be
     * transformed as needed.
     */
    private void initializeReferenceMeshes() {
        // Create reference mesh for each tetrahedron type using t8code algorithm
        for (int type = 0; type < 6; type++) {
            // Create a unit tetrahedron at level 0 (size 1) to get the vertex pattern
            Tet unitTet = new Tet(0, 0, 0, (byte) 21, (byte) type); // Level 21 gives size 1
            Point3i[] vertices = unitTet.coordinates();

            // Create unit-sized reference mesh at origin
            TriangleMesh mesh = createUnitTetrahedronMesh(vertices);
            referenceMeshes.put(type, mesh);
        }
    }
}
