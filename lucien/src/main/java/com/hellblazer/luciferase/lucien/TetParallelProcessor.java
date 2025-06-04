package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.TetQueryOptimizer.*;
import com.hellblazer.luciferase.lucien.TetSpatialIndexOptimizer.*;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Phase 5C: Tetrahedral Parallel Processing
 * 
 * Provides advanced parallel processing optimizations specifically designed for tetrahedral
 * spatial operations. Unlike cubic parallel processing, this leverages the unique properties
 * of tetrahedral space-filling curves, 6-type subdivision, and tetrahedral load balancing.
 * 
 * Key tetrahedral parallel optimizations:
 * - Work-stealing for SFC range queries with tetrahedral granularity
 * - Thread-safe caching adapted for 6-type tetrahedral complexity
 * - Load balancing considering tetrahedral subdivision patterns
 * - Parallel batch processing for tetrahedral operations
 * - Lock-free data structures for tetrahedral spatial indexing
 * 
 * @author hal.hildebrand
 */
public class TetParallelProcessor {
    
    // Thread pool configuration optimized for tetrahedral operations
    private final ForkJoinPool customForkJoinPool;
    private final int parallelism;
    private final TetParallelMetrics metrics;
    
    // Thread-safe caches for parallel tetrahedral operations
    private final ConcurrentHashMap<TetRangeKey, CompletableFuture<List<Long>>> rangeQueryCache;
    private final ConcurrentHashMap<TetIntersectionKey, CompletableFuture<Boolean>> intersectionCache;
    private final ConcurrentHashMap<Long, CompletableFuture<Point3f[]>> coordinateCache;
    
    public TetParallelProcessor() {
        this(ForkJoinPool.getCommonPoolParallelism());
    }
    
    public TetParallelProcessor(int parallelism) {
        this.parallelism = parallelism;
        this.customForkJoinPool = new ForkJoinPool(
            parallelism,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null,
            true // Enable async mode for better work stealing
        );
        this.metrics = new TetParallelMetrics();
        this.rangeQueryCache = new ConcurrentHashMap<>();
        this.intersectionCache = new ConcurrentHashMap<>();
        this.coordinateCache = new ConcurrentHashMap<>();
    }
    
    /**
     * Parallel tetrahedral SFC range query processor
     * Uses work-stealing with tetrahedral-aware task decomposition
     */
    public static class TetParallelRangeQuery {
        
        private final ForkJoinPool executor;
        private final TetParallelMetrics metrics;
        private final int parallelism;
        
        public TetParallelRangeQuery(ForkJoinPool executor, TetParallelMetrics metrics) {
            this.executor = executor;
            this.metrics = metrics;
            this.parallelism = executor.getParallelism();
        }
        
        /**
         * Parallel tetrahedral range query with adaptive work decomposition
         * Splits work based on tetrahedral SFC properties and 6-type complexity
         */
        public CompletableFuture<List<Long>> queryTetRangeParallel(Spatial volume, QueryMode mode) {
            metrics.recordParallelRangeQuery();
            
            return CompletableFuture.supplyAsync(() -> {
                var bounds = extractVolumeBounds(volume);
                if (bounds == null) {
                    return Collections.<Long>emptyList();
                }
                
                // Decompose query into parallel-friendly tetrahedral tasks
                var tasks = decomposeTetRangeQuery(bounds, mode);
                
                // Execute tasks in parallel using work-stealing
                var futures = tasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(() -> 
                        task.execute(), executor))
                    .toList();
                
                // Combine results from all parallel tasks
                return futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .distinct()
                    .sorted()
                    .toList();
                
            }, executor);
        }
        
        /**
         * Decompose tetrahedral range query into parallel tasks
         * Considers 6-type tetrahedral complexity for optimal load balancing
         */
        private List<TetRangeTask> decomposeTetRangeQuery(VolumeBounds bounds, QueryMode mode) {
            var tasks = new ArrayList<TetRangeTask>();
            
            // Calculate optimal task granularity based on volume size and available parallelism
            float volumeSize = (bounds.maxX() - bounds.minX()) * 
                              (bounds.maxY() - bounds.minY()) * 
                              (bounds.maxZ() - bounds.minZ());
            
            // Adaptive level selection for parallel decomposition
            byte optimalLevel = selectParallelLevel(bounds, volumeSize);
            int length = Constants.lengthAtLevel(optimalLevel);
            
            // Calculate grid bounds
            int minX = (int) Math.floor(bounds.minX() / length);
            int maxX = (int) Math.ceil(bounds.maxX() / length);
            int minY = (int) Math.floor(bounds.minY() / length);
            int maxY = (int) Math.ceil(bounds.maxY() / length);
            int minZ = (int) Math.floor(bounds.minZ() / length);
            int maxZ = (int) Math.ceil(bounds.maxZ() / length);
            
            // Calculate task count considering 6-type tetrahedral complexity
            int totalCells = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            int targetTaskCount = Math.min(totalCells, parallelism * 4); // 4x parallelism for good load balancing
            
            if (totalCells <= targetTaskCount) {
                // Create one task per grid cell
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            tasks.add(new TetRangeTask(bounds, mode, optimalLevel, x, x, y, y, z, z));
                        }
                    }
                }
            } else {
                // Decompose into larger chunks for fewer tasks
                var chunks = decomposeIntoChunks(minX, maxX, minY, maxY, minZ, maxZ, targetTaskCount);
                for (var chunk : chunks) {
                    tasks.add(new TetRangeTask(bounds, mode, optimalLevel, 
                        chunk.minX, chunk.maxX, chunk.minY, chunk.maxY, chunk.minZ, chunk.maxZ));
                }
            }
            
            return tasks;
        }
        
        /**
         * Select optimal level for parallel processing
         * Balances parallelism with tetrahedral computation overhead
         */
        private byte selectParallelLevel(VolumeBounds bounds, float volumeSize) {
            float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), 
                                                bounds.maxY() - bounds.minY()),
                                      bounds.maxZ() - bounds.minZ());
            
            // For parallel processing, prefer levels that create enough work for all threads
            // but not so fine that overhead dominates
            for (byte level = 5; level <= 15; level++) {
                int tetLength = Constants.lengthAtLevel(level);
                int estimatedCells = (int) Math.ceil(maxExtent / tetLength);
                int estimatedWork = estimatedCells * estimatedCells * estimatedCells * 6; // 6 types per cell
                
                if (estimatedWork >= parallelism * 100 && estimatedWork <= parallelism * 10000) {
                    return level;
                }
            }
            
            return (byte) 10; // Default fallback
        }
        
        /**
         * Decompose grid into chunks for parallel processing
         */
        private List<GridChunk> decomposeIntoChunks(int minX, int maxX, int minY, int maxY, 
                                                   int minZ, int maxZ, int targetChunks) {
            var chunks = new ArrayList<GridChunk>();
            
            // Simple decomposition along largest dimension
            int xSpan = maxX - minX + 1;
            int ySpan = maxY - minY + 1;
            int zSpan = maxZ - minZ + 1;
            
            if (xSpan >= ySpan && xSpan >= zSpan) {
                // Split along X dimension
                int chunkSize = Math.max(1, xSpan / targetChunks);
                for (int x = minX; x <= maxX; x += chunkSize) {
                    chunks.add(new GridChunk(x, Math.min(x + chunkSize - 1, maxX), 
                                           minY, maxY, minZ, maxZ));
                }
            } else if (ySpan >= zSpan) {
                // Split along Y dimension
                int chunkSize = Math.max(1, ySpan / targetChunks);
                for (int y = minY; y <= maxY; y += chunkSize) {
                    chunks.add(new GridChunk(minX, maxX, y, Math.min(y + chunkSize - 1, maxY), 
                                           minZ, maxZ));
                }
            } else {
                // Split along Z dimension
                int chunkSize = Math.max(1, zSpan / targetChunks);
                for (int z = minZ; z <= maxZ; z += chunkSize) {
                    chunks.add(new GridChunk(minX, maxX, minY, maxY, 
                                           z, Math.min(z + chunkSize - 1, maxZ)));
                }
            }
            
            return chunks;
        }
    }
    
    /**
     * Parallel tetrahedral intersection processor
     * Thread-safe intersection testing with shared caching
     */
    public static class TetParallelIntersectionProcessor {
        
        private final ForkJoinPool executor;
        private final TetParallelMetrics metrics;
        private final ConcurrentHashMap<TetIntersectionKey, Boolean> sharedCache;
        
        public TetParallelIntersectionProcessor(ForkJoinPool executor, TetParallelMetrics metrics) {
            this.executor = executor;
            this.metrics = metrics;
            this.sharedCache = new ConcurrentHashMap<>();
        }
        
        /**
         * Parallel batch intersection testing for multiple tetrahedra
         */
        public CompletableFuture<Map<Long, Boolean>> intersectBatchParallel(
                Collection<Long> tetIndices, Spatial volume) {
            metrics.recordParallelIntersectionBatch();
            
            return CompletableFuture.supplyAsync(() -> {
                // Partition indices for parallel processing
                var partitions = partitionWork(tetIndices, executor.getParallelism());
                
                // Process each partition in parallel
                var futures = partitions.stream()
                    .map(partition -> CompletableFuture.supplyAsync(() -> 
                        processIntersectionPartition(partition, volume), executor))
                    .toList();
                
                // Combine results from all partitions
                return futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(map -> map.entrySet().stream())
                    .collect(Collectors.toConcurrentMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (v1, v2) -> v1 // Keep first value in case of duplicates
                    ));
                
            }, executor);
        }
        
        /**
         * Process intersection tests for a partition of tetrahedra
         */
        private Map<Long, Boolean> processIntersectionPartition(List<Long> indices, Spatial volume) {
            var results = new HashMap<Long, Boolean>();
            var optimizer = new TetIntersectionOptimizer(new TetQueryMetrics());
            
            for (var index : indices) {
                try {
                    var tet = Tet.tetrahedron(index);
                    var cacheKey = new TetIntersectionKey(index, volume.hashCode());
                    
                    // Check shared cache first
                    var cached = sharedCache.get(cacheKey);
                    if (cached != null) {
                        results.put(index, cached);
                        metrics.recordCacheHit();
                    } else {
                        // Compute intersection
                        boolean intersects = optimizer.intersects(tet, volume);
                        results.put(index, intersects);
                        sharedCache.put(cacheKey, intersects);
                        metrics.recordCacheMiss();
                    }
                } catch (Exception e) {
                    // Skip invalid indices
                    results.put(index, false);
                }
            }
            
            return results;
        }
        
        /**
         * Partition work for parallel processing
         */
        private List<List<Long>> partitionWork(Collection<Long> indices, int parallelism) {
            var indexList = new ArrayList<>(indices);
            var partitions = new ArrayList<List<Long>>();
            int partitionSize = Math.max(1, indexList.size() / parallelism);
            
            for (int i = 0; i < indexList.size(); i += partitionSize) {
                partitions.add(indexList.subList(i, Math.min(i + partitionSize, indexList.size())));
            }
            
            return partitions;
        }
    }
    
    /**
     * Parallel tetrahedral containment processor
     * Optimized batch containment testing with work-stealing
     */
    public static class TetParallelContainmentProcessor {
        
        private final ForkJoinPool executor;
        private final TetParallelMetrics metrics;
        
        public TetParallelContainmentProcessor(ForkJoinPool executor, TetParallelMetrics metrics) {
            this.executor = executor;
            this.metrics = metrics;
        }
        
        /**
         * Parallel batch containment testing for multiple points in multiple tetrahedra
         */
        public CompletableFuture<Map<TetPointPair, Boolean>> containsBatchParallel(
                Collection<Long> tetIndices, Collection<Point3f> points) {
            metrics.recordParallelContainmentBatch();
            
            return CompletableFuture.supplyAsync(() -> {
                // Create all tet-point pairs
                var pairs = new ArrayList<TetPointPair>();
                for (var tetIndex : tetIndices) {
                    for (var point : points) {
                        pairs.add(new TetPointPair(tetIndex, point));
                    }
                }
                
                // Process pairs in parallel using work-stealing
                return pairs.parallelStream()
                    .collect(Collectors.toConcurrentMap(
                        pair -> pair,
                        this::processContainmentPair,
                        (v1, v2) -> v1 // Keep first value in case of duplicates
                    ));
                
            }, executor);
        }
        
        /**
         * Process a single containment test pair
         */
        private Boolean processContainmentPair(TetPointPair pair) {
            try {
                var tet = Tet.tetrahedron(pair.tetIndex());
                var optimizer = new TetContainmentOptimizer(new TetQueryMetrics());
                return optimizer.contains(tet, pair.point());
            } catch (Exception e) {
                return false; // Default to false for invalid cases
            }
        }
        
        /**
         * Parallel spatial filtering: find all tetrahedra containing any of the given points
         */
        public CompletableFuture<Set<Long>> findContainingTetrahedraParallel(
                Collection<Long> tetIndices, Collection<Point3f> points) {
            
            return containsBatchParallel(tetIndices, points)
                .thenApply(results -> 
                    results.entrySet().stream()
                        .filter(Map.Entry::getValue)
                        .map(entry -> entry.getKey().tetIndex())
                        .collect(Collectors.toSet())
                );
        }
    }
    
    /**
     * Load balancer for tetrahedral operations
     * Considers 6-type complexity and SFC locality
     */
    public static class TetLoadBalancer {
        
        private final int parallelism;
        private final TetParallelMetrics metrics;
        
        public TetLoadBalancer(int parallelism, TetParallelMetrics metrics) {
            this.parallelism = parallelism;
            this.metrics = metrics;
        }
        
        /**
         * Balance tetrahedral workload considering 6-type complexity
         */
        public List<List<Long>> balanceTetWorkload(Collection<Long> tetIndices) {
            metrics.recordLoadBalancing();
            
            var indexList = new ArrayList<>(tetIndices);
            
            // Sort by SFC index to maintain spatial locality within partitions
            indexList.sort(Long::compareTo);
            
            // Calculate work complexity for each tetrahedron
            var workComplexities = indexList.stream()
                .collect(Collectors.toMap(
                    index -> index,
                    this::calculateTetWorkComplexity
                ));
            
            // Create balanced partitions using greedy algorithm
            return createBalancedPartitions(indexList, workComplexities);
        }
        
        /**
         * Calculate work complexity for a tetrahedron
         * Considers level, type, and spatial characteristics
         */
        private double calculateTetWorkComplexity(long tetIndex) {
            try {
                var tet = Tet.tetrahedron(tetIndex);
                
                // Base complexity depends on level (finer levels are more expensive)
                double levelComplexity = Math.pow(2, tet.l());
                
                // Type complexity (some types may have more expensive operations)
                double typeComplexity = 1.0 + (tet.type() * 0.1);
                
                // Coordinate complexity (larger coordinates may be more expensive)
                double coordComplexity = 1.0 + (Math.log(Math.max(1, tet.x() + tet.y() + tet.z())) / 10.0);
                
                return levelComplexity * typeComplexity * coordComplexity;
            } catch (Exception e) {
                return 1.0; // Default complexity for invalid tetrahedra
            }
        }
        
        /**
         * Create balanced partitions using greedy bin packing
         */
        private List<List<Long>> createBalancedPartitions(List<Long> indices, 
                                                         Map<Long, Double> complexities) {
            var partitions = new ArrayList<List<Long>>();
            var partitionComplexities = new ArrayList<Double>();
            
            // Initialize partitions
            for (int i = 0; i < parallelism; i++) {
                partitions.add(new ArrayList<>());
                partitionComplexities.add(0.0);
            }
            
            // Greedy assignment: assign each tetrahedron to least loaded partition
            for (var index : indices) {
                double complexity = complexities.get(index);
                
                // Find partition with minimum total complexity
                int minIndex = 0;
                double minComplexity = partitionComplexities.get(0);
                for (int i = 1; i < parallelism; i++) {
                    if (partitionComplexities.get(i) < minComplexity) {
                        minComplexity = partitionComplexities.get(i);
                        minIndex = i;
                    }
                }
                
                // Assign to least loaded partition
                partitions.get(minIndex).add(index);
                partitionComplexities.set(minIndex, minComplexity + complexity);
            }
            
            return partitions;
        }
    }
    
    /**
     * Thread-safe metrics for parallel tetrahedral operations
     */
    public static class TetParallelMetrics {
        
        private final AtomicLong parallelRangeQueries = new AtomicLong(0);
        private final AtomicLong parallelIntersectionBatches = new AtomicLong(0);
        private final AtomicLong parallelContainmentBatches = new AtomicLong(0);
        private final AtomicLong loadBalancingOperations = new AtomicLong(0);
        private final AtomicLong cacheHits = new AtomicLong(0);
        private final AtomicLong cacheMisses = new AtomicLong(0);
        private final AtomicReference<Double> averageParallelism = new AtomicReference<>(0.0);
        
        public void recordParallelRangeQuery() { parallelRangeQueries.incrementAndGet(); }
        public void recordParallelIntersectionBatch() { parallelIntersectionBatches.incrementAndGet(); }
        public void recordParallelContainmentBatch() { parallelContainmentBatches.incrementAndGet(); }
        public void recordLoadBalancing() { loadBalancingOperations.incrementAndGet(); }
        public void recordCacheHit() { cacheHits.incrementAndGet(); }
        public void recordCacheMiss() { cacheMisses.incrementAndGet(); }
        
        public void updateAverageParallelism(double parallelism) {
            averageParallelism.updateAndGet(current -> (current + parallelism) / 2.0);
        }
        
        public double getCacheHitRate() {
            long hits = cacheHits.get();
            long misses = cacheMisses.get();
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
        
        public String getMetricsSummary() {
            return String.format(
                "TetParallel Metrics:\n" +
                "  Parallel Range Queries: %d\n" +
                "  Parallel Intersection Batches: %d\n" +
                "  Parallel Containment Batches: %d\n" +
                "  Load Balancing Operations: %d\n" +
                "  Cache Hit Rate: %.2f%% (%d hits, %d misses)\n" +
                "  Average Parallelism: %.2f",
                parallelRangeQueries.get(), parallelIntersectionBatches.get(),
                parallelContainmentBatches.get(), loadBalancingOperations.get(),
                getCacheHitRate() * 100, cacheHits.get(), cacheMisses.get(),
                averageParallelism.get()
            );
        }
        
        public void reset() {
            parallelRangeQueries.set(0);
            parallelIntersectionBatches.set(0);
            parallelContainmentBatches.set(0);
            loadBalancingOperations.set(0);
            cacheHits.set(0);
            cacheMisses.set(0);
            averageParallelism.set(0.0);
        }
    }
    
    // Helper classes and records
    
    /**
     * Task for parallel tetrahedral range queries
     */
    private static class TetRangeTask {
        private final VolumeBounds bounds;
        private final QueryMode mode;
        private final byte level;
        private final int minX, maxX, minY, maxY, minZ, maxZ;
        
        public TetRangeTask(VolumeBounds bounds, QueryMode mode, byte level,
                           int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.bounds = bounds;
            this.mode = mode;
            this.level = level;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
        
        public List<Long> execute() {
            var results = new ArrayList<Long>();
            int length = Constants.lengthAtLevel(level);
            
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        // Process all 6 tetrahedral types for this grid cell
                        for (byte type = 0; type < 6; type++) {
                            int cellX = x * length;
                            int cellY = y * length;
                            int cellZ = z * length;
                            
                            var tet = new Tet(cellX, cellY, cellZ, level, type);
                            
                            boolean matches = switch (mode) {
                                case CONTAINED -> isTetrahedronContained(tet, bounds);
                                case INTERSECTING, OVERLAPPING -> isTetrahedronIntersecting(tet, bounds);
                            };
                            
                            if (matches) {
                                results.add(tet.index());
                            }
                        }
                    }
                }
            }
            
            return results;
        }
    }
    
    // Helper methods
    
    private static boolean isTetrahedronContained(Tet tet, VolumeBounds bounds) {
        var vertices = tet.coordinates();
        for (var vertex : vertices) {
            if (vertex.x < bounds.minX() || vertex.x > bounds.maxX() ||
                vertex.y < bounds.minY() || vertex.y > bounds.maxY() ||
                vertex.z < bounds.minZ() || vertex.z > bounds.maxZ()) {
                return false;
            }
        }
        return true;
    }
    
    private static boolean isTetrahedronIntersecting(Tet tet, VolumeBounds bounds) {
        var vertices = tet.coordinates();
        
        // Quick bounding box test
        float tetMinX = Float.MAX_VALUE, tetMaxX = Float.MIN_VALUE;
        float tetMinY = Float.MAX_VALUE, tetMaxY = Float.MIN_VALUE;
        float tetMinZ = Float.MAX_VALUE, tetMaxZ = Float.MIN_VALUE;
        
        for (var vertex : vertices) {
            tetMinX = Math.min(tetMinX, vertex.x);
            tetMaxX = Math.max(tetMaxX, vertex.x);
            tetMinY = Math.min(tetMinY, vertex.y);
            tetMaxY = Math.max(tetMaxY, vertex.y);
            tetMinZ = Math.min(tetMinZ, vertex.z);
            tetMaxZ = Math.max(tetMaxZ, vertex.z);
        }
        
        return !(tetMaxX < bounds.minX() || tetMinX > bounds.maxX() ||
                tetMaxY < bounds.minY() || tetMinY > bounds.maxY() ||
                tetMaxZ < bounds.minZ() || tetMinZ > bounds.maxZ());
    }
    
    private static VolumeBounds extractVolumeBounds(Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube -> new VolumeBounds(
                cube.originX(), cube.originY(), cube.originZ(),
                cube.originX() + cube.extent(), cube.originY() + cube.extent(), cube.originZ() + cube.extent()
            );
            case Spatial.Sphere sphere -> new VolumeBounds(
                sphere.centerX() - sphere.radius(), sphere.centerY() - sphere.radius(), sphere.centerZ() - sphere.radius(),
                sphere.centerX() + sphere.radius(), sphere.centerY() + sphere.radius(), sphere.centerZ() + sphere.radius()
            );
            case Spatial.aabb aabb -> new VolumeBounds(
                aabb.originX(), aabb.originY(), aabb.originZ(),
                aabb.extentX(), aabb.extentY(), aabb.extentZ()
            );
            default -> null;
        };
    }
    
    // Helper records
    private record VolumeBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
    private record GridChunk(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {}
    private record TetRangeKey(VolumeBounds bounds, QueryMode mode, byte level) {}
    private record TetIntersectionKey(long tetIndex, int volumeHash) {}
    record TetPointPair(long tetIndex, Point3f point) {}
    
    // Public API methods
    
    /**
     * Get parallel processing metrics
     */
    public TetParallelMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Get parallelism level
     */
    public int getParallelism() {
        return parallelism;
    }
    
    /**
     * Create parallel range query processor
     */
    public TetParallelRangeQuery createParallelRangeQuery() {
        return new TetParallelRangeQuery(customForkJoinPool, metrics);
    }
    
    /**
     * Create parallel intersection processor
     */
    public TetParallelIntersectionProcessor createParallelIntersectionProcessor() {
        return new TetParallelIntersectionProcessor(customForkJoinPool, metrics);
    }
    
    /**
     * Create parallel containment processor
     */
    public TetParallelContainmentProcessor createParallelContainmentProcessor() {
        return new TetParallelContainmentProcessor(customForkJoinPool, metrics);
    }
    
    /**
     * Create load balancer
     */
    public TetLoadBalancer createLoadBalancer() {
        return new TetLoadBalancer(parallelism, metrics);
    }
    
    /**
     * Execute tetrahedral operation with automatic load balancing
     */
    public <T> CompletableFuture<List<T>> executeBalanced(Collection<Long> tetIndices, 
                                                         Function<Long, T> operation) {
        var balancer = createLoadBalancer();
        var partitions = balancer.balanceTetWorkload(tetIndices);
        
        var futures = partitions.stream()
            .map(partition -> CompletableFuture.supplyAsync(() -> 
                partition.stream()
                    .map(operation)
                    .filter(Objects::nonNull)
                    .toList(), customForkJoinPool))
            .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList());
    }
    
    /**
     * Shutdown the parallel processor
     */
    public void shutdown() {
        customForkJoinPool.shutdown();
        try {
            if (!customForkJoinPool.awaitTermination(60, TimeUnit.SECONDS)) {
                customForkJoinPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            customForkJoinPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Clear all caches
     */
    public void clearCaches() {
        rangeQueryCache.clear();
        intersectionCache.clear();
        coordinateCache.clear();
    }
}