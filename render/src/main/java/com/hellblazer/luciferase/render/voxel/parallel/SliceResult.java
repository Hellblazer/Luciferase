package com.hellblazer.luciferase.render.voxel.parallel;

import com.hellblazer.luciferase.render.voxel.quality.QualityController.VoxelData;
import java.util.List;

/**
 * Result container for slice-based parallel octree processing.
 * 
 * Contains the processed voxels from a slice along with timing
 * and performance information for benchmarking and optimization.
 */
public class SliceResult {
    /** Processed voxels with quality analysis */
    public final List<ProcessedVoxel> processedVoxels;
    
    /** Starting Z coordinate of the processed slice */
    public final int startZ;
    
    /** Ending Z coordinate of the processed slice */
    public final int endZ;
    
    /** Processing time in nanoseconds */
    public final long processingTimeNs;
    
    /**
     * Create a new slice result.
     * 
     * @param processedVoxels List of voxels processed in this slice
     * @param startZ Starting Z coordinate
     * @param endZ Ending Z coordinate  
     * @param processingTimeNs Processing time in nanoseconds
     */
    public SliceResult(List<ProcessedVoxel> processedVoxels, 
                      int startZ, 
                      int endZ, 
                      long processingTimeNs) {
        this.processedVoxels = processedVoxels;
        this.startZ = startZ;
        this.endZ = endZ;
        this.processingTimeNs = processingTimeNs;
    }
    
    /**
     * Get the number of slices processed.
     */
    public int getSliceCount() {
        return endZ - startZ;
    }
    
    /**
     * Get the number of voxels processed.
     */
    public int getVoxelCount() {
        return processedVoxels.size();
    }
    
    /**
     * Get processing time in milliseconds.
     */
    public double getProcessingTimeMs() {
        return processingTimeNs / 1_000_000.0;
    }
    
    /**
     * Calculate voxels processed per millisecond.
     */
    public double getVoxelsPerMs() {
        double timeMs = getProcessingTimeMs();
        return timeMs > 0 ? processedVoxels.size() / timeMs : 0.0;
    }
    
    @Override
    public String toString() {
        return String.format("SliceResult{slices=Z[%d-%d], voxels=%d, time=%.2fms, rate=%.1f voxels/ms}",
                           startZ, endZ, getVoxelCount(), getProcessingTimeMs(), getVoxelsPerMs());
    }
}