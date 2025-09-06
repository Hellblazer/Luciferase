package com.hellblazer.luciferase.esvo.builder;

import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch of voxels for thread-local processing.
 */
public class VoxelBatch {
    private final int id;
    private final List<VoxelData> voxels;
    
    public VoxelBatch(int id) {
        this.id = id;
        this.voxels = new ArrayList<>();
    }
    
    public int getId() {
        return id;
    }
    
    public void addVoxel(Vector3f position, int level, float density) {
        voxels.add(new VoxelData(position, level, density));
    }
    
    public List<VoxelData> getVoxels() {
        return voxels;
    }
    
    public int size() {
        return voxels.size();
    }
    
    /**
     * Data for a single voxel.
     */
    public static class VoxelData {
        public final Vector3f position;
        public final int level;
        public final float density;
        
        public VoxelData(Vector3f position, int level, float density) {
            this.position = position;
            this.level = level;
            this.density = density;
        }
    }
}