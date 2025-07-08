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

/**
 * Triangle mesh collision shape for complex geometry.
 * Uses BVH acceleration for efficient collision detection.
 *
 * @author hal.hildebrand
 */
public final class MeshShape extends CollisionShape {
    
    private final TriangleMeshData meshData;
    private final MeshBVH bvh;
    private EntityBounds cachedBounds;
    
    public MeshShape(Point3f position, TriangleMeshData meshData) {
        super(position);
        this.meshData = meshData;
        
        // Transform mesh to position
        meshData.transform(position);
        
        // Build BVH for acceleration
        this.bvh = new MeshBVH(meshData);
        this.cachedBounds = meshData.getBounds();
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
        // Find vertex furthest in given direction
        var maxDot = -Float.MAX_VALUE;
        var support = new Point3f();
        
        for (int i = 0; i < meshData.getVertexCount(); i++) {
            var vertex = meshData.getVertex(i);
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
        var hit = bvh.intersectRay(ray);
        if (hit == null) {
            return RayIntersectionResult.noIntersection();
        }
        return RayIntersectionResult.intersection(hit.t, hit.point, hit.normal);
    }
    
    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
        meshData.transform(new Point3f(delta));
        cachedBounds = meshData.getBounds();
    }
    
    /**
     * Get the BVH acceleration structure
     */
    public MeshBVH getBVH() {
        return bvh;
    }
    
    /**
     * Get the triangle mesh data
     */
    public TriangleMeshData getMeshData() {
        return meshData;
    }
    
    /**
     * Get approximate radius for broad-phase
     */
    public float getRadius() {
        var center = meshData.computeCentroid();
        float maxDist = 0;
        for (int i = 0; i < meshData.getVertexCount(); i++) {
            var dist = meshData.getVertex(i).distance(center);
            maxDist = Math.max(maxDist, dist);
        }
        return maxDist;
    }
}