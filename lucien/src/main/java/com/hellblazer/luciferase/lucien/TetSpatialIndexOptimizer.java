package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Phase 5A: Tetrahedral Spatial Index Optimization
 * 
 * Provides advanced optimization techniques specifically designed for tetrahedral spatial indexing.
 * Unlike cubic Morton curve optimization, this focuses on the unique properties of tetrahedral
 * space-filling curves with 6 types per grid cell.
 * 
 * Key differences from cubic optimization:
 * - Uses tetrahedral SFC (not Morton curve) with 6 types per grid location
 * - Leverages tetrahedral geometric properties for containment/intersection
 * - Optimizes for the 8-child to 6-type tetrahedral subdivision pattern
 * - Maintains positive coordinate constraints throughout
 * 
 * @author hal.hildebrand
 */
public class TetSpatialIndexOptimizer {
    
    // Cache for tetrahedral SFC calculations
    private static final Map<Long, Tet> tetCache = new ConcurrentHashMap<>();
    private static final Map<TetCacheKey, Point3i[]> coordinateCache = new ConcurrentHashMap<>();
    private static final Map<Byte, Integer> levelLengthCache = new ConcurrentHashMap<>();
    
    // Performance monitoring
    private final TetOptimizationMetrics metrics = new TetOptimizationMetrics();
    private final TetLazyEvaluator lazyEvaluator = new TetLazyEvaluator();
    
    /**
     * Optimized tetrahedral SFC encoding that leverages the 6-type structure
     * and pre-computed lookup tables for maximum performance.
     */
    public static class OptimizedTetCalculator {
        
        /**
         * Calculate tetrahedral SFC index with optimized lookup tables
         * Respects the 6-type tetrahedral subdivision and positive coordinate constraint
         */
        public static long encodeTetSFC(int x, int y, int z, byte level, byte type) {
            // Validate positive coordinates
            if (x < 0 || y < 0 || z < 0) {
                throw new IllegalArgumentException("Coordinates must be positive: (" + x + ", " + y + ", " + z + ")");
            }
            
            // Validate tetrahedral type (0-5)
            if (type < 0 || type > 5) {
                throw new IllegalArgumentException("Tetrahedral type must be in range [0, 5]: " + type);
            }
            
            // Use the existing Tet index() method which implements the proper tetrahedral SFC
            var tet = new Tet(x, y, z, level, type);
            return tet.index();
        }
        
        /**
         * Decode tetrahedral SFC index back to Tet with coordinates and type
         * Uses optimized tetrahedral SFC decoding (not Morton curve)
         */
        public static Tet decodeTetSFC(long index) {
            // Use cached version if available
            return tetCache.computeIfAbsent(index, Tet::tetrahedron);
        }
        
        /**
         * Fast tetrahedral level calculation from SFC index
         * Uses the tetrahedral 3-bit per level encoding
         */
        public static byte calculateTetLevel(long index) {
            return Tet.tetLevelFromIndex(index);
        }
        
        /**
         * Calculate grid cell bounds for a given tetrahedral level
         * All tetrahedra at the same level and grid location share these bounds
         */
        public static GridCellBounds calculateGridCellBounds(int gridX, int gridY, int gridZ, byte level) {
            int length = Constants.lengthAtLevel(level);
            return new GridCellBounds(
                gridX * length, gridY * length, gridZ * length,
                (gridX + 1) * length, (gridY + 1) * length, (gridZ + 1) * length
            );
        }
    }
    
    /**
     * Adaptive tetrahedral level selection based on data distribution and spatial characteristics.
     * Unlike cubic levels, considers the 6-type tetrahedral complexity per grid cell.
     */
    public static class AdaptiveTetLevelSelector {
        
        private final Map<SpatialCharacteristics, Byte> levelCache = new ConcurrentHashMap<>();
        
        /**
         * Select optimal tetrahedral level based on spatial data characteristics
         * Considers tetrahedral geometry and 6-type complexity
         */
        public byte selectOptimalLevel(Collection<Point3f> points, float volumeExtent) {
            if (points.isEmpty()) {
                return Constants.getMaxRefinementLevel();
            }
            
            var characteristics = analyzeSpatialCharacteristics(points, volumeExtent);
            return levelCache.computeIfAbsent(characteristics, this::computeOptimalLevel);
        }
        
        /**
         * Calculate level that minimizes tetrahedral subdivision overhead
         * while maintaining spatial resolution
         */
        public byte selectLevelForVolumeQuery(float minX, float minY, float minZ, 
                                            float maxX, float maxY, float maxZ) {
            float maxExtent = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ);
            
            // Find level where tetrahedral grid cell size is appropriate for volume
            for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
                int tetLength = Constants.lengthAtLevel(level);
                // For tetrahedra, we want grid cells that are 1/2 to 1/4 the volume extent
                if (tetLength <= maxExtent * 2 && tetLength >= maxExtent / 4) {
                    return level;
                }
            }
            
            return Constants.getMaxRefinementLevel();
        }
        
        private SpatialCharacteristics analyzeSpatialCharacteristics(Collection<Point3f> points, float volumeExtent) {
            if (points.isEmpty()) {
                return new SpatialCharacteristics(0, 0.0f, 0.0f);
            }
            
            // Calculate density and distribution metrics for tetrahedral optimization
            float density = points.size() / (volumeExtent * volumeExtent * volumeExtent);
            
            // Calculate coefficient of variation for spatial distribution
            List<Float> coords = points.stream()
                .flatMap(p -> java.util.stream.Stream.of(p.x, p.y, p.z))
                .toList();
            
            float mean = coords.stream().reduce(0f, Float::sum) / coords.size();
            float variance = coords.stream()
                .map(x -> (x - mean) * (x - mean))
                .reduce(0f, Float::sum) / coords.size();
            float coefficientOfVariation = (float) Math.sqrt(variance) / mean;
            
            return new SpatialCharacteristics(points.size(), density, coefficientOfVariation);
        }
        
        private byte computeOptimalLevel(SpatialCharacteristics characteristics) {
            // Tetrahedral-specific level selection logic
            if (characteristics.density > 1000.0f) {
                // High density: use finer levels to reduce tetrahedral overlap
                return (byte) Math.min(Constants.getMaxRefinementLevel(), 15);
            } else if (characteristics.density < 0.1f) {
                // Low density: use coarser levels to reduce empty tetrahedra
                return (byte) Math.max(5, 8);
            } else {
                // Medium density: balance between resolution and efficiency
                return (byte) 10;
            }
        }
    }
    
    /**
     * Cache-friendly tetrahedral data structures optimized for spatial locality
     * and 6-type tetrahedral access patterns.
     */
    public static class TetCacheFriendlyStructures {
        
        /**
         * Compact tetrahedral representation for cache efficiency
         * Stores only essential data to minimize memory footprint
         */
        public static class CompactTet {
            public final int x, y, z;
            public final byte level, type;
            public final long sfcIndex;
            
            public CompactTet(int x, int y, int z, byte level, byte type) {
                // Validate positive coordinates
                if (x < 0 || y < 0 || z < 0) {
                    throw new IllegalArgumentException("Coordinates must be positive: (" + x + ", " + y + ", " + z + ")");
                }
                
                this.x = x;
                this.y = y;
                this.z = z;
                this.level = level;
                this.type = type;
                this.sfcIndex = OptimizedTetCalculator.encodeTetSFC(x, y, z, level, type);
            }
            
            public CompactTet(Tet tet) {
                this(tet.x(), tet.y(), tet.z(), tet.l(), tet.type());
            }
            
            public Tet toTet() {
                return new Tet(x, y, z, level, type);
            }
            
            /**
             * Get coordinates with caching for performance
             */
            public Point3i[] getCoordinates() {
                var key = new TetCacheKey(x, y, z, level, type);
                return coordinateCache.computeIfAbsent(key, k -> toTet().coordinates());
            }
        }
        
        /**
         * Tetrahedral SFC-ordered array for optimal spatial traversal
         * Orders tetrahedra by their SFC index for cache-friendly access
         */
        public static class TetSFCOrderedArray {
            private final CompactTet[] tetrahedra;
            private final boolean sorted;
            
            public TetSFCOrderedArray(Collection<Tet> tets) {
                this.tetrahedra = tets.stream()
                    .map(CompactTet::new)
                    .sorted(Comparator.comparingLong(t -> t.sfcIndex))
                    .toArray(CompactTet[]::new);
                this.sorted = true;
            }
            
            /**
             * Binary search for tetrahedral SFC index range
             */
            public CompactTet[] findInSFCRange(long startIndex, long endIndex) {
                if (!sorted) {
                    throw new IllegalStateException("Array must be sorted for range queries");
                }
                
                int startPos = binarySearchSFC(startIndex);
                int endPos = binarySearchSFC(endIndex + 1);
                
                return Arrays.copyOfRange(tetrahedra, startPos, endPos);
            }
            
            private int binarySearchSFC(long targetIndex) {
                int left = 0, right = tetrahedra.length;
                
                while (left < right) {
                    int mid = (left + right) / 2;
                    if (tetrahedra[mid].sfcIndex < targetIndex) {
                        left = mid + 1;
                    } else {
                        right = mid;
                    }
                }
                
                return left;
            }
            
            public int size() {
                return tetrahedra.length;
            }
            
            public CompactTet get(int index) {
                return tetrahedra[index];
            }
        }
        
        /**
         * Grid cell that contains all 6 tetrahedral types
         * Optimizes for the fact that each grid cell has exactly 6 tetrahedra
         */
        public static class TetGridCell {
            public final int gridX, gridY, gridZ;
            public final byte level;
            public final CompactTet[] sixTetrahedra; // Always exactly 6
            
            public TetGridCell(int gridX, int gridY, int gridZ, byte level) {
                this.gridX = gridX;
                this.gridY = gridY;
                this.gridZ = gridZ;
                this.level = level;
                
                // Create all 6 tetrahedral types for this grid cell
                this.sixTetrahedra = new CompactTet[6];
                int length = Constants.lengthAtLevel(level);
                int cellX = gridX * length;
                int cellY = gridY * length;
                int cellZ = gridZ * length;
                
                for (byte type = 0; type < 6; type++) {
                    this.sixTetrahedra[type] = new CompactTet(cellX, cellY, cellZ, level, type);
                }
            }
            
            /**
             * Get tetrahedral bounds for this grid cell
             */
            public GridCellBounds getBounds() {
                int length = Constants.lengthAtLevel(level);
                return new GridCellBounds(
                    gridX * length, gridY * length, gridZ * length,
                    (gridX + 1) * length, (gridY + 1) * length, (gridZ + 1) * length
                );
            }
            
            /**
             * Test if any of the 6 tetrahedra in this cell intersect the given bounds
             */
            public boolean intersectsBounds(float minX, float minY, float minZ,
                                          float maxX, float maxY, float maxZ) {
                var cellBounds = getBounds();
                
                // Quick grid cell bounds check first
                if (cellBounds.maxX < minX || cellBounds.minX > maxX ||
                    cellBounds.maxY < minY || cellBounds.minY > maxY ||
                    cellBounds.maxZ < minZ || cellBounds.minZ > maxZ) {
                    return false;
                }
                
                // Check individual tetrahedra if grid cell intersects
                for (CompactTet tet : sixTetrahedra) {
                    if (tetrahedronIntersectsBounds(tet, minX, minY, minZ, maxX, maxY, maxZ)) {
                        return true;
                    }
                }
                
                return false;
            }
            
            private boolean tetrahedronIntersectsBounds(CompactTet tet, 
                                                      float minX, float minY, float minZ,
                                                      float maxX, float maxY, float maxZ) {
                var coords = tet.getCoordinates();
                
                // Quick bounding box test first
                float tetMinX = Float.MAX_VALUE, tetMaxX = Float.MIN_VALUE;
                float tetMinY = Float.MAX_VALUE, tetMaxY = Float.MIN_VALUE;
                float tetMinZ = Float.MAX_VALUE, tetMaxZ = Float.MIN_VALUE;
                
                for (var vertex : coords) {
                    tetMinX = Math.min(tetMinX, vertex.x);
                    tetMaxX = Math.max(tetMaxX, vertex.x);
                    tetMinY = Math.min(tetMinY, vertex.y);
                    tetMaxY = Math.max(tetMaxY, vertex.y);
                    tetMinZ = Math.min(tetMinZ, vertex.z);
                    tetMaxZ = Math.max(tetMaxZ, vertex.z);
                }
                
                // If tetrahedron bounding box doesn't intersect bounds, no intersection
                if (tetMaxX < minX || tetMinX > maxX ||
                    tetMaxY < minY || tetMinY > maxY ||
                    tetMaxZ < minZ || tetMinZ > maxZ) {
                    return false;
                }
                
                // Check if any vertex is within bounds
                for (var vertex : coords) {
                    if (vertex.x >= minX && vertex.x <= maxX &&
                        vertex.y >= minY && vertex.y <= maxY &&
                        vertex.z >= minZ && vertex.z <= maxZ) {
                        return true;
                    }
                }
                
                // Check if the center of bounds is inside the tetrahedron
                var centerX = (minX + maxX) / 2;
                var centerY = (minY + maxY) / 2;
                var centerZ = (minZ + maxZ) / 2;
                var centerPoint = new Point3f(centerX, centerY, centerZ);
                
                return tet.toTet().contains(centerPoint);
            }
        }
    }
    
    /**
     * Tetrahedral geometry lookup tables for fast orientation and containment tests
     */
    public static class TetGeometryLookupTables {
        
        // Cache for expensive orientation calculations
        private static final Map<OrientationKey, Double> orientationCache = new ConcurrentHashMap<>();
        
        /**
         * Optimized orientation test using cached results
         * Critical for tetrahedral containment testing
         */
        public static double orientationCached(Point3i query, Point3i a, Point3i b, Point3i c) {
            var key = new OrientationKey(query, a, b, c);
            return orientationCache.computeIfAbsent(key, k -> 
                Tet.orientation(k.query, k.a, k.b, k.c));
        }
        
        /**
         * Pre-compute orientation results for standard tetrahedral configurations
         */
        public static void precomputeStandardOrientations() {
            // Pre-compute orientations for the 6 standard tetrahedral types
            for (byte type = 0; type < 6; type++) {
                var standardVertices = Constants.SIMPLEX_STANDARD[type];
                // Cache common orientation tests for this type
                for (int i = 0; i < standardVertices.length; i++) {
                    for (int j = i + 1; j < standardVertices.length; j++) {
                        for (int k = j + 1; k < standardVertices.length; k++) {
                            // Pre-compute orientation for standard test points
                            var testPoint = new Point3i(0, 0, 0);
                            orientationCached(testPoint, standardVertices[i], 
                                            standardVertices[j], standardVertices[k]);
                        }
                    }
                }
            }
        }
        
        /**
         * Get pre-computed tetrahedral vertices for a type
         */
        public static Point3i[] getStandardVertices(byte type) {
            if (type < 0 || type > 5) {
                throw new IllegalArgumentException("Invalid tetrahedral type: " + type);
            }
            return Constants.SIMPLEX_STANDARD[type].clone();
        }
    }
    
    /**
     * Lazy evaluation framework for tetrahedral calculations
     * Defers expensive operations until actually needed
     */
    public static class TetLazyEvaluator {
        
        private final Map<Long, LazyTetData> lazyCache = new ConcurrentHashMap<>();
        
        /**
         * Lazy tetrahedral coordinate calculation
         */
        public Point3i[] getCoordinatesLazy(long sfcIndex) {
            return lazyCache.computeIfAbsent(sfcIndex, LazyTetData::new).getCoordinates();
        }
        
        /**
         * Lazy tetrahedral containment test
         */
        public boolean containsPointLazy(long sfcIndex, Point3f point) {
            return lazyCache.computeIfAbsent(sfcIndex, LazyTetData::new).containsPoint(point);
        }
        
        /**
         * Clear cache to manage memory usage
         */
        public void clearCache() {
            lazyCache.clear();
        }
        
        private static class LazyTetData {
            private final long sfcIndex;
            private volatile Tet tet;
            private volatile Point3i[] coordinates;
            
            LazyTetData(long sfcIndex) {
                this.sfcIndex = sfcIndex;
            }
            
            Tet getTet() {
                if (tet == null) {
                    synchronized (this) {
                        if (tet == null) {
                            tet = Tet.tetrahedron(sfcIndex);
                        }
                    }
                }
                return tet;
            }
            
            Point3i[] getCoordinates() {
                if (coordinates == null) {
                    synchronized (this) {
                        if (coordinates == null) {
                            coordinates = getTet().coordinates();
                        }
                    }
                }
                return coordinates;
            }
            
            boolean containsPoint(Point3f point) {
                return getTet().contains(point);
            }
        }
    }
    
    /**
     * Performance metrics collection for tetrahedral operations
     */
    public static class TetOptimizationMetrics {
        
        private long tetSFCCalculations = 0;
        private long coordinateCalculations = 0;
        private long orientationTests = 0;
        private long cacheHits = 0;
        private long cacheMisses = 0;
        
        public synchronized void recordTetSFCCalculation() {
            tetSFCCalculations++;
        }
        
        public synchronized void recordCoordinateCalculation() {
            coordinateCalculations++;
        }
        
        public synchronized void recordOrientationTest() {
            orientationTests++;
        }
        
        public synchronized void recordCacheHit() {
            cacheHits++;
        }
        
        public synchronized void recordCacheMiss() {
            cacheMisses++;
        }
        
        public synchronized double getCacheHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total : 0.0;
        }
        
        public synchronized String getMetricsSummary() {
            return String.format(
                "TetOptimization Metrics:\n" +
                "  SFC Calculations: %d\n" +
                "  Coordinate Calculations: %d\n" +
                "  Orientation Tests: %d\n" +
                "  Cache Hit Rate: %.2f%% (%d hits, %d misses)",
                tetSFCCalculations, coordinateCalculations, orientationTests,
                getCacheHitRate() * 100, cacheHits, cacheMisses
            );
        }
        
        public synchronized void reset() {
            tetSFCCalculations = 0;
            coordinateCalculations = 0;
            orientationTests = 0;
            cacheHits = 0;
            cacheMisses = 0;
        }
    }
    
    // Helper records and classes
    
    private record SpatialCharacteristics(int pointCount, float density, float coefficientOfVariation) {}
    
    private record TetCacheKey(int x, int y, int z, byte level, byte type) {}
    
    private record OrientationKey(Point3i query, Point3i a, Point3i b, Point3i c) {}
    
    public record GridCellBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
    
    // Public API methods
    
    /**
     * Clear all caches to free memory
     */
    public static void clearCaches() {
        tetCache.clear();
        coordinateCache.clear();
        levelLengthCache.clear();
        TetGeometryLookupTables.orientationCache.clear();
    }
    
    /**
     * Get current optimization metrics
     */
    public TetOptimizationMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Get lazy evaluator for deferred calculations
     */
    public TetLazyEvaluator getLazyEvaluator() {
        return lazyEvaluator;
    }
    
    /**
     * Initialize optimization tables and caches
     */
    public static void initialize() {
        // Pre-compute commonly used values
        for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
            levelLengthCache.put(level, Constants.lengthAtLevel(level));
        }
        
        // Pre-compute standard tetrahedral orientations
        TetGeometryLookupTables.precomputeStandardOrientations();
    }
    
    /**
     * Create optimized tetrahedral grid for spatial queries
     */
    public static TetCacheFriendlyStructures.TetSFCOrderedArray createOptimizedTetArray(
            Collection<Tet> tetrahedra) {
        return new TetCacheFriendlyStructures.TetSFCOrderedArray(tetrahedra);
    }
    
    /**
     * Create grid cell with all 6 tetrahedral types
     */
    public static TetCacheFriendlyStructures.TetGridCell createTetGridCell(
            int gridX, int gridY, int gridZ, byte level) {
        return new TetCacheFriendlyStructures.TetGridCell(gridX, gridY, gridZ, level);
    }
}