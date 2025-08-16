package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import com.hellblazer.luciferase.render.voxel.esvo.ESVOPage;

/**
 * Configuration for octree construction.
 */
public class OctreeConfig {
    private int maxDepth = 8;
    private int minVoxelsPerNode = 1;
    private int pageSize = ESVOPage.PAGE_BYTES;
    private BuildStrategy buildStrategy = BuildStrategy.TOP_DOWN;
    private boolean compressHomogeneous = false;
    private boolean storeContours = false;
    private boolean optimizeLayout = false;
    private int lodLevels = 1;
    private boolean progressiveEncoding = false;
    
    public OctreeConfig withMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }
    
    public OctreeConfig withMinVoxelsPerNode(int minVoxelsPerNode) {
        this.minVoxelsPerNode = minVoxelsPerNode;
        return this;
    }
    
    public OctreeConfig withPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }
    
    public OctreeConfig withBuildStrategy(BuildStrategy strategy) {
        this.buildStrategy = strategy;
        return this;
    }
    
    public OctreeConfig withCompressHomogeneous(boolean compress) {
        this.compressHomogeneous = compress;
        return this;
    }
    
    public OctreeConfig withStoreContours(boolean storeContours) {
        this.storeContours = storeContours;
        return this;
    }
    
    public OctreeConfig withOptimizeLayout(boolean optimize) {
        this.optimizeLayout = optimize;
        return this;
    }
    
    public OctreeConfig withLODLevels(int levels) {
        this.lodLevels = levels;
        return this;
    }
    
    public OctreeConfig withProgressiveEncoding(boolean progressive) {
        this.progressiveEncoding = progressive;
        return this;
    }
    
    // Getters
    
    public int getMaxDepth() {
        return maxDepth;
    }
    
    public int getMinVoxelsPerNode() {
        return minVoxelsPerNode;
    }
    
    public int getPageSize() {
        return pageSize;
    }
    
    public BuildStrategy getBuildStrategy() {
        return buildStrategy;
    }
    
    public boolean isCompressHomogeneous() {
        return compressHomogeneous;
    }
    
    public boolean isStoreContours() {
        return storeContours;
    }
    
    public boolean isOptimizeLayout() {
        return optimizeLayout;
    }
    
    public int getLODLevels() {
        return lodLevels;
    }
    
    public boolean isProgressiveEncoding() {
        return progressiveEncoding;
    }
}