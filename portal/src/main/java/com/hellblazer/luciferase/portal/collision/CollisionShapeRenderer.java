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
package com.hellblazer.luciferase.portal.collision;

import com.hellblazer.luciferase.lucien.collision.*;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Renders collision shapes as wireframe visualizations for debugging.
 * Supports all collision shape types with proper wireframe rendering.
 *
 * @author hal.hildebrand
 */
public class CollisionShapeRenderer {
    
    private static final Color DEFAULT_WIREFRAME_COLOR = Color.CYAN;
    private static final Color COLLISION_COLOR = Color.RED;
    private static final Color CONTACT_POINT_COLOR = Color.YELLOW;
    private static final double WIREFRAME_RADIUS = 2.5;
    private static final double CONTACT_POINT_RADIUS = 5.0;
    
    /**
     * Render a collision shape as a wireframe visualization.
     *
     * @param shape the collision shape to render
     * @param color the wireframe color
     * @return JavaFX node representing the wireframe
     */
    public static Node renderWireframe(CollisionShape shape, Color color) {
        return switch (shape) {
            case SphereShape sphere -> renderSphereWireframe(sphere, color);
            case BoxShape box -> renderBoxWireframe(box, color);
            case OrientedBoxShape orientedBox -> renderOrientedBoxWireframe(orientedBox, color);
            case CapsuleShape capsule -> renderCapsuleWireframe(capsule, color);
            case MeshShape mesh -> renderMeshWireframe(mesh, color);
            case ConvexHullShape hull -> renderConvexHullWireframe(hull, color);
            case HeightmapShape heightmap -> renderHeightmapWireframe(heightmap, color);
        };
    }
    
    /**
     * Render a collision shape with default wireframe color.
     */
    public static Node renderWireframe(CollisionShape shape) {
        return renderWireframe(shape, DEFAULT_WIREFRAME_COLOR);
    }
    
    /**
     * Render a sphere as wireframe circles.
     */
    private static Node renderSphereWireframe(SphereShape sphere, Color color) {
        var group = new Group();
        var material = new PhongMaterial(color);
        var pos = sphere.getPosition();
        var radius = sphere.getRadius();
        
        // Use JavaFX Sphere with DrawMode.LINE for wireframe
        var wireframeSphere = new Sphere(radius);
        wireframeSphere.setMaterial(material);
        wireframeSphere.setDrawMode(DrawMode.LINE);
        
        group.getChildren().add(wireframeSphere);
        group.getTransforms().add(new Translate(pos.x, pos.y, pos.z));
        return group;
    }
    
    /**
     * Render an axis-aligned box as wireframe edges.
     */
    private static Node renderBoxWireframe(BoxShape box, Color color) {
        var group = new Group();
        // Use bright green for boxes to make them more visible
        var material = new PhongMaterial(Color.LIMEGREEN);
        var pos = box.getPosition();
        var halfExtents = box.getHalfExtents();
        
        // Define the 8 vertices of the box
        var vertices = new Point3f[8];
        vertices[0] = new Point3f(-halfExtents.x, -halfExtents.y, -halfExtents.z);
        vertices[1] = new Point3f( halfExtents.x, -halfExtents.y, -halfExtents.z);
        vertices[2] = new Point3f( halfExtents.x,  halfExtents.y, -halfExtents.z);
        vertices[3] = new Point3f(-halfExtents.x,  halfExtents.y, -halfExtents.z);
        vertices[4] = new Point3f(-halfExtents.x, -halfExtents.y,  halfExtents.z);
        vertices[5] = new Point3f( halfExtents.x, -halfExtents.y,  halfExtents.z);
        vertices[6] = new Point3f( halfExtents.x,  halfExtents.y,  halfExtents.z);
        vertices[7] = new Point3f(-halfExtents.x,  halfExtents.y,  halfExtents.z);
        
        // Define the 12 edges as vertex pairs
        int[][] edges = {
            // Bottom face
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            // Top face
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            // Vertical edges
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        
        // Create cylinders for each edge
        for (var edge : edges) {
            var cylinder = createEdge(vertices[edge[0]], vertices[edge[1]], material);
            group.getChildren().add(cylinder);
        }
        
        group.getTransforms().add(new Translate(pos.x, pos.y, pos.z));
        return group;
    }
    
    /**
     * Render an oriented box as wireframe edges with rotation.
     */
    private static Node renderOrientedBoxWireframe(OrientedBoxShape orientedBox, Color color) {
        var group = new Group();
        var material = new PhongMaterial(color);
        var halfExtents = orientedBox.getHalfExtents();
        
        // Create wireframe box
        var wireframeBox = new Box(halfExtents.x * 2, halfExtents.y * 2, halfExtents.z * 2);
        wireframeBox.setMaterial(material);
        wireframeBox.setDrawMode(DrawMode.LINE);
        
        // Apply orientation transformation
        var orientation = orientedBox.getOrientation();
        applyMatrix3fTransform(wireframeBox, orientation);
        
        group.getChildren().add(wireframeBox);
        
        // Apply position
        var pos = orientedBox.getPosition();
        group.getTransforms().add(new Translate(pos.x, pos.y, pos.z));
        
        return group;
    }
    
    /**
     * Render a capsule as wireframe with cylinder body and sphere caps.
     */
    private static Node renderCapsuleWireframe(CapsuleShape capsule, Color color) {
        var group = new Group();
        var material = new PhongMaterial(color);
        var pos = capsule.getPosition();
        var radius = capsule.getRadius();
        var height = capsule.getHeight();
        // The capsule height is the distance between endpoints
        var halfHeight = height / 2;
        
        // Only create vertical lines if height > 0 (not a sphere)
        if (height > 0.01f) {
            // Create vertical lines for the cylinder body
            int numVerticalLines = 8;
            for (int i = 0; i < numVerticalLines; i++) {
                var angle = i * 2 * Math.PI / numVerticalLines;
                var x = radius * Math.cos(angle);
                var z = radius * Math.sin(angle);
                
                // Vertical lines connecting the two ends
                var edge = createEdge(new Point3f((float)x, -halfHeight, (float)z),
                                     new Point3f((float)x, halfHeight, (float)z), material);
                group.getChildren().add(edge);
            }
            
            // Create horizontal circles at cylinder ends
            var topCircle = createHorizontalCircleY(radius, halfHeight, 16, material);
            group.getChildren().add(topCircle);
            
            var bottomCircle = createHorizontalCircleY(radius, -halfHeight, 16, material);
            group.getChildren().add(bottomCircle);
        }
        
        // Add a middle circle for visual reference
        var middleCircle = createHorizontalCircleY(radius, 0, 16, material);
        group.getChildren().add(middleCircle);
        
        // Add hemisphere caps with meridian lines
        // Top hemisphere
        for (int i = 0; i < 4; i++) {
            var angle = i * Math.PI / 2;
            // Create meridian arc for top hemisphere
            var meridianGroup = new Group();
            for (int j = 0; j < 8; j++) {
                var t1 = j * Math.PI / 16; // 0 to PI/2
                var t2 = (j + 1) * Math.PI / 16;
                
                var y1 = halfHeight + radius * Math.cos(t1);
                var r1 = radius * Math.sin(t1);
                var y2 = halfHeight + radius * Math.cos(t2);
                var r2 = radius * Math.sin(t2);
                
                var x1 = r1 * Math.cos(angle);
                var z1 = r1 * Math.sin(angle);
                var x2 = r2 * Math.cos(angle);
                var z2 = r2 * Math.sin(angle);
                
                if (r1 > 0.01 || r2 > 0.01) {
                    var segment = createEdge(new Point3f((float)x1, (float)y1, (float)z1),
                                           new Point3f((float)x2, (float)y2, (float)z2), material);
                    meridianGroup.getChildren().add(segment);
                }
            }
            group.getChildren().add(meridianGroup);
        }
        
        // Bottom hemisphere
        for (int i = 0; i < 4; i++) {
            var angle = i * Math.PI / 2;
            // Create meridian arc for bottom hemisphere
            var meridianGroup = new Group();
            for (int j = 0; j < 8; j++) {
                var t1 = Math.PI - j * Math.PI / 16; // PI to PI/2
                var t2 = Math.PI - (j + 1) * Math.PI / 16;
                
                var y1 = -halfHeight + radius * Math.cos(t1);
                var r1 = radius * Math.sin(t1);
                var y2 = -halfHeight + radius * Math.cos(t2);
                var r2 = radius * Math.sin(t2);
                
                var x1 = r1 * Math.cos(angle);
                var z1 = r1 * Math.sin(angle);
                var x2 = r2 * Math.cos(angle);
                var z2 = r2 * Math.sin(angle);
                
                if (r1 > 0.01 || r2 > 0.01) {
                    var segment = createEdge(new Point3f((float)x1, (float)y1, (float)z1),
                                           new Point3f((float)x2, (float)y2, (float)z2), material);
                    meridianGroup.getChildren().add(segment);
                }
            }
            group.getChildren().add(meridianGroup);
        }
        
        group.getTransforms().add(new Translate(pos.x, pos.y, pos.z));
        return group;
    }
    
    /**
     * Render a mesh as wireframe edges.
     */
    private static Node renderMeshWireframe(MeshShape mesh, Color color) {
        var group = new Group();
        var material = new PhongMaterial(color);
        var pos = mesh.getPosition();
        var meshData = mesh.getMeshData();
        
        // Render triangle edges
        var triangles = meshData.getTriangles();
        
        for (var triangle : triangles) {
            var v0 = meshData.getVertex(triangle.v0);
            var v1 = meshData.getVertex(triangle.v1);
            var v2 = meshData.getVertex(triangle.v2);
            
            group.getChildren().add(createEdge(v0, v1, material));
            group.getChildren().add(createEdge(v1, v2, material));
            group.getChildren().add(createEdge(v2, v0, material));
        }
        
        group.getTransforms().add(new Translate(pos.x, pos.y, pos.z));
        return group;
    }
    
    /**
     * Render a convex hull as wireframe edges.
     */
    private static Node renderConvexHullWireframe(ConvexHullShape hull, Color color) {
        var group = new Group();
        var material = new PhongMaterial(color);
        var pos = hull.getPosition();
        var vertices = hull.getVertices();
        
        // Simple wireframe: connect all vertices to center
        var center = new Point3f();
        for (var vertex : vertices) {
            center.add(vertex);
        }
        center.scale(1.0f / vertices.size());
        
        for (var vertex : vertices) {
            group.getChildren().add(createEdge(center, vertex, material));
        }
        
        group.getTransforms().add(new Translate(pos.x, pos.y, pos.z));
        return group;
    }
    
    /**
     * Render a heightmap as wireframe grid.
     */
    private static Node renderHeightmapWireframe(HeightmapShape heightmap, Color color) {
        var group = new Group();
        var material = new PhongMaterial(color);
        var pos = heightmap.getPosition();
        var width = heightmap.getWidth();
        var depth = heightmap.getDepth();
        
        // Create grid wireframe
        for (int x = 0; x < width - 1; x++) {
            for (int z = 0; z < depth - 1; z++) {
                var h00 = heightmap.getHeightAtPosition(pos.x + x, pos.z + z);
                var h10 = heightmap.getHeightAtPosition(pos.x + x + 1, pos.z + z);
                var h01 = heightmap.getHeightAtPosition(pos.x + x, pos.z + z + 1);
                var h11 = heightmap.getHeightAtPosition(pos.x + x + 1, pos.z + z + 1);
                
                var p00 = new Point3f(x, h00, z);
                var p10 = new Point3f(x + 1, h10, z);
                var p01 = new Point3f(x, h01, z + 1);
                var p11 = new Point3f(x + 1, h11, z + 1);
                
                // Add grid edges
                group.getChildren().add(createEdge(p00, p10, material));
                group.getChildren().add(createEdge(p00, p01, material));
                if (x == width - 2) group.getChildren().add(createEdge(p10, p11, material));
                if (z == depth - 2) group.getChildren().add(createEdge(p01, p11, material));
            }
        }
        
        group.getTransforms().add(new Translate(pos.x, pos.y, pos.z));
        return group;
    }
    
    
    /**
     * Create a horizontal circle at a given Z height.
     */
    private static Group createHorizontalCircle(float radius, float z, int segments, PhongMaterial material) {
        var group = new Group();
        
        for (int i = 0; i < segments; i++) {
            var angle1 = 2.0 * Math.PI * i / segments;
            var angle2 = 2.0 * Math.PI * ((i + 1) % segments) / segments;
            
            var x1 = (float) (radius * Math.cos(angle1));
            var y1 = (float) (radius * Math.sin(angle1));
            var x2 = (float) (radius * Math.cos(angle2));
            var y2 = (float) (radius * Math.sin(angle2));
            
            var edge = createEdge(new Point3f(x1, y1, z), new Point3f(x2, y2, z), material);
            group.getChildren().add(edge);
        }
        
        return group;
    }
    
    /**
     * Create a horizontal circle in the XZ plane at a given Y height.
     */
    private static Group createHorizontalCircleY(float radius, float y, int segments, PhongMaterial material) {
        var group = new Group();
        
        for (int i = 0; i < segments; i++) {
            var angle1 = 2.0 * Math.PI * i / segments;
            var angle2 = 2.0 * Math.PI * ((i + 1) % segments) / segments;
            
            var x1 = (float) (radius * Math.cos(angle1));
            var z1 = (float) (radius * Math.sin(angle1));
            var x2 = (float) (radius * Math.cos(angle2));
            var z2 = (float) (radius * Math.sin(angle2));
            
            var edge = createEdge(new Point3f(x1, y, z1), new Point3f(x2, y, z2), material);
            group.getChildren().add(edge);
        }
        
        return group;
    }
    
    /**
     * Create a vertical semi-circle for hemisphere caps.
     */
    private static Group createVerticalSemiCircle(float radius, float zCenter, double rotationAngle, boolean topHemisphere, PhongMaterial material) {
        var group = new Group();
        
        int segments = 8;
        for (int i = 0; i < segments; i++) {
            var angle1 = (topHemisphere ? 0 : Math.PI) + (Math.PI * i / segments);
            var angle2 = (topHemisphere ? 0 : Math.PI) + (Math.PI * (i + 1) / segments);
            
            var z1 = zCenter + (float) (radius * Math.cos(angle1));
            var r1 = (float) (radius * Math.sin(angle1));
            var z2 = zCenter + (float) (radius * Math.cos(angle2));
            var r2 = (float) (radius * Math.sin(angle2));
            
            var x1 = (float) (r1 * Math.cos(rotationAngle));
            var y1 = (float) (r1 * Math.sin(rotationAngle));
            var x2 = (float) (r2 * Math.cos(rotationAngle));
            var y2 = (float) (r2 * Math.sin(rotationAngle));
            
            if (r1 > 0.01 || r2 > 0.01) { // Skip degenerate edges at poles
                var edge = createEdge(new Point3f(x1, y1, z1), new Point3f(x2, y2, z2), material);
                group.getChildren().add(edge);
            }
        }
        
        return group;
    }
    
    /**
     * Create an edge between two points as a cylinder.
     */
    public static Cylinder createEdge(Point3f start, Point3f end, PhongMaterial material) {
        var length = start.distance(end);
        var cylinder = new Cylinder(WIREFRAME_RADIUS, length);
        cylinder.setMaterial(material);
        
        // Position at midpoint
        var midpoint = new Point3f();
        midpoint.add(start, end);
        midpoint.scale(0.5f);
        cylinder.getTransforms().add(new Translate(midpoint.x, midpoint.y, midpoint.z));
        
        // Calculate direction vector
        var direction = new Vector3f();
        direction.sub(end, start);
        direction.normalize();
        
        // JavaFX cylinders are oriented along Y-axis by default
        // We need to rotate from Y-axis to the edge direction
        var yAxis = new Vector3f(0, 1, 0);
        
        // Special case: if direction is already along Y-axis
        var dot = yAxis.dot(direction);
        if (Math.abs(dot) > 0.999) {
            // Already aligned or opposite
            if (dot < 0) {
                cylinder.getTransforms().add(new Rotate(180, Rotate.Z_AXIS));
            }
        } else {
            // General case: rotate from Y-axis to direction
            var rotationAxis = new Vector3f();
            rotationAxis.cross(yAxis, direction);
            rotationAxis.normalize();
            
            var angle = Math.acos(dot) * 180.0 / Math.PI;
            cylinder.getTransforms().add(new Rotate(angle, new Point3D(rotationAxis.x, rotationAxis.y, rotationAxis.z)));
        }
        
        return cylinder;
    }
    
    /**
     * Apply Matrix3f transformation to a JavaFX node.
     */
    private static void applyMatrix3fTransform(Node node, Matrix3f matrix) {
        // Convert to Euler angles for JavaFX transforms (simplified)
        var rotateX = Math.atan2(matrix.m21, matrix.m22) * 180.0 / Math.PI;
        var rotateY = Math.atan2(-matrix.m20, Math.sqrt(matrix.m21 * matrix.m21 + matrix.m22 * matrix.m22)) * 180.0 / Math.PI;
        var rotateZ = Math.atan2(matrix.m10, matrix.m00) * 180.0 / Math.PI;
        
        node.getTransforms().addAll(
            new Rotate(rotateX, Rotate.X_AXIS),
            new Rotate(rotateY, Rotate.Y_AXIS),
            new Rotate(rotateZ, Rotate.Z_AXIS)
        );
    }
    
    /**
     * Create a contact point visualization.
     */
    public static Node createContactPoint(Point3f point, Vector3f normal, Color color) {
        var group = new Group();
        
        // Contact point sphere
        var sphere = new Sphere(CONTACT_POINT_RADIUS);
        sphere.setMaterial(new PhongMaterial(color));
        
        // Normal vector as arrow
        var normalLength = 1.0f;
        var arrow = createEdge(new Point3f(0, 0, 0), 
                              new Point3f(normal.x * normalLength, normal.y * normalLength, normal.z * normalLength),
                              new PhongMaterial(color));
        
        group.getChildren().addAll(sphere, arrow);
        group.getTransforms().add(new Translate(point.x, point.y, point.z));
        
        return group;
    }
    
    /**
     * Create a penetration vector visualization.
     */
    public static Node createPenetrationVector(Point3f start, Vector3f direction, float depth, Color color) {
        var scaledDirection = new Vector3f(direction);
        scaledDirection.scale(depth);
        
        var end = new Point3f(start);
        end.add(scaledDirection);
        
        return createEdge(start, end, new PhongMaterial(color));
    }
    
}