package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.NavigableMap;
import java.util.stream.Stream;

/**
 * Optimized version of Tetree with enhanced performance for Phase 2.
 * Uses TetSpatialOptimizer for improved SFC range computation and caching.
 * 
 * Key optimizations:
 * - Cached SFC computations
 * - Optimized range merging
 * - Reduced object allocation
 * - Hierarchical spatial processing
 * 
 * @author hal.hildebrand
 */
public class TetreeOptimized<Content> extends Tetree<Content> {
    
    public TetreeOptimized(NavigableMap<Long, Content> contents) {
        super(contents);
    }
    
    /**
     * Optimized boundedBy query using enhanced SFC range computation
     */
    @Override
    public Stream<Simplex<Content>> boundedBy(Spatial volume) {
        var bounds = convertToOptimizedBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }
        
        // Use optimized spatial range query
        return TetSpatialOptimizer.optimizedSpatialRangeQuery(getContents(), bounds, false)
            .filter(entry -> {
                // Use cached tetrahedron reconstruction
                var tet = TetSpatialOptimizer.getCachedTetrahedron(entry.getKey());
                return tetrahedronContainedInVolumeOptimized(tet, volume);
            })
            .map(entry -> new Simplex<>(entry.getKey(), (Content) entry.getValue()));
    }
    
    /**
     * Optimized bounding query using enhanced SFC range computation
     */
    @Override
    public Stream<Simplex<Content>> bounding(Spatial volume) {
        var bounds = convertToOptimizedBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }
        
        // Use optimized spatial range query
        return TetSpatialOptimizer.optimizedSpatialRangeQuery(getContents(), bounds, true)
            .filter(entry -> {
                // Use cached tetrahedron reconstruction  
                var tet = TetSpatialOptimizer.getCachedTetrahedron(entry.getKey());
                return tetrahedronIntersectsVolumeOptimized(tet, volume);
            })
            .map(entry -> new Simplex<>(entry.getKey(), (Content) entry.getValue()));
    }
    
    /**
     * Convert Spatial volume to optimized bounds format
     */
    private TetSpatialOptimizer.VolumeBounds convertToOptimizedBounds(Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> new TetSpatialOptimizer.VolumeBounds(
                cube.originX(), cube.originY(), cube.originZ(),
                cube.originX() + cube.extent(), cube.originY() + cube.extent(), cube.originZ() + cube.extent()
            );
            case Spatial.Sphere sphere -> new TetSpatialOptimizer.VolumeBounds(
                sphere.centerX() - sphere.radius(), sphere.centerY() - sphere.radius(), sphere.centerZ() - sphere.radius(),
                sphere.centerX() + sphere.radius(), sphere.centerY() + sphere.radius(), sphere.centerZ() + sphere.radius()
            );
            case Spatial.aabb aabb -> new TetSpatialOptimizer.VolumeBounds(
                aabb.originX(), aabb.originY(), aabb.originZ(), aabb.extentX(), aabb.extentY(), aabb.extentZ()
            );
            case Spatial.aabt aabt -> new TetSpatialOptimizer.VolumeBounds(
                aabt.originX(), aabt.originY(), aabt.originZ(), aabt.extentX(), aabt.extentY(), aabt.extentZ()
            );
            case Spatial.Parallelepiped para -> new TetSpatialOptimizer.VolumeBounds(
                para.originX(), para.originY(), para.originZ(), para.extentX(), para.extentY(), para.extentZ()
            );
            case Spatial.Tetrahedron tet -> {
                var vertices = new Tuple3f[] { tet.a(), tet.b(), tet.c(), tet.d() };
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
                for (var vertex : vertices) {
                    minX = Math.min(minX, vertex.x);
                    minY = Math.min(minY, vertex.y);
                    minZ = Math.min(minZ, vertex.z);
                    maxX = Math.max(maxX, vertex.x);
                    maxY = Math.max(maxY, vertex.y);
                    maxZ = Math.max(maxZ, vertex.z);
                }
                yield new TetSpatialOptimizer.VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
            }
            default -> null;
        };
    }
    
    /**
     * Optimized tetrahedron containment test using AABB pre-filtering
     */
    private boolean tetrahedronContainedInVolumeOptimized(Tet tet, Spatial volume) {
        // Quick AABB rejection test first
        var tetBounds = getTetAABB(tet);
        if (!aabbContainedInVolume(tetBounds, volume)) {
            return false;
        }
        
        // Full precise test only if AABB test passes  
        return callTetrahedronContainedInVolume(tet, volume);
    }
    
    /**
     * Optimized tetrahedron intersection test using AABB pre-filtering
     */
    private boolean tetrahedronIntersectsVolumeOptimized(Tet tet, Spatial volume) {
        // Quick AABB rejection test first
        var tetBounds = getTetAABB(tet);
        if (!aabbIntersectsVolume(tetBounds, volume)) {
            return false;
        }
        
        // Full precise test only if AABB test passes
        return callTetrahedronIntersectsVolume(tet, volume);
    }
    
    /**
     * Fast AABB computation for tetrahedron
     */
    private AABB getTetAABB(Tet tet) {
        var vertices = tet.coordinates();
        
        float minX = vertices[0].x, maxX = vertices[0].x;
        float minY = vertices[0].y, maxY = vertices[0].y;
        float minZ = vertices[0].z, maxZ = vertices[0].z;
        
        for (int i = 1; i < 4; i++) {
            var v = vertices[i];
            if (v.x < minX) minX = v.x;
            if (v.x > maxX) maxX = v.x;
            if (v.y < minY) minY = v.y;
            if (v.y > maxY) maxY = v.y;
            if (v.z < minZ) minZ = v.z;
            if (v.z > maxZ) maxZ = v.z;
        }
        
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    /**
     * Fast AABB containment test
     */
    private boolean aabbContainedInVolume(AABB aabb, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> 
                aabb.minX >= cube.originX() && aabb.maxX <= cube.originX() + cube.extent() &&
                aabb.minY >= cube.originY() && aabb.maxY <= cube.originY() + cube.extent() &&
                aabb.minZ >= cube.originZ() && aabb.maxZ <= cube.originZ() + cube.extent();
            
            case Spatial.Sphere sphere -> {
                // Conservative test: all AABB corners must be within sphere
                float dx1 = aabb.minX - sphere.centerX();
                float dy1 = aabb.minY - sphere.centerY();
                float dz1 = aabb.minZ - sphere.centerZ();
                float dx2 = aabb.maxX - sphere.centerX();
                float dy2 = aabb.maxY - sphere.centerY();
                float dz2 = aabb.maxZ - sphere.centerZ();
                
                float radiusSquared = sphere.radius() * sphere.radius();
                yield (dx1*dx1 + dy1*dy1 + dz1*dz1 <= radiusSquared &&
                       dx2*dx2 + dy2*dy2 + dz2*dz2 <= radiusSquared &&
                       dx1*dx1 + dy1*dy1 + dz2*dz2 <= radiusSquared &&
                       dx1*dx1 + dy2*dy2 + dz1*dz1 <= radiusSquared &&
                       dx2*dx2 + dy1*dy1 + dz2*dz2 <= radiusSquared &&
                       dx2*dx2 + dy2*dy2 + dz1*dz1 <= radiusSquared &&
                       dx1*dx1 + dy2*dy2 + dz2*dz2 <= radiusSquared &&
                       dx2*dx2 + dy1*dy1 + dz1*dz1 <= radiusSquared);
            }
            
            case Spatial.aabb volumeAABB ->
                aabb.minX >= volumeAABB.originX() && aabb.maxX <= volumeAABB.extentX() &&
                aabb.minY >= volumeAABB.originY() && aabb.maxY <= volumeAABB.extentY() &&
                aabb.minZ >= volumeAABB.originZ() && aabb.maxZ <= volumeAABB.extentZ();
                
            default -> false; // Use full test for complex volumes
        };
    }
    
    /**
     * Fast AABB intersection test
     */
    private boolean aabbIntersectsVolume(AABB aabb, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> 
                !(aabb.maxX < cube.originX() || aabb.minX > cube.originX() + cube.extent() ||
                  aabb.maxY < cube.originY() || aabb.minY > cube.originY() + cube.extent() ||
                  aabb.maxZ < cube.originZ() || aabb.minZ > cube.originZ() + cube.extent());
            
            case Spatial.Sphere sphere -> {
                // Distance from sphere center to closest point in AABB
                float dx = Math.max(0, Math.max(aabb.minX - sphere.centerX(), sphere.centerX() - aabb.maxX));
                float dy = Math.max(0, Math.max(aabb.minY - sphere.centerY(), sphere.centerY() - aabb.maxY));
                float dz = Math.max(0, Math.max(aabb.minZ - sphere.centerZ(), sphere.centerZ() - aabb.maxZ));
                yield dx*dx + dy*dy + dz*dz <= sphere.radius() * sphere.radius();
            }
            
            case Spatial.aabb volumeAABB ->
                !(aabb.maxX < volumeAABB.originX() || aabb.minX > volumeAABB.extentX() ||
                  aabb.maxY < volumeAABB.originY() || aabb.minY > volumeAABB.extentY() ||
                  aabb.maxZ < volumeAABB.originZ() || aabb.minZ > volumeAABB.extentZ());
                
            default -> true; // Use full test for complex volumes
        };
    }
    
    /**
     * Access to contents for optimized queries
     */
    private NavigableMap<Long, Content> getContents() {
        try {
            var field = Tetree.class.getDeclaredField("contents");
            field.setAccessible(true);
            return (NavigableMap<Long, Content>) field.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access contents field", e);
        }
    }
    
    /**
     * Get optimization statistics
     */
    public String getOptimizationStats() {
        return TetSpatialOptimizer.getCacheStats();
    }
    
    /**
     * Clear optimization caches
     */
    public void clearOptimizationCaches() {
        TetSpatialOptimizer.clearCaches();
    }
    
    /**
     * Helper methods to access parent's private methods via reflection
     */
    private boolean callTetrahedronContainedInVolume(Tet tet, Spatial volume) {
        try {
            var method = Tetree.class.getDeclaredMethod("tetrahedronContainedInVolume", Tet.class, Spatial.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(this, tet, volume);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access tetrahedronContainedInVolume method", e);
        }
    }
    
    private boolean callTetrahedronIntersectsVolume(Tet tet, Spatial volume) {
        try {
            var method = Tetree.class.getDeclaredMethod("tetrahedronIntersectsVolume", Tet.class, Spatial.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(this, tet, volume);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access tetrahedronIntersectsVolume method", e);
        }
    }
    
    // Helper class for AABB
    private record AABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
}