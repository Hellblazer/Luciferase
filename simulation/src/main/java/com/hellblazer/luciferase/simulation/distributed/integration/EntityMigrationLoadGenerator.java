/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Generates entity migration load at a configurable target TPS (transactions per
 * second).
 * <p>
 * Thread-safe: Uses atomic counters and scheduled executor for load generation.
 *
 * @author hal.hildebrand
 */
public class EntityMigrationLoadGenerator {

    private final List<UUID>                 entityPool;
    private final Consumer<UUID>             onMigrationRequest;
    private final AtomicLong                 totalGenerated;
    private final AtomicLong                 totalFailures;
    private final AtomicInteger              pendingRequests;
    private final AtomicInteger              entityIndex;
    private       ScheduledExecutorService   executor;
    private       int                        targetTPS;
    private       long                       startTime;

    /**
     * Creates a new load generator.
     *
     * @param targetTPS           target transactions per second
     * @param entityCount         number of entities in the pool
     * @param onMigrationRequest callback invoked for each migration request
     */
    public EntityMigrationLoadGenerator(int targetTPS, int entityCount, Consumer<UUID> onMigrationRequest) {
        this.targetTPS = targetTPS;
        this.onMigrationRequest = onMigrationRequest;
        this.totalGenerated = new AtomicLong(0);
        this.totalFailures = new AtomicLong(0);
        this.pendingRequests = new AtomicInteger(0);
        this.entityIndex = new AtomicInteger(0);

        // Pre-generate entity pool
        this.entityPool = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            entityPool.add(UUID.randomUUID());
        }
    }

    /**
     * Starts load generation at the target TPS.
     */
    public void start() {
        if (executor != null && !executor.isShutdown()) {
            throw new IllegalStateException("Generator already running");
        }

        startTime = System.currentTimeMillis();

        executor = Executors.newScheduledThreadPool(2, r -> {
            var thread = new Thread(r, "LoadGenerator");
            thread.setDaemon(true);
            return thread;
        });

        // Calculate inter-request delay
        var delayMs = targetTPS > 0 ? 1000 / targetTPS : 1000;

        executor.scheduleAtFixedRate(this::generateRequest, delayMs, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops load generation and releases resources.
     */
    public void stop() {
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns true if the generator is currently running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    /**
     * Adjusts the target TPS while the generator is running.
     *
     * @param newTPS new target transactions per second
     */
    public void setTargetTPS(int newTPS) {
        this.targetTPS = newTPS;

        if (executor != null && !executor.isShutdown()) {
            // Restart with new rate
            stop();
            start();
        }
    }

    /**
     * Returns current load generation metrics.
     *
     * @return load metrics
     */
    public LoadMetrics getMetrics() {
        var elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        var actualTPS = elapsed > 0 ? totalGenerated.get() / elapsed : 0.0;

        return new LoadMetrics(targetTPS, actualTPS, pendingRequests.get(), totalGenerated.get(), totalFailures.get());
    }

    private void generateRequest() {
        try {
            pendingRequests.incrementAndGet();

            // Select entity using round-robin for uniform distribution
            var index = entityIndex.getAndIncrement() % entityPool.size();
            var entity = entityPool.get(index);

            // Invoke callback
            onMigrationRequest.accept(entity);

            totalGenerated.incrementAndGet();
        } catch (Exception e) {
            totalFailures.incrementAndGet();
        } finally {
            pendingRequests.decrementAndGet();
        }
    }
}

/**
 * Metrics for load generation.
 *
 * @param targetTPS       target transactions per second
 * @param actualTPS       actual measured TPS
 * @param pendingRequests number of pending requests
 * @param totalGenerated  total requests generated
 * @param totalFailures   total failed requests
 */
record LoadMetrics(int targetTPS, double actualTPS, int pendingRequests, long totalGenerated, long totalFailures) {
}
