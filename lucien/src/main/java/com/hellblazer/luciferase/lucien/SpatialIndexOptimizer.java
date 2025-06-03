package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spatial indexing optimizations for Octree
 * Provides adaptive level selection, Morton code optimizations, and cache-friendly data structures
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class SpatialIndexOptimizer {

    /**
     * Statistics about spatial data distribution
     */
    public static class SpatialDistributionStats {
        public final int totalPoints;
        public final float minX, maxX, minY, maxY, minZ, maxZ;
        public final float centerX, centerY, centerZ;
        public final float spanX, spanY, spanZ;
        public final float density;
        public final byte recommendedLevel;
        public final int clusteredRegions;
        public final float uniformityScore; // 0.0 = highly clustered, 1.0 = perfectly uniform
        
        public SpatialDistributionStats(int totalPoints, float minX, float maxX, float minY, float maxY, 
                                      float minZ, float maxZ, byte recommendedLevel, int clusteredRegions, 
                                      float uniformityScore) {
            this.totalPoints = totalPoints;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.centerX = (minX + maxX) / 2.0f;
            this.centerY = (minY + maxY) / 2.0f;
            this.centerZ = (minZ + maxZ) / 2.0f;
            this.spanX = maxX - minX;
            this.spanY = maxY - minY;
            this.spanZ = maxZ - minZ;
            this.density = totalPoints / (spanX * spanY * spanZ);
            this.recommendedLevel = recommendedLevel;
            this.clusteredRegions = clusteredRegions;
            this.uniformityScore = uniformityScore;
        }
        
        @Override
        public String toString() {
            return String.format("SpatialStats[points=%d, bounds=(%.1f,%.1f,%.1f)-(%.1f,%.1f,%.1f), " +
                               "density=%.3f, level=%d, clusters=%d, uniformity=%.3f]",
                               totalPoints, minX, minY, minZ, maxX, maxY, maxZ, 
                               density, recommendedLevel, clusteredRegions, uniformityScore);
        }
    }

    /**
     * Cache-friendly Morton code calculator with optimized bit operations
     */
    public static class OptimizedMortonCalculator {
        
        /**
         * Optimized Morton encoding using bit manipulation
         */
        public static long encodeMorton3D(int x, int y, int z) {
            return splitBy3(x) | (splitBy3(y) << 1) | (splitBy3(z) << 2);
        }
        
        /**
         * Fast Morton decoding with bit manipulation tricks
         */
        public static int[] decodeMorton3D(long morton) {
            int x = (int) compactBy3(morton);
            int y = (int) compactBy3(morton >> 1);
            int z = (int) compactBy3(morton >> 2);
            return new int[]{x, y, z};
        }
        
        /**
         * Split bits by inserting 2 zeros between each bit (for 3D Morton)
         * Input:  00000000 00000000 00000000 000abcde
         * Output: 00000000 000a000b 000c000d 0000000e
         */
        private static long splitBy3(int value) {
            long x = value & 0x1fffffL; // Mask to 21 bits (2^21 = 2M, enough for most spatial coordinates)
            
            x = (x | (x << 32)) & 0x1f00000000ffffL;
            x = (x | (x << 16)) & 0x1f0000ff0000ffL;
            x = (x | (x << 8)) & 0x100f00f00f00f00fL;
            x = (x | (x << 4)) & 0x10c30c30c30c30c3L;
            x = (x | (x << 2)) & 0x1249249249249249L;
            
            return x;
        }
        
        /**
         * Compact bits by removing every 2nd and 3rd bit (inverse of splitBy3)
         */
        private static long compactBy3(long value) {
            long x = value & 0x1249249249249249L;
            
            x = (x | (x >> 2)) & 0x10c30c30c30c30c3L;
            x = (x | (x >> 4)) & 0x100f00f00f00f00fL;
            x = (x | (x >> 8)) & 0x1f0000ff0000ffL;
            x = (x | (x >> 16)) & 0x1f00000000ffffL;
            x = (x | (x >> 32)) & 0x1fffffL;
            
            return x;
        }
    }

    /**
     * Adaptive level selector that analyzes spatial distribution to recommend optimal octree levels
     */
    public static class AdaptiveLevelSelector {
        private static final int MIN_POINTS_PER_CELL = 8;
        private static final int MAX_POINTS_PER_CELL = 64;
        private static final byte MIN_LEVEL = 10;
        private static final byte MAX_LEVEL = 20;
        
        /**
         * Analyze spatial distribution and recommend optimal level
         */
        public static SpatialDistributionStats analyzeSpatialDistribution(Collection<Point3f> points) {
            if (points.isEmpty()) {
                return new SpatialDistributionStats(0, 0, 0, 0, 0, 0, 0, MIN_LEVEL, 0, 1.0f);
            }
            
            // Calculate bounding box
            float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
            float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;
            
            for (Point3f point : points) {
                validatePositiveCoordinates(point, "point");
                minX = Math.min(minX, point.x);
                maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y);
                maxY = Math.max(maxY, point.y);
                minZ = Math.min(minZ, point.z);
                maxZ = Math.max(maxZ, point.z);
            }
            
            // Calculate recommended level based on data distribution
            byte recommendedLevel = calculateOptimalLevel(points, minX, maxX, minY, maxY, minZ, maxZ);
            
            // Analyze clustering
            int clusteredRegions = analyzeClusterDistribution(points, recommendedLevel);
            
            // Calculate uniformity score
            float uniformityScore = calculateUniformityScore(points, minX, maxX, minY, maxY, minZ, maxZ);
            
            return new SpatialDistributionStats(points.size(), minX, maxX, minY, maxY, minZ, maxZ,
                                              recommendedLevel, clusteredRegions, uniformityScore);
        }
        
        /**
         * Calculate optimal octree level based on data distribution
         */
        private static byte calculateOptimalLevel(Collection<Point3f> points, float minX, float maxX, 
                                                float minY, float maxY, float minZ, float maxZ) {
            float volume = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
            if (volume <= 0) {
                return MIN_LEVEL;
            }
            
            float density = points.size() / volume;
            
            // Start with a level that gives reasonable cell density
            byte level = MIN_LEVEL;
            for (byte l = MIN_LEVEL; l <= MAX_LEVEL; l++) {
                int cellSize = Constants.lengthAtLevel(l);
                float cellVolume = cellSize * cellSize * cellSize;
                float expectedPointsPerCell = density * cellVolume;
                
                if (expectedPointsPerCell >= MIN_POINTS_PER_CELL && 
                    expectedPointsPerCell <= MAX_POINTS_PER_CELL) {
                    level = l;
                    break;
                } else if (expectedPointsPerCell < MIN_POINTS_PER_CELL) {
                    level = (byte) Math.max(MIN_LEVEL, l - 1);
                    break;
                }
            }
            
            return (byte) Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
        }
        
        /**
         * Analyze spatial clustering to detect hotspots
         */
        private static int analyzeClusterDistribution(Collection<Point3f> points, byte level) {
            if (points.size() < 10) {
                return 1;
            }
            
            // Use a simplified grid-based clustering analysis
            Map<Long, Integer> cellCounts = new HashMap<>();
            int cellSize = Constants.lengthAtLevel(level);
            
            for (Point3f point : points) {
                long cellX = (long) (point.x / cellSize);
                long cellY = (long) (point.y / cellSize);
                long cellZ = (long) (point.z / cellSize);
                long cellKey = (cellX << 32) | (cellY << 16) | cellZ;
                
                cellCounts.merge(cellKey, 1, Integer::sum);
            }
            
            // Count cells with significantly above-average density
            double averageDensity = (double) points.size() / cellCounts.size();
            int clusteredCells = 0;
            
            for (int count : cellCounts.values()) {
                if (count > averageDensity * 2.0) {
                    clusteredCells++;
                }
            }
            
            return Math.max(1, clusteredCells);
        }
        
        /**
         * Calculate uniformity score (0.0 = clustered, 1.0 = uniform)
         */
        private static float calculateUniformityScore(Collection<Point3f> points, float minX, float maxX,
                                                    float minY, float maxY, float minZ, float maxZ) {
            if (points.size() < 4) {
                return 1.0f;
            }
            
            // Sample a subset of points for efficiency
            List<Point3f> samplePoints = new ArrayList<>();
            if (points.size() <= 100) {
                samplePoints.addAll(points);
            } else {
                // Random sampling
                Random random = ThreadLocalRandom.current();
                Point3f[] pointArray = points.toArray(new Point3f[0]);
                for (int i = 0; i < 100; i++) {
                    samplePoints.add(pointArray[random.nextInt(pointArray.length)]);
                }
            }
            
            // Calculate average nearest neighbor distance
            double totalDistance = 0.0;
            int comparisons = 0;
            
            for (int i = 0; i < samplePoints.size(); i++) {
                Point3f p1 = samplePoints.get(i);
                double minDistance = Double.MAX_VALUE;
                
                for (int j = 0; j < samplePoints.size(); j++) {
                    if (i != j) {
                        Point3f p2 = samplePoints.get(j);
                        double distance = calculateDistance(p1, p2);
                        minDistance = Math.min(minDistance, distance);
                    }
                }
                
                if (minDistance < Double.MAX_VALUE) {
                    totalDistance += minDistance;
                    comparisons++;
                }
            }
            
            if (comparisons == 0) {
                return 1.0f;
            }
            
            double averageNearestDistance = totalDistance / comparisons;
            
            // Calculate expected distance for uniform distribution
            double volume = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
            double density = points.size() / volume;
            double expectedDistance = Math.pow(1.0 / density, 1.0/3.0) * 0.5; // Rough approximation
            
            // Uniformity score: closer to expected = more uniform
            double ratio = averageNearestDistance / expectedDistance;
            return (float) Math.max(0.0, Math.min(1.0, ratio));
        }
    }

    /**
     * Cache-friendly data structure optimizations
     */
    public static class CacheOptimizedStructures {
        
        /**
         * Compact spatial point representation for better cache performance
         */
        public static class CompactPoint {
            public final int x, y, z;
            
            public CompactPoint(int x, int y, int z) {
                this.x = x;
                this.y = y;
                this.z = z;
            }
            
            public static CompactPoint fromPoint3f(Point3f point, float scale) {
                validatePositiveCoordinates(point, "point");
                return new CompactPoint(
                    (int) (point.x * scale),
                    (int) (point.y * scale),
                    (int) (point.z * scale)
                );
            }
            
            public Point3f toPoint3f(float invScale) {
                return new Point3f(x * invScale, y * invScale, z * invScale);
            }
            
            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof CompactPoint)) return false;
                CompactPoint other = (CompactPoint) obj;
                return x == other.x && y == other.y && z == other.z;
            }
            
            @Override
            public int hashCode() {
                return Objects.hash(x, y, z);
            }
        }
        
        /**
         * Morton-ordered array for cache-friendly spatial traversal
         */
        public static class MortonOrderedArray<T> {
            private final List<MortonEntry<T>> entries;
            private boolean sorted;
            
            public MortonOrderedArray() {
                this.entries = new ArrayList<>();
                this.sorted = true;
            }
            
            public void add(Point3f point, T data) {
                validatePositiveCoordinates(point, "point");
                
                CompactPoint compact = CompactPoint.fromPoint3f(point, 1000.0f); // Scale for precision
                long morton = OptimizedMortonCalculator.encodeMorton3D(compact.x, compact.y, compact.z);
                entries.add(new MortonEntry<>(morton, point, data));
                sorted = false;
            }
            
            public void sortByMortonOrder() {
                if (!sorted) {
                    entries.sort(Comparator.comparing(e -> e.morton));
                    sorted = true;
                }
            }
            
            public List<MortonEntry<T>> getEntries() {
                sortByMortonOrder();
                return Collections.unmodifiableList(entries);
            }
            
            public int size() {
                return entries.size();
            }
            
            public void clear() {
                entries.clear();
                sorted = true;
            }
        }
        
        public static class MortonEntry<T> {
            public final long morton;
            public final Point3f point;
            public final T data;
            
            public MortonEntry(long morton, Point3f point, T data) {
                this.morton = morton;
                this.point = point;
                this.data = data;
            }
        }
    }

    /**
     * Lazy evaluation framework for expensive spatial operations
     */
    public static class LazyEvaluationFramework {
        
        /**
         * Lazy spatial query that defers computation until results are needed
         */
        public static abstract class LazySpatialQuery<T> {
            protected boolean computed = false;
            protected List<T> cachedResults;
            protected Exception computationError;
            
            public List<T> getResults() {
                if (!computed) {
                    try {
                        cachedResults = computeResults();
                        computed = true;
                    } catch (Exception e) {
                        computationError = e;
                        cachedResults = Collections.emptyList();
                        computed = true;
                    }
                }
                
                if (computationError != null) {
                    throw new RuntimeException("Lazy computation failed", computationError);
                }
                
                return cachedResults;
            }
            
            public boolean isComputed() {
                return computed;
            }
            
            public void invalidate() {
                computed = false;
                cachedResults = null;
                computationError = null;
            }
            
            protected abstract List<T> computeResults();
        }
        
        /**
         * Lazy distance calculator with threshold-based early termination
         */
        public static class LazyDistanceCalculator {
            private final Point3f queryPoint;
            private final List<Point3f> targetPoints;
            private final float maxDistance;
            private List<Float> cachedDistances;
            
            public LazyDistanceCalculator(Point3f queryPoint, List<Point3f> targetPoints, float maxDistance) {
                validatePositiveCoordinates(queryPoint, "queryPoint");
                this.queryPoint = queryPoint;
                this.targetPoints = new ArrayList<>(targetPoints);
                this.maxDistance = maxDistance;
            }
            
            public List<Float> getDistances() {
                if (cachedDistances == null) {
                    cachedDistances = new ArrayList<>();
                    
                    for (Point3f target : targetPoints) {
                        float distance = calculateDistance(queryPoint, target);
                        if (distance <= maxDistance) {
                            cachedDistances.add(distance);
                        } else {
                            cachedDistances.add(Float.MAX_VALUE); // Sentinel for "too far"
                        }
                    }
                }
                
                return cachedDistances;
            }
            
            public int countWithinDistance() {
                return (int) getDistances().stream()
                    .mapToDouble(Float::doubleValue)
                    .filter(d -> d <= maxDistance)
                    .count();
            }
        }
    }

    /**
     * Performance benchmarking utilities
     */
    public static class SpatialPerformanceBenchmark {
        
        public static class BenchmarkResult {
            public final String operation;
            public final long timeNanos;
            public final int dataSize;
            public final double throughput; // operations per second
            
            public BenchmarkResult(String operation, long timeNanos, int dataSize) {
                this.operation = operation;
                this.timeNanos = timeNanos;
                this.dataSize = dataSize;
                this.throughput = dataSize > 0 ? (1_000_000_000.0 * dataSize) / timeNanos : 0.0;
            }
            
            @Override
            public String toString() {
                return String.format("Benchmark[%s: %.2fms, %d items, %.1f ops/sec]",
                    operation, timeNanos / 1_000_000.0, dataSize, throughput);
            }
        }
        
        public static BenchmarkResult benchmarkMortonEncoding(List<Point3f> points) {
            long startTime = System.nanoTime();
            
            for (Point3f point : points) {
                CacheOptimizedStructures.CompactPoint compact = CacheOptimizedStructures.CompactPoint.fromPoint3f(point, 1000.0f);
                OptimizedMortonCalculator.encodeMorton3D(compact.x, compact.y, compact.z);
            }
            
            long endTime = System.nanoTime();
            return new BenchmarkResult("Morton Encoding", endTime - startTime, points.size());
        }
        
        public static BenchmarkResult benchmarkSpatialAnalysis(List<Point3f> points) {
            long startTime = System.nanoTime();
            
            AdaptiveLevelSelector.analyzeSpatialDistribution(points);
            
            long endTime = System.nanoTime();
            return new BenchmarkResult("Spatial Analysis", endTime - startTime, points.size());
        }
    }

    // Utility methods
    
    private static float calculateDistance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    private static void validatePositiveCoordinates(Point3f point, String paramName) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(paramName + " coordinates must be positive, got: " + point);
        }
    }
}