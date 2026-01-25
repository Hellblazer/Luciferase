/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.balancing.TwoOneBalanceChecker.BalanceViolation;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Parallel 2:1 balance violation detector using virtual threads.
 * Checks ghost layer consistency across partitions concurrently.
 *
 * <p>This detector partitions ghost elements into chunks and processes them in parallel
 * using virtual threads. Results are aggregated thread-safely using a concurrent queue.
 *
 * <p>Usage:
 * <pre>
 * try (var detector = new ParallelViolationDetector&lt;&gt;(checker)) {
 *     var violations = detector.detectViolations(ghostLayer, forest);
 *     // Process violations...
 * }
 * </pre>
 *
 * @param <Key> spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> entity identifier type
 * @param <Content> entity content type
 *
 * @author hal.hildebrand
 */
public class ParallelViolationDetector<Key extends SpatialKey<Key>, ID extends EntityID, Content>
    implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ParallelViolationDetector.class);

    private final ExecutorService virtualThreadPool;
    private final TwoOneBalanceChecker<Key, ID, Content> checker;
    private final int parallelism;

    /**
     * Creates a new parallel violation detector with default parallelism.
     *
     * @param checker the balance checker to use for violation detection
     * @throws NullPointerException if checker is null
     */
    public ParallelViolationDetector(TwoOneBalanceChecker<Key, ID, Content> checker) {
        this(checker, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Creates a new parallel violation detector with custom parallelism.
     *
     * @param checker the balance checker to use for violation detection
     * @param parallelism the number of parallel tasks to run (must be > 0)
     * @throws NullPointerException if checker is null
     * @throws IllegalArgumentException if parallelism <= 0
     */
    public ParallelViolationDetector(TwoOneBalanceChecker<Key, ID, Content> checker, int parallelism) {
        this.checker = Objects.requireNonNull(checker, "Checker cannot be null");

        if (parallelism <= 0) {
            throw new IllegalArgumentException("Parallelism must be > 0, got: " + parallelism);
        }

        this.parallelism = parallelism;
        this.virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();

        log.debug("Created ParallelViolationDetector with parallelism={}", parallelism);
    }

    /**
     * Detects all 2:1 balance violations in the ghost layer using parallel processing.
     *
     * <p>Partitions ghost elements into chunks and processes them concurrently using virtual threads.
     * Results are aggregated thread-safely and returned as a single list.
     *
     * @param ghostLayer ghost elements to check (non-local boundary elements from adjacent partitions)
     * @param forest local forest containing local elements
     * @return list of violations found (empty if none)
     * @throws IllegalArgumentException if ghostLayer or forest is null
     */
    public List<BalanceViolation<Key>> detectViolations(
        GhostLayer<Key, ID, Content> ghostLayer,
        Forest<Key, ID, Content> forest
    ) {
        if (ghostLayer == null) {
            throw new IllegalArgumentException("ghostLayer cannot be null");
        }
        if (forest == null) {
            throw new IllegalArgumentException("forest cannot be null");
        }

        // Get all ghost elements
        var allGhostElements = ghostLayer.getAllGhostElements();

        log.debug("Detecting violations in {} ghost elements with parallelism={}",
                 allGhostElements.size(), parallelism);

        // Early exit for empty ghost layer
        if (allGhostElements.isEmpty()) {
            return List.of();
        }

        // Thread-safe result collection
        var violations = new ConcurrentLinkedQueue<BalanceViolation<Key>>();

        // Partition ghost elements into chunks
        var chunks = partitionGhostElements(allGhostElements, parallelism);

        log.debug("Partitioned {} ghost elements into {} chunks", allGhostElements.size(), chunks.size());

        // Create futures for each chunk
        var futures = new ArrayList<CompletableFuture<Void>>();

        for (var chunk : chunks) {
            var future = CompletableFuture.runAsync(() -> {
                try {
                    processChunk(chunk, forest, violations);
                } catch (Exception e) {
                    log.error("Failed to process ghost element chunk", e);
                    throw new CompletionException("Violation detection failed", e);
                }
            }, virtualThreadPool);

            futures.add(future);
        }

        // Wait for all tasks to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            log.error("Parallel violation detection failed", e);
            throw new RuntimeException("Violation detection failed", e.getCause());
        }

        log.debug("Detected {} violations across {} chunks", violations.size(), chunks.size());

        // Return aggregated results
        return new ArrayList<>(violations);
    }

    /**
     * Gets the configured parallelism level.
     *
     * @return the number of parallel tasks
     */
    public int getParallelism() {
        return parallelism;
    }

    /**
     * Checks if the detector has been shut down.
     *
     * @return true if the virtual thread pool is shutdown
     */
    public boolean isShutdown() {
        return virtualThreadPool.isShutdown();
    }

    /**
     * Shuts down the virtual thread pool.
     * Waits up to 5 seconds for graceful shutdown, then forces shutdown.
     */
    @Override
    public void close() {
        log.debug("Shutting down ParallelViolationDetector");

        virtualThreadPool.shutdown();

        try {
            if (!virtualThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Virtual thread pool did not terminate gracefully, forcing shutdown");
                virtualThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for virtual thread pool shutdown", e);
            virtualThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.debug("ParallelViolationDetector shutdown complete");
    }

    /**
     * Partitions ghost elements into approximately equal chunks for parallel processing.
     *
     * @param elements all ghost elements to partition
     * @param numChunks desired number of chunks
     * @return list of chunks (each chunk is a list of ghost elements)
     */
    private List<List<GhostElement<Key, ID, Content>>> partitionGhostElements(
        List<GhostElement<Key, ID, Content>> elements,
        int numChunks
    ) {
        var chunks = new ArrayList<List<GhostElement<Key, ID, Content>>>();

        if (elements.isEmpty()) {
            return chunks;
        }

        // Calculate chunk size (round up to ensure all elements are included)
        int chunkSize = (elements.size() + numChunks - 1) / numChunks;

        // Create chunks
        for (int i = 0; i < elements.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, elements.size());
            chunks.add(elements.subList(i, end));
        }

        return chunks;
    }

    /**
     * Processes a chunk of ghost elements and adds violations to the result queue.
     *
     * @param chunk ghost elements to process
     * @param forest local forest for violation checking
     * @param violations concurrent queue to collect violations
     */
    private void processChunk(
        List<GhostElement<Key, ID, Content>> chunk,
        Forest<Key, ID, Content> forest,
        ConcurrentLinkedQueue<BalanceViolation<Key>> violations
    ) {
        log.debug("Processing chunk of {} ghost elements", chunk.size());

        // Create a temporary ghost layer for this chunk
        // We need to use the checker's findViolations method which expects a GhostLayer
        var tempGhostLayer = new GhostLayer<Key, ID, Content>(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES);

        // Add chunk elements to temporary ghost layer
        for (var element : chunk) {
            tempGhostLayer.addGhostElement(element);
        }

        // Find violations for this chunk
        var chunkViolations = checker.findViolations(tempGhostLayer, forest);

        // Add to shared result queue (thread-safe)
        violations.addAll(chunkViolations);

        log.debug("Found {} violations in chunk", chunkViolations.size());
    }
}
