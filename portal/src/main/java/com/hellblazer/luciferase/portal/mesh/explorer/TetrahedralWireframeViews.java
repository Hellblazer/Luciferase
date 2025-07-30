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
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;

import javax.vecmath.Point3i;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages tetrahedral wireframe views using transformations and reference wireframes for efficient rendering.
 * This class creates and caches the 6 reference wireframes (one for each characteristic tetrahedron type S0-S5)
 * and provides transformation calculations to position, scale, and orient instances as needed.
 *
 * @author hal.hildebrand
 */
public class TetrahedralWireframeViews {
    // Standard tetrahedron edges (vertex indices)
    private static final int[][] EDGES = {
        {0, 1}, {0, 2}, {0, 3},  // Edges from vertex 0
        {1, 2}, {1, 3},          // Edges from vertex 1
        {2, 3}                   // Edge from vertex 2 to 3
    };
    
    private final Group[]                   referenceWireframes = new Group[6];
    private final Map<TetreeKey<?>, Affine> transformCache      = new HashMap<>();
    private final double                    edgeThickness;
    private final Material                  edgeMaterial;
    
    /**
     * Initialize the wireframe manager with default edge thickness and material.
     */
    public TetrahedralWireframeViews() {
        this(0.01, new PhongMaterial(javafx.scene.paint.Color.BLACK));
    }
    
    /**
     * Initialize the wireframe manager with specified edge thickness and material.
     *
     * @param edgeThickness The thickness of the wireframe edges
     * @param edgeMaterial  The material to use for the edges
     */
    public TetrahedralWireframeViews(double edgeThickness, Material edgeMaterial) {
        this.edgeThickness = edgeThickness;
        this.edgeMaterial = edgeMaterial;
        
        // Create reference wireframe for each tetrahedron type
        for (int type = 0; type < 6; type++) {
            // Get the standard simplex vertices for this type from Constants
            Point3i[] vertices = Constants.SIMPLEX_STANDARD[type];
            
            // Create unit-sized reference wireframe at origin
            Group wireframe = createUnitTetrahedronWireframe(vertices);
            referenceWireframes[type] = wireframe;
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
        stats.put("referenceWireframeCount", referenceWireframes.length);
        stats.put("transformCacheSize", transformCache.size());
        return stats;
    }
    
    /**
     * Create a wireframe view for the given tetrahedron.
     *
     * @param tet The tetrahedron to visualize
     * @return A Group containing the transformed wireframe
     */
    public Group of(Tet tet) {
        Group referenceWireframe = referenceWireframes[(int) tet.type()];
        
        if (referenceWireframe == null) {
            System.err.println("ERROR: No reference wireframe for type " + tet.type());
            return null;
        }
        
        // Create a new group and copy the reference wireframe structure
        Group wireframeView = new Group();
        for (var child : referenceWireframe.getChildren()) {
            if (child instanceof Cylinder) {
                Cylinder refCylinder = (Cylinder) child;
                Cylinder cylinder = new Cylinder(refCylinder.getRadius(), refCylinder.getHeight());
                cylinder.setMaterial(refCylinder.getMaterial());
                
                // Copy position
                cylinder.setTranslateX(refCylinder.getTranslateX());
                cylinder.setTranslateY(refCylinder.getTranslateY());
                cylinder.setTranslateZ(refCylinder.getTranslateZ());
                
                // Copy transforms from reference
                cylinder.getTransforms().addAll(refCylinder.getTransforms());
                wireframeView.getChildren().add(cylinder);
            }
        }
        
        // Calculate and apply transform
        Affine transform = calculateTransform(tet);
        wireframeView.getTransforms().add(transform);
        
        return wireframeView;
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
     * Create a unit tetrahedron wireframe with vertices in standard positions.
     *
     * @param vertices The vertices of the tetrahedron
     * @return A Group containing the wireframe edges
     */
    private Group createUnitTetrahedronWireframe(Point3i[] vertices) {
        Group wireframe = new Group();
        
        // Create cylinders for each edge
        for (int[] edge : EDGES) {
            Point3i v1 = vertices[edge[0]];
            Point3i v2 = vertices[edge[1]];
            
            double dx = v2.x - v1.x;
            double dy = v2.y - v1.y;
            double dz = v2.z - v1.z;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            
            if (length < 0.001) continue; // Skip zero-length edges
            
            // Create cylinder
            Cylinder cylinder = new Cylinder(edgeThickness / 2, length);
            cylinder.setMaterial(edgeMaterial);
            
            // Position at midpoint
            double midX = (v1.x + v2.x) / 2.0;
            double midY = (v1.y + v2.y) / 2.0;
            double midZ = (v1.z + v2.z) / 2.0;
            cylinder.setTranslateX(midX);
            cylinder.setTranslateY(midY);
            cylinder.setTranslateZ(midZ);
            
            // Calculate rotation to align cylinder (Y-axis) with edge direction
            Point3D yAxis = new Point3D(0, 1, 0);
            Point3D edgeDir = new Point3D(dx, dy, dz).normalize();
            
            // Calculate rotation axis (cross product)
            Point3D rotAxis = yAxis.crossProduct(edgeDir);
            
            if (rotAxis.magnitude() > 0.001) {
                // Calculate rotation angle
                double angle = Math.toDegrees(Math.acos(yAxis.dotProduct(edgeDir)));
                
                Rotate rotation = new Rotate(angle, rotAxis);
                cylinder.getTransforms().add(rotation);
            }
            
            wireframe.getChildren().add(cylinder);
        }
        
        return wireframe;
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
