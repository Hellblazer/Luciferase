package com.hellblazer.luciferase.render.voxel.pipeline;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sparse voxel grid representation with multi-resolution support.
 * Uses concurrent data structures for thread-safe parallel voxelization.
 */
public class VoxelGrid {
    
    private final int resolution;
    private final Point3f min;
    private final Point3f max;
    private final Vector3f voxelSize;
    private final Vector3f voxelHalfSize;
    private final ConcurrentHashMap<Long, Voxel> voxels;
    private final AtomicInteger voxelCount;
    
    public VoxelGrid(int resolution, Point3f min, Point3f max) {
        this.resolution = resolution;
        this.min = new Point3f(min);
        this.max = new Point3f(max);
        
        float sizeX = (max.x - min.x) / resolution;
        float sizeY = (max.y - min.y) / resolution;
        float sizeZ = (max.z - min.z) / resolution;
        
        this.voxelSize = new Vector3f(sizeX, sizeY, sizeZ);
        this.voxelHalfSize = new Vector3f(sizeX * 0.5f, sizeY * 0.5f, sizeZ * 0.5f);
        this.voxels = new ConcurrentHashMap<>();
        this.voxelCount = new AtomicInteger(0);
    }
    
    /**
     * Adds or updates a voxel at the specified grid coordinates.
     */
    public void addVoxel(int x, int y, int z, int material, float coverage) {
        long key = encodeKey(x, y, z);
        
        voxels.compute(key, (k, existing) -> {
            if (existing == null) {
                voxelCount.incrementAndGet();
                return new Voxel(x, y, z, material, coverage);
            } else {
                // Blend with existing voxel
                existing.blend(material, coverage);
                return existing;
            }
        });
    }
    
    /**
     * Gets a voxel at the specified coordinates.
     */
    public Voxel getVoxel(int x, int y, int z) {
        return voxels.get(encodeKey(x, y, z));
    }
    
    /**
     * Checks if a voxel exists at the specified coordinates.
     */
    public boolean hasVoxel(int x, int y, int z) {
        return voxels.containsKey(encodeKey(x, y, z));
    }
    
    /**
     * Converts world coordinates to voxel grid coordinates.
     */
    public Point3f worldToVoxel(Point3f world) {
        float x = (world.x - min.x) / voxelSize.x;
        float y = (world.y - min.y) / voxelSize.y;
        float z = (world.z - min.z) / voxelSize.z;
        return new Point3f(x, y, z);
    }
    
    /**
     * Converts voxel grid coordinates to world center position.
     */
    public Point3f voxelToWorld(int x, int y, int z) {
        float wx = min.x + (x + 0.5f) * voxelSize.x;
        float wy = min.y + (y + 0.5f) * voxelSize.y;
        float wz = min.z + (z + 0.5f) * voxelSize.z;
        return new Point3f(wx, wy, wz);
    }
    
    /**
     * Gets the half-size of a voxel for intersection tests.
     */
    public Vector3f getVoxelHalfSize() {
        return new Vector3f(voxelHalfSize);
    }
    
    /**
     * Computes the density of voxels in a region.
     */
    public float getRegionDensity(int startX, int startY, int startZ, int size) {
        int count = 0;
        int total = size * size * size;
        
        for (int x = startX; x < startX + size && x < resolution; x++) {
            for (int y = startY; y < startY + size && y < resolution; y++) {
                for (int z = startZ; z < startZ + size && z < resolution; z++) {
                    if (hasVoxel(x, y, z)) {
                        count++;
                    }
                }
            }
        }
        
        return (float) count / total;
    }
    
    /**
     * Copies voxels from another grid.
     */
    public void copyFrom(VoxelGrid other) {
        other.voxels.forEach((key, voxel) -> {
            voxels.put(key, new Voxel(voxel));
            voxelCount.incrementAndGet();
        });
    }
    
    /**
     * Merges a sub-grid into a specific region.
     */
    public void mergeSubGrid(VoxelGrid subGrid, Region region) {
        float scale = (float) region.size / subGrid.resolution;
        
        subGrid.voxels.forEach((key, voxel) -> {
            // Map sub-grid coordinates to main grid
            int x = region.x + (int)(voxel.x * scale);
            int y = region.y + (int)(voxel.y * scale);
            int z = region.z + (int)(voxel.z * scale);
            
            if (x < resolution && y < resolution && z < resolution) {
                addVoxel(x, y, z, voxel.material, voxel.coverage);
            }
        });
    }
    
    /**
     * Clears all voxels from the grid.
     */
    public void clear() {
        voxels.clear();
        voxelCount.set(0);
    }
    
    public int getResolution() {
        return resolution;
    }
    
    public Point3f getMin() {
        return new Point3f(min);
    }
    
    public Point3f getMax() {
        return new Point3f(max);
    }
    
    public int getVoxelCount() {
        return voxelCount.get();
    }
    
    /**
     * Gets the fill rate of the grid (occupied voxels / total possible voxels).
     */
    public float getFillRate() {
        int totalPossible = resolution * resolution * resolution;
        return (float) voxelCount.get() / totalPossible;
    }
    
    /**
     * Iterates over all voxels in the grid.
     */
    public void forEachVoxel(VoxelConsumer consumer) {
        voxels.forEach((key, voxel) -> {
            consumer.accept(voxel.x, voxel.y, voxel.z, voxel);
        });
    }
    
    private long encodeKey(int x, int y, int z) {
        // Pack coordinates into a long (20 bits each, supports up to 1M resolution)
        return ((long)x & 0xFFFFF) | 
               (((long)y & 0xFFFFF) << 20) | 
               (((long)z & 0xFFFFF) << 40);
    }
    
    /**
     * Individual voxel data.
     */
    public static class Voxel {
        public final int x, y, z;
        private int material;
        private float coverage;
        private final AtomicReference<float[]> attributes;
        
        public Voxel(int x, int y, int z, int material, float coverage) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
            this.coverage = coverage;
            this.attributes = new AtomicReference<>();
        }
        
        public Voxel(Voxel other) {
            this.x = other.x;
            this.y = other.y;
            this.z = other.z;
            this.material = other.material;
            this.coverage = other.coverage;
            this.attributes = new AtomicReference<>(
                other.attributes.get() != null ? 
                other.attributes.get().clone() : null
            );
        }
        
        public void blend(int newMaterial, float newCoverage) {
            // Simple coverage-weighted blending
            if (newCoverage > coverage) {
                material = newMaterial;
                coverage = newCoverage;
            }
        }
        
        public int getMaterial() {
            return material;
        }
        
        public float getCoverage() {
            return coverage;
        }
        
        public void setAttributes(float[] attrs) {
            attributes.set(attrs);
        }
        
        public float[] getAttributes() {
            return attributes.get();
        }
    }
    
    /**
     * Functional interface for voxel iteration.
     */
    @FunctionalInterface
    public interface VoxelConsumer {
        void accept(int x, int y, int z, Voxel voxel);
    }
    
    public static class Region {
        final int x, y, z, size;
        
        public Region(int x, int y, int z, int size) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
        }
    }
}