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
package com.hellblazer.luciferase.esvo.dag.cache;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.dag.pipeline.DAGPipelineAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Lazy background DAG compression with scheduling support.
 *
 * <p>Provides non-blocking compression operations:
 * <ul>
 * <li>Asynchronous compression with {@link Future} results</li>
 * <li>Scheduled compression with configurable delay</li>
 * <li>Graceful shutdown with pending task completion</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All operations are thread-safe. Multiple compressions can execute concurrently.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * var executor = Executors.newScheduledThreadPool(2);
 * var adapter = new DAGPipelineAdapter(config);
 * var compressor = new LazyDAGCompressor(executor, adapter);
 *
 * // Async compression
 * var future = compressor.compressAsync(esvoData);
 * var result = future.get();
 *
 * // Scheduled compression
 * compressor.scheduleCompression(esvoData, 1000, result -> {
 *     System.out.println("Compressed with ratio: " + result.getCompressionRatio());
 * });
 *
 * // Shutdown
 * compressor.shutdown();
 * compressor.awaitCompletion(5000);
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class LazyDAGCompressor {
    private static final Logger log = LoggerFactory.getLogger(LazyDAGCompressor.class);

    private final ScheduledExecutorService executor;
    private final DAGPipelineAdapter adapter;
    private final CopyOnWriteArrayList<Future<?>> pendingTasks;

    private volatile boolean shutdown = false;

    /**
     * Create a lazy DAG compressor.
     *
     * @param executor executor service for background tasks (must not be null)
     * @param adapter pipeline adapter for compression (must not be null)
     * @throws NullPointerException if executor or adapter is null
     */
    public LazyDAGCompressor(ScheduledExecutorService executor, DAGPipelineAdapter adapter) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(adapter, "adapter cannot be null");

        this.executor = executor;
        this.adapter = adapter;
        this.pendingTasks = new CopyOnWriteArrayList<>();
    }

    /**
     * Compress asynchronously and return a Future.
     *
     * <p>The compression executes in the background and the Future
     * can be used to retrieve the result.
     *
     * @param source source ESVO octree (must not be null)
     * @return future containing compressed DAG data
     * @throws NullPointerException if source is null
     * @throws RejectedExecutionException if compressor is shutdown
     */
    public Future<DAGOctreeData> compressAsync(ESVOOctreeData source) {
        Objects.requireNonNull(source, "source cannot be null");

        if (shutdown) {
            throw new RejectedExecutionException("Compressor is shutdown");
        }

        var future = executor.submit(() -> {
            try {
                return (DAGOctreeData) adapter.compress(source);
            } catch (Exception e) {
                log.error("Compression failed for source", e);
                throw e;
            }
        });

        pendingTasks.add(future);
        return future;
    }

    /**
     * Schedule compression to execute after a delay.
     *
     * <p>Invokes the callback with the result when compression completes.
     * The callback is guaranteed to execute even if exceptions occur
     * (callback receives the result or exception is logged).
     *
     * @param source source ESVO octree (must not be null)
     * @param delayMs delay in milliseconds (must be >= 0)
     * @param callback callback to receive result (must not be null)
     * @throws NullPointerException if source or callback is null
     * @throws IllegalArgumentException if delayMs < 0
     * @throws RejectedExecutionException if compressor is shutdown
     */
    public void scheduleCompression(ESVOOctreeData source, long delayMs, Consumer<DAGOctreeData> callback) {
        Objects.requireNonNull(source, "source cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");

        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs cannot be negative: " + delayMs);
        }

        if (shutdown) {
            throw new RejectedExecutionException("Compressor is shutdown");
        }

        var future = executor.schedule(() -> {
            try {
                var result = (DAGOctreeData) adapter.compress(source);
                try {
                    callback.accept(result);
                } catch (Exception e) {
                    log.error("Callback exception during scheduled compression", e);
                }
            } catch (Exception e) {
                log.error("Scheduled compression failed for source", e);
                throw e;
            }
        }, delayMs, TimeUnit.MILLISECONDS);

        pendingTasks.add(future);
    }

    /**
     * Initiate graceful shutdown.
     *
     * <p>Prevents new tasks from being submitted and attempts to cancel
     * pending scheduled tasks. Running tasks will complete.
     */
    public void shutdown() {
        shutdown = true;

        // Cancel pending scheduled tasks
        for (var task : pendingTasks) {
            if (!task.isDone()) {
                task.cancel(false); // Don't interrupt running tasks
            }
        }

        executor.shutdown();
    }

    /**
     * Wait for pending tasks to complete.
     *
     * @param timeoutMs timeout in milliseconds
     * @return true if all tasks completed, false if timeout occurred
     */
    public boolean awaitCompletion(long timeoutMs) {
        var deadline = System.currentTimeMillis() + timeoutMs;

        try {
            // Wait for executor to terminate
            var remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }

            return executor.awaitTermination(remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while awaiting completion", e);
            return false;
        }
    }
}
