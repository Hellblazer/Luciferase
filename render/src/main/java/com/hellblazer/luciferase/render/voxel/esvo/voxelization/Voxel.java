package com.hellblazer.luciferase.render.voxel.esvo.voxelization;

/**
 * Represents a single voxel in 3D space.
 * Contains position, attributes, and metadata.
 */
public class Voxel {
    public final int x;
    public final int y;
    public final int z;
    
    private float[] position;
    private VoxelAttribute attribute;
    private float[] normal;
    private ContourData contour;
    
    // Constructor for integer coordinates
    public Voxel(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.position = new float[]{x, y, z};
    }
    
    // Constructor for float coordinates (will be quantized)
    public Voxel(float x, float y, float z) {
        this.x = (int) Math.floor(x);
        this.y = (int) Math.floor(y);
        this.z = (int) Math.floor(z);
        this.position = new float[]{x, y, z};
    }
    
    public float[] getPosition() {
        return position;
    }
    
    public VoxelAttribute getAttribute() {
        return attribute;
    }
    
    public void setAttribute(VoxelAttribute attribute) {
        this.attribute = attribute;
    }
    
    public float[] getNormal() {
        return normal;
    }
    
    public void setNormal(float[] normal) {
        this.normal = normal;
    }
    
    public ContourData getContour() {
        return contour;
    }
    
    public void setContour(ContourData contour) {
        this.contour = contour;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Voxel)) return false;
        Voxel other = (Voxel) obj;
        return x == other.x && y == other.y && z == other.z;
    }
    
    @Override
    public int hashCode() {
        return x * 73856093 ^ y * 19349663 ^ z * 83492791;
    }
}