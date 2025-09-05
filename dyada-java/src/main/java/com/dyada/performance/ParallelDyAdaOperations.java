package com.dyada.performance;

import java.util.concurrent.*;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.BinaryOperator;

/**
 * Parallel processing utilities for DyAda bulk operations
 * Uses ForkJoinPool and parallel streams for maximum throughput
 */
public final class ParallelDyAdaOperations {
    
    private static final int PARALLEL_THRESHOLD = 1000;
    private static final ForkJoinPool WORK_STEALING_POOL;
    
    static {
        int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
        WORK_STEALING_POOL = new ForkJoinPool(
            parallelism,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null,
            true // Enable async mode for better throughput
        );
    }
    
    /**
     * Parallel Morton encoding for large coordinate arrays
     */
    public static long[] encodeMorton2DParallel(int[] xCoords, int[] yCoords) {
        if (xCoords.length != yCoords.length) {
            throw new IllegalArgumentException("Coordinate arrays must have same length");
        }
        
        int length = xCoords.length;
        if (length < PARALLEL_THRESHOLD) {
            return encodeMorton2DSequential(xCoords, yCoords);
        }
        
        return IntStream.range(0, length)
            .parallel()
            .mapToLong(i -> MortonOptimizer.encode2D(xCoords[i], yCoords[i]))
            .toArray();
    }
    
    /**
     * Parallel Morton encoding for 3D coordinates
     */
    public static long[] encodeMorton3DParallel(int[] xCoords, int[] yCoords, int[] zCoords) {
        if (xCoords.length != yCoords.length || yCoords.length != zCoords.length) {
            throw new IllegalArgumentException("Coordinate arrays must have same length");
        }
        
        int length = xCoords.length;
        if (length < PARALLEL_THRESHOLD) {
            return encodeMorton3DSequential(xCoords, yCoords, zCoords);
        }
        
        return IntStream.range(0, length)
            .parallel()
            .mapToLong(i -> MortonOptimizer.encode3D(xCoords[i], yCoords[i], zCoords[i]))
            .toArray();
    }
    
    /**
     * Parallel Morton decoding for large arrays
     */
    public static void decodeMorton2DParallel(long[] mortonCodes, int[] xCoords, int[] yCoords) {
        if (mortonCodes.length != xCoords.length || xCoords.length != yCoords.length) {
            throw new IllegalArgumentException("Arrays must have same length");
        }
        
        int length = mortonCodes.length;
        if (length < PARALLEL_THRESHOLD) {
            decodeMorton2DSequential(mortonCodes, xCoords, yCoords);
            return;
        }
        
        IntStream.range(0, length)
            .parallel()
            .forEach(i -> {
                xCoords[i] = MortonOptimizer.decodeX2D(mortonCodes[i]);
                yCoords[i] = MortonOptimizer.decodeY2D(mortonCodes[i]);
            });
    }
    
    /**
     * Parallel BitArray operations
     */
    public static OptimizedBitArray parallelBitwiseAnd(List<OptimizedBitArray> bitArrays) {
        if (bitArrays.isEmpty()) {
            throw new IllegalArgumentException("Cannot AND empty list");
        }
        
        int size = bitArrays.get(0).size();
        if (bitArrays.stream().anyMatch(ba -> ba.size() != size)) {
            throw new IllegalArgumentException("All BitArrays must have same size");
        }
        
        // Use parallel reduction for large arrays
        return bitArrays.parallelStream()
            .reduce(OptimizedBitArray::and)
            .orElseThrow();
    }
    
    /**
     * Parallel bit counting across multiple BitArrays
     */
    public static int[] parallelCardinality(List<OptimizedBitArray> bitArrays) {
        return bitArrays.parallelStream()
            .mapToInt(OptimizedBitArray::cardinality)
            .toArray();
    }
    
    /**
     * Parallel spatial range queries using ForkJoin
     */
    public static class ParallelRangeQuery extends RecursiveTask<List<Long>> {
        private final long startMorton;
        private final long endMorton;
        private final LongFunction<Boolean> predicate;
        private final int threshold;
        
        public ParallelRangeQuery(long startMorton, long endMorton, 
                                LongFunction<Boolean> predicate, int threshold) {
            this.startMorton = startMorton;
            this.endMorton = endMorton;
            this.predicate = predicate;
            this.threshold = threshold;
        }
        
        @Override
        protected List<Long> compute() {
            long range = endMorton - startMorton + 1;
            
            if (range <= threshold) {
                // Process range sequentially
                var result = new ArrayList<Long>();
                for (long morton = startMorton; morton <= endMorton; morton++) {
                    if (predicate.apply(morton)) {
                        result.add(morton);
                    }
                }
                return result;
            } else {
                // Split range and process in parallel
                long mid = startMorton + range / 2;
                
                var leftTask = new ParallelRangeQuery(startMorton, mid - 1, predicate, threshold);
                var rightTask = new ParallelRangeQuery(mid, endMorton, predicate, threshold);
                
                leftTask.fork();
                var rightResult = rightTask.compute();
                var leftResult = leftTask.join();
                
                // Merge results
                var combined = new ArrayList<>(leftResult);
                combined.addAll(rightResult);
                return combined;
            }
        }
    }
    
    /**
     * Execute parallel range query
     */
    public static List<Long> executeParallelRangeQuery(long startMorton, long endMorton, 
                                                     LongFunction<Boolean> predicate) {
        var task = new ParallelRangeQuery(startMorton, endMorton, predicate, 10000);
        return WORK_STEALING_POOL.invoke(task);
    }
    
    /**
     * Parallel bulk transformation
     */
    public static <T, R> List<R> parallelTransform(List<T> input, Function<T, R> transformer) {
        if (input.size() < PARALLEL_THRESHOLD) {
            return input.stream().map(transformer).toList();
        }
        
        return input.parallelStream()
            .map(transformer)
            .toList();
    }
    
    /**
     * Parallel coordinate transformation
     */
    public static record Point2D(double x, double y) {}
    public static record Point3D(double x, double y, double z) {}
    
    public static Point2D[] transformCoordinates2D(Point2D[] points, 
                                                  Function<Point2D, Point2D> transform) {
        if (points.length < PARALLEL_THRESHOLD) {
            return java.util.Arrays.stream(points)
                .map(transform)
                .toArray(Point2D[]::new);
        }
        
        return java.util.Arrays.stream(points)
            .parallel()
            .map(transform)
            .toArray(Point2D[]::new);
    }
    
    /**
     * Parallel spatial partitioning
     */
    public static class ParallelSpatialPartitioner<T> extends RecursiveTask<List<List<T>>> {
        private final List<T> items;
        private final Function<T, Point2D> coordinateExtractor;
        private final double minX, minY, maxX, maxY;
        private final int maxDepth;
        private final int currentDepth;
        private final int threshold;
        
        public ParallelSpatialPartitioner(List<T> items, Function<T, Point2D> coordinateExtractor,
                                        double minX, double minY, double maxX, double maxY,
                                        int maxDepth, int threshold) {
            this(items, coordinateExtractor, minX, minY, maxX, maxY, maxDepth, 0, threshold);
        }
        
        private ParallelSpatialPartitioner(List<T> items, Function<T, Point2D> coordinateExtractor,
                                         double minX, double minY, double maxX, double maxY,
                                         int maxDepth, int currentDepth, int threshold) {
            this.items = items;
            this.coordinateExtractor = coordinateExtractor;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxDepth = maxDepth;
            this.currentDepth = currentDepth;
            this.threshold = threshold;
        }
        
        @Override
        protected List<List<T>> compute() {
            if (items.size() <= threshold || currentDepth >= maxDepth) {
                return List.of(items);
            }
            
            double midX = (minX + maxX) / 2;
            double midY = (minY + maxY) / 2;
            
            var quadrants = new ArrayList<List<T>>(4);
            for (int i = 0; i < 4; i++) {
                quadrants.add(new ArrayList<>());
            }
            
            for (var item : items) {
                var point = coordinateExtractor.apply(item);
                int quadrant = ((point.x() >= midX) ? 1 : 0) + ((point.y() >= midY) ? 2 : 0);
                quadrants.get(quadrant).add(item);
            }
            
            var tasks = new ArrayList<ParallelSpatialPartitioner<T>>();
            
            // Create subtasks for non-empty quadrants
            if (!quadrants.get(0).isEmpty()) {
                tasks.add(new ParallelSpatialPartitioner<>(quadrants.get(0), coordinateExtractor,
                    minX, minY, midX, midY, maxDepth, currentDepth + 1, threshold));
            }
            if (!quadrants.get(1).isEmpty()) {
                tasks.add(new ParallelSpatialPartitioner<>(quadrants.get(1), coordinateExtractor,
                    midX, minY, maxX, midY, maxDepth, currentDepth + 1, threshold));
            }
            if (!quadrants.get(2).isEmpty()) {
                tasks.add(new ParallelSpatialPartitioner<>(quadrants.get(2), coordinateExtractor,
                    minX, midY, midX, maxY, maxDepth, currentDepth + 1, threshold));
            }
            if (!quadrants.get(3).isEmpty()) {
                tasks.add(new ParallelSpatialPartitioner<>(quadrants.get(3), coordinateExtractor,
                    midX, midY, maxX, maxY, maxDepth, currentDepth + 1, threshold));
            }
            
            if (tasks.size() <= 1) {
                return tasks.isEmpty() ? List.of() : tasks.get(0).compute();
            }
            
            // Fork all tasks except the last one
            for (int i = 0; i < tasks.size() - 1; i++) {
                tasks.get(i).fork();
            }
            
            // Compute last task directly
            var results = new ArrayList<List<T>>();
            results.addAll(tasks.get(tasks.size() - 1).compute());
            
            // Join all forked tasks
            for (int i = 0; i < tasks.size() - 1; i++) {
                results.addAll(tasks.get(i).join());
            }
            
            return results;
        }
    }
    
    /**
     * Execute parallel spatial partitioning
     */
    public static <T> List<List<T>> partitionSpatially(List<T> items, 
                                                     Function<T, Point2D> coordinateExtractor,
                                                     double minX, double minY, double maxX, double maxY,
                                                     int maxDepth) {
        var task = new ParallelSpatialPartitioner<>(items, coordinateExtractor, 
            minX, minY, maxX, maxY, maxDepth, 1000);
        return WORK_STEALING_POOL.invoke(task);
    }
    
    /**
     * Parallel aggregation with work stealing
     */
    public static <T, R> R parallelAggregate(List<T> data, 
                                           Function<T, R> mapper,
                                           R identity,
                                           BinaryOperator<R> combiner) {
        if (data.size() < PARALLEL_THRESHOLD) {
            return data.stream()
                .map(mapper)
                .reduce(identity, combiner);
        }
        
        return data.parallelStream()
            .map(mapper)
            .reduce(identity, combiner);
    }
    
    // Sequential fallback methods
    private static long[] encodeMorton2DSequential(int[] xCoords, int[] yCoords) {
        var result = new long[xCoords.length];
        for (int i = 0; i < xCoords.length; i++) {
            result[i] = MortonOptimizer.encode2D(xCoords[i], yCoords[i]);
        }
        return result;
    }
    
    private static long[] encodeMorton3DSequential(int[] xCoords, int[] yCoords, int[] zCoords) {
        var result = new long[xCoords.length];
        for (int i = 0; i < xCoords.length; i++) {
            result[i] = MortonOptimizer.encode3D(xCoords[i], yCoords[i], zCoords[i]);
        }
        return result;
    }
    
    private static void decodeMorton2DSequential(long[] mortonCodes, int[] xCoords, int[] yCoords) {
        for (int i = 0; i < mortonCodes.length; i++) {
            xCoords[i] = MortonOptimizer.decodeX2D(mortonCodes[i]);
            yCoords[i] = MortonOptimizer.decodeY2D(mortonCodes[i]);
        }
    }
    
    /**
     * Get work stealing pool statistics
     */
    public static String getPoolStats() {
        return String.format(
            "ForkJoinPool Stats: %d threads, %d active, %d queued, %d stolen",
            WORK_STEALING_POOL.getParallelism(),
            WORK_STEALING_POOL.getActiveThreadCount(),
            WORK_STEALING_POOL.getQueuedTaskCount(),
            WORK_STEALING_POOL.getStealCount()
        );
    }
    
    /**
     * Shutdown the work stealing pool (for testing)
     */
    public static void shutdown() {
        WORK_STEALING_POOL.shutdown();
    }
}