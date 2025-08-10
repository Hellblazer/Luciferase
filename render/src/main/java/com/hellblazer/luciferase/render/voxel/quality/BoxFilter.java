package com.hellblazer.luciferase.render.voxel.quality;

import javax.vecmath.Color3f;
import javax.vecmath.Vector3f;

/**
 * Box filter implementation for voxel attribute filtering.
 * Applies simple averaging (box filter) to voxel attributes using neighborhood information.
 * This is the fastest filter with basic quality improvement suitable for real-time applications.
 * 
 * Algorithm:
 * - Equal weight for all valid neighboring voxels
 * - Simple arithmetic mean for all attributes
 * - Efficient implementation with minimal computational overhead
 */
public class BoxFilter implements AttributeFilter {
    
    private static final FilterCharacteristics CHARACTERISTICS = 
        new FilterCharacteristics("Box Filter", 1.0f, 0.3f, 27, true, FilterType.BOX);
    
    @Override
    public Color3f filterColor(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return new Color3f(0, 0, 0);
        }
        
        float totalRed = 0.0f;
        float totalGreen = 0.0f;
        float totalBlue = 0.0f;
        int validCount = 0;
        
        // Sum all valid voxel colors
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                totalRed += voxel.color.x;
                totalGreen += voxel.color.y;
                totalBlue += voxel.color.z;
                validCount++;
            }
        }
        
        // Return average if we have valid voxels, otherwise return center voxel color
        if (validCount > 0) {
            return new Color3f(
                totalRed / validCount,
                totalGreen / validCount,
                totalBlue / validCount
            );
        } else {
            return new Color3f(neighborhood[centerIndex].color);
        }
    }
    
    @Override
    public Vector3f filterNormal(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return new Vector3f(0, 1, 0);
        }
        
        float totalX = 0.0f;
        float totalY = 0.0f;
        float totalZ = 0.0f;
        int validCount = 0;
        
        // Sum all valid voxel normals
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                totalX += voxel.normal.x;
                totalY += voxel.normal.y;
                totalZ += voxel.normal.z;
                validCount++;
            }
        }
        
        // Return average normal if we have valid voxels, otherwise return center voxel normal
        if (validCount > 0) {
            var avgNormal = new Vector3f(
                totalX / validCount,
                totalY / validCount,
                totalZ / validCount
            );
            
            // Normalize the averaged normal
            float length = avgNormal.length();
            if (length > 0.001f) {
                avgNormal.scale(1.0f / length);
            } else {
                // If averaged normal is zero vector, use center normal
                avgNormal.set(neighborhood[centerIndex].normal);
            }
            
            return avgNormal;
        } else {
            return new Vector3f(neighborhood[centerIndex].normal);
        }
    }
    
    @Override
    public float filterOpacity(VoxelData[] neighborhood, int centerIndex) {
        if (neighborhood == null || centerIndex < 0 || centerIndex >= neighborhood.length) {
            return 0.0f;
        }
        
        float totalOpacity = 0.0f;
        int validCount = 0;
        
        // Sum all valid voxel opacities
        for (var voxel : neighborhood) {
            if (voxel != null && voxel.isValid) {
                totalOpacity += voxel.opacity;
                validCount++;
            }
        }
        
        // Return average if we have valid voxels, otherwise return center voxel opacity
        if (validCount > 0) {
            return totalOpacity / validCount;
        } else {
            return neighborhood[centerIndex].opacity;
        }
    }
    
    @Override
    public FilterCharacteristics getCharacteristics() {
        return CHARACTERISTICS;
    }
    
    @Override
    public String getName() {
        return "Box Filter";
    }
    
    /**
     * Batch processing optimization for multiple voxels.
     * Processes multiple center voxels with their neighborhoods in a single call.
     * 
     * @param neighborhoods Array of neighborhood arrays
     * @param centerIndices Array of center indices for each neighborhood
     * @param results Array to store filtered results (must be pre-allocated)
     */
    public void filterColorBatch(VoxelData[][] neighborhoods, int[] centerIndices, Color3f[] results) {
        if (neighborhoods == null || centerIndices == null || results == null ||
            neighborhoods.length != centerIndices.length || neighborhoods.length != results.length) {
            return;
        }
        
        for (int i = 0; i < neighborhoods.length; i++) {
            results[i] = filterColor(neighborhoods[i], centerIndices[i]);
        }
    }
    
    /**
     * Batch processing for normal filtering.
     */
    public void filterNormalBatch(VoxelData[][] neighborhoods, int[] centerIndices, Vector3f[] results) {
        if (neighborhoods == null || centerIndices == null || results == null ||
            neighborhoods.length != centerIndices.length || neighborhoods.length != results.length) {
            return;
        }
        
        for (int i = 0; i < neighborhoods.length; i++) {
            results[i] = filterNormal(neighborhoods[i], centerIndices[i]);
        }
    }
    
    /**
     * Batch processing for opacity filtering.
     */
    public void filterOpacityBatch(VoxelData[][] neighborhoods, int[] centerIndices, float[] results) {
        if (neighborhoods == null || centerIndices == null || results == null ||
            neighborhoods.length != centerIndices.length || neighborhoods.length != results.length) {
            return;
        }
        
        for (int i = 0; i < neighborhoods.length; i++) {
            results[i] = filterOpacity(neighborhoods[i], centerIndices[i]);
        }
    }
}