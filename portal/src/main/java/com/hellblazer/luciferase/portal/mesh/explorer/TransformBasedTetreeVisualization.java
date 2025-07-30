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
    private final        TetrahedralTransformViews transformManager = new TetrahedralTransformViews();
    // Scene root
    private final        Group                     sceneRoot        = new Group();
    // Materials for each type
    private final        Map<Integer, Material>      typeMaterials    = new HashMap<>();

    /**
     * Initialize the transform-based visualization.
     */
    public TransformBasedTetreeVisualization() {
        // Initialization is handled by the transform manager
    }

    /**
     * Add a tetrahedron instance to the scene using transforms.
     *
     * @param tet     The tetrahedron to visualize
     * @param opacity The opacity for the tetrahedron
     * @return The transformed mesh view
     */
    public MeshView addTetrahedronInstance(Tet tet, double opacity) {
        var meshView = transformManager.of(tet);

        // Apply material
        int type = tet.type();
        Color baseColor = getColorForType(type);
        PhongMaterial material1 = new PhongMaterial();
        material1.setDiffuseColor(baseColor.deriveColor(0, 1, 1, opacity));
        material1.setSpecularColor(baseColor.brighter());
        PhongMaterial material = material1;
        meshView.setMaterial(material);
        meshView.setOpacity(opacity);

        // Add to scene
        sceneRoot.getChildren().add(meshView);

        return meshView;
    }

    /**
     * Clear all tetrahedra from the scene.
     */
    public void clear() {
        sceneRoot.getChildren().clear();
        // The transform manager keeps reference meshes and materials - they can be reused
    }

    /**
     * Clear the transform cache. Useful when switching between different visualizations.
     */
    public void clearTransformCache() {
        transformManager.clearTransformCache();
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
        Map<String, Integer> stats = transformManager.getStatistics();

        System.out.println("\n=== Transform-Based Rendering Active ===");
        System.out.println(
        "Created " + tetCount + " tetrahedra using only " + stats.get("referenceMeshCount") + " reference meshes");
        System.out.println("Reference meshes: " + stats.get("referenceMeshCount"));
        System.out.println("Transform cache size: " + stats.get("transformCacheSize"));
        if (tetCount > 0) {
            System.out.println("Memory saved: ~" + ((tetCount - stats.get("referenceMeshCount")) * 100 / tetCount)
                               + "%");
        } else {
            System.out.println("No tetrahedra to display - add some entities first!");
        }
        System.out.println("=====================================\n");
    }

    /**
     * Get the color for a specific tetrahedron type.
     *
     * @param type The tetrahedron type (0-5)
     * @return The color for this type
     */
    public Color getColorForType(int type) {
        return TYPE_COLORS[type % TYPE_COLORS.length];
    }

    /**
     * Get the scene root.
     */
    public Group getSceneRoot() {
        return sceneRoot;
    }

    /**
     * Get the transform manager for direct access if needed.
     *
     * @return The tetrahedral transform manager
     */
    public TetrahedralTransformViews getTransformManager() {
        return transformManager;
    }

    /**
     * Create materials for each tetrahedron type.
     */
    private void initializeMaterials() {
        for (int i = 0; i < TYPE_COLORS.length; i++) {
            PhongMaterial material = new PhongMaterial(TYPE_COLORS[i].deriveColor(0, 1, 1, 0.3));
            material.setSpecularColor(TYPE_COLORS[i].brighter());
            typeMaterials.put(i, material);
        }
    }
}
