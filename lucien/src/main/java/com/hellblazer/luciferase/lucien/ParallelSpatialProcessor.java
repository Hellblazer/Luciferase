package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Parallel processing enhancements for Octree spatial operations
 * Provides thread-safe parallel execution of spatial queries and operations
 * All operations are constrained to positive coordinates only
 * 
 * @author hal.hildebrand
 */
public class ParallelSpatialProcessor {

    /**
     * Configuration for parallel processing behavior
     */
    public static class ParallelConfig {
        public final int threadPoolSize;
        public final int minDataSizeForParallel;
        public final int workChunkSize;
        public final boolean enableWorkStealing;
        public final long timeoutMillis;
        
        public ParallelConfig(int threadPoolSize, int minDataSizeForParallel, int workChunkSize, 
                            boolean enableWorkStealing, long timeoutMillis) {
            this.threadPoolSize = Math.max(1, threadPoolSize);
            this.minDataSizeForParallel = Math.max(1, minDataSizeForParallel);
            this.workChunkSize = Math.max(1, workChunkSize);
            this.enableWorkStealing = enableWorkStealing;
            this.timeoutMillis = Math.max(1000, timeoutMillis);
        }
        
        public static ParallelConfig defaultConfig() {
            int processors = Runtime.getRuntime().availableProcessors();
            return new ParallelConfig(
                Math.max(2, processors),  // Use available processors, minimum 2
                100,                      // Parallelize if data size >= 100
                50,                       // Process in chunks of 50
                true,                     // Enable work stealing
                30000                     // 30 second timeout
            );
        }
        
        public static ParallelConfig conservativeConfig() {
            return new ParallelConfig(2, 500, 100, false, 60000);
        }
        
        public static ParallelConfig aggressiveConfig() {
            int processors = Runtime.getRuntime().availableProcessors();
            return new ParallelConfig(processors * 2, 50, 25, true, 15000);
        }
        
        @Override
        public String toString() {
            return String.format("ParallelConfig[threads=%d, minSize=%d, chunkSize=%d, workStealing=%s, timeout=%dms]",
                threadPoolSize, minDataSizeForParallel, workChunkSize, enableWorkStealing, timeoutMillis);
        }
    }

    /**
     * Result of parallel processing operation with performance metrics
     */
    public static class ParallelResult<T> {
        public final List<T> results;
        public final long executionTimeNanos;
        public final int threadsUsed;
        public final int chunksProcessed;
        public final boolean timedOut;
        public final Exception error;
        
        public ParallelResult(List<T> results, long executionTimeNanos, int threadsUsed, 
                            int chunksProcessed, boolean timedOut, Exception error) {
            this.results = Collections.unmodifiableList(new ArrayList<>(results));
            this.executionTimeNanos = executionTimeNanos;
            this.threadsUsed = threadsUsed;
            this.chunksProcessed = chunksProcessed;
            this.timedOut = timedOut;
            this.error = error;
        }
        
        public boolean isSuccessful() {
            return !timedOut && error == null;
        }
        
        public double getThroughputItemsPerSecond() {
            return executionTimeNanos > 0 ? (results.size() * 1_000_000_000.0) / executionTimeNanos : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("ParallelResult[items=%d, time=%.2fms, threads=%d, chunks=%d, success=%s]",
                results.size(), executionTimeNanos / 1_000_000.0, threadsUsed, chunksProcessed, isSuccessful());
        }
    }

    /**
     * Thread-safe parallel spatial query executor
     */
    public static class ParallelSpatialQueryExecutor<Content> {
        private final Octree<Content> octree;
        private final ParallelConfig config;
        private final ExecutorService executorService;
        
        public ParallelSpatialQueryExecutor(Octree<Content> octree, ParallelConfig config) {
            this.octree = octree;
            this.config = config;
            this.executorService = config.enableWorkStealing ? 
                ForkJoinPool.commonPool() :
                Executors.newFixedThreadPool(config.threadPoolSize);
        }
        
        /**
         * Execute parallel radius query
         */
        public ParallelResult<Content> parallelRadiusQuery(Point3f center, float radius) {
            validatePositiveCoordinates(center, "center");
            
            long startTime = System.nanoTime();
            Map<Long, Content> map = octree.getMap();
            
            if (map.size() < config.minDataSizeForParallel) {
                // Fall back to sequential processing for small datasets
                return executeSequentialRadiusQuery(center, radius, startTime);
            }
            
            try {
                List<Map.Entry<Long, Content>> entries = new ArrayList<>(map.entrySet());
                List<List<Map.Entry<Long, Content>>> chunks = partitionData(entries, config.workChunkSize);
                
                List<CompletableFuture<List<Content>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> 
                        processRadiusQueryChunk(chunk, center, radius), executorService))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                List<Content> results = futures.stream()
                    .flatMap(future -> {
                        try {
                            return future.get().stream();
                        } catch (Exception e) {
                            throw new RuntimeException("Chunk processing failed", e);
                        }
                    })
                    .collect(Collectors.toList());
                
                long endTime = System.nanoTime();
                return new ParallelResult<>(results, endTime - startTime, 
                    Math.min(chunks.size(), config.threadPoolSize), chunks.size(), false, null);
                
            } catch (TimeoutException e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    config.threadPoolSize, 0, true, e);
            } catch (Exception e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    0, 0, false, e);
            }
        }
        
        /**
         * Execute parallel range query
         */
        public ParallelResult<Content> parallelRangeQuery(Point3f minBounds, Point3f maxBounds) {
            validatePositiveCoordinates(minBounds, "minBounds");
            validatePositiveCoordinates(maxBounds, "maxBounds");
            
            long startTime = System.nanoTime();
            Map<Long, Content> map = octree.getMap();
            
            if (map.size() < config.minDataSizeForParallel) {
                return executeSequentialRangeQuery(minBounds, maxBounds, startTime);
            }
            
            try {
                List<Map.Entry<Long, Content>> entries = new ArrayList<>(map.entrySet());
                List<List<Map.Entry<Long, Content>>> chunks = partitionData(entries, config.workChunkSize);
                
                List<CompletableFuture<List<Content>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> 
                        processRangeQueryChunk(chunk, minBounds, maxBounds), executorService))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                List<Content> results = futures.stream()
                    .flatMap(future -> {
                        try {
                            return future.get().stream();
                        } catch (Exception e) {
                            throw new RuntimeException("Chunk processing failed", e);
                        }
                    })
                    .collect(Collectors.toList());
                
                long endTime = System.nanoTime();
                return new ParallelResult<>(results, endTime - startTime, 
                    Math.min(chunks.size(), config.threadPoolSize), chunks.size(), false, null);
                
            } catch (TimeoutException e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    config.threadPoolSize, 0, true, e);
            } catch (Exception e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    0, 0, false, e);
            }
        }
        
        /**
         * Execute parallel k-nearest neighbor query
         */
        public ParallelResult<Content> parallelKNearestNeighborQuery(Point3f queryPoint, int k) {
            validatePositiveCoordinates(queryPoint, "queryPoint");
            
            long startTime = System.nanoTime();
            Map<Long, Content> map = octree.getMap();
            
            if (map.size() < config.minDataSizeForParallel || k >= map.size()) {
                return executeSequentialKNNQuery(queryPoint, k, startTime);
            }
            
            try {
                List<Map.Entry<Long, Content>> entries = new ArrayList<>(map.entrySet());
                List<List<Map.Entry<Long, Content>>> chunks = partitionData(entries, config.workChunkSize);
                
                List<CompletableFuture<List<DistanceContentPair<Content>>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> 
                        processKNNQueryChunk(chunk, queryPoint, k), executorService))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                // Merge results from all chunks and get top k
                List<DistanceContentPair<Content>> allCandidates = futures.stream()
                    .flatMap(future -> {
                        try {
                            return future.get().stream();
                        } catch (Exception e) {
                            throw new RuntimeException("Chunk processing failed", e);
                        }
                    })
                    .sorted(Comparator.comparing(pair -> pair.distance))
                    .limit(k)
                    .collect(Collectors.toList());
                
                List<Content> results = allCandidates.stream()
                    .map(pair -> pair.content)
                    .collect(Collectors.toList());
                
                long endTime = System.nanoTime();
                return new ParallelResult<>(results, endTime - startTime, 
                    Math.min(chunks.size(), config.threadPoolSize), chunks.size(), false, null);
                
            } catch (TimeoutException e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    config.threadPoolSize, 0, true, e);
            } catch (Exception e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    0, 0, false, e);
            }
        }
        
        /**
         * Execute parallel custom query with user-defined predicate
         */
        public ParallelResult<Content> parallelCustomQuery(Predicate<Map.Entry<Long, Content>> predicate) {
            long startTime = System.nanoTime();
            Map<Long, Content> map = octree.getMap();
            
            if (map.size() < config.minDataSizeForParallel) {
                return executeSequentialCustomQuery(predicate, startTime);
            }
            
            try {
                List<Map.Entry<Long, Content>> entries = new ArrayList<>(map.entrySet());
                List<List<Map.Entry<Long, Content>>> chunks = partitionData(entries, config.workChunkSize);
                
                List<CompletableFuture<List<Content>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> 
                        processCustomQueryChunk(chunk, predicate), executorService))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                List<Content> results = futures.stream()
                    .flatMap(future -> {
                        try {
                            return future.get().stream();
                        } catch (Exception e) {
                            throw new RuntimeException("Chunk processing failed", e);
                        }
                    })
                    .collect(Collectors.toList());
                
                long endTime = System.nanoTime();
                return new ParallelResult<>(results, endTime - startTime, 
                    Math.min(chunks.size(), config.threadPoolSize), chunks.size(), false, null);
                
            } catch (TimeoutException e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    config.threadPoolSize, 0, true, e);
            } catch (Exception e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    0, 0, false, e);
            }
        }
        
        // Helper methods for chunk processing
        
        private List<Content> processRadiusQueryChunk(List<Map.Entry<Long, Content>> chunk, 
                                                    Point3f center, float radius) {
            List<Content> results = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : chunk) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                Point3f cubeCenter = new Point3f(
                    cube.originX() + cube.extent() / 2.0f,
                    cube.originY() + cube.extent() / 2.0f,
                    cube.originZ() + cube.extent() / 2.0f
                );
                
                if (calculateDistance(center, cubeCenter) <= radius) {
                    results.add(entry.getValue());
                }
            }
            
            return results;
        }
        
        private List<Content> processRangeQueryChunk(List<Map.Entry<Long, Content>> chunk, 
                                                   Point3f minBounds, Point3f maxBounds) {
            List<Content> results = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : chunk) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                
                // Check if cube intersects with query bounds
                if (cube.originX() <= maxBounds.x && cube.originX() + cube.extent() >= minBounds.x &&
                    cube.originY() <= maxBounds.y && cube.originY() + cube.extent() >= minBounds.y &&
                    cube.originZ() <= maxBounds.z && cube.originZ() + cube.extent() >= minBounds.z) {
                    results.add(entry.getValue());
                }
            }
            
            return results;
        }
        
        private List<DistanceContentPair<Content>> processKNNQueryChunk(List<Map.Entry<Long, Content>> chunk, 
                                                                       Point3f queryPoint, int k) {
            List<DistanceContentPair<Content>> candidates = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : chunk) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                Point3f cubeCenter = new Point3f(
                    cube.originX() + cube.extent() / 2.0f,
                    cube.originY() + cube.extent() / 2.0f,
                    cube.originZ() + cube.extent() / 2.0f
                );
                
                float distance = calculateDistance(queryPoint, cubeCenter);
                candidates.add(new DistanceContentPair<>(distance, entry.getValue()));
            }
            
            // Return top k candidates from this chunk
            return candidates.stream()
                .sorted(Comparator.comparing(pair -> pair.distance))
                .limit(k * 2) // Get extra candidates to improve global k selection
                .collect(Collectors.toList());
        }
        
        private List<Content> processCustomQueryChunk(List<Map.Entry<Long, Content>> chunk, 
                                                    Predicate<Map.Entry<Long, Content>> predicate) {
            return chunk.stream()
                .filter(predicate)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        }
        
        // Sequential fallback methods
        
        private ParallelResult<Content> executeSequentialRadiusQuery(Point3f center, float radius, long startTime) {
            List<Content> results = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                Point3f cubeCenter = new Point3f(
                    cube.originX() + cube.extent() / 2.0f,
                    cube.originY() + cube.extent() / 2.0f,
                    cube.originZ() + cube.extent() / 2.0f
                );
                
                if (calculateDistance(center, cubeCenter) <= radius) {
                    results.add(entry.getValue());
                }
            }
            
            long endTime = System.nanoTime();
            return new ParallelResult<>(results, endTime - startTime, 1, 1, false, null);
        }
        
        private ParallelResult<Content> executeSequentialRangeQuery(Point3f minBounds, Point3f maxBounds, long startTime) {
            List<Content> results = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                
                if (cube.originX() <= maxBounds.x && cube.originX() + cube.extent() >= minBounds.x &&
                    cube.originY() <= maxBounds.y && cube.originY() + cube.extent() >= minBounds.y &&
                    cube.originZ() <= maxBounds.z && cube.originZ() + cube.extent() >= minBounds.z) {
                    results.add(entry.getValue());
                }
            }
            
            long endTime = System.nanoTime();
            return new ParallelResult<>(results, endTime - startTime, 1, 1, false, null);
        }
        
        private ParallelResult<Content> executeSequentialKNNQuery(Point3f queryPoint, int k, long startTime) {
            List<DistanceContentPair<Content>> candidates = new ArrayList<>();
            
            for (Map.Entry<Long, Content> entry : octree.getMap().entrySet()) {
                Spatial.Cube cube = Octree.toCube(entry.getKey());
                Point3f cubeCenter = new Point3f(
                    cube.originX() + cube.extent() / 2.0f,
                    cube.originY() + cube.extent() / 2.0f,
                    cube.originZ() + cube.extent() / 2.0f
                );
                
                float distance = calculateDistance(queryPoint, cubeCenter);
                candidates.add(new DistanceContentPair<>(distance, entry.getValue()));
            }
            
            List<Content> results = candidates.stream()
                .sorted(Comparator.comparing(pair -> pair.distance))
                .limit(k)
                .map(pair -> pair.content)
                .collect(Collectors.toList());
            
            long endTime = System.nanoTime();
            return new ParallelResult<>(results, endTime - startTime, 1, 1, false, null);
        }
        
        private ParallelResult<Content> executeSequentialCustomQuery(Predicate<Map.Entry<Long, Content>> predicate, long startTime) {
            List<Content> results = octree.getMap().entrySet().stream()
                .filter(predicate)
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
            
            long endTime = System.nanoTime();
            return new ParallelResult<>(results, endTime - startTime, 1, 1, false, null);
        }
        
        public void shutdown() {
            if (!config.enableWorkStealing && executorService instanceof ThreadPoolExecutor) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Parallel spatial data processing utilities
     */
    public static class ParallelSpatialDataProcessor {
        
        /**
         * Process spatial data in parallel with custom transformation
         */
        public static <T, R> ParallelResult<R> parallelTransform(Collection<T> data, 
                                                                Function<T, R> transformer, 
                                                                ParallelConfig config) {
            long startTime = System.nanoTime();
            
            if (data.size() < config.minDataSizeForParallel) {
                List<R> results = data.stream()
                    .map(transformer)
                    .collect(Collectors.toList());
                long endTime = System.nanoTime();
                return new ParallelResult<>(results, endTime - startTime, 1, 1, false, null);
            }
            
            ExecutorService executor = config.enableWorkStealing ? 
                ForkJoinPool.commonPool() :
                Executors.newFixedThreadPool(config.threadPoolSize);
            
            try {
                List<T> dataList = new ArrayList<>(data);
                List<List<T>> chunks = partitionData(dataList, config.workChunkSize);
                
                List<CompletableFuture<List<R>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> 
                        chunk.stream().map(transformer).collect(Collectors.toList()), executor))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                List<R> results = futures.stream()
                    .flatMap(future -> {
                        try {
                            return future.get().stream();
                        } catch (Exception e) {
                            throw new RuntimeException("Chunk processing failed", e);
                        }
                    })
                    .collect(Collectors.toList());
                
                long endTime = System.nanoTime();
                return new ParallelResult<>(results, endTime - startTime, 
                    Math.min(chunks.size(), config.threadPoolSize), chunks.size(), false, null);
                
            } catch (TimeoutException e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    config.threadPoolSize, 0, true, e);
            } catch (Exception e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    0, 0, false, e);
            } finally {
                if (!config.enableWorkStealing && executor instanceof ThreadPoolExecutor) {
                    executor.shutdown();
                }
            }
        }
        
        /**
         * Parallel Morton encoding for large point sets
         */
        public static ParallelResult<Long> parallelMortonEncoding(List<Point3f> points, ParallelConfig config) {
            return parallelTransform(points, point -> {
                validatePositiveCoordinates(point, "point");
                SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint compact = 
                    SpatialIndexOptimizer.CacheOptimizedStructures.CompactPoint.fromPoint3f(point, 1000.0f);
                return SpatialIndexOptimizer.OptimizedMortonCalculator.encodeMorton3D(
                    compact.x, compact.y, compact.z);
            }, config);
        }
        
        /**
         * Parallel spatial analysis for large datasets
         */
        public static ParallelResult<SpatialIndexOptimizer.SpatialDistributionStats> parallelSpatialAnalysis(
                List<Point3f> points, ParallelConfig config) {
            
            long startTime = System.nanoTime();
            
            if (points.size() < config.minDataSizeForParallel) {
                SpatialIndexOptimizer.SpatialDistributionStats stats = 
                    SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(points);
                long endTime = System.nanoTime();
                return new ParallelResult<>(List.of(stats), endTime - startTime, 1, 1, false, null);
            }
            
            // For large datasets, we can parallelize parts of the analysis
            // This is a simplified version - in practice you'd parallelize bounding box calculation,
            // clustering analysis, etc.
            try {
                SpatialIndexOptimizer.SpatialDistributionStats stats = 
                    SpatialIndexOptimizer.AdaptiveLevelSelector.analyzeSpatialDistribution(points);
                long endTime = System.nanoTime();
                return new ParallelResult<>(List.of(stats), endTime - startTime, 1, 1, false, null);
            } catch (Exception e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 0, 0, false, e);
            }
        }
    }

    /**
     * Helper class for distance-content pairs used in k-NN queries
     */
    private static class DistanceContentPair<Content> {
        final float distance;
        final Content content;
        
        DistanceContentPair(float distance, Content content) {
            this.distance = distance;
            this.content = content;
        }
    }

    /**
     * Performance monitoring for parallel operations
     */
    public static class ParallelPerformanceMonitor {
        private final List<ParallelResult<?>> executionHistory = new CopyOnWriteArrayList<>();
        
        public void recordExecution(ParallelResult<?> result) {
            executionHistory.add(result);
            
            // Keep only recent history to avoid memory bloat
            if (executionHistory.size() > 1000) {
                executionHistory.subList(0, 500).clear();
            }
        }
        
        public ParallelPerformanceStats getStatistics() {
            if (executionHistory.isEmpty()) {
                return new ParallelPerformanceStats(0, 0, 0, 0, 0, 0, 0);
            }
            
            int totalExecutions = executionHistory.size();
            int successfulExecutions = (int) executionHistory.stream().mapToInt(r -> r.isSuccessful() ? 1 : 0).sum();
            double avgExecutionTimeMs = executionHistory.stream()
                .mapToLong(r -> r.executionTimeNanos)
                .average().orElse(0.0) / 1_000_000.0;
            double avgThroughput = executionHistory.stream()
                .mapToDouble(ParallelResult::getThroughputItemsPerSecond)
                .average().orElse(0.0);
            double avgThreadsUsed = executionHistory.stream()
                .mapToInt(r -> r.threadsUsed)
                .average().orElse(0.0);
            int timeouts = (int) executionHistory.stream().mapToInt(r -> r.timedOut ? 1 : 0).sum();
            int errors = (int) executionHistory.stream().mapToInt(r -> r.error != null ? 1 : 0).sum();
            
            return new ParallelPerformanceStats(totalExecutions, successfulExecutions, 
                avgExecutionTimeMs, avgThroughput, avgThreadsUsed, timeouts, errors);
        }
        
        public void clearHistory() {
            executionHistory.clear();
        }
        
        public static class ParallelPerformanceStats {
            public final int totalExecutions;
            public final int successfulExecutions;
            public final double avgExecutionTimeMs;
            public final double avgThroughputItemsPerSecond;
            public final double avgThreadsUsed;
            public final int timeouts;
            public final int errors;
            
            public ParallelPerformanceStats(int totalExecutions, int successfulExecutions,
                                          double avgExecutionTimeMs, double avgThroughputItemsPerSecond,
                                          double avgThreadsUsed, int timeouts, int errors) {
                this.totalExecutions = totalExecutions;
                this.successfulExecutions = successfulExecutions;
                this.avgExecutionTimeMs = avgExecutionTimeMs;
                this.avgThroughputItemsPerSecond = avgThroughputItemsPerSecond;
                this.avgThreadsUsed = avgThreadsUsed;
                this.timeouts = timeouts;
                this.errors = errors;
            }
            
            public double getSuccessRate() {
                return totalExecutions > 0 ? (double) successfulExecutions / totalExecutions : 0.0;
            }
            
            @Override
            public String toString() {
                return String.format("ParallelStats[executions=%d, success=%.1f%%, avgTime=%.2fms, " +
                    "avgThroughput=%.1f items/sec, avgThreads=%.1f, timeouts=%d, errors=%d]",
                    totalExecutions, getSuccessRate() * 100, avgExecutionTimeMs, 
                    avgThroughputItemsPerSecond, avgThreadsUsed, timeouts, errors);
            }
        }
    }

    // Utility methods
    
    private static <T> List<List<T>> partitionData(List<T> data, int chunkSize) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < data.size(); i += chunkSize) {
            chunks.add(data.subList(i, Math.min(i + chunkSize, data.size())));
        }
        return chunks;
    }
    
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