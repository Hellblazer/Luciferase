package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

/**
 * Contour data for voxels representing surface information.
 */
public class ContourData {
    private float[] normal;
    private float distance;
    
    public ContourData(float nx, float ny, float nz, float distance) {
        this.normal = new float[]{nx, ny, nz};
        this.distance = distance;
    }
    
    public float[] getNormal() {
        return normal;
    }
    
    public void setNormal(float[] normal) {
        this.normal = normal;
    }
    
    public float getDistance() {
        return distance;
    }
    
    public void setDistance(float distance) {
        this.distance = distance;
    }
}