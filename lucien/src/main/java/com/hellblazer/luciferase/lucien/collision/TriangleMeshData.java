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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Triangle mesh data storage for collision detection.
 * Stores vertices and triangle indices efficiently.
 *
 * @author hal.hildebrand
 */
public class TriangleMeshData {
    
    private final List<Point3f> vertices;
    private final List<Triangle> triangles;
    private EntityBounds bounds;
    
    public TriangleMeshData() {
        this.vertices = new ArrayList<>();
        this.triangles = new ArrayList<>();
    }
    
    /**
     * Add a vertex to the mesh
     * @return the index of the added vertex
     */
    public int addVertex(Point3f vertex) {
        vertices.add(new Point3f(vertex));
        invalidateBounds();
        return vertices.size() - 1;
    }
    
    /**
     * Add a triangle to the mesh using vertex indices
     */
    public void addTriangle(int v0, int v1, int v2) {
        if (v0 < 0 || v0 >= vertices.size() || 
            v1 < 0 || v1 >= vertices.size() || 
            v2 < 0 || v2 >= vertices.size()) {
            throw new IllegalArgumentException("Invalid vertex indices");
        }
        triangles.add(new Triangle(v0, v1, v2));
        invalidateBounds();
    }
    
    /**
     * Get the number of vertices
     */
    public int getVertexCount() {
        return vertices.size();
    }
    
    /**
     * Get the number of triangles
     */
    public int getTriangleCount() {
        return triangles.size();
    }
    
    /**
     * Get a vertex by index
     */
    public Point3f getVertex(int index) {
        return new Point3f(vertices.get(index));
    }
    
    /**
     * Get a triangle by index
     */
    public Triangle getTriangle(int index) {
        return triangles.get(index);
    }
    
    /**
     * Get all triangles
     */
    public List<Triangle> getTriangles() {
        return new ArrayList<>(triangles);
    }
    
    /**
     * Get the three vertices of a triangle
     */
    public void getTriangleVertices(int triangleIndex, Point3f v0, Point3f v1, Point3f v2) {
        var tri = triangles.get(triangleIndex);
        v0.set(vertices.get(tri.v0));
        v1.set(vertices.get(tri.v1));
        v2.set(vertices.get(tri.v2));
    }
    
    /**
     * Get the axis-aligned bounding box of the mesh
     */
    public EntityBounds getBounds() {
        if (bounds == null) {
            computeBounds();
        }
        return bounds;
    }
    
    /**
     * Transform all vertices by the given transformation
     */
    public void transform(Point3f translation) {
        for (var vertex : vertices) {
            vertex.add(translation);
        }
        invalidateBounds();
    }
    
    /**
     * Scale the mesh by the given factors
     */
    public void scale(float scaleX, float scaleY, float scaleZ) {
        for (var vertex : vertices) {
            vertex.x *= scaleX;
            vertex.y *= scaleY;
            vertex.z *= scaleZ;
        }
        invalidateBounds();
    }
    
    /**
     * Compute the centroid of the mesh
     */
    public Point3f computeCentroid() {
        var centroid = new Point3f(0, 0, 0);
        for (var vertex : vertices) {
            centroid.add(vertex);
        }
        if (!vertices.isEmpty()) {
            centroid.scale(1.0f / vertices.size());
        }
        return centroid;
    }
    
    /**
     * Test if a point is inside the mesh (for closed meshes)
     * Uses ray casting algorithm
     */
    public boolean containsPoint(Point3f point) {
        // Cast a ray in +X direction and count intersections
        var ray = new Vector3f(1, 0, 0);
        int intersections = 0;
        
        for (var triangle : triangles) {
            var v0 = vertices.get(triangle.v0);
            var v1 = vertices.get(triangle.v1);
            var v2 = vertices.get(triangle.v2);
            
            if (rayIntersectsTriangle(point, ray, v0, v1, v2)) {
                intersections++;
            }
        }
        
        // Odd number of intersections means inside
        return (intersections & 1) == 1;
    }
    
    private void invalidateBounds() {
        bounds = null;
    }
    
    private void computeBounds() {
        if (vertices.isEmpty()) {
            bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(0, 0, 0));
            return;
        }
        
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
        
        bounds = new EntityBounds(min, max);
    }
    
    private boolean rayIntersectsTriangle(Point3f origin, Vector3f direction, 
                                         Point3f v0, Point3f v1, Point3f v2) {
        // Möller–Trumbore intersection algorithm
        var edge1 = new Vector3f();
        edge1.sub(v1, v0);
        var edge2 = new Vector3f();
        edge2.sub(v2, v0);
        
        var h = new Vector3f();
        h.cross(direction, edge2);
        var a = edge1.dot(h);
        
        if (a > -1e-6f && a < 1e-6f) {
            return false; // Ray is parallel to triangle
        }
        
        var f = 1.0f / a;
        var s = new Vector3f();
        s.sub(origin, v0);
        var u = f * s.dot(h);
        
        if (u < 0.0f || u > 1.0f) {
            return false;
        }
        
        var q = new Vector3f();
        q.cross(s, edge1);
        var v = f * direction.dot(q);
        
        if (v < 0.0f || u + v > 1.0f) {
            return false;
        }
        
        var t = f * edge2.dot(q);
        return t > 1e-6f; // Intersection is in front of ray origin
    }
    
    /**
     * Triangle representation using vertex indices
     */
    public static class Triangle {
        public final int v0, v1, v2;
        
        public Triangle(int v0, int v1, int v2) {
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
        }
        
        /**
         * Compute the normal of this triangle
         */
        public Vector3f computeNormal(List<Point3f> vertices) {
            var v0 = vertices.get(this.v0);
            var v1 = vertices.get(this.v1);
            var v2 = vertices.get(this.v2);
            
            var edge1 = new Vector3f();
            edge1.sub(v1, v0);
            var edge2 = new Vector3f();
            edge2.sub(v2, v0);
            
            var normal = new Vector3f();
            normal.cross(edge1, edge2);
            normal.normalize();
            return normal;
        }
    }
}