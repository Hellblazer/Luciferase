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

package com.hellblazer.luciferase.portal.esvo.visualization;

import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.DeepTraversalResult;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;

/**
 * Visualizes ray casting through ESVO octree, showing:
 * - Ray path through the scene
 * - Hit point (if any)
 * - Surface normal at hit point
 * - Traversal statistics
 * 
 * @author hal.hildebrand
 */
public class RayCastVisualizer {
    
    private static final Logger log = LoggerFactory.getLogger(RayCastVisualizer.class);
    
    /**
     * Visualization mode for ray casting display
     */
    public enum VisualizationMode {
        /** Show only the ray path */
        RAY_ONLY,
        /** Show ray path and hit point */
        RAY_AND_HIT,
        /** Show ray, hit point, and surface normal */
        RAY_HIT_NORMAL,
        /** Show full traversal with visited nodes highlighted */
        FULL_TRAVERSAL
    }
    
    private final VisualizationMode mode;
    private final Color rayColor;
    private final Color hitColor;
    private final Color normalColor;
    private final double rayThickness;
    private final double hitPointSize;
    
    /**
     * Create visualizer with default settings
     */
    public RayCastVisualizer() {
        this(VisualizationMode.RAY_HIT_NORMAL, 
             Color.YELLOW, 
             Color.RED, 
             Color.CYAN, 
             1.0, 
             3.0);
    }
    
    /**
     * Create visualizer with custom settings
     */
    public RayCastVisualizer(VisualizationMode mode, 
                            Color rayColor, 
                            Color hitColor, 
                            Color normalColor,
                            double rayThickness,
                            double hitPointSize) {
        this.mode = mode;
        this.rayColor = rayColor;
        this.hitColor = hitColor;
        this.normalColor = normalColor;
        this.rayThickness = rayThickness;
        this.hitPointSize = hitPointSize;
    }
    
    /**
     * Visualize a ray cast result
     * 
     * @param origin Ray origin in world space [0,1]
     * @param direction Ray direction (normalized)
     * @param result Traversal result from ESVOBridge
     * @param maxDistance Maximum distance to show ray (used if no hit)
     * @return Group containing all visualization geometry
     */
    public Group visualize(Vector3f origin, Vector3f direction, 
                          DeepTraversalResult result, float maxDistance) {
        var group = new Group();
        
        if (result == null) {
            log.warn("Cannot visualize null traversal result");
            return group;
        }
        
        // Scale coordinates to match JavaFX scene (typically 100x for better visibility)
        float scale = 100.0f;
        
        // Convert to JavaFX coordinates
        Point3D rayOrigin = toPoint3D(origin, scale);
        Point3D rayDir = toPoint3D(direction, 1.0f); // Direction is unit vector
        
        // Determine ray end point
        Point3D rayEnd;
        if (result.hit && result.hitPoint != null) {
            rayEnd = toPoint3D(result.hitPoint, scale);
        } else {
            // Show ray extending to max distance
            rayEnd = rayOrigin.add(rayDir.multiply(maxDistance * scale));
        }
        
        // Add ray visualization
        if (mode != VisualizationMode.FULL_TRAVERSAL || !result.hit) {
            addRayLine(group, rayOrigin, rayEnd, rayColor, rayThickness);
        }
        
        // Add hit point visualization
        if (result.hit && (mode == VisualizationMode.RAY_AND_HIT || 
                          mode == VisualizationMode.RAY_HIT_NORMAL ||
                          mode == VisualizationMode.FULL_TRAVERSAL)) {
            addHitPoint(group, rayEnd, hitColor, hitPointSize);
            
            // Add surface normal
            if (result.normal != null && 
                (mode == VisualizationMode.RAY_HIT_NORMAL || 
                 mode == VisualizationMode.FULL_TRAVERSAL)) {
                Point3D normalDir = toPoint3D(result.normal, 1.0f);
                Point3D normalEnd = rayEnd.add(normalDir.multiply(20.0)); // Scale normal for visibility
                addRayLine(group, rayEnd, normalEnd, normalColor, rayThickness * 0.8);
            }
        }
        
        // Add traversal statistics visualization
        if (mode == VisualizationMode.FULL_TRAVERSAL) {
            // Could add more detailed traversal visualization here
            // For now, just log the statistics
            log.debug("Ray traversal: hit={}, depth={}, iterations={}", 
                     result.hit, result.traversalDepth, result.iterations);
        }
        
        return group;
    }
    
    /**
     * Add a line representing a ray segment
     */
    private void addRayLine(Group group, Point3D start, Point3D end, Color color, double thickness) {
        // Calculate line direction and length
        Point3D direction = end.subtract(start);
        double length = direction.magnitude();
        
        if (length < 0.001) {
            return; // Too short to visualize
        }
        
        // Create cylinder for the ray
        Cylinder ray = new Cylinder(thickness, length);
        
        // Create material
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(Color.WHITE);
        material.setSpecularPower(10);
        ray.setMaterial(material);
        
        // Position at midpoint
        Point3D midpoint = start.add(end).multiply(0.5);
        ray.setTranslateX(midpoint.getX());
        ray.setTranslateY(midpoint.getY());
        ray.setTranslateZ(midpoint.getZ());
        
        // Rotate to align with direction
        // Default cylinder is along Y axis, need to rotate to align with our direction
        Point3D yAxis = new Point3D(0, 1, 0);
        Point3D normalizedDir = direction.normalize();
        
        // Calculate rotation axis and angle
        Point3D rotationAxis = yAxis.crossProduct(normalizedDir);
        double rotationAngle = Math.toDegrees(Math.acos(yAxis.dotProduct(normalizedDir)));
        
        if (rotationAxis.magnitude() > 0.001) {
            Rotate rotate = new Rotate(rotationAngle, rotationAxis);
            ray.getTransforms().add(rotate);
        }
        
        group.getChildren().add(ray);
    }
    
    /**
     * Add a sphere representing the hit point
     */
    private void addHitPoint(Group group, Point3D position, Color color, double size) {
        Sphere hitSphere = new Sphere(size);
        
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(Color.WHITE);
        material.setSpecularPower(20);
        hitSphere.setMaterial(material);
        
        hitSphere.setTranslateX(position.getX());
        hitSphere.setTranslateY(position.getY());
        hitSphere.setTranslateZ(position.getZ());
        
        group.getChildren().add(hitSphere);
    }
    
    /**
     * Convert Vector3f to JavaFX Point3D with scaling
     */
    private Point3D toPoint3D(Vector3f vec, float scale) {
        return new Point3D(vec.x * scale, vec.y * scale, vec.z * scale);
    }
    
    /**
     * Create a statistics display group
     * 
     * @param result Traversal result
     * @return Group with text overlay (or null if not implemented)
     */
    public Group createStatisticsDisplay(DeepTraversalResult result) {
        // TODO: Implement 3D text or billboard for statistics
        // For now, statistics are logged
        if (result != null) {
            log.info("Traversal Statistics:");
            log.info("  Hit: {}", result.hit);
            log.info("  Distance: {:.3f}", result.distance);
            log.info("  Traversal Depth: {}", result.traversalDepth);
            log.info("  Iterations: {}", result.iterations);
            if (result.hitPoint != null) {
                log.info("  Hit Point: ({:.3f}, {:.3f}, {:.3f})", 
                        result.hitPoint.x, result.hitPoint.y, result.hitPoint.z);
            }
            if (result.normal != null) {
                log.info("  Normal: ({:.3f}, {:.3f}, {:.3f})", 
                        result.normal.x, result.normal.y, result.normal.z);
            }
        }
        return new Group();
    }
    
    /**
     * Get current visualization mode
     */
    public VisualizationMode getMode() {
        return mode;
    }
    
    /**
     * Create a new visualizer with different mode
     */
    public RayCastVisualizer withMode(VisualizationMode newMode) {
        return new RayCastVisualizer(newMode, rayColor, hitColor, normalColor, 
                                    rayThickness, hitPointSize);
    }
    
    /**
     * Create a new visualizer with different colors
     */
    public RayCastVisualizer withColors(Color ray, Color hit, Color normal) {
        return new RayCastVisualizer(mode, ray, hit, normal, rayThickness, hitPointSize);
    }
}
