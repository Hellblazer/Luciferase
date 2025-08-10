package com.hellblazer.luciferase.render.voxel.parallel;

import com.hellblazer.luciferase.render.voxel.quality.QualityController.VoxelData;

/**
 * Container for a voxel that has been processed with quality analysis.
 * 
 * Used in the slice-based parallel octree building pipeline to carry
 * voxel data along with its associated quality metrics for 
 * intelligent subdivision decisions.
 */
public class ProcessedVoxel {
    /** World space position (normalized 0-1) */
    public final float[] position;
    
    /** Packed RGBA color as integer */
    public final int color;
    
    /** Quality analysis data for subdivision decisions */
    public final VoxelData qualityData;
    
    /**
     * Create a processed voxel with quality data.
     * 
     * @param position World space position (normalized 0-1)
     * @param color Packed RGBA color as integer
     * @param qualityData Quality analysis results
     */
    public ProcessedVoxel(float[] position, int color, VoxelData qualityData) {
        this.position = position.clone();
        this.color = color;
        this.qualityData = qualityData;
    }
    
    /**
     * Unpack color components from integer.
     */
    public float[] getColorComponents() {
        return new float[] {
            ((color >> 24) & 0xFF) / 255.0f,  // R
            ((color >> 16) & 0xFF) / 255.0f,  // G  
            ((color >> 8) & 0xFF) / 255.0f,   // B
            (color & 0xFF) / 255.0f           // A
        };
    }
    
    /**
     * Get red component.
     */
    public float getRed() {
        return ((color >> 24) & 0xFF) / 255.0f;
    }
    
    /**
     * Get green component.
     */
    public float getGreen() {
        return ((color >> 16) & 0xFF) / 255.0f;
    }
    
    /**
     * Get blue component.
     */
    public float getBlue() {
        return ((color >> 8) & 0xFF) / 255.0f;
    }
    
    /**
     * Get alpha component.
     */
    public float getAlpha() {
        return (color & 0xFF) / 255.0f;
    }
    
    @Override
    public String toString() {
        return String.format("ProcessedVoxel{pos=[%.3f,%.3f,%.3f], color=0x%08X, samples=%d}",
                           position[0], position[1], position[2], color, 
                           qualityData.getSampleCount());
    }
}