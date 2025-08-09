package com.hellblazer.luciferase.render.voxel.pipeline;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

/**
 * Multi-resolution voxelizer for creating Level-of-Detail (LOD) voxel representations.
 * Generates multiple resolutions of the same mesh for efficient rendering at different distances.
 */
public class MultiResolutionVoxelizer {
    
    /**
     * LOD level configuration.
     */
    public static class LODLevel {
        public final int resolution;
        public final float errorThreshold;
        public final float distanceRange;
        
        public LODLevel(int resolution, float errorThreshold, float distanceRange) {
            this.resolution = resolution;
            this.errorThreshold = errorThreshold;
            this.distanceRange = distanceRange;
        }
    }
    
    /**
     * Multi-resolution voxel hierarchy.
     */
    public static class VoxelLODHierarchy {
        private final List<VoxelGrid> levels;
        private final List<LODLevel> configs;
        private final Map<Integer, Float> levelErrors;
        
        public VoxelLODHierarchy() {
            this.levels = new ArrayList<>();
            this.configs = new ArrayList<>();
            this.levelErrors = new HashMap<>();
        }
        
        public void addLevel(VoxelGrid grid, LODLevel config, float error) {
            levels.add(grid);
            configs.add(config);
            levelErrors.put(levels.size() - 1, error);
        }
        
        public VoxelGrid getLevel(int index) {
            return levels.get(index);
        }
        
        public int getLevelCount() {
            return levels.size();
        }
        
        public VoxelGrid selectLOD(float viewDistance) {
            for (int i = 0; i < configs.size(); i++) {
                if (viewDistance <= configs.get(i).distanceRange) {
                    return levels.get(i);
                }
            }
            return levels.get(levels.size() - 1); // Coarsest level
        }
        
        public float getLevelError(int level) {
            return levelErrors.getOrDefault(level, 0.0f);
        }
    }
    
    private final MeshVoxelizer baseVoxelizer;
    private final GPUVoxelizer gpuVoxelizer;
    private final ForkJoinPool threadPool;
    
    public MultiResolutionVoxelizer(MeshVoxelizer baseVoxelizer, GPUVoxelizer gpuVoxelizer) {
        this.baseVoxelizer = baseVoxelizer;
        this.gpuVoxelizer = gpuVoxelizer;
        this.threadPool = new ForkJoinPool();
    }
    
    /**
     * Creates a multi-resolution voxel hierarchy from a triangle mesh.
     */
    public CompletableFuture<VoxelLODHierarchy> createLODHierarchy(
            List<MeshVoxelizer.Triangle> triangles,
            List<LODLevel> lodLevels,
            boolean useGPU) {
        
        return CompletableFuture.supplyAsync(() -> {
            var hierarchy = new VoxelLODHierarchy();
            
            // Sort LOD levels by resolution (finest to coarsest)
            var sortedLevels = new ArrayList<>(lodLevels);
            sortedLevels.sort((a, b) -> Integer.compare(b.resolution, a.resolution));
            
            VoxelGrid previousGrid = null;
            
            for (var lodLevel : sortedLevels) {
                VoxelGrid grid;
                float error;
                
                if (previousGrid == null) {
                    // Generate finest level from triangles
                    if (useGPU && gpuVoxelizer != null) {
                        grid = gpuVoxelizer.voxelizeGPU(triangles, lodLevel.resolution).join();
                    } else {
                        grid = baseVoxelizer.voxelize(triangles, lodLevel.resolution);
                    }
                    error = 0.0f; // Finest level has no error
                } else {
                    // Generate coarser level by downsampling
                    grid = downsampleGrid(previousGrid, lodLevel.resolution);
                    error = computeError(previousGrid, grid, lodLevel.errorThreshold);
                    
                    // If error exceeds threshold, re-voxelize from triangles
                    if (error > lodLevel.errorThreshold) {
                        if (useGPU && gpuVoxelizer != null) {
                            grid = gpuVoxelizer.voxelizeGPU(triangles, lodLevel.resolution).join();
                        } else {
                            grid = baseVoxelizer.voxelize(triangles, lodLevel.resolution);
                        }
                        error = computeError(previousGrid, grid, lodLevel.errorThreshold);
                    }
                }
                
                hierarchy.addLevel(grid, lodLevel, error);
                previousGrid = grid;
            }
            
            return hierarchy;
        }, threadPool);
    }
    
    /**
     * Creates an adaptive LOD hierarchy based on geometric complexity.
     */
    public CompletableFuture<VoxelLODHierarchy> createAdaptiveLODHierarchy(
            List<MeshVoxelizer.Triangle> triangles,
            int maxResolution,
            int minResolution,
            int numLevels) {
        
        // Generate LOD levels based on geometric progression
        var lodLevels = generateAdaptiveLODLevels(minResolution, maxResolution, numLevels);
        
        return createLODHierarchy(triangles, lodLevels, true);
    }
    
    /**
     * Downsamples a voxel grid to a lower resolution.
     */
    private VoxelGrid downsampleGrid(VoxelGrid source, int targetResolution) {
        float scale = (float) source.getResolution() / targetResolution;
        var target = new VoxelGrid(targetResolution, source.getMin(), source.getMax());
        
        // Use box filtering for downsampling
        source.forEachVoxel((sx, sy, sz, sourceVoxel) -> {
            int tx = (int)(sx / scale);
            int ty = (int)(sy / scale);
            int tz = (int)(sz / scale);
            
            if (tx < targetResolution && ty < targetResolution && tz < targetResolution) {
                // Accumulate coverage
                target.addVoxel(tx, ty, tz, sourceVoxel.getMaterial(), 
                              sourceVoxel.getCoverage() / (scale * scale * scale));
            }
        });
        
        return target;
    }
    
    /**
     * Computes the error between two voxel grids.
     */
    private float computeError(VoxelGrid fine, VoxelGrid coarse, float maxError) {
        var errorAccum = new ErrorAccumulator();
        float scale = (float) fine.getResolution() / coarse.getResolution();
        
        // Sample error at regular intervals
        int sampleStep = Math.max(1, fine.getResolution() / 32);
        
        for (int x = 0; x < fine.getResolution(); x += sampleStep) {
            for (int y = 0; y < fine.getResolution(); y += sampleStep) {
                for (int z = 0; z < fine.getResolution(); z += sampleStep) {
                    var fineVoxel = fine.getVoxel(x, y, z);
                    
                    int cx = (int)(x / scale);
                    int cy = (int)(y / scale);
                    int cz = (int)(z / scale);
                    var coarseVoxel = coarse.getVoxel(cx, cy, cz);
                    
                    float error = computeVoxelError(fineVoxel, coarseVoxel);
                    errorAccum.add(error);
                    
                    // Early exit if error exceeds threshold
                    if (errorAccum.getAverage() > maxError) {
                        return errorAccum.getAverage();
                    }
                }
            }
        }
        
        return errorAccum.getAverage();
    }
    
    /**
     * Computes error between individual voxels.
     */
    private float computeVoxelError(VoxelGrid.Voxel fine, VoxelGrid.Voxel coarse) {
        if (fine == null && coarse == null) {
            return 0.0f;
        }
        if (fine == null || coarse == null) {
            return 1.0f; // Maximum error for presence mismatch
        }
        
        // Compute error based on material and coverage differences
        float materialError = (fine.getMaterial() != coarse.getMaterial()) ? 0.5f : 0.0f;
        float coverageError = Math.abs(fine.getCoverage() - coarse.getCoverage());
        
        return materialError + coverageError * 0.5f;
    }
    
    /**
     * Generates adaptive LOD levels based on geometric progression.
     */
    private List<LODLevel> generateAdaptiveLODLevels(int minRes, int maxRes, int numLevels) {
        var levels = new ArrayList<LODLevel>();
        
        // Geometric progression for resolutions
        double ratio = Math.pow((double)minRes / maxRes, 1.0 / (numLevels - 1));
        
        for (int i = 0; i < numLevels; i++) {
            int resolution = (int)(maxRes * Math.pow(ratio, i));
            float errorThreshold = 0.01f * (i + 1); // Increasing error tolerance
            float distanceRange = 10.0f * (float)Math.pow(2, i); // Exponential distance ranges
            
            levels.add(new LODLevel(resolution, errorThreshold, distanceRange));
        }
        
        return levels;
    }
    
    /**
     * Optimizes voxel grid by removing interior voxels.
     */
    public static VoxelGrid optimizeGrid(VoxelGrid grid) {
        var optimized = new VoxelGrid(grid.getResolution(), grid.getMin(), grid.getMax());
        
        grid.forEachVoxel((x, y, z, voxel) -> {
            // Check if voxel is on the surface (has at least one empty neighbor)
            boolean isSurface = false;
            
            for (int dx = -1; dx <= 1 && !isSurface; dx++) {
                for (int dy = -1; dy <= 1 && !isSurface; dy++) {
                    for (int dz = -1; dz <= 1 && !isSurface; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        
                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        
                        if (nx >= 0 && nx < grid.getResolution() &&
                            ny >= 0 && ny < grid.getResolution() &&
                            nz >= 0 && nz < grid.getResolution()) {
                            
                            if (!grid.hasVoxel(nx, ny, nz)) {
                                isSurface = true;
                            }
                        } else {
                            isSurface = true; // Border voxels are surface
                        }
                    }
                }
            }
            
            if (isSurface) {
                optimized.addVoxel(x, y, z, voxel.getMaterial(), voxel.getCoverage());
            }
        });
        
        return optimized;
    }
    
    public void shutdown() {
        threadPool.shutdown();
    }
    
    /**
     * Helper class for accumulating error statistics.
     */
    private static class ErrorAccumulator {
        private float sum = 0.0f;
        private int count = 0;
        
        void add(float error) {
            sum += error;
            count++;
        }
        
        float getAverage() {
            return count > 0 ? sum / count : 0.0f;
        }
    }
}