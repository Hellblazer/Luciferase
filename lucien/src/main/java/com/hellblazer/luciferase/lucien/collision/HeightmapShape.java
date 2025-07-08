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
 * Heightmap collision shape for terrain representation.
 * Uses a 2D grid of height values to define a 3D terrain surface.
 *
 * @author hal.hildebrand
 */
public final class HeightmapShape extends CollisionShape {
    
    private final float[][] heights;
    private final int width;
    private final int depth;
    private final float cellSize;
    private final float minHeight;
    private final float maxHeight;
    private EntityBounds cachedBounds;
    
    /**
     * Create a heightmap from a 2D array of height values
     * @param position Origin position (corner of the heightmap)
     * @param heights 2D array of height values [x][z]
     * @param cellSize Size of each cell in world units
     */
    public HeightmapShape(Point3f position, float[][] heights, float cellSize) {
        super(position);
        this.heights = heights;
        this.width = heights.length;
        this.depth = heights[0].length;
        this.cellSize = cellSize;
        
        // Find min/max heights
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                min = Math.min(min, heights[x][z]);
                max = Math.max(max, heights[x][z]);
            }
        }
        this.minHeight = min;
        this.maxHeight = max;
        
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
        // Find the point on the heightmap surface that is furthest in the given direction
        var support = new Point3f();
        var maxDot = -Float.MAX_VALUE;
        
        // Check all grid points
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                float worldX = position.x + x * cellSize;
                float worldZ = position.z + z * cellSize;
                float worldY = position.y + heights[x][z];
                
                float dot = worldX * direction.x + worldY * direction.y + worldZ * direction.z;
                
                if (dot > maxDot) {
                    maxDot = dot;
                    support.set(worldX, worldY, worldZ);
                }
            }
        }
        
        return support;
    }
    
    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        // Use DDA or similar algorithm to traverse the heightmap
        var tMin = 0.0f;
        var tMax = ray.maxDistance();
        
        // First intersect with bounding box
        if (!rayIntersectsAABB(ray, cachedBounds, tMin, tMax)) {
            return RayIntersectionResult.noIntersection();
        }
        
        // Start at ray entry point
        var entryPoint = ray.pointAt(tMin);
        var exitPoint = ray.pointAt(tMax);
        
        // Convert to grid coordinates
        int startX = worldToGridX(entryPoint.x);
        int startZ = worldToGridZ(entryPoint.z);
        int endX = worldToGridX(exitPoint.x);
        int endZ = worldToGridZ(exitPoint.z);
        
        // Traverse grid cells
        var result = traverseGrid(ray, startX, startZ, endX, endZ);
        
        return result != null ? result : RayIntersectionResult.noIntersection();
    }
    
    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
        cachedBounds = computeBounds();
    }
    
    /**
     * Get the height at a specific world position
     */
    public float getHeightAtPosition(float worldX, float worldZ) {
        float localX = worldX - position.x;
        float localZ = worldZ - position.z;
        
        float gridX = localX / cellSize;
        float gridZ = localZ / cellSize;
        
        int x0 = (int)Math.floor(gridX);
        int z0 = (int)Math.floor(gridZ);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        
        // Clamp to grid bounds
        x0 = Math.max(0, Math.min(width - 1, x0));
        x1 = Math.max(0, Math.min(width - 1, x1));
        z0 = Math.max(0, Math.min(depth - 1, z0));
        z1 = Math.max(0, Math.min(depth - 1, z1));
        
        // Bilinear interpolation
        float fx = gridX - x0;
        float fz = gridZ - z0;
        
        float h00 = heights[x0][z0];
        float h10 = heights[x1][z0];
        float h01 = heights[x0][z1];
        float h11 = heights[x1][z1];
        
        float h0 = h00 * (1 - fx) + h10 * fx;
        float h1 = h01 * (1 - fx) + h11 * fx;
        
        return position.y + h0 * (1 - fz) + h1 * fz;
    }
    
    /**
     * Get the normal at a specific world position
     */
    public Vector3f getNormalAtPosition(float worldX, float worldZ) {
        float localX = worldX - position.x;
        float localZ = worldZ - position.z;
        
        int gridX = (int)(localX / cellSize);
        int gridZ = (int)(localZ / cellSize);
        
        // Clamp to valid range
        gridX = Math.max(0, Math.min(width - 2, gridX));
        gridZ = Math.max(0, Math.min(depth - 2, gridZ));
        
        // Get heights of the quad
        float h00 = heights[gridX][gridZ];
        float h10 = heights[gridX + 1][gridZ];
        float h01 = heights[gridX][gridZ + 1];
        
        // Calculate normal using cross product
        var v1 = new Vector3f(cellSize, h10 - h00, 0);
        var v2 = new Vector3f(0, h01 - h00, cellSize);
        
        var normal = new Vector3f();
        normal.cross(v2, v1);  // Swapped order to get correct normal direction
        normal.normalize();
        
        return normal;
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getDepth() {
        return depth;
    }
    
    public float getCellSize() {
        return cellSize;
    }
    
    private EntityBounds computeBounds() {
        var min = new Point3f(position.x, position.y + minHeight, position.z);
        var max = new Point3f(
            position.x + (width - 1) * cellSize,
            position.y + maxHeight,
            position.z + (depth - 1) * cellSize
        );
        return new EntityBounds(min, max);
    }
    
    
    private int worldToGridX(float worldX) {
        return (int)((worldX - position.x) / cellSize);
    }
    
    private int worldToGridZ(float worldZ) {
        return (int)((worldZ - position.z) / cellSize);
    }
    
    private RayIntersectionResult traverseGrid(Ray3D ray, int startX, int startZ, int endX, int endZ) {
        // Simple grid traversal - check each cell the ray passes through
        // This is a simplified version - a production implementation would use DDA
        
        float stepSize = cellSize * 0.01f; // Very small steps for accuracy
        float t = 0;
        float prevHeight = Float.MAX_VALUE;
        
        while (t <= ray.maxDistance()) {
            var point = ray.pointAt(t);
            float terrainHeight = getHeightAtPosition(point.x, point.z);
            
            // Check if ray crossed the terrain surface
            if (point.y <= terrainHeight) {
                // Use binary search to refine the intersection point
                float tMin = Math.max(0, t - stepSize);
                float tMax = t;
                
                for (int i = 0; i < 10; i++) {
                    float tMid = (tMin + tMax) / 2;
                    var midPoint = ray.pointAt(tMid);
                    float midHeight = getHeightAtPosition(midPoint.x, midPoint.z);
                    
                    if (midPoint.y <= midHeight) {
                        tMax = tMid;
                    } else {
                        tMin = tMid;
                    }
                }
                
                var finalPoint = ray.pointAt(tMax);
                var normal = getNormalAtPosition(finalPoint.x, finalPoint.z);
                return RayIntersectionResult.intersection(tMax, finalPoint, normal);
            }
            
            prevHeight = terrainHeight;
            t += stepSize;
        }
        
        return null;
    }
    
    private boolean rayIntersectsAABB(Ray3D ray, EntityBounds bounds, float tMin, float tMax) {
        var invDir = new Vector3f(
            1.0f / ray.direction().x,
            1.0f / ray.direction().y,
            1.0f / ray.direction().z
        );
        
        float t1 = (bounds.getMin().x - ray.origin().x) * invDir.x;
        float t2 = (bounds.getMax().x - ray.origin().x) * invDir.x;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        t1 = (bounds.getMin().y - ray.origin().y) * invDir.y;
        t2 = (bounds.getMax().y - ray.origin().y) * invDir.y;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        t1 = (bounds.getMin().z - ray.origin().z) * invDir.z;
        t2 = (bounds.getMax().z - ray.origin().z) * invDir.z;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        return tMax >= tMin && tMax >= 0;
    }
}