package com.hellblazer.luciferase.render.voxel.parallel;

import com.hellblazer.luciferase.render.voxel.quality.QualityController;
import com.hellblazer.luciferase.render.voxel.quality.QualityController.VoxelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import javax.vecmath.Color3f;
import java.util.concurrent.Callable;
import java.util.List;
import java.util.ArrayList;

/**
 * Parallel slice processing task for multi-threaded octree building.
 * 
 * Processes a slice of the voxel grid, analyzing quality metrics and
 * preparing voxel data for octree insertion. Uses ESVO-style quality
 * analysis to determine subdivision requirements.
 */
public class SliceTask implements Callable<SliceResult> {
    private static final Logger log = LoggerFactory.getLogger(SliceTask.class);
    
    // Task state (reused across calls)
    private int[] denseGrid;
    private float[][] voxelColors;
    private int gridSize;
    private float[] boundsMin;
    private float[] boundsMax;
    private int maxDepth;
    private int startZ;
    private int endZ;
    private QualityController qualityController;
    private SliceBasedOctreeBuilder.ObjectPool<VoxelData> voxelDataPool;
    
    // Task-local state
    private boolean initialized = false;
    
    /**
     * Default constructor for object pooling.
     */
    public SliceTask() {
        // Empty - initialized via initialize() method
    }
    
    /**
     * Initialize task parameters for reuse.
     */
    public void initialize(int[] denseGrid,
                          float[][] voxelColors,
                          int gridSize,
                          float[] boundsMin,
                          float[] boundsMax,
                          int maxDepth,
                          int startZ,
                          int endZ,
                          QualityController qualityController,
                          SliceBasedOctreeBuilder.ObjectPool<VoxelData> voxelDataPool) {
        this.denseGrid = denseGrid;
        this.voxelColors = voxelColors;
        this.gridSize = gridSize;
        this.boundsMin = boundsMin.clone();
        this.boundsMax = boundsMax.clone();
        this.maxDepth = maxDepth;
        this.startZ = startZ;
        this.endZ = endZ;
        this.qualityController = qualityController;
        this.voxelDataPool = voxelDataPool;
        this.initialized = true;
    }
    
    @Override
    public SliceResult call() throws Exception {
        if (!initialized) {
            throw new IllegalStateException("SliceTask not initialized");
        }
        
        long startTime = System.nanoTime();
        var processedVoxels = new ArrayList<ProcessedVoxel>();
        
        // Calculate voxel size
        float voxelSize = (boundsMax[0] - boundsMin[0]) / gridSize;
        
        // Process all voxels in this slice range
        for (int z = startZ; z < endZ; z++) {
            for (int y = 0; y < gridSize; y++) {
                for (int x = 0; x < gridSize; x++) {
                    int idx = getVoxelIndex(x, y, z);
                    
                    if (denseGrid[idx] != 0) {
                        // Calculate world position
                        float[] position = {
                            (float)x / gridSize,
                            (float)y / gridSize,
                            (float)z / gridSize
                        };
                        
                        // Pack color
                        int color = packColor(voxelColors[idx]);
                        
                        // Analyze quality in local neighborhood
                        var qualityData = analyzeVoxelQuality(x, y, z, voxelSize);
                        
                        // Create processed voxel
                        var processedVoxel = new ProcessedVoxel(position, color, qualityData);
                        processedVoxels.add(processedVoxel);
                    }
                }
            }
        }
        
        long processingTime = System.nanoTime() - startTime;
        
        log.debug("Slice task completed: Z[{}-{}], {} voxels processed in {:.2f}ms",
                 startZ, endZ, processedVoxels.size(), processingTime / 1_000_000.0);
        
        return new SliceResult(processedVoxels, startZ, endZ, processingTime);
    }
    
    /**
     * Analyze quality metrics for a voxel and its neighborhood.
     */
    private VoxelData analyzeVoxelQuality(int x, int y, int z, float voxelSize) {
        var qualityData = voxelDataPool.acquire();
        qualityData = new VoxelData(voxelSize); // Reset for reuse
        
        // Sample colors and normals in 3x3x3 neighborhood
        analyzeColorVariation(qualityData, x, y, z);
        analyzeNormalVariation(qualityData, x, y, z);
        analyzeContourComplexity(qualityData, x, y, z);
        
        return qualityData;
    }
    
    /**
     * Analyze color variation in voxel neighborhood.
     */
    private void analyzeColorVariation(VoxelData qualityData, int centerX, int centerY, int centerZ) {
        // Sample 3x3x3 neighborhood for color variation analysis
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = centerX + dx;
                    int y = centerY + dy;
                    int z = centerZ + dz;
                    
                    if (isValidCoordinate(x, y, z)) {
                        int idx = getVoxelIndex(x, y, z);
                        if (denseGrid[idx] != 0) {
                            var color = new Color3f(
                                voxelColors[idx][0],
                                voxelColors[idx][1],
                                voxelColors[idx][2]
                            );
                            qualityData.addColorSample(color);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Analyze normal variation by estimating surface normals.
     */
    private void analyzeNormalVariation(VoxelData qualityData, int centerX, int centerY, int centerZ) {
        // Estimate surface normals using gradient analysis
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = centerX + dx;
                    int y = centerY + dy;
                    int z = centerZ + dz;
                    
                    if (isValidCoordinate(x, y, z)) {
                        var normal = estimateSurfaceNormal(x, y, z);
                        if (normal.length() > 0.1f) { // Valid normal
                            qualityData.addNormalSample(normal);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Analyze contour complexity by detecting surface boundaries.
     */
    private void analyzeContourComplexity(VoxelData qualityData, int centerX, int centerY, int centerZ) {
        // Count surface-adjacent voxels to estimate contour complexity
        int surfaceVoxels = 0;
        int totalVoxels = 0;
        
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = centerX + dx;
                    int y = centerY + dy;
                    int z = centerZ + dz;
                    
                    if (isValidCoordinate(x, y, z)) {
                        int idx = getVoxelIndex(x, y, z);
                        totalVoxels++;
                        
                        if (denseGrid[idx] != 0) {
                            // Check if this voxel is on the surface
                            if (isSurfaceVoxel(x, y, z)) {
                                surfaceVoxels++;
                            }
                        }
                    }
                }
            }
        }
        
        // Use surface density as contour error estimate
        if (totalVoxels > 0) {
            float contourError = (float) surfaceVoxels / totalVoxels;
            qualityData.setContourError(contourError);
        }
    }
    
    /**
     * Estimate surface normal using gradient analysis.
     */
    private Vector3f estimateSurfaceNormal(int x, int y, int z) {
        // Use central differences to estimate gradient
        float dx = getDensityDifference(x + 1, y, z, x - 1, y, z);
        float dy = getDensityDifference(x, y + 1, z, x, y - 1, z);
        float dz = getDensityDifference(x, y, z + 1, x, y, z - 1);
        
        var normal = new Vector3f(dx, dy, dz);
        if (normal.length() > 0) {
            normal.normalize();
        }
        
        return normal;
    }
    
    /**
     * Calculate density difference between two voxel positions.
     */
    private float getDensityDifference(int x1, int y1, int z1, int x2, int y2, int z2) {
        float density1 = isValidCoordinate(x1, y1, z1) ? 
                        (denseGrid[getVoxelIndex(x1, y1, z1)] != 0 ? 1.0f : 0.0f) : 0.0f;
        float density2 = isValidCoordinate(x2, y2, z2) ? 
                        (denseGrid[getVoxelIndex(x2, y2, z2)] != 0 ? 1.0f : 0.0f) : 0.0f;
        
        return density1 - density2;
    }
    
    /**
     * Check if a voxel is on the surface (has at least one empty neighbor).
     */
    private boolean isSurfaceVoxel(int x, int y, int z) {
        if (!isValidCoordinate(x, y, z) || denseGrid[getVoxelIndex(x, y, z)] == 0) {
            return false;
        }
        
        // Check 6-connected neighbors
        int[][] neighbors = {{-1,0,0}, {1,0,0}, {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}};
        
        for (int[] neighbor : neighbors) {
            int nx = x + neighbor[0];
            int ny = y + neighbor[1];
            int nz = z + neighbor[2];
            
            if (!isValidCoordinate(nx, ny, nz) || 
                denseGrid[getVoxelIndex(nx, ny, nz)] == 0) {
                return true; // Has empty neighbor
            }
        }
        
        return false; // Completely surrounded
    }
    
    /**
     * Pack color components into integer format.
     */
    private int packColor(float[] color) {
        return ((int)(color[0] * 255) << 24) |
               ((int)(color[1] * 255) << 16) |
               ((int)(color[2] * 255) << 8) |
               ((int)(color[3] * 255));
    }
    
    /**
     * Check if coordinates are valid within grid bounds.
     */
    private boolean isValidCoordinate(int x, int y, int z) {
        return x >= 0 && x < gridSize && 
               y >= 0 && y < gridSize && 
               z >= 0 && z < gridSize;
    }
    
    /**
     * Get linear index from 3D coordinates.
     */
    private int getVoxelIndex(int x, int y, int z) {
        return x + y * gridSize + z * gridSize * gridSize;
    }
    
    /**
     * Reset task state for reuse.
     */
    public void reset() {
        this.initialized = false;
        // Don't null references to avoid GC pressure - just mark as uninitialized
    }
}