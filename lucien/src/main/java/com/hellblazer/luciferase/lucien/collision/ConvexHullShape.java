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
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Convex hull collision shape defined by a set of vertices.
 * Uses GJK algorithm support functions for collision detection.
 *
 * @author hal.hildebrand
 */
public final class ConvexHullShape extends CollisionShape {
    
    private final List<Point3f> vertices;
    private final List<HullFace> faces;
    private EntityBounds cachedBounds;
    
    /**
     * Create a convex hull from a set of vertices
     */
    public ConvexHullShape(Point3f position, List<Point3f> vertices) {
        super(position);
        this.vertices = new ArrayList<>();
        
        // Transform vertices to world space
        for (var vertex : vertices) {
            var worldVertex = new Point3f(vertex);
            worldVertex.add(position);
            this.vertices.add(worldVertex);
        }
        
        // Build convex hull faces (simplified - assumes vertices form a convex shape)
        this.faces = buildConvexHull(this.vertices);
        this.cachedBounds = computeBounds();
    }
    
    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return CollisionDetector.detectCollision(this, other);
    }
    
    @Override
    public EntityBounds getAABB() {
        return cachedBounds;
    }
    
    @Override
    public Point3f getSupport(Vector3f direction) {
        var maxDot = -Float.MAX_VALUE;
        var support = new Point3f();
        
        for (var vertex : vertices) {
            var dot = vertex.x * direction.x + vertex.y * direction.y + vertex.z * direction.z;
            if (dot > maxDot) {
                maxDot = dot;
                support.set(vertex);
            }
        }
        
        return support;
    }
    
    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        var minT = 0.0f;
        var maxT = ray.maxDistance();
        var closestFace = -1;
        
        // Test ray against each face
        for (int i = 0; i < faces.size(); i++) {
            var face = faces.get(i);
            var t = rayIntersectsFace(ray, face);
            
            if (t >= 0 && t < maxT) {
                maxT = t;
                closestFace = i;
            }
        }
        
        if (closestFace == -1) {
            return RayIntersectionResult.noIntersection();
        }
        
        var point = ray.pointAt(maxT);
        var normal = faces.get(closestFace).normal;
        
        return RayIntersectionResult.intersection(maxT, point, normal);
    }
    
    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
        for (var vertex : vertices) {
            vertex.add(delta);
        }
        cachedBounds = computeBounds();
    }
    
    /**
     * Get the vertices of the convex hull
     */
    public List<Point3f> getVertices() {
        return new ArrayList<>(vertices);
    }
    
    /**
     * Get the faces of the convex hull
     */
    public List<HullFace> getFaces() {
        return new ArrayList<>(faces);
    }
    
    /**
     * Get the centroid of the convex hull
     */
    public Point3f getCentroid() {
        var centroid = new Point3f();
        for (var vertex : vertices) {
            centroid.add(vertex);
        }
        centroid.scale(1.0f / vertices.size());
        return centroid;
    }
    
    private EntityBounds computeBounds() {
        var min = new Point3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        var max = new Point3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        
        for (var vertex : vertices) {
            min.x = Math.min(min.x, vertex.x);
            min.y = Math.min(min.y, vertex.y);
            min.z = Math.min(min.z, vertex.z);
            max.x = Math.max(max.x, vertex.x);
            max.y = Math.max(max.y, vertex.y);
            max.z = Math.max(max.z, vertex.z);
        }
        
        return new EntityBounds(min, max);
    }
    
    private List<HullFace> buildConvexHull(List<Point3f> vertices) {
        var faces = new ArrayList<HullFace>();
        
        // For a box (8 vertices), create the 12 triangular faces (2 per box face)
        if (vertices.size() == 8) {
            // Assume vertices are ordered as a box:
            // 0: (-x, -y, -z), 1: (+x, -y, -z), 2: (+x, +y, -z), 3: (-x, +y, -z)
            // 4: (-x, -y, +z), 5: (+x, -y, +z), 6: (+x, +y, +z), 7: (-x, +y, +z)
            
            // Bottom face (y = -y) - normal points down
            faces.add(new HullFace(0, 4, 5, vertices));
            faces.add(new HullFace(0, 5, 1, vertices));
            // Top face (y = +y) - normal points up
            faces.add(new HullFace(2, 6, 7, vertices));
            faces.add(new HullFace(2, 7, 3, vertices));
            // Front face (z = -z) - normal points forward
            faces.add(new HullFace(0, 1, 2, vertices));
            faces.add(new HullFace(0, 2, 3, vertices));
            // Back face (z = +z) - normal points backward
            faces.add(new HullFace(5, 4, 7, vertices));
            faces.add(new HullFace(5, 7, 6, vertices));
            // Left face (x = -x) - normal points left
            faces.add(new HullFace(4, 0, 3, vertices));
            faces.add(new HullFace(4, 3, 7, vertices));
            // Right face (x = +x) - normal points right
            faces.add(new HullFace(1, 5, 6, vertices));
            faces.add(new HullFace(1, 6, 2, vertices));
        } else if (vertices.size() >= 4) {
            // For other cases, create a simple tetrahedron
            faces.add(new HullFace(0, 2, 1, vertices));
            faces.add(new HullFace(0, 1, 3, vertices));
            faces.add(new HullFace(0, 3, 2, vertices));
            faces.add(new HullFace(1, 2, 3, vertices));
        } else if (vertices.size() == 3) {
            // Single triangle
            faces.add(new HullFace(0, 1, 2, vertices));
        }
        
        return faces;
    }
    
    private float rayIntersectsFace(Ray3D ray, HullFace face) {
        // Ray-plane intersection
        var denom = ray.direction().dot(face.normal);
        
        if (Math.abs(denom) < 1e-6f) {
            return -1; // Ray parallel to face
        }
        
        var v0 = vertices.get(face.v0);
        var toPlane = new Vector3f();
        toPlane.sub(v0, ray.origin());
        
        var t = toPlane.dot(face.normal) / denom;
        
        if (t < 0) {
            return -1; // Behind ray origin
        }
        
        // Check if intersection point is inside face
        var point = ray.pointAt(t);
        if (isPointInsideFace(point, face)) {
            return t;
        }
        
        return -1;
    }
    
    private boolean isPointInsideFace(Point3f point, HullFace face) {
        // Check if point lies within the triangle using barycentric coordinates
        var v0 = vertices.get(face.v0);
        var v1 = vertices.get(face.v1);
        var v2 = vertices.get(face.v2);
        
        // Compute vectors
        var v0v1 = new Vector3f();
        v0v1.sub(v1, v0);
        var v0v2 = new Vector3f();
        v0v2.sub(v2, v0);
        var v0p = new Vector3f();
        v0p.sub(point, v0);
        
        // Compute dot products
        float dot00 = v0v1.dot(v0v1);
        float dot01 = v0v1.dot(v0v2);
        float dot02 = v0v1.dot(v0p);
        float dot11 = v0v2.dot(v0v2);
        float dot12 = v0v2.dot(v0p);
        
        // Compute barycentric coordinates
        float invDenom = 1 / (dot00 * dot11 - dot01 * dot01);
        float u = (dot11 * dot02 - dot01 * dot12) * invDenom;
        float v = (dot00 * dot12 - dot01 * dot02) * invDenom;
        
        // Check if point is in triangle
        return (u >= 0) && (v >= 0) && (u + v <= 1);
    }
    
    /**
     * Represents a face of the convex hull
     */
    public static class HullFace {
        public final int v0, v1, v2;
        public final Vector3f normal;
        
        public HullFace(int v0, int v1, int v2, List<Point3f> vertices) {
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            
            // Compute face normal
            var edge1 = new Vector3f();
            edge1.sub(vertices.get(v1), vertices.get(v0));
            var edge2 = new Vector3f();
            edge2.sub(vertices.get(v2), vertices.get(v0));
            
            this.normal = new Vector3f();
            this.normal.cross(edge1, edge2);
            this.normal.normalize();
            
            // Ensure normal points outward (simplified - assumes convex shape centered around origin)
            var faceCentroid = new Point3f();
            faceCentroid.add(vertices.get(v0));
            faceCentroid.add(vertices.get(v1));
            faceCentroid.add(vertices.get(v2));
            faceCentroid.scale(1.0f / 3.0f);
            
            // Compute hull centroid (approximate)
            var hullCentroid = new Point3f();
            for (var v : vertices) {
                hullCentroid.add(v);
            }
            hullCentroid.scale(1.0f / vertices.size());
            
            // Check if normal points outward
            var toFace = new Vector3f();
            toFace.sub(faceCentroid, hullCentroid);
            
            if (toFace.dot(normal) < 0) {
                normal.scale(-1); // Flip to point outward
            }
        }
    }
}