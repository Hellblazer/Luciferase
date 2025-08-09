package com.hellblazer.luciferase.render.voxel.core;

/**
 * 3D voxel grid for storing volumetric data.
 */
public class VoxelGrid {
    private final int resolution;
    private final boolean[][][] voxels;
    private int voxelCount = 0;
    
    public VoxelGrid(int resolution) {
        this.resolution = resolution;
        this.voxels = new boolean[resolution][resolution][resolution];
    }
    
    public void setVoxel(int x, int y, int z, boolean value) {
        if (x >= 0 && x < resolution && y >= 0 && y < resolution && z >= 0 && z < resolution) {
            if (!voxels[x][y][z] && value) {
                voxelCount++;
            } else if (voxels[x][y][z] && !value) {
                voxelCount--;
            }
            voxels[x][y][z] = value;
        }
    }
    
    public boolean getVoxel(int x, int y, int z) {
        if (x >= 0 && x < resolution && y >= 0 && y < resolution && z >= 0 && z < resolution) {
            return voxels[x][y][z];
        }
        return false;
    }
    
    public int getResolution() {
        return resolution;
    }
    
    public int getVoxelCount() {
        return voxelCount;
    }
}