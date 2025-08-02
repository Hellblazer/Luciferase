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

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;

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

    // Default colors for each tetrahedron type
    private static final Color[]                   TYPE_COLORS      = { Color.RED, Color.GREEN, Color.BLUE,
                                                                        Color.YELLOW, Color.MAGENTA, Color.CYAN };
    // Transform manager handles mesh creation and transformations
    private final        PrimitiveTransformManager transformManager = new PrimitiveTransformManager();
    // Scene root
    private final        Group                     sceneRoot        = new Group();
    // Materials for each type
    private final        Map<Integer, Material>    typeMaterials    = new HashMap<>();

    /**
     * Demonstrate the efficiency of this approach.
     */
    public void demonstrateUsage(Tetree<ID, Content> tetree) {
        // Clear existing visualization
        sceneRoot.getChildren().clear();
        // The transform manager keeps reference meshes and materials - they can be reused

        // Debug: count nodes
        long nodeCount = tetree.nodes().count();
        System.out.println("TransformBasedTetreeVisualization: Processing " + nodeCount + " nodes");

        // Add all tetrahedra from the tetree
        tetree.nodes().forEach(node -> {
            TetreeKey key = node.sfcIndex();
            Tet tet = key.toTet();

            // All tetrahedra share the same 6 reference meshes
            // Only the transforms are different
            // Use higher opacity for nodes with entities
            double opacity = node.entityIds().isEmpty() ? 0.3 : 0.7;
            addTetrahedronInstance(tet, opacity);

            // Add entity spheres for this node
            for (ID entityId : node.entityIds()) {
                javax.vecmath.Point3f entityPos = tetree.getEntityPosition(entityId);
                if (entityPos != null) {
                    // Calculate sphere size based on tetrahedron level
                    float tetSize = (float) (1 << (com.hellblazer.luciferase.geometry.MortonCurve.MAX_REFINEMENT_LEVEL
                                                   - tet.l));
                    float sphereRadius = tetSize * 0.05f; // 5% of tet size

                    Sphere entitySphere = new Sphere(sphereRadius);
                    entitySphere.setTranslateX(entityPos.x);
                    entitySphere.setTranslateY(entityPos.y);
                    entitySphere.setTranslateZ(entityPos.z);

                    PhongMaterial entityMaterial = new PhongMaterial(Color.YELLOW);
                    entityMaterial.setSpecularColor(Color.YELLOW.brighter());
                    entitySphere.setMaterial(entityMaterial);

                    sceneRoot.getChildren().add(entitySphere);
                }
            }
        });
    }

    /**
     * Get the scene root.
     */
    public Group getSceneRoot() {
        return sceneRoot;
    }

    /**
     * Add a tetrahedron instance to the scene using transforms.
     *
     * @param tet     The tetrahedron to visualize
     * @param opacity The opacity for the tetrahedron
     * @return The transformed mesh view
     */
    private void addTetrahedronInstance(Tet tet, double opacity) {
        // Get color and create material
        int type = tet.type();
        Color baseColor = TYPE_COLORS[type % TYPE_COLORS.length];
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(baseColor.deriveColor(0, 1, 1, opacity));
        material.setSpecularColor(baseColor.brighter());

        // Create tetrahedron using PrimitiveTransformManager
        // Calculate edge length from level (same as Octree - halves at each level)
        float edgeLength = (float) (1 << (com.hellblazer.luciferase.geometry.MortonCurve.MAX_REFINEMENT_LEVEL - tet.l));

        // Debug output
        System.out.println(
        "Adding tetrahedron: type=" + type + ", position=(" + tet.x() + "," + tet.y() + "," + tet.z() + "), level="
        + tet.l + ", edgeLength=" + edgeLength);

        MeshView meshView = transformManager.createTetrahedron(type,
                                                               new javax.vecmath.Point3f(tet.x(), tet.y(), tet.z()),
                                                               edgeLength, material);

        meshView.setOpacity(opacity);

        // Add to scene
        sceneRoot.getChildren().add(meshView);
    }
}
