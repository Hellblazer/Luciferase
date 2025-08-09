package com.hellblazer.luciferase.render.voxel.pipeline;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts triangle meshes to voxel representations.
 * Supports multi-threaded voxelization and progressive refinement.
 */
public class MeshVoxelizer {
    
    private final ForkJoinPool threadPool;
    private final int maxThreads;
    
    public MeshVoxelizer() {
        this(ForkJoinPool.getCommonPoolParallelism());
    }
    
    public MeshVoxelizer(int maxThreads) {
        this.maxThreads = maxThreads;
        this.threadPool = new ForkJoinPool(maxThreads);
    }
    
    /**
     * Voxelizes a triangle mesh at the specified resolution.
     *
     * @param triangles List of triangles (3 points each)
     * @param resolution Number of voxels along each axis
     * @return VoxelGrid containing the voxelized representation
     */
    public VoxelGrid voxelize(List<Triangle> triangles, int resolution) {
        // Compute bounding box
        var bounds = computeBounds(triangles);
        var grid = new VoxelGrid(resolution, bounds.min, bounds.max);
        
        // Process triangles in parallel
        var futures = new ArrayList<CompletableFuture<Void>>();
        int batchSize = Math.max(1, triangles.size() / (maxThreads * 4));
        
        for (int i = 0; i < triangles.size(); i += batchSize) {
            int start = i;
            int end = Math.min(i + batchSize, triangles.size());
            
            var future = CompletableFuture.runAsync(() -> {
                voxelizeBatch(triangles.subList(start, end), grid);
            }, threadPool);
            
            futures.add(future);
        }
        
        // Wait for completion
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        return grid;
    }
    
    /**
     * Voxelizes with adaptive resolution based on triangle density.
     */
    public VoxelGrid voxelizeAdaptive(List<Triangle> triangles, 
                                      int minResolution, 
                                      int maxResolution,
                                      float densityThreshold) {
        // Start with minimum resolution
        var coarseGrid = voxelize(triangles, minResolution);
        
        // Identify regions needing refinement
        var refinementRegions = identifyRefinementRegions(coarseGrid, densityThreshold);
        
        // Create hierarchical grid
        var adaptiveGrid = new VoxelGrid(maxResolution, coarseGrid.getMin(), coarseGrid.getMax());
        adaptiveGrid.copyFrom(coarseGrid);
        
        // Refine dense regions
        for (var region : refinementRegions) {
            refineRegion(triangles, adaptiveGrid, region, maxResolution);
        }
        
        return adaptiveGrid;
    }
    
    private void voxelizeBatch(List<Triangle> triangles, VoxelGrid grid) {
        for (var triangle : triangles) {
            voxelizeTriangle(triangle, grid);
        }
    }
    
    private void voxelizeTriangle(Triangle triangle, VoxelGrid grid) {
        // Get triangle bounding box in voxel coordinates
        var triMin = grid.worldToVoxel(triangle.getMin());
        var triMax = grid.worldToVoxel(triangle.getMax());
        
        // Clamp to grid bounds
        int minX = Math.max(0, (int)triMin.x);
        int minY = Math.max(0, (int)triMin.y);
        int minZ = Math.max(0, (int)triMin.z);
        int maxX = Math.min(grid.getResolution() - 1, (int)Math.ceil(triMax.x));
        int maxY = Math.min(grid.getResolution() - 1, (int)Math.ceil(triMax.y));
        int maxZ = Math.min(grid.getResolution() - 1, (int)Math.ceil(triMax.z));
        
        // Test each voxel in bounding box
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    var voxelCenter = grid.voxelToWorld(x, y, z);
                    var voxelHalfSize = grid.getVoxelHalfSize();
                    
                    if (TriangleBoxIntersection.intersects(
                            triangle.v0, triangle.v1, triangle.v2,
                            voxelCenter, voxelHalfSize)) {
                        
                        // Compute coverage for anti-aliasing
                        float coverage = TriangleBoxIntersection.computeCoverageSampled(
                            triangle.v0, triangle.v1, triangle.v2,
                            voxelCenter, voxelHalfSize, 2
                        );
                        
                        if (coverage > 0) {
                            grid.addVoxel(x, y, z, triangle.getMaterial(), coverage);
                        }
                    }
                }
            }
        }
    }
    
    private BoundingBox computeBounds(List<Triangle> triangles) {
        var min = new Point3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        var max = new Point3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        
        for (var triangle : triangles) {
            updateBounds(min, max, triangle.v0);
            updateBounds(min, max, triangle.v1);
            updateBounds(min, max, triangle.v2);
        }
        
        return new BoundingBox(min, max);
    }
    
    private void updateBounds(Point3f min, Point3f max, Point3f point) {
        min.x = Math.min(min.x, point.x);
        min.y = Math.min(min.y, point.y);
        min.z = Math.min(min.z, point.z);
        max.x = Math.max(max.x, point.x);
        max.y = Math.max(max.y, point.y);
        max.z = Math.max(max.z, point.z);
    }
    
    private List<VoxelGrid.Region> identifyRefinementRegions(VoxelGrid grid, float threshold) {
        var regions = new ArrayList<VoxelGrid.Region>();
        int regionSize = 8; // 8x8x8 regions
        
        for (int rx = 0; rx < grid.getResolution(); rx += regionSize) {
            for (int ry = 0; ry < grid.getResolution(); ry += regionSize) {
                for (int rz = 0; rz < grid.getResolution(); rz += regionSize) {
                    float density = grid.getRegionDensity(rx, ry, rz, regionSize);
                    if (density > threshold) {
                        regions.add(new VoxelGrid.Region(rx, ry, rz, regionSize));
                    }
                }
            }
        }
        
        return regions;
    }
    
    private void refineRegion(List<Triangle> triangles, VoxelGrid grid, 
                              VoxelGrid.Region region, int targetResolution) {
        // Extract triangles intersecting this region
        var regionTriangles = new ArrayList<Triangle>();
        var regionMin = grid.voxelToWorld(region.x, region.y, region.z);
        var regionMax = grid.voxelToWorld(
            region.x + region.size,
            region.y + region.size,
            region.z + region.size
        );
        
        for (var triangle : triangles) {
            if (triangleIntersectsBox(triangle, regionMin, regionMax)) {
                regionTriangles.add(triangle);
            }
        }
        
        // Re-voxelize at higher resolution
        if (!regionTriangles.isEmpty()) {
            var subGrid = voxelize(regionTriangles, targetResolution / grid.getResolution() * region.size);
            grid.mergeSubGrid(subGrid, region);
        }
    }
    
    private boolean triangleIntersectsBox(Triangle triangle, Point3f boxMin, Point3f boxMax) {
        var boxCenter = new Point3f(
            (boxMin.x + boxMax.x) * 0.5f,
            (boxMin.y + boxMax.y) * 0.5f,
            (boxMin.z + boxMax.z) * 0.5f
        );
        var boxHalfSize = new Vector3f(
            (boxMax.x - boxMin.x) * 0.5f,
            (boxMax.y - boxMin.y) * 0.5f,
            (boxMax.z - boxMin.z) * 0.5f
        );
        
        return TriangleBoxIntersection.intersects(
            triangle.v0, triangle.v1, triangle.v2,
            boxCenter, boxHalfSize
        );
    }
    
    public void shutdown() {
        threadPool.shutdown();
    }
    
    /**
     * Triangle representation with material properties.
     */
    public static class Triangle {
        public final Point3f v0, v1, v2;
        private final int material;
        
        public Triangle(Point3f v0, Point3f v1, Point3f v2, int material) {
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            this.material = material;
        }
        
        public int getMaterial() {
            return material;
        }
        
        public Point3f getMin() {
            return new Point3f(
                Math.min(Math.min(v0.x, v1.x), v2.x),
                Math.min(Math.min(v0.y, v1.y), v2.y),
                Math.min(Math.min(v0.z, v1.z), v2.z)
            );
        }
        
        public Point3f getMax() {
            return new Point3f(
                Math.max(Math.max(v0.x, v1.x), v2.x),
                Math.max(Math.max(v0.y, v1.y), v2.y),
                Math.max(Math.max(v0.z, v1.z), v2.z)
            );
        }
    }
    
    private static class BoundingBox {
        final Point3f min, max;
        
        BoundingBox(Point3f min, Point3f max) {
            this.min = min;
            this.max = max;
        }
    }
    

}