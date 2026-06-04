/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * High-performance parallel bulk operations for spatial indices. Implements concurrent processing with optimized
 * locking strategies.
 *
 * Key features: - Concurrent Morton code preprocessing - Lock-free entity grouping by spatial regions - Parallel
 * insertion with minimized lock contention - Work-stealing task distribution - NUMA-aware processing for large
 * datasets
 *
 * @param <ID>       The type of EntityID used
 * @param <Content>  The type of content stored
 * @param <NodeType> The type of spatial node used by the implementation
 * @author hal.hildebrand
 */
public class ParallelBulkOperations<Key extends SpatialKey<Key>, ID extends EntityID, Content>
implements AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(ParallelBulkOperations.class);

    // Dependencies
    private final AbstractSpatialIndex<Key, ID, Content> spatialIndex;
    private final BulkOperationProcessor<Key, ID, Content>         bulkProcessor;
    private final ParallelConfig                                   config;
    // Thread pools
    private final ForkJoinPool                                     workStealingPool;
    private final ExecutorService                                  fixedThreadPool;
    // Spatial partitioning for reduced lock contention
    private final Map<Key, SpatialRegion>                          spatialRegions      = new ConcurrentHashMap<>();
    private final int                                              regionPartitionBits = 6; // 64 regions per dimension
    // Performance tracking
    private final Map<String, Long>                                operationTimings    = new ConcurrentHashMap<>();
    private final Map<String, Integer>                             operationCounts     = new ConcurrentHashMap<>();
    private volatile Clock                                         clock               = Clock.system(); // Luciferase-mt7hi

    public ParallelBulkOperations(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                                  BulkOperationProcessor<Key, ID, Content> bulkProcessor, ParallelConfig config) {
        this.spatialIndex = spatialIndex;
        this.bulkProcessor = bulkProcessor;
        this.config = config;

        // Initialize thread pools
        if (config.isUseWorkStealing()) {
            this.workStealingPool = new ForkJoinPool(config.getThreadCount());
            this.fixedThreadPool = null;
        } else {
            this.workStealingPool = null;
            // Daemon threads so stray pool threads never block JVM exit (Luciferase-7wzml.60)
            var daemonFactory = new DaemonThreadFactory("lucien-parallel");
            this.fixedThreadPool = Executors.newFixedThreadPool(config.getThreadCount(), daemonFactory);
        }
    }

    /**
     * Create default parallel configuration
     */
    public static ParallelConfig defaultConfig() {
        return new ParallelConfig();
    }

    /**
     * Create high-performance parallel configuration
     */
    public static ParallelConfig highPerformanceConfig() {
        return new ParallelConfig().withThreadCount(Runtime.getRuntime().availableProcessors() * 2)
                                   .withBatchSize(500)
                                   .withWorkStealing(true)
                                   .withTaskThreshold(50);
    }

    /**
     * Create configuration optimized for large datasets
     */
    public static ParallelConfig largeDatasetConfig() {
        return new ParallelConfig().withThreadCount(Runtime.getRuntime().availableProcessors())
                                   .withBatchSize(2000)
                                   .withWorkStealing(true)
                                   .withNUMAOptimization(true)
                                   .withTaskThreshold(200);
    }

    /** Inject a deterministic clock for tests (Luciferase-mt7hi). Defaults to {@code Clock.system()}. */
    public ParallelBulkOperations<Key, ID, Content> setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Get performance statistics
     */
    public Map<String, Object> getPerformanceStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("spatialRegions", spatialRegions.size());
        stats.put("threadCount", config.getThreadCount());
        stats.put("useWorkStealing", config.isUseWorkStealing());
        stats.put("operationTimings", new HashMap<>(operationTimings));
        stats.put("operationCounts", new HashMap<>(operationCounts));
        return stats;
    }

    /**
     * Perform parallel bulk insertion with optimized batch operations
     */
    public ParallelOperationResult<ID> insertBatchParallel(List<Point3f> positions, List<Content> contents, byte level)
    throws InterruptedException {
        long startTime = clock.currentTimeMillis();

        // Validate inputs
        if (positions.size() != contents.size()) {
            throw new IllegalArgumentException("Positions and contents lists must have the same size");
        }

        Map<String, Long> timings = new ConcurrentHashMap<>();
        Map<String, Integer> statistics = new ConcurrentHashMap<>();
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
        List<ID> allInsertedIds = Collections.synchronizedList(new ArrayList<>());

        // For small datasets, use single-threaded batch insertion
        if (positions.size() < config.getThreadCount() * config.getTaskThreshold()) {
            try {
                long singleThreadStart = clock.currentTimeMillis();
                List<ID> ids = spatialIndex.insertBatch(positions, contents, level);
                allInsertedIds.addAll(ids);
                timings.put("singleThreadedBatch", clock.currentTimeMillis() - singleThreadStart);
                statistics.put("totalEntities", positions.size());
                statistics.put("partitionCount", 1);
                statistics.put("threadsUsed", 1);
                statistics.put("successfulInsertions", ids.size());
                statistics.put("errors", 0);
            } catch (Exception e) {
                errors.add(e);
            }
            timings.put("total", clock.currentTimeMillis() - startTime);
            return new ParallelOperationResult<>(allInsertedIds, timings, statistics, errors);
        }

        try {
            // Phase 1: Parallel Morton code preprocessing (keep this parallel)
            long preprocessStart = clock.currentTimeMillis();
            List<BulkOperationProcessor.SfcEntity<Key, Content>> mortonEntities = preprocessParallel(positions,
                                                                                                     contents, level);
            timings.put("preprocessing", clock.currentTimeMillis() - preprocessStart);

            // Phase 2: Adaptive spatial partitioning
            long partitionStart = clock.currentTimeMillis();
            var partitionedEntities = adaptivePartitioning(mortonEntities, level);
            timings.put("partitioning", clock.currentTimeMillis() - partitionStart);

            // Phase 3: Parallel batch insertion with coarse-grained locking
            long insertionStart = clock.currentTimeMillis();
            insertPartitionsParallel(partitionedEntities, level, allInsertedIds, errors);
            timings.put("insertion", clock.currentTimeMillis() - insertionStart);

            // Collect statistics
            statistics.put("totalEntities", positions.size());
            statistics.put("partitionCount", partitionedEntities.size());
            statistics.put("threadsUsed", config.getThreadCount());
            statistics.put("successfulInsertions", allInsertedIds.size());
            statistics.put("errors", errors.size());

        } catch (Exception e) {
            errors.add(e);
        }

        timings.put("total", clock.currentTimeMillis() - startTime);

        return new ParallelOperationResult<>(allInsertedIds, timings, statistics, errors);
    }

    /**
     * Batch remove. Runs serially under one global write-lock critical section so the batch is atomic versus
     * concurrent readers that take the global read lock (range queries, collision, {@code entityCount}). Despite the
     * {@code Parallel} name (kept for API shape), the mutation is serial — writes already serialize on the single
     * global lock. Note: kNN uses an independent fine-grained locking strategy and is NOT excluded by this lock
     * (tracked separately, Luciferase-us4zr).
     */
    public CompletableFuture<Integer> removeBatchParallel(List<ID> entityIds) {
        // Luciferase-aqx6x: the whole batch removes under a single write-lock critical section, serially. The
        // previous per-entity parallel removes raced for spanning entities that share nodes, and exposed partial
        // batch state to concurrent range/kNN/collision readers. One critical section makes the batch atomic vs
        // readers (they observe the index before or after the batch, never mid-batch).
        return CompletableFuture.supplyAsync(() -> {
            int removed = 0;
            spatialIndex.lock.writeLock().lock();
            try {
                for (ID id : entityIds) {
                    try {
                        if (spatialIndex.removeEntity(id)) {
                            removed++;
                        }
                    } catch (Exception e) {
                        // skip individual failures, preserving the prior best-effort contract
                    }
                }
            } finally {
                spatialIndex.lock.writeLock().unlock();
            }
            return removed;
        }, getExecutor());
    }

    /**
     * AutoCloseable entry point — shuts down pools and waits for orderly termination (Luciferase-7wzml.60).
     * Suitable for try-with-resources.
     */
    @Override
    public void close() {
        shutdown();
        // Await termination with a reasonable timeout so callers get deterministic cleanup
        try {
            if (workStealingPool != null) {
                workStealingPool.awaitTermination(5, TimeUnit.SECONDS);
            }
            if (fixedThreadPool != null) {
                fixedThreadPool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while awaiting parallel-operations pool termination");
        }
    }

    /**
     * Clean up resources (initiates shutdown without waiting; use {@link #close()} for orderly termination).
     */
    public void shutdown() {
        if (workStealingPool != null && !workStealingPool.isShutdown()) {
            workStealingPool.shutdown();
        }
        if (fixedThreadPool != null && !fixedThreadPool.isShutdown()) {
            fixedThreadPool.shutdown();
        }
        spatialRegions.clear();
    }

    /**
     * Batch update each entity to a new position, preserving its entity ID. Runs serially under one global write-lock
     * critical section so the batch is atomic versus concurrent readers that take the global read lock (range
     * queries, collision, {@code entityCount}); kNN's independent fine-grained path is not excluded
     * (Luciferase-us4zr). Despite the {@code Parallel} name, the mutation is serial.
     *
     * <p><b>ID semantics:</b> uses {@code updateEntity} which moves each entity in-place; the entity ID is
     * preserved across the call. The returned list contains the same IDs as the input list (in the same order,
     * skipping any entity that was absent or threw). Callers' held IDs remain valid after this call
     * (Luciferase-7wzml.61).
     */
    public CompletableFuture<List<ID>> updateBatchParallel(List<ID> entityIds, List<Point3f> newPositions, byte level) {
        if (entityIds.size() != newPositions.size()) {
            throw new IllegalArgumentException("Entity IDs and positions lists must have the same size");
        }

        // Luciferase-aqx6x: hold ONE write-lock critical section across the whole batch so the update is atomic
        // versus concurrent readers. The write lock is reentrant; spatialIndex.updateEntity() re-acquires it safely.
        // Luciferase-7wzml.61: use updateEntity (id-preserving) instead of the old remove+insert pattern which
        // silently assigned new IDs, invalidating every caller-held ID after the batch.
        return CompletableFuture.supplyAsync(() -> {
            var updatedIds = new ArrayList<ID>(entityIds.size());
            spatialIndex.lock.writeLock().lock();
            try {
                for (int i = 0; i < entityIds.size(); i++) {
                    try {
                        ID id = entityIds.get(i);
                        Point3f newPos = newPositions.get(i);
                        spatialIndex.updateEntity(id, newPos, level);
                        updatedIds.add(id);
                    } catch (Exception e) {
                        // skip individual failures, preserving the prior best-effort contract
                        log.debug("updateBatchParallel: skipping entity {} — {}", entityIds.get(i), e.getMessage());
                    }
                }
            } finally {
                spatialIndex.lock.writeLock().unlock();
            }
            return updatedIds;
        }, getExecutor());
    }

    /**
     * Adaptive partitioning based on entity count and distribution
     */
    private Map<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>> adaptivePartitioning(
    List<BulkOperationProcessor.SfcEntity<Key, Content>> entities, byte level) {

        // Calculate optimal partition count based on entity count and thread count
        int optimalPartitions = Math.min(config.getThreadCount() * 2,  // Some oversubscription for load balancing
                                         Math.max(1, entities.size() / config.getBatchSize())
                                         // Ensure reasonable batch sizes
                                        );

        // For sorted Morton entities, partition by ranges for better spatial locality
        Map<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>> partitions = new LinkedHashMap<>();
        int entitiesPerPartition = Math.max(1, entities.size() / optimalPartitions);

        for (int i = 0; i < optimalPartitions; i++) {
            int startIdx = i * entitiesPerPartition;
            int endIdx = (i == optimalPartitions - 1) ? entities.size() : Math.min((i + 1) * entitiesPerPartition,
                                                                                   entities.size());

            if (startIdx < entities.size()) {
                List<BulkOperationProcessor.SfcEntity<Key, Content>> partition = entities.subList(startIdx, endIdx);
                if (!partition.isEmpty()) {
                    // Use the first entity's Morton code as partition key
                    partitions.put(partition.get(0).sfcIndex, partition);
                }
            }
        }

        return partitions;
    }

    /**
     * Calculate spatial region ID for an entity position
     */
    private long calculateSpatialRegion(Point3f position, byte level) {
        // Quantize position to region grid
        long x = (long) (position.x * (1L << regionPartitionBits)) >> regionPartitionBits;
        long y = (long) (position.y * (1L << regionPartitionBits)) >> regionPartitionBits;
        long z = (long) (position.z * (1L << regionPartitionBits)) >> regionPartitionBits;

        // Combine coordinates into region ID
        return (x << 24) | (y << 12) | z;
    }

    /**
     * Get the appropriate executor service
     */
    private ExecutorService getExecutor() {
        return config.isUseWorkStealing() ? workStealingPool : fixedThreadPool;
    }

    /**
     * Insert partitions in parallel with region-based locking
     */
    private void insertPartitionsParallel(
    Map<Key, List<BulkOperationProcessor.SfcEntity<Key, Content>>> partitionedEntities, byte level,
    List<ID> allInsertedIds, List<Exception> errors) throws InterruptedException {

        ExecutorService executor = getExecutor();

        var futures = partitionedEntities.entrySet().parallelStream().map(entry -> {
            var regionId = entry.getKey();
            List<BulkOperationProcessor.SfcEntity<Key, Content>> entities = entry.getValue();

            return executor.submit(() -> insertRegionEntities(regionId, entities, level));
        }).collect(Collectors.toList());

        // Collect results
        for (Future<List<ID>> future : futures) {
            try {
                List<ID> regionIds = future.get(config.getLockTimeoutMs(), TimeUnit.MILLISECONDS);
                allInsertedIds.addAll(regionIds);
            } catch (ExecutionException | TimeoutException e) {
                errors.add(new RuntimeException("Region insertion failed", e));
            }
        }
    }

    /**
     * Insert entities for a specific spatial region using true batch operations
     */
    private List<ID> insertRegionEntities(Key regionId, List<BulkOperationProcessor.SfcEntity<Key, Content>> entities,
                                          byte level) {
        // Extract positions and contents for batch insertion
        List<Point3f> positions = new ArrayList<>(entities.size());
        List<Content> contents = new ArrayList<>(entities.size());

        for (BulkOperationProcessor.SfcEntity<Key, Content> entity : entities) {
            positions.add(entity.position);
            contents.add(entity.content);
        }

        // Use coarse-grained locking for the entire batch operation
        // This is much more efficient than fine-grained per-entity locking
        try {
            // Use the spatial index's batch insertion method
            // This allows the index to optimize the insertion process
            List<ID> insertedIds = spatialIndex.insertBatch(positions, contents, level);

            // Track the spatial region for statistics
            SpatialRegion region = spatialRegions.computeIfAbsent(regionId, SpatialRegion::new);
            for (BulkOperationProcessor.SfcEntity<Key, Content> entity : entities) {
                region.nodeIndices.add(entity.sfcIndex);
            }

            return insertedIds;
        } catch (Exception e) {
            // Log error and return empty list
            log.error("Failed to insert batch in region {}: {}", regionId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Parallel Morton code preprocessing with optimized batching
     */
    private List<BulkOperationProcessor.SfcEntity<Key, Content>> preprocessParallel(List<Point3f> positions,
                                                                                    List<Content> contents, byte level)
    throws InterruptedException {

        // Use the bulk processor's parallel preprocessing which already handles this efficiently
        return bulkProcessor.preprocessBatchParallel(positions, contents, level, true, config.getTaskThreshold());
    }

    /**
     * Configuration for parallel processing
     */
    public static class ParallelConfig {
        private int     threadCount            = Runtime.getRuntime().availableProcessors();
        private int     batchSize              = 1000;
        private int     taskThreshold          = 100; // Minimum entities per task
        private boolean useWorkStealing        = true;
        private boolean enableNUMAOptimization = false;
        private long    lockTimeoutMs          = 5000;

        public int getBatchSize() {
            return batchSize;
        }

        public long getLockTimeoutMs() {
            return lockTimeoutMs;
        }

        public int getTaskThreshold() {
            return taskThreshold;
        }

        // Getters
        public int getThreadCount() {
            return threadCount;
        }

        public boolean isEnableNUMAOptimization() {
            return enableNUMAOptimization;
        }

        public boolean isUseWorkStealing() {
            return useWorkStealing;
        }

        public ParallelConfig withBatchSize(int size) {
            this.batchSize = Math.max(1, size);
            return this;
        }

        public ParallelConfig withLockTimeout(long timeoutMs) {
            this.lockTimeoutMs = Math.max(100, timeoutMs);
            return this;
        }

        public ParallelConfig withNUMAOptimization(boolean enable) {
            this.enableNUMAOptimization = enable;
            return this;
        }

        public ParallelConfig withTaskThreshold(int threshold) {
            this.taskThreshold = Math.max(1, threshold);
            return this;
        }

        public ParallelConfig withThreadCount(int count) {
            this.threadCount = Math.max(1, count);
            return this;
        }

        public ParallelConfig withWorkStealing(boolean enable) {
            this.useWorkStealing = enable;
            return this;
        }
    }

    /**
     * Result of parallel bulk operation
     */
    public static class ParallelOperationResult<ID extends EntityID> {
        private final List<ID>             insertedIds;
        private final Map<String, Long>    timings;
        private final Map<String, Integer> statistics;
        private final List<Exception>      errors;

        public ParallelOperationResult(List<ID> insertedIds, Map<String, Long> timings, Map<String, Integer> statistics,
                                       List<Exception> errors) {
            this.insertedIds = insertedIds;
            this.timings = timings;
            this.statistics = statistics;
            this.errors = errors;
        }

        public List<Exception> getErrors() {
            return errors;
        }

        public List<ID> getInsertedIds() {
            return insertedIds;
        }

        public Map<String, Integer> getStatistics() {
            return statistics;
        }

        public double getThroughput() {
            long totalTime = getTotalTime();
            return totalTime > 0 ? (insertedIds.size() * 1000.0) / totalTime : 0.0;
        }

        public Map<String, Long> getTimings() {
            return timings;
        }

        public long getTotalTime() {
            return timings.values().stream().mapToLong(Long::longValue).sum();
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * ThreadFactory that creates daemon threads so stray pool threads never pin JVM exit (Luciferase-7wzml.60).
     */
    private static class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String namePrefix;
        private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(
        1);

        DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            var t = new Thread(r, namePrefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Entity batch for parallel processing
     */
    private static class EntityBatch<Content> {
        final List<Point3f> positions;
        final List<Content> contents;
        final byte          level;
        final int           startIndex;
        final int           endIndex;

        EntityBatch(List<Point3f> positions, List<Content> contents, byte level, int startIndex, int endIndex) {
            this.positions = positions;
            this.contents = contents;
            this.level = level;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }

        List<Content> getContents() {
            return contents.subList(startIndex, endIndex);
        }

        List<Point3f> getPositions() {
            return positions.subList(startIndex, endIndex);
        }

        int size() {
            return endIndex - startIndex;
        }
    }

    /**
     * Spatial region for lock partitioning
     */
    private static class SpatialRegion<Key extends SpatialKey<Key>> {
        final Key      regionId;
        final Set<Key> nodeIndices;
        final Object   lock = new Object();

        SpatialRegion(Key regionId) {
            this.regionId = regionId;
            this.nodeIndices = ConcurrentHashMap.newKeySet();
        }
    }
}
