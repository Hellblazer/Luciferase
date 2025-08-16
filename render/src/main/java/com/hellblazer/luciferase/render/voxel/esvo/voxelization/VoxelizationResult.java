package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of voxelization process.
 * Contains voxels and optional octree structure.
 */
public class VoxelizationResult {
    private final List<Voxel> voxels;
    private final Map<Integer, Voxel> voxelMap;
    private Octree octree;
    private int triangleCount;
    
    public VoxelizationResult() {
        this.voxels = new ArrayList<>();
        this.voxelMap = new HashMap<>();
    }
    
    public void addVoxel(Voxel voxel) {
        voxels.add(voxel);
        int key = hashVoxelPosition(voxel.x, voxel.y, voxel.z);
        voxelMap.put(key, voxel);
    }
    
    public List<Voxel> getVoxels() {
        return voxels;
    }
    
    public int getVoxelCount() {
        return voxels.size();
    }
    
    public boolean containsVoxel(int x, int y, int z) {
        int key = hashVoxelPosition(x, y, z);
        return voxelMap.containsKey(key);
    }
    
    public boolean hasVoxelNear(float x, float y, float z, float radius) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        
        // Check neighboring voxels
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (containsVoxel(ix + dx, iy + dy, iz + dz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    public Octree getOctree() {
        return octree;
    }
    
    public void setOctree(Octree octree) {
        this.octree = octree;
    }
    
    public int getTriangleCount() {
        return triangleCount;
    }
    
    public void setTriangleCount(int triangleCount) {
        this.triangleCount = triangleCount;
    }
    
    private int hashVoxelPosition(int x, int y, int z) {
        return x * 73856093 ^ y * 19349663 ^ z * 83492791;
    }
}