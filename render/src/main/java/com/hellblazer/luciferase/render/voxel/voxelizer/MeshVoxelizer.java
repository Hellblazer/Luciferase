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
package com.hellblazer.luciferase.render.voxel.voxelizer;

import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.core.VoxelData;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Converts 3D mesh data into voxel representations.
 * Supports multiple voxelization algorithms including:
 * - Conservative voxelization (GPU-based)
 * - Ray-based voxelization (CPU-based)
 * - Flood-fill solid voxelization
 */
public class MeshVoxelizer {
    
    private static final Logger log = Logger.getLogger(MeshVoxelizer.class.getName());
    
    // Voxelization algorithms
    public enum Algorithm {
        CONSERVATIVE,   // GPU-based conservative rasterization
        RAY_BASED,     // CPU ray-triangle intersection
        FLOOD_FILL,    // Surface voxelization + flood fill
        HYBRID         // GPU surface + CPU fill
    }
    
    // Mesh data structure
    public static class Mesh {
        public final float[] vertices;  // x,y,z triplets
        public final int[] indices;     // Triangle indices
        public final float[] normals;   // Optional vertex normals
        public final float[] colors;    // Optional vertex colors
        
        public Mesh(float[] vertices, int[] indices, float[] normals, float[] colors) {
            this.vertices = vertices;
            this.indices = indices;
            this.normals = normals;
            this.colors = colors;
        }
        
        public int getTriangleCount() {
            return indices.length / 3;
        }
        
        public void getTriangle(int index, float[] v0, float[] v1, float[] v2) {
            int i0 = indices[index * 3] * 3;
            int i1 = indices[index * 3 + 1] * 3;
            int i2 = indices[index * 3 + 2] * 3;
            
            v0[0] = vertices[i0];
            v0[1] = vertices[i0 + 1];
            v0[2] = vertices[i0 + 2];
            
            v1[0] = vertices[i1];
            v1[1] = vertices[i1 + 1];
            v1[2] = vertices[i1 + 2];
            
            v2[0] = vertices[i2];
            v2[1] = vertices[i2 + 1];
            v2[2] = vertices[i2 + 2];
        }
    }
    
    // Voxelization parameters
    private final int resolution;
    private final Algorithm algorithm;
    private final ExecutorService executor;
    private final boolean useParallel;
    
    // Statistics
    private long lastVoxelizationTime;
    private int lastVoxelCount;
    
    public MeshVoxelizer(int resolution, Algorithm algorithm, boolean useParallel) {
        this.resolution = resolution;
        this.algorithm = algorithm;
        this.useParallel = useParallel;
        this.executor = useParallel ? 
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) : null;
    }
    
    /**
     * Voxelize a mesh into an octree structure.
     * 
     * @param mesh The mesh to voxelize
     * @param worldSize Size of the voxel world
     * @return Root node of voxelized octree
     */
    public EnhancedVoxelOctreeNode voxelize(Mesh mesh, float worldSize) {
        log.info("Voxelizing mesh with " + mesh.getTriangleCount() + " triangles at resolution " + resolution);
        
        long startTime = System.currentTimeMillis();
        
        // Calculate mesh bounds
        float[] minBounds = new float[3];
        float[] maxBounds = new float[3];
        calculateBounds(mesh, minBounds, maxBounds);
        
        // Create root node
        var rootNode = new EnhancedVoxelOctreeNode(
            minBounds[0], minBounds[1], minBounds[2],
            maxBounds[0], maxBounds[1], maxBounds[2],
            0, 0
        );
        
        // Perform voxelization based on algorithm
        var voxelGrid = switch (algorithm) {
            case CONSERVATIVE -> voxelizeConservative(mesh, minBounds, maxBounds);
            case RAY_BASED -> voxelizeRayBased(mesh, minBounds, maxBounds);
            case FLOOD_FILL -> voxelizeFloodFill(mesh, minBounds, maxBounds);
            case HYBRID -> voxelizeHybrid(mesh, minBounds, maxBounds);
        };
        
        // Convert voxel grid to octree
        buildOctreeFromGrid(rootNode, voxelGrid, minBounds, maxBounds);
        
        lastVoxelizationTime = System.currentTimeMillis() - startTime;
        log.info("Voxelization completed in " + lastVoxelizationTime + "ms with " + lastVoxelCount + " voxels");
        
        return rootNode;
    }
    
    /**
     * Ray-based voxelization algorithm.
     * Shoots rays through the voxel grid and tests for triangle intersections.
     */
    private boolean[][][] voxelizeRayBased(Mesh mesh, float[] minBounds, float[] maxBounds) {
        var grid = new boolean[resolution][resolution][resolution];
        var voxelSize = calculateVoxelSize(minBounds, maxBounds);
        
        if (useParallel) {
            // Parallel voxelization
            var futures = new ArrayList<Future<?>>();
            
            for (int z = 0; z < resolution; z++) {
                final int zFinal = z;
                futures.add(executor.submit(() -> {
                    voxelizeSlice(mesh, grid, zFinal, minBounds, voxelSize);
                }));
            }
            
            // Wait for all slices to complete
            for (var future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log.severe("Error in parallel voxelization: " + e.getMessage());
                }
            }
        } else {
            // Sequential voxelization
            for (int z = 0; z < resolution; z++) {
                voxelizeSlice(mesh, grid, z, minBounds, voxelSize);
            }
        }
        
        return grid;
    }
    
    /**
     * Voxelize a single Z slice.
     */
    private void voxelizeSlice(Mesh mesh, boolean[][][] grid, int z, float[] minBounds, float voxelSize) {
        var v0 = new float[3];
        var v1 = new float[3];
        var v2 = new float[3];
        
        for (int y = 0; y < resolution; y++) {
            for (int x = 0; x < resolution; x++) {
                // Calculate ray origin
                float rayX = minBounds[0] + (x + 0.5f) * voxelSize;
                float rayY = minBounds[1] + (y + 0.5f) * voxelSize;
                float rayZ = minBounds[2] + (z + 0.5f) * voxelSize;
                
                // Test ray against all triangles
                int intersectionCount = 0;
                
                for (int t = 0; t < mesh.getTriangleCount(); t++) {
                    mesh.getTriangle(t, v0, v1, v2);
                    
                    // Ray-triangle intersection test (Z-axis aligned ray)
                    if (rayTriangleIntersect(rayX, rayY, rayZ, v0, v1, v2)) {
                        intersectionCount++;
                    }
                }
                
                // Odd number of intersections means inside the mesh
                grid[x][y][z] = (intersectionCount % 2) == 1;
            }
        }
    }
    
    /**
     * Conservative voxelization (placeholder - would use GPU).
     */
    private boolean[][][] voxelizeConservative(Mesh mesh, float[] minBounds, float[] maxBounds) {
        // Conservative voxelization typically requires GPU support
        // For now, fall back to ray-based
        log.warning("Conservative voxelization not yet implemented, using ray-based");
        return voxelizeRayBased(mesh, minBounds, maxBounds);
    }
    
    /**
     * Flood fill voxelization.
     * First voxelizes the surface, then fills the interior.
     */
    private boolean[][][] voxelizeFloodFill(Mesh mesh, float[] minBounds, float[] maxBounds) {
        var grid = new boolean[resolution][resolution][resolution];
        var voxelSize = calculateVoxelSize(minBounds, maxBounds);
        
        // First, voxelize the surface
        voxelizeSurface(mesh, grid, minBounds, voxelSize);
        
        // Then, flood fill the interior
        floodFillInterior(grid);
        
        return grid;
    }
    
    /**
     * Voxelize only the surface of the mesh.
     */
    private void voxelizeSurface(Mesh mesh, boolean[][][] grid, float[] minBounds, float voxelSize) {
        var v0 = new float[3];
        var v1 = new float[3];
        var v2 = new float[3];
        
        for (int t = 0; t < mesh.getTriangleCount(); t++) {
            mesh.getTriangle(t, v0, v1, v2);
            
            // Calculate triangle bounding box in voxel space
            int minX = Math.max(0, (int)((Math.min(v0[0], Math.min(v1[0], v2[0])) - minBounds[0]) / voxelSize));
            int maxX = Math.min(resolution - 1, (int)((Math.max(v0[0], Math.max(v1[0], v2[0])) - minBounds[0]) / voxelSize) + 1);
            int minY = Math.max(0, (int)((Math.min(v0[1], Math.min(v1[1], v2[1])) - minBounds[1]) / voxelSize));
            int maxY = Math.min(resolution - 1, (int)((Math.max(v0[1], Math.max(v1[1], v2[1])) - minBounds[1]) / voxelSize) + 1);
            int minZ = Math.max(0, (int)((Math.min(v0[2], Math.min(v1[2], v2[2])) - minBounds[2]) / voxelSize));
            int maxZ = Math.min(resolution - 1, (int)((Math.max(v0[2], Math.max(v1[2], v2[2])) - minBounds[2]) / voxelSize) + 1);
            
            // Test voxels in bounding box
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        float voxelX = minBounds[0] + (x + 0.5f) * voxelSize;
                        float voxelY = minBounds[1] + (y + 0.5f) * voxelSize;
                        float voxelZ = minBounds[2] + (z + 0.5f) * voxelSize;
                        
                        if (triangleVoxelOverlap(v0, v1, v2, voxelX, voxelY, voxelZ, voxelSize * 0.5f)) {
                            grid[x][y][z] = true;
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Flood fill the interior of a surface-voxelized mesh.
     */
    private void floodFillInterior(boolean[][][] grid) {
        // Find a seed point outside the mesh
        var outside = new boolean[resolution][resolution][resolution];
        var queue = new LinkedList<int[]>();
        
        // Start from corners
        queue.add(new int[]{0, 0, 0});
        outside[0][0][0] = true;
        
        // Flood fill outside
        while (!queue.isEmpty()) {
            var pos = queue.poll();
            int x = pos[0], y = pos[1], z = pos[2];
            
            // Check neighbors
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                        
                        int nx = x + dx, ny = y + dy, nz = z + dz;
                        if (nx >= 0 && nx < resolution && ny >= 0 && ny < resolution && 
                            nz >= 0 && nz < resolution && !outside[nx][ny][nz] && !grid[nx][ny][nz]) {
                            outside[nx][ny][nz] = true;
                            queue.add(new int[]{nx, ny, nz});
                        }
                    }
                }
            }
        }
        
        // Fill interior (everything not outside and not surface)
        for (int z = 0; z < resolution; z++) {
            for (int y = 0; y < resolution; y++) {
                for (int x = 0; x < resolution; x++) {
                    if (!outside[x][y][z]) {
                        grid[x][y][z] = true;
                    }
                }
            }
        }
    }
    
    /**
     * Hybrid voxelization (GPU surface + CPU fill).
     */
    private boolean[][][] voxelizeHybrid(Mesh mesh, float[] minBounds, float[] maxBounds) {
        // For now, use flood fill approach
        return voxelizeFloodFill(mesh, minBounds, maxBounds);
    }
    
    /**
     * Ray-triangle intersection test.
     */
    private boolean rayTriangleIntersect(float rayX, float rayY, float rayZ,
                                        float[] v0, float[] v1, float[] v2) {
        // Möller-Trumbore algorithm for Z-aligned ray
        float edge1X = v1[0] - v0[0];
        float edge1Y = v1[1] - v0[1];
        float edge1Z = v1[2] - v0[2];
        
        float edge2X = v2[0] - v0[0];
        float edge2Y = v2[1] - v0[1];
        float edge2Z = v2[2] - v0[2];
        
        // Ray direction is (0, 0, 1) for Z-aligned ray
        float pX = -edge2Y;
        float pY = edge2X;
        float pZ = 0;
        
        float det = edge1X * pX + edge1Y * pY + edge1Z * pZ;
        
        if (Math.abs(det) < 1e-8f) {
            return false; // Ray parallel to triangle
        }
        
        float invDet = 1.0f / det;
        
        float tX = rayX - v0[0];
        float tY = rayY - v0[1];
        float tZ = rayZ - v0[2];
        
        float u = (tX * pX + tY * pY + tZ * pZ) * invDet;
        
        if (u < 0 || u > 1) {
            return false;
        }
        
        float qX = tY * edge1Z - tZ * edge1Y;
        float qY = tZ * edge1X - tX * edge1Z;
        float qZ = tX * edge1Y - tY * edge1X;
        
        float v = qZ * invDet;
        
        if (v < 0 || u + v > 1) {
            return false;
        }
        
        float t = (edge2X * qX + edge2Y * qY + edge2Z * qZ) * invDet;
        
        return t > 0; // Intersection if t > 0
    }
    
    /**
     * Triangle-voxel overlap test.
     */
    private boolean triangleVoxelOverlap(float[] v0, float[] v1, float[] v2,
                                        float voxelX, float voxelY, float voxelZ,
                                        float halfSize) {
        // Simplified AABB-triangle test
        // Check if triangle bounding box overlaps voxel
        float minX = Math.min(v0[0], Math.min(v1[0], v2[0]));
        float maxX = Math.max(v0[0], Math.max(v1[0], v2[0]));
        float minY = Math.min(v0[1], Math.min(v1[1], v2[1]));
        float maxY = Math.max(v0[1], Math.max(v1[1], v2[1]));
        float minZ = Math.min(v0[2], Math.min(v1[2], v2[2]));
        float maxZ = Math.max(v0[2], Math.max(v1[2], v2[2]));
        
        return !(maxX < voxelX - halfSize || minX > voxelX + halfSize ||
                maxY < voxelY - halfSize || minY > voxelY + halfSize ||
                maxZ < voxelZ - halfSize || minZ > voxelZ + halfSize);
    }
    
    /**
     * Calculate mesh bounds.
     */
    private void calculateBounds(Mesh mesh, float[] minBounds, float[] maxBounds) {
        minBounds[0] = minBounds[1] = minBounds[2] = Float.MAX_VALUE;
        maxBounds[0] = maxBounds[1] = maxBounds[2] = -Float.MAX_VALUE;
        
        for (int i = 0; i < mesh.vertices.length; i += 3) {
            minBounds[0] = Math.min(minBounds[0], mesh.vertices[i]);
            minBounds[1] = Math.min(minBounds[1], mesh.vertices[i + 1]);
            minBounds[2] = Math.min(minBounds[2], mesh.vertices[i + 2]);
            
            maxBounds[0] = Math.max(maxBounds[0], mesh.vertices[i]);
            maxBounds[1] = Math.max(maxBounds[1], mesh.vertices[i + 1]);
            maxBounds[2] = Math.max(maxBounds[2], mesh.vertices[i + 2]);
        }
    }
    
    /**
     * Calculate voxel size from bounds.
     */
    private float calculateVoxelSize(float[] minBounds, float[] maxBounds) {
        float maxExtent = Math.max(maxBounds[0] - minBounds[0],
                                  Math.max(maxBounds[1] - minBounds[1],
                                          maxBounds[2] - minBounds[2]));
        return maxExtent / resolution;
    }
    
    /**
     * Build octree from voxel grid.
     */
    private void buildOctreeFromGrid(EnhancedVoxelOctreeNode root, boolean[][][] grid,
                                    float[] minBounds, float[] maxBounds) {
        var voxelSize = calculateVoxelSize(minBounds, maxBounds);
        lastVoxelCount = 0;
        
        for (int z = 0; z < resolution; z++) {
            for (int y = 0; y < resolution; y++) {
                for (int x = 0; x < resolution; x++) {
                    if (grid[x][y][z]) {
                        float[] position = {
                            minBounds[0] + (x + 0.5f) * voxelSize,
                            minBounds[1] + (y + 0.5f) * voxelSize,
                            minBounds[2] + (z + 0.5f) * voxelSize
                        };
                        
                        // Insert voxel into octree
                        root.insertVoxel(position, 0xFFFFFFFF, calculateMaxDepth());
                        lastVoxelCount++;
                    }
                }
            }
        }
    }
    
    /**
     * Calculate maximum octree depth based on resolution.
     */
    private int calculateMaxDepth() {
        return (int)(Math.log(resolution) / Math.log(2)) + 1;
    }
    
    /**
     * Cleanup resources.
     */
    public void dispose() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
    }
    
    // Getters for statistics
    public long getLastVoxelizationTime() { return lastVoxelizationTime; }
    public int getLastVoxelCount() { return lastVoxelCount; }
}