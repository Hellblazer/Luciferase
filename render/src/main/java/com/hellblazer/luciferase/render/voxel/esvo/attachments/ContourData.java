package com.hellblazer.luciferase.render.voxel.esvo.attachments;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Contour data for ESVO voxels.
 * Stores surface normal and intersection point for accurate surface reconstruction.
 * 
 * Format (16 bytes per contour):
 * - Normal: 3 floats (12 bytes) - surface normal at voxel boundary
 * - Distance: 1 float (4 bytes) - distance along ray to surface
 */
public class ContourData {
    
    public static final int CONTOUR_SIZE = 16; // 4 floats
    public static final int MAX_CONTOURS_PER_NODE = 8; // One per child
    
    private final float normalX;
    private final float normalY;
    private final float normalZ;
    private final float distance;
    
    public ContourData(float nx, float ny, float nz, float dist) {
        // Normalize the normal vector
        float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0.0001f) {
            this.normalX = nx / len;
            this.normalY = ny / len;
            this.normalZ = nz / len;
        } else {
            this.normalX = 0;
            this.normalY = 0;
            this.normalZ = 1; // Default up
        }
        this.distance = dist;
    }
    
    /**
     * Create contour from surface intersection
     */
    public static ContourData fromIntersection(
            float rayOriginX, float rayOriginY, float rayOriginZ,
            float rayDirX, float rayDirY, float rayDirZ,
            float hitDistance,
            float surfaceNormalX, float surfaceNormalY, float surfaceNormalZ) {
        
        return new ContourData(surfaceNormalX, surfaceNormalY, surfaceNormalZ, hitDistance);
    }
    
    /**
     * Serialize contour to bytes
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(CONTOUR_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(normalX);
        buffer.putFloat(normalY);
        buffer.putFloat(normalZ);
        buffer.putFloat(distance);
        return buffer.array();
    }
    
    /**
     * Deserialize contour from bytes
     */
    public static ContourData fromBytes(byte[] data) {
        if (data.length != CONTOUR_SIZE) {
            throw new IllegalArgumentException("Invalid contour data size");
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        float nx = buffer.getFloat();
        float ny = buffer.getFloat();
        float nz = buffer.getFloat();
        float dist = buffer.getFloat();
        
        return new ContourData(nx, ny, nz, dist);
    }
    
    /**
     * Write contour to memory segment
     */
    public void writeTo(MemorySegment memory, long offset) {
        memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset, normalX);
        memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 4, normalY);
        memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 8, normalZ);
        memory.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 12, distance);
    }
    
    /**
     * Read contour from memory segment
     */
    public static ContourData readFrom(MemorySegment memory, long offset) {
        float nx = memory.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset);
        float ny = memory.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 4);
        float nz = memory.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 8);
        float dist = memory.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 12);
        
        return new ContourData(nx, ny, nz, dist);
    }
    
    /**
     * Interpolate between two contours
     */
    public static ContourData interpolate(ContourData c1, ContourData c2, float t) {
        float nx = c1.normalX * (1 - t) + c2.normalX * t;
        float ny = c1.normalY * (1 - t) + c2.normalY * t;
        float nz = c1.normalZ * (1 - t) + c2.normalZ * t;
        float dist = c1.distance * (1 - t) + c2.distance * t;
        
        return new ContourData(nx, ny, nz, dist);
    }
    
    /**
     * Average multiple contours (for level-of-detail)
     */
    public static ContourData average(ContourData[] contours) {
        if (contours.length == 0) {
            return new ContourData(0, 0, 1, 0);
        }
        
        float sumNx = 0, sumNy = 0, sumNz = 0, sumDist = 0;
        
        for (ContourData c : contours) {
            sumNx += c.normalX;
            sumNy += c.normalY;
            sumNz += c.normalZ;
            sumDist += c.distance;
        }
        
        float count = contours.length;
        return new ContourData(
            sumNx / count,
            sumNy / count,
            sumNz / count,
            sumDist / count
        );
    }
    
    /**
     * Check if contour is valid
     */
    public boolean isValid() {
        float lenSq = normalX * normalX + normalY * normalY + normalZ * normalZ;
        return Math.abs(lenSq - 1.0f) < 0.01f && distance >= 0;
    }
    
    /**
     * Get intersection point along ray
     */
    public float[] getIntersectionPoint(float rayOriginX, float rayOriginY, float rayOriginZ,
                                        float rayDirX, float rayDirY, float rayDirZ) {
        return new float[] {
            rayOriginX + rayDirX * distance,
            rayOriginY + rayDirY * distance,
            rayOriginZ + rayDirZ * distance
        };
    }
    
    // Getters
    public float getNormalX() { return normalX; }
    public float getNormalY() { return normalY; }
    public float getNormalZ() { return normalZ; }
    public float getDistance() { return distance; }
    
    @Override
    public String toString() {
        return String.format("Contour[normal=(%.3f,%.3f,%.3f), dist=%.3f]",
                           normalX, normalY, normalZ, distance);
    }
}