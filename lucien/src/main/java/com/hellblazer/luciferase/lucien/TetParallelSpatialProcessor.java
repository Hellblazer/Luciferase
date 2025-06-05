package com.hellblazer.luciferase.lucien;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parallel processing enhancements for Tetree spatial operations
 * Provides thread-safe parallel execution of spatial queries and operations
 * All operations are constrained to positive coordinates only, as required by tetrahedral SFC
 * 
 * @author hal.hildebrand
 */
public class TetParallelSpatialProcessor extends TetrahedralSearchBase {

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
     * Thread-safe parallel spatial query executor for Tetree
     */
    public static class ParallelTetreeSpatialQueryExecutor<Content> {
        private final Tetree<Content> tetree;
        private final ParallelConfig config;
        private final ExecutorService executorService;
        
        public ParallelTetreeSpatialQueryExecutor(Tetree<Content> tetree, ParallelConfig config) {
            this.tetree = tetree;
            this.config = config;
            this.executorService = config.enableWorkStealing ? 
                ForkJoinPool.commonPool() :
                Executors.newFixedThreadPool(config.threadPoolSize);
        }
        
        /**
         * Execute parallel radius query in tetrahedral space
         */
        public ParallelResult<Tetree.Simplex<Content>> parallelRadiusQuery(Point3f center, float radius) {
            validatePositiveCoordinates(center);
            
            long startTime = System.nanoTime();
            
            // Create spatial AABB for radius query
            Spatial.aabb searchBounds = new Spatial.aabb(
                Math.max(0, center.x - radius),
                Math.max(0, center.y - radius),
                Math.max(0, center.z - radius),
                center.x + radius,
                center.y + radius,
                center.z + radius
            );
            
            // Get all simplicies within search bounds
            // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
            List<Tetree.Simplex<Content>> candidates = TetreeHelper.directScanBoundedBy(tetree, searchBounds)
                .collect(Collectors.toList());
            
            if (candidates.size() < config.minDataSizeForParallel) {
                // Fall back to sequential processing for small datasets
                return executeSequentialRadiusQuery(candidates, center, radius, startTime);
            }
            
            try {
                List<List<Tetree.Simplex<Content>>> chunks = partitionData(candidates, config.workChunkSize);
                
                List<CompletableFuture<List<Tetree.Simplex<Content>>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> 
                        processRadiusQueryChunk(chunk, center, radius), executorService))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                List<Tetree.Simplex<Content>> results = futures.stream()
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
         * Execute parallel range query in tetrahedral space
         */
        public ParallelResult<Tetree.Simplex<Content>> parallelRangeQuery(Point3f minBounds, Point3f maxBounds) {
            validatePositiveCoordinates(minBounds);
            validatePositiveCoordinates(maxBounds);
            
            long startTime = System.nanoTime();
            
            // Create spatial AABB for range query
            Spatial.aabb searchBounds = new Spatial.aabb(
                minBounds.x, minBounds.y, minBounds.z,
                maxBounds.x, maxBounds.y, maxBounds.z
            );
            
            // Get all simplicies within search bounds
            // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
            List<Tetree.Simplex<Content>> candidates = TetreeHelper.directScanBoundedBy(tetree, searchBounds)
                .collect(Collectors.toList());
            
            if (candidates.size() < config.minDataSizeForParallel) {
                // Return candidates directly for small datasets
                long endTime = System.nanoTime();
                return new ParallelResult<>(candidates, endTime - startTime, 1, 1, false, null);
            }
            
            try {
                // For range queries, we can directly process the filtered results in parallel
                List<List<Tetree.Simplex<Content>>> chunks = partitionData(candidates, config.workChunkSize);
                
                // Since tetree.boundedBy already filters by bounds, we just need to parallelize additional processing
                // For now, we'll return the results as-is, but this could be extended with additional filtering
                long endTime = System.nanoTime();
                return new ParallelResult<>(candidates, endTime - startTime, 
                    Math.min(chunks.size(), config.threadPoolSize), chunks.size(), false, null);
                
            } catch (Exception e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    0, 0, false, e);
            }
        }
        
        /**
         * Execute parallel k-nearest neighbor query in tetrahedral space
         */
        public ParallelResult<Tetree.Simplex<Content>> parallelKNearestNeighborQuery(Point3f queryPoint, int k) {
            validatePositiveCoordinates(queryPoint);
            
            long startTime = System.nanoTime();
            
            // Use adaptive search radius for k-NN
            float searchRadius = estimateSearchRadiusForKNN(k);
            Spatial.aabb searchBounds = new Spatial.aabb(
                Math.max(0, queryPoint.x - searchRadius),
                Math.max(0, queryPoint.y - searchRadius),
                Math.max(0, queryPoint.z - searchRadius),
                queryPoint.x + searchRadius,
                queryPoint.y + searchRadius,
                queryPoint.z + searchRadius
            );
            
            // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
            List<Tetree.Simplex<Content>> candidates = TetreeHelper.directScanBoundedBy(tetree, searchBounds)
                .collect(Collectors.toList());
            
            if (candidates.size() < config.minDataSizeForParallel || k >= candidates.size()) {
                return executeSequentialKNNQuery(candidates, queryPoint, k, startTime);
            }
            
            try {
                List<List<Tetree.Simplex<Content>>> chunks = partitionData(candidates, config.workChunkSize);
                
                List<CompletableFuture<List<DistanceSimplexPair<Content>>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> 
                        processKNNQueryChunk(chunk, queryPoint, k), executorService))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                // Merge results from all chunks and get top k
                List<DistanceSimplexPair<Content>> allCandidates = futures.stream()
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
                
                List<Tetree.Simplex<Content>> results = allCandidates.stream()
                    .map(pair -> pair.simplex)
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
        public ParallelResult<Tetree.Simplex<Content>> parallelCustomQuery(
                Predicate<Tetree.Simplex<Content>> predicate, Spatial.aabb bounds) {
            
            long startTime = System.nanoTime();
            
            // TODO: Using direct scan as workaround for Tetree.boundedBy() issue
            List<Tetree.Simplex<Content>> candidates = TetreeHelper.directScanBoundedBy(tetree, bounds)
                .collect(Collectors.toList());
            
            if (candidates.size() < config.minDataSizeForParallel) {
                return executeSequentialCustomQuery(candidates, predicate, startTime);
            }
            
            try {
                List<List<Tetree.Simplex<Content>>> chunks = partitionData(candidates, config.workChunkSize);
                
                List<CompletableFuture<List<Tetree.Simplex<Content>>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> 
                        processCustomQueryChunk(chunk, predicate), executorService))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                List<Tetree.Simplex<Content>> results = futures.stream()
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
         * Execute parallel batch insertion with multiple points
         */
        public ParallelResult<Long> parallelBatchInsert(List<Point3f> points, byte level, Content content) {
            for (Point3f point : points) {
                validatePositiveCoordinates(point);
            }
            
            long startTime = System.nanoTime();
            
            if (points.size() < config.minDataSizeForParallel) {
                // Sequential insertion for small batches
                List<Long> indices = new ArrayList<>();
                for (Point3f point : points) {
                    indices.add(tetree.insert(point, level, content));
                }
                long endTime = System.nanoTime();
                return new ParallelResult<>(indices, endTime - startTime, 1, 1, false, null);
            }
            
            try {
                List<List<Point3f>> chunks = partitionData(points, config.workChunkSize);
                
                List<CompletableFuture<List<Long>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> {
                        List<Long> indices = new ArrayList<>();
                        synchronized (tetree) {  // Ensure thread-safe insertion
                            for (Point3f point : chunk) {
                                indices.add(tetree.insert(point, level, content));
                            }
                        }
                        return indices;
                    }, executorService))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                List<Long> results = futures.stream()
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
        
        private List<Tetree.Simplex<Content>> processRadiusQueryChunk(
                List<Tetree.Simplex<Content>> chunk, Point3f center, float radius) {
            
            List<Tetree.Simplex<Content>> results = new ArrayList<>();
            
            for (Tetree.Simplex<Content> simplex : chunk) {
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                
                if (calculateDistance(center, tetCenter) <= radius) {
                    results.add(simplex);
                }
            }
            
            return results;
        }
        
        private List<DistanceSimplexPair<Content>> processKNNQueryChunk(
                List<Tetree.Simplex<Content>> chunk, Point3f queryPoint, int k) {
            
            List<DistanceSimplexPair<Content>> candidates = new ArrayList<>();
            
            for (Tetree.Simplex<Content> simplex : chunk) {
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                float distance = calculateDistance(queryPoint, tetCenter);
                candidates.add(new DistanceSimplexPair<>(distance, simplex));
            }
            
            // Return top k candidates from this chunk
            return candidates.stream()
                .sorted(Comparator.comparing(pair -> pair.distance))
                .limit(k * 2) // Get extra candidates to improve global k selection
                .collect(Collectors.toList());
        }
        
        private List<Tetree.Simplex<Content>> processCustomQueryChunk(
                List<Tetree.Simplex<Content>> chunk, Predicate<Tetree.Simplex<Content>> predicate) {
            
            return chunk.stream()
                .filter(predicate)
                .collect(Collectors.toList());
        }
        
        // Sequential fallback methods
        
        private ParallelResult<Tetree.Simplex<Content>> executeSequentialRadiusQuery(
                List<Tetree.Simplex<Content>> candidates, Point3f center, float radius, long startTime) {
            
            List<Tetree.Simplex<Content>> results = new ArrayList<>();
            
            for (Tetree.Simplex<Content> simplex : candidates) {
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                
                if (calculateDistance(center, tetCenter) <= radius) {
                    results.add(simplex);
                }
            }
            
            long endTime = System.nanoTime();
            return new ParallelResult<>(results, endTime - startTime, 1, 1, false, null);
        }
        
        private ParallelResult<Tetree.Simplex<Content>> executeSequentialKNNQuery(
                List<Tetree.Simplex<Content>> candidates, Point3f queryPoint, int k, long startTime) {
            
            List<DistanceSimplexPair<Content>> pairs = new ArrayList<>();
            
            for (Tetree.Simplex<Content> simplex : candidates) {
                Point3f tetCenter = tetrahedronCenter(simplex.index());
                float distance = calculateDistance(queryPoint, tetCenter);
                pairs.add(new DistanceSimplexPair<>(distance, simplex));
            }
            
            List<Tetree.Simplex<Content>> results = pairs.stream()
                .sorted(Comparator.comparing(pair -> pair.distance))
                .limit(k)
                .map(pair -> pair.simplex)
                .collect(Collectors.toList());
            
            long endTime = System.nanoTime();
            return new ParallelResult<>(results, endTime - startTime, 1, 1, false, null);
        }
        
        private ParallelResult<Tetree.Simplex<Content>> executeSequentialCustomQuery(
                List<Tetree.Simplex<Content>> candidates, Predicate<Tetree.Simplex<Content>> predicate, long startTime) {
            
            List<Tetree.Simplex<Content>> results = candidates.stream()
                .filter(predicate)
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
     * Parallel spatial data processing utilities for tetrahedral space
     */
    public static class ParallelTetSpatialDataProcessor {
        
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
         * Parallel Tet SFC encoding for large point sets
         */
        public static ParallelResult<Long> parallelTetEncoding(List<Point3f> points, byte level, ParallelConfig config) {
            return parallelTransform(points, point -> {
                validatePositiveCoordinates(point);
                // Create a tetree instance for location
                Tetree<Void> tempTetree = new Tetree<>(new TreeMap<>());
                Tet tet = tempTetree.locate(point, level);
                return tet.index();
            }, config);
        }
        
        /**
         * Parallel tetrahedron reconstruction from SFC indices
         */
        public static ParallelResult<Tet> parallelTetReconstruction(List<Long> indices, ParallelConfig config) {
            return parallelTransform(indices, index -> Tet.tetrahedron(index), config);
        }
        
        /**
         * Parallel spatial analysis for tetrahedral datasets
         */
        public static ParallelResult<TetrahedralStats> parallelTetrahedralAnalysis(
                List<Long> tetIndices, ParallelConfig config) {
            
            long startTime = System.nanoTime();
            
            if (tetIndices.size() < config.minDataSizeForParallel) {
                TetrahedralStats stats = analyzeTetrahedral(tetIndices);
                long endTime = System.nanoTime();
                return new ParallelResult<>(List.of(stats), endTime - startTime, 1, 1, false, null);
            }
            
            // Parallelize statistical analysis
            try {
                List<List<Long>> chunks = partitionData(tetIndices, config.workChunkSize);
                ExecutorService executor = config.enableWorkStealing ? 
                    ForkJoinPool.commonPool() :
                    Executors.newFixedThreadPool(config.threadPoolSize);
                
                List<CompletableFuture<TetrahedralStats>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> analyzeTetrahedral(chunk), executor))
                    .collect(Collectors.toList());
                
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
                
                allFutures.get(config.timeoutMillis, TimeUnit.MILLISECONDS);
                
                // Merge statistics from all chunks
                TetrahedralStats mergedStats = futures.stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new RuntimeException("Chunk analysis failed", e);
                        }
                    })
                    .reduce(TetrahedralStats::merge)
                    .orElse(new TetrahedralStats());
                
                long endTime = System.nanoTime();
                return new ParallelResult<>(List.of(mergedStats), endTime - startTime, 
                    Math.min(chunks.size(), config.threadPoolSize), chunks.size(), false, null);
                
            } catch (Exception e) {
                long endTime = System.nanoTime();
                return new ParallelResult<>(Collections.emptyList(), endTime - startTime, 
                    0, 0, false, e);
            }
        }
    }

    /**
     * Helper class for distance-simplex pairs used in k-NN queries
     */
    private static class DistanceSimplexPair<Content> {
        final float distance;
        final Tetree.Simplex<Content> simplex;
        
        DistanceSimplexPair(float distance, Tetree.Simplex<Content> simplex) {
            this.distance = distance;
            this.simplex = simplex;
        }
    }

    /**
     * Statistics for tetrahedral dataset analysis
     */
    public static class TetrahedralStats {
        public long count;
        public float minX, minY, minZ;
        public float maxX, maxY, maxZ;
        public double avgVolume;
        public Map<Integer, Long> typeDistribution = new HashMap<>();
        
        public TetrahedralStats() {
            this.minX = this.minY = this.minZ = Float.MAX_VALUE;
            this.maxX = this.maxY = this.maxZ = Float.MIN_VALUE;
        }
        
        public TetrahedralStats merge(TetrahedralStats other) {
            TetrahedralStats merged = new TetrahedralStats();
            merged.count = this.count + other.count;
            merged.minX = Math.min(this.minX, other.minX);
            merged.minY = Math.min(this.minY, other.minY);
            merged.minZ = Math.min(this.minZ, other.minZ);
            merged.maxX = Math.max(this.maxX, other.maxX);
            merged.maxY = Math.max(this.maxY, other.maxY);
            merged.maxZ = Math.max(this.maxZ, other.maxZ);
            
            // Merge type distribution
            merged.typeDistribution.putAll(this.typeDistribution);
            other.typeDistribution.forEach((type, count) -> 
                merged.typeDistribution.merge(type, count, Long::sum));
            
            // Weighted average for volume
            if (merged.count > 0) {
                merged.avgVolume = (this.avgVolume * this.count + other.avgVolume * other.count) / merged.count;
            }
            
            return merged;
        }
        
        @Override
        public String toString() {
            return String.format("TetrahedralStats[count=%d, bounds=(%.2f,%.2f,%.2f)-(%.2f,%.2f,%.2f), avgVolume=%.6f, types=%s]",
                count, minX, minY, minZ, maxX, maxY, maxZ, avgVolume, typeDistribution);
        }
    }

    /**
     * Performance monitoring for parallel operations
     */
    public static class TetParallelPerformanceMonitor {
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
            int successfulExecutions = (int) executionHistory.stream().filter(ParallelResult::isSuccessful).count();
            double avgExecutionTimeMs = executionHistory.stream()
                .mapToLong(r -> r.executionTimeNanos)
                .average().orElse(0.0) / 1_000_000.0;
            double avgThroughput = executionHistory.stream()
                .mapToDouble(ParallelResult::getThroughputItemsPerSecond)
                .average().orElse(0.0);
            double avgThreadsUsed = executionHistory.stream()
                .mapToInt(r -> r.threadsUsed)
                .average().orElse(0.0);
            int timeouts = (int) executionHistory.stream().filter(r -> r.timedOut).count();
            int errors = (int) executionHistory.stream().filter(r -> r.error != null).count();
            
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
                return String.format("TetParallelStats[executions=%d, success=%.1f%%, avgTime=%.2fms, " +
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
    
    private static float estimateSearchRadiusForKNN(int k) {
        // Heuristic for estimating search radius based on k
        // This could be improved with tetree statistics
        return Constants.MAX_EXTENT * 0.1f * (float) Math.sqrt(k);
    }
    
    private static TetrahedralStats analyzeTetrahedral(List<Long> tetIndices) {
        TetrahedralStats stats = new TetrahedralStats();
        
        for (Long index : tetIndices) {
            Tet tet = Tet.tetrahedron(index);
            Point3i[] vertices = tet.coordinates();
            
            stats.count++;
            
            // Update bounds
            for (Point3i vertex : vertices) {
                stats.minX = Math.min(stats.minX, vertex.x);
                stats.minY = Math.min(stats.minY, vertex.y);
                stats.minZ = Math.min(stats.minZ, vertex.z);
                stats.maxX = Math.max(stats.maxX, vertex.x);
                stats.maxY = Math.max(stats.maxY, vertex.y);
                stats.maxZ = Math.max(stats.maxZ, vertex.z);
            }
            
            // Update type distribution
            stats.typeDistribution.merge((int) tet.type(), 1L, Long::sum);
            
            // Calculate volume (simplified - actual tetrahedral volume calculation would be more complex)
            // For now, use bounding box volume as approximation
            float dx = stats.maxX - stats.minX;
            float dy = stats.maxY - stats.minY;
            float dz = stats.maxZ - stats.minZ;
            stats.avgVolume += (dx * dy * dz) / stats.count;
        }
        
        return stats;
    }
}