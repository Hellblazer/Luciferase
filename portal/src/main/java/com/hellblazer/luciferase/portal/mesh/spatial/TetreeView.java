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
package com.hellblazer.luciferase.portal.mesh.spatial;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.visitor.AbstractTreeVisitor;
import com.hellblazer.luciferase.lucien.visitor.TraversalStrategy;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Shape3D;

import java.util.HashMap;
import java.util.Map;

/**
 * A JavaFX Group that displays Tetree elements using tree traversal.
 * Shows wireframes for parents of occupied cells and either filled mesh 
 * tetrahedrons or wireframes for occupied cells.
 *
 * @author hal.hildebrand
 */
public class TetreeView extends Group {
    
    protected final CellViews cellViews;
    private final boolean showOccupiedAsMesh;
    private final Material occupiedMeshMaterial;
    private final Material occupiedWireframeMaterial;
    private final Material parentWireframeMaterial;
    
    /**
     * Create a TetreeView with default settings.
     * Occupied cells shown as mesh with translucent red material.
     * Parent wireframes shown in black.
     */
    public TetreeView() {
        this(true);
    }
    
    /**
     * Create a TetreeView with specified display mode.
     * 
     * @param showOccupiedAsMesh If true, occupied cells shown as mesh; otherwise as wireframe
     */
    public TetreeView(boolean showOccupiedAsMesh) {
        this(showOccupiedAsMesh, 
             new PhongMaterial(Color.RED.deriveColor(0, 1, 1, 0.6)),
             new PhongMaterial(Color.RED),
             new PhongMaterial(Color.BLACK.deriveColor(0, 1, 1, 0.3)));
    }
    
    /**
     * Create a TetreeView with full customization.
     * 
     * @param showOccupiedAsMesh If true, occupied cells shown as mesh; otherwise as wireframe
     * @param occupiedMeshMaterial Material for occupied cell meshes
     * @param occupiedWireframeMaterial Material for occupied cell wireframes
     * @param parentWireframeMaterial Material for parent cell wireframes
     */
    public TetreeView(boolean showOccupiedAsMesh, 
                      Material occupiedMeshMaterial,
                      Material occupiedWireframeMaterial,
                      Material parentWireframeMaterial) {
        this(showOccupiedAsMesh, occupiedMeshMaterial, occupiedWireframeMaterial, 
             parentWireframeMaterial, new CellViews());
    }
    
    /**
     * Create a TetreeView with full customization and custom CellViews.
     * 
     * @param showOccupiedAsMesh If true, occupied cells shown as mesh; otherwise as wireframe
     * @param occupiedMeshMaterial Material for occupied cell meshes
     * @param occupiedWireframeMaterial Material for occupied cell wireframes
     * @param parentWireframeMaterial Material for parent cell wireframes
     * @param cellViews The CellViews instance to use (e.g., ScaledCellViews)
     */
    public TetreeView(boolean showOccupiedAsMesh, 
                      Material occupiedMeshMaterial,
                      Material occupiedWireframeMaterial,
                      Material parentWireframeMaterial,
                      CellViews cellViews) {
        this.cellViews = cellViews;
        this.showOccupiedAsMesh = showOccupiedAsMesh;
        this.occupiedMeshMaterial = occupiedMeshMaterial;
        this.occupiedWireframeMaterial = occupiedWireframeMaterial;
        this.parentWireframeMaterial = parentWireframeMaterial;
    }
    
    /**
     * Update the display with the current state of a Tetree using tree traversal.
     * 
     * @param tetree The Tetree to visualize
     */
    @SuppressWarnings("rawtypes")
    public <ID extends EntityID, Content> void updateFromTetree(Tetree<ID, Content> tetree) {
        // Clear existing children
        getChildren().clear();
        
        System.out.println("TetreeView: Updating from tetree with " + tetree.nodeCount() + " nodes and " + tetree.entityCount() + " entities");
        
        // Create a visitor that emits visualizations dynamically during traversal
        TetreeVisualizationVisitor<ID, Content> visitor = new TetreeVisualizationVisitor<>(this);
        
        // Traverse the tree in post-order to ensure we process children before parents
        tetree.traverse(visitor, TraversalStrategy.POST_ORDER);
        
        System.out.println("TetreeView: Added " + getChildren().size() + " visual elements");
    }
    
    /**
     * Apply material to all Shape3D children in a group.
     */
    private void applyMaterialToGroup(Group group, Material material) {
        group.getChildren().forEach(child -> {
            if (child instanceof Shape3D) {
                ((Shape3D) child).setMaterial(material);
            }
        });
    }
    
    /**
     * Clear the transform cache in CellViews.
     * Useful when switching between different visualizations.
     */
    public void clearCache() {
        cellViews.clearTransformCache();
    }
    
    /**
     * Get the CellViews instance for additional customization.
     * 
     * @return The CellViews instance
     */
    public CellViews getCellViews() {
        return cellViews;
    }
    
    /**
     * Check if occupied cells are being shown as mesh (vs wireframe).
     * 
     * @return true if showing as mesh, false if showing as wireframe
     */
    public boolean isShowingOccupiedAsMesh() {
        return showOccupiedAsMesh;
    }
    
    /**
     * Custom visitor that emits visualizations dynamically during tree traversal.
     * Every node in the spatial index either contains entities or is an ancestor
     * of nodes that contain entities.
     */
    @SuppressWarnings("rawtypes")
    private static class TetreeVisualizationVisitor<ID extends EntityID, Content> 
            extends AbstractTreeVisitor<TetreeKey<? extends TetreeKey>, ID, Content> {
        
        private final TetreeView view;
        
        TetreeVisualizationVisitor(TetreeView view) {
            this.view = view;
        }
        
        @Override
        public boolean visitNode(SpatialIndex.SpatialNode<TetreeKey<? extends TetreeKey>, ID> node, 
                               int level, TetreeKey<? extends TetreeKey> parentIndex) {
            // In post-order, just continue traversal - we'll process in leaveNode
            return true;
        }
        
        @Override
        public void leaveNode(SpatialIndex.SpatialNode<TetreeKey<? extends TetreeKey>, ID> node, 
                            int level, int childCount) {
            TetreeKey<? extends TetreeKey> key = node.sfcIndex();
            boolean isOccupied = !node.entityIds().isEmpty();
            
            if (isOccupied) {
                System.out.println("Rendering occupied node at level " + key.toTet().l() + 
                                 " with " + node.entityIds().size() + " entities");
                // This node is occupied - render it as mesh or wireframe
                if (view.showOccupiedAsMesh) {
                    MeshView mesh = view.cellViews.createMeshView(key.toTet());
                    if (mesh != null) {
                        mesh.setMaterial(view.occupiedMeshMaterial);
                        view.getChildren().add(mesh);
                    }
                } else {
                    Group wireframe = view.cellViews.createWireframe(key.toTet());
                    if (wireframe != null) {
                        view.applyMaterialToGroup(wireframe, view.occupiedWireframeMaterial);
                        view.getChildren().add(wireframe);
                    }
                }
            } else {
                System.out.println("Rendering parent node at level " + key.toTet().l());
                // This node exists but is not occupied - it must be a parent of occupied nodes
                // (otherwise it wouldn't exist in the spatial index)
                // Render as parent wireframe
                Group wireframe = view.cellViews.createWireframe(key.toTet());
                if (wireframe != null) {
                    view.applyMaterialToGroup(wireframe, view.parentWireframeMaterial);
                    view.getChildren().add(wireframe);
                }
            }
        }
        
        @Override
        public boolean shouldVisitEntities() {
            // We don't need to visit individual entities, just check if nodes are occupied
            return false;
        }
    }
}
