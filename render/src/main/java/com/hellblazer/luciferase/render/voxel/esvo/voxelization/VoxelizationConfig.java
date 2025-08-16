package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

/**
 * Configuration for voxelization process.
 * Controls resolution, bounds, and various options.
 */
public class VoxelizationConfig {
    private int resolution = 32;
    private float[] bounds = {-1, -1, -1, 1, 1, 1};
    private boolean use6Connectivity = false;
    private boolean conservative = false;
    private boolean computeNormals = false;
    private boolean generateOctree = false;
    private int maxOctreeDepth = 8;
    
    public VoxelizationConfig withResolution(int resolution) {
        this.resolution = resolution;
        return this;
    }
    
    public VoxelizationConfig withBounds(float minX, float minY, float minZ,
                                         float maxX, float maxY, float maxZ) {
        this.bounds = new float[]{minX, minY, minZ, maxX, maxY, maxZ};
        return this;
    }
    
    public VoxelizationConfig with6Connectivity(boolean use6Connectivity) {
        this.use6Connectivity = use6Connectivity;
        return this;
    }
    
    public VoxelizationConfig withConservative(boolean conservative) {
        this.conservative = conservative;
        return this;
    }
    
    public VoxelizationConfig withComputeNormals(boolean computeNormals) {
        this.computeNormals = computeNormals;
        return this;
    }
    
    public VoxelizationConfig withGenerateOctree(boolean generateOctree) {
        this.generateOctree = generateOctree;
        return this;
    }
    
    public VoxelizationConfig withMaxOctreeDepth(int maxOctreeDepth) {
        this.maxOctreeDepth = maxOctreeDepth;
        return this;
    }
    
    public int getResolution() {
        return resolution;
    }
    
    public float[] getBounds() {
        return bounds;
    }
    
    public float getVoxelSize() {
        float width = bounds[3] - bounds[0];
        return width / resolution;
    }
    
    public boolean isUse6Connectivity() {
        return use6Connectivity;
    }
    
    public boolean isConservative() {
        return conservative;
    }
    
    public boolean isComputeNormals() {
        return computeNormals;
    }
    
    public boolean isGenerateOctree() {
        return generateOctree;
    }
    
    public int getMaxOctreeDepth() {
        return maxOctreeDepth;
    }
}