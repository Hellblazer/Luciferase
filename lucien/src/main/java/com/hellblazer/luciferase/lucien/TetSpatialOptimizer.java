package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Performance optimizations for Tetree spatial operations.
 * Implements caching, optimized SFC range computation, and reduced object allocation.
 * 
 * This achieves Phase 2 performance parity with Octree by:
 * - Caching frequently accessed SFC computations
 * - Optimizing SFC range merging and splitting
 * - Reducing temporary object creation
 * - Using more efficient geometric algorithms
 * 
 * @author hal.hildebrand
 */
public class TetSpatialOptimizer {
    
    // SFC computation cache to avoid repeated calculations
    private static final Map<SFCCacheKey, Long> sfcCache = new ConcurrentHashMap<>();
    private static final Map<Long, TetCacheEntry> tetCache = new ConcurrentHashMap<>();
    
    // Cache for SFC range computations
    private static final Map<RangeCacheKey, List<SFCRange>> rangeCache = new ConcurrentHashMap<>();
    
    // Reusable objects to reduce allocation
    private static final ThreadLocal<Point3f> tempPoint = ThreadLocal.withInitial(Point3f::new);
    private static final ThreadLocal<VolumeBounds> tempBounds = ThreadLocal.withInitial(() -> new VolumeBounds(0, 0, 0, 0, 0, 0));
    
    /**
     * Optimized SFC range computation using caching and hierarchical splitting
     */
    public static List<SFCRange> computeOptimizedSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        // Check cache first
        var cacheKey = new RangeCacheKey(bounds, includeIntersecting);
        var cached = rangeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        var ranges = new ArrayList<SFCRange>();
        
        // Use adaptive level selection based on volume size
        byte baseLevel = findOptimalLevel(bounds);
        
        // Process only necessary levels (reduced from original algorithm)
        for (byte level = (byte) Math.max(0, baseLevel - 1); level <= Math.min(Constants.getMaxRefinementLevel(), baseLevel + 2); level++) {
            computeLevelRanges(bounds, level, includeIntersecting, ranges);
        }
        
        // Efficient range merging
        var mergedRanges = mergeRangesOptimized(ranges);
        
        // Cache result for future use
        rangeCache.put(cacheKey, mergedRanges);
        
        return mergedRanges;
    }
    
    /**
     * Optimized range computation for a specific level
     */
    private static void computeLevelRanges(VolumeBounds bounds, byte level, boolean includeIntersecting, List<SFCRange> ranges) {
        int length = Constants.lengthAtLevel(level);
        
        // Calculate grid bounds with better precision
        int minX = Math.max(0, (int) Math.floor(bounds.minX / length));
        int maxX = (int) Math.ceil(bounds.maxX / length);
        int minY = Math.max(0, (int) Math.floor(bounds.minY / length));
        int maxY = (int) Math.ceil(bounds.maxY / length);
        int minZ = Math.max(0, (int) Math.floor(bounds.minZ / length));
        int maxZ = (int) Math.ceil(bounds.maxZ / length);
        
        // Use hierarchical processing to avoid checking every cell
        processGridHierarchically(minX, maxX, minY, maxY, minZ, maxZ, length, level, bounds, includeIntersecting, ranges);
    }
    
    /**
     * Hierarchical grid processing to reduce redundant checks
     */
    private static void processGridHierarchically(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, 
                                                  int length, byte level, VolumeBounds bounds, 
                                                  boolean includeIntersecting, List<SFCRange> ranges) {
        
        // For small grids, process directly
        int totalCells = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (totalCells <= 64) {
            processGridDirect(minX, maxX, minY, maxY, minZ, maxZ, length, level, bounds, includeIntersecting, ranges);
            return;
        }
        
        // For large grids, subdivide and process recursively
        int midX = (minX + maxX) / 2;
        int midY = (minY + maxY) / 2;
        int midZ = (minZ + maxZ) / 2;
        
        // Process octants
        processGridSubdivision(minX, midX, minY, midY, minZ, midZ, length, level, bounds, includeIntersecting, ranges);
        processGridSubdivision(midX + 1, maxX, minY, midY, minZ, midZ, length, level, bounds, includeIntersecting, ranges);
        processGridSubdivision(minX, midX, midY + 1, maxY, minZ, midZ, length, level, bounds, includeIntersecting, ranges);
        processGridSubdivision(midX + 1, maxX, midY + 1, maxY, minZ, midZ, length, level, bounds, includeIntersecting, ranges);
        processGridSubdivision(minX, midX, minY, midY, midZ + 1, maxZ, length, level, bounds, includeIntersecting, ranges);
        processGridSubdivision(midX + 1, maxX, minY, midY, midZ + 1, maxZ, length, level, bounds, includeIntersecting, ranges);
        processGridSubdivision(minX, midX, midY + 1, maxY, midZ + 1, maxZ, length, level, bounds, includeIntersecting, ranges);
        processGridSubdivision(midX + 1, maxX, midY + 1, maxY, midZ + 1, maxZ, length, level, bounds, includeIntersecting, ranges);
    }
    
    private static void processGridSubdivision(int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                              int length, byte level, VolumeBounds bounds, 
                                              boolean includeIntersecting, List<SFCRange> ranges) {
        if (minX > maxX || minY > maxY || minZ > maxZ) return;
        
        // Quick rejection test for this subdivision
        float subMinX = minX * length;
        float subMaxX = (maxX + 1) * length;
        float subMinY = minY * length;
        float subMaxY = (maxY + 1) * length;
        float subMinZ = minZ * length;
        float subMaxZ = (maxZ + 1) * length;
        
        if (!boundsIntersect(subMinX, subMinY, subMinZ, subMaxX, subMaxY, subMaxZ, bounds, includeIntersecting)) {
            return;
        }
        
        processGridHierarchically(minX, maxX, minY, maxY, minZ, maxZ, length, level, bounds, includeIntersecting, ranges);
    }
    
    /**
     * Direct grid processing for small regions
     */
    private static void processGridDirect(int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                         int length, byte level, VolumeBounds bounds, 
                                         boolean includeIntersecting, List<SFCRange> ranges) {
        
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    float cellX = x * length;
                    float cellY = y * length;
                    float cellZ = z * length;
                    
                    if (boundsIntersect(cellX, cellY, cellZ, cellX + length, cellY + length, cellZ + length, 
                                       bounds, includeIntersecting)) {
                        
                        // Add SFC ranges for all tetrahedron types in this cell
                        addCellRanges(x * length, y * length, z * length, level, ranges);
                    }
                }
            }
        }
    }
    
    /**
     * Fast bounds intersection test
     */
    private static boolean boundsIntersect(float cellMinX, float cellMinY, float cellMinZ,
                                          float cellMaxX, float cellMaxY, float cellMaxZ,
                                          VolumeBounds bounds, boolean includeIntersecting) {
        if (includeIntersecting) {
            // Intersection test
            return !(cellMaxX < bounds.minX || cellMinX > bounds.maxX ||
                     cellMaxY < bounds.minY || cellMinY > bounds.maxY ||
                     cellMaxZ < bounds.minZ || cellMinZ > bounds.maxZ);
        } else {
            // Containment test
            return (cellMinX >= bounds.minX && cellMaxX <= bounds.maxX &&
                    cellMinY >= bounds.minY && cellMaxY <= bounds.maxY &&
                    cellMinZ >= bounds.minZ && cellMaxZ <= bounds.maxZ);
        }
    }
    
    /**
     * Add SFC ranges for all tetrahedron types in a grid cell
     */
    private static void addCellRanges(int x, int y, int z, byte level, List<SFCRange> ranges) {
        // Use cached SFC computations when possible
        for (byte type = 0; type < 6; type++) {
            long index = getCachedSFCIndex(x, y, z, level, type);
            ranges.add(new SFCRange(index, index));
        }
    }
    
    /**
     * Get SFC index with caching
     */
    private static long getCachedSFCIndex(int x, int y, int z, byte level, byte type) {
        var cacheKey = new SFCCacheKey(x, y, z, level, type);
        return sfcCache.computeIfAbsent(cacheKey, key -> {
            var tet = new Tet(key.x, key.y, key.z, key.level, key.type);
            return tet.index();
        });
    }
    
    /**
     * Optimized range merging using single-pass algorithm
     */
    private static List<SFCRange> mergeRangesOptimized(List<SFCRange> ranges) {
        if (ranges.isEmpty()) return ranges;
        
        // Sort by start index
        ranges.sort(Comparator.comparingLong(SFCRange::startIndex));
        
        var merged = new ArrayList<SFCRange>();
        SFCRange current = ranges.get(0);
        
        for (int i = 1; i < ranges.size(); i++) {
            SFCRange next = ranges.get(i);
            
            // Merge if ranges overlap or are adjacent
            if (current.endIndex() + 1 >= next.startIndex()) {
                current = new SFCRange(current.startIndex(), Math.max(current.endIndex(), next.endIndex()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        
        return merged;
    }
    
    /**
     * Find optimal level for SFC computation based on volume characteristics
     */
    private static byte findOptimalLevel(VolumeBounds bounds) {
        float maxExtent = Math.max(Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY),
                                  bounds.maxZ - bounds.minZ);
        
        // Find level where tetrahedron size is appropriate for the volume
        for (byte level = 5; level <= Constants.getMaxRefinementLevel(); level++) {
            int tetSize = Constants.lengthAtLevel(level);
            if (tetSize <= maxExtent * 2) {
                return level;
            }
        }
        return 10; // Default fallback
    }
    
    /**
     * Optimized spatial range query that reduces stream overhead
     */
    public static Stream<Map.Entry<Long, ?>> optimizedSpatialRangeQuery(Map<Long, ?> contents, 
                                                                        VolumeBounds bounds, 
                                                                        boolean includeIntersecting) {
        var ranges = computeOptimizedSFCRanges(bounds, includeIntersecting);
        
        // Use parallel streams for large range sets - filter entries since we don't have subMap
        if (ranges.size() > 10) {
            return ranges.parallelStream()
                .flatMap(range -> contents.entrySet().stream()
                    .filter(entry -> entry.getKey() >= range.startIndex() && entry.getKey() <= range.endIndex()));
        } else {
            return ranges.stream()
                .flatMap(range -> contents.entrySet().stream()
                    .filter(entry -> entry.getKey() >= range.startIndex() && entry.getKey() <= range.endIndex()));
        }
    }
    
    /**
     * Cached tetrahedron reconstruction
     */
    public static Tet getCachedTetrahedron(long index) {
        var cached = tetCache.get(index);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 60000) { // 1 minute cache
            return cached.tet;
        }
        
        var tet = Tet.tetrahedron(index);
        tetCache.put(index, new TetCacheEntry(tet, System.currentTimeMillis()));
        return tet;
    }
    
    /**
     * Clear all caches (for testing or memory management)
     */
    public static void clearCaches() {
        sfcCache.clear();
        tetCache.clear();
        rangeCache.clear();
    }
    
    /**
     * Get cache statistics for monitoring
     */
    public static String getCacheStats() {
        return String.format("SFC Cache: %d entries, Tet Cache: %d entries, Range Cache: %d entries",
            sfcCache.size(), tetCache.size(), rangeCache.size());
    }
    
    // Cache data structures
    
    private record SFCCacheKey(int x, int y, int z, byte level, byte type) {}
    
    private record TetCacheEntry(Tet tet, long timestamp) {}
    
    private record RangeCacheKey(VolumeBounds bounds, boolean includeIntersecting) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RangeCacheKey other)) return false;
            return includeIntersecting == other.includeIntersecting &&
                   Float.compare(bounds.minX, other.bounds.minX) == 0 &&
                   Float.compare(bounds.minY, other.bounds.minY) == 0 &&
                   Float.compare(bounds.minZ, other.bounds.minZ) == 0 &&
                   Float.compare(bounds.maxX, other.bounds.maxX) == 0 &&
                   Float.compare(bounds.maxY, other.bounds.maxY) == 0 &&
                   Float.compare(bounds.maxZ, other.bounds.maxZ) == 0;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, includeIntersecting);
        }
    }
    
    public record SFCRange(long startIndex, long endIndex) {}
    
    public record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
}