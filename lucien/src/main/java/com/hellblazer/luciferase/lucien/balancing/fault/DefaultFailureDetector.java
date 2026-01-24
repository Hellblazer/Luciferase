/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of FailureDetector.
 *
 * <p>Uses a ScheduledExecutorService to periodically check partition health
 * based on heartbeat timeout thresholds. Reports failures to FaultHandler when
 * timeouts are exceeded.
 *
 * <p><b>Thread-Safe</b>: Uses ConcurrentHashMap for partition state and
 * ScheduledExecutorService for background monitoring.
 */
public class DefaultFailureDetector implements FailureDetector {

    private static final Logger log = LoggerFactory.getLogger(DefaultFailureDetector.class);

    private final FailureDetectionConfig config;
    private final FaultHandler faultHandler;
    private volatile Clock clock = Clock.systemDefaultZone();

    private final Map<UUID, Instant> lastHeartbeat = new ConcurrentHashMap<>();
    private final Map<UUID, PartitionStatus> detectorState = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService executor;

    /**
     * Create a FailureDetector with given configuration and fault handler.
     *
     * @param config failure detection configuration
     * @param faultHandler handler to report failures to
     */
    public DefaultFailureDetector(FailureDetectionConfig config, FaultHandler faultHandler) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.faultHandler = Objects.requireNonNull(faultHandler, "faultHandler must not be null");
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor = new ScheduledThreadPoolExecutor(1, r -> {
                var thread = new Thread(r, "FailureDetector-HealthCheck");
                thread.setDaemon(true);
                return thread;
            });

            // Schedule periodic health checks
            executor.scheduleAtFixedRate(
                this::checkHealth,
                config.checkIntervalMs(),
                config.checkIntervalMs(),
                TimeUnit.MILLISECONDS
            );

            log.info("FailureDetector started with config: {}", config);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            lastHeartbeat.clear();
            detectorState.clear();
            log.info("FailureDetector stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void registerPartition(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        if (!isRunning()) {
            throw new IllegalStateException("FailureDetector is not running");
        }

        lastHeartbeat.put(partitionId, clock.instant());
        detectorState.put(partitionId, PartitionStatus.HEALTHY);
        log.debug("Registered partition {} for monitoring", partitionId);
    }

    @Override
    public void unregisterPartition(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        lastHeartbeat.remove(partitionId);
        detectorState.remove(partitionId);
        log.debug("Unregistered partition {} from monitoring", partitionId);
    }

    @Override
    public void recordHeartbeat(UUID partitionId) {
        Objects.requireNonNull(partitionId, "partitionId must not be null");

        lastHeartbeat.put(partitionId, clock.instant());
        log.trace("Recorded heartbeat for partition {}", partitionId);
    }

    @Override
    public FailureDetectionConfig getConfig() {
        return config;
    }

    @Override
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Internal method: Perform health check on all registered partitions.
     *
     * <p>Called periodically by the executor. Checks if any partitions have
     * exceeded timeout thresholds and reports failures to the FaultHandler.
     *
     * <p>Package-visible for testing purposes.
     */
    void checkHealth() {
        var now = clock.instant();

        for (var partitionId : new HashSet<>(lastHeartbeat.keySet())) {
            var lastHb = lastHeartbeat.get(partitionId);
            if (lastHb == null) {
                continue;
            }

            var timeSinceHeartbeat = Duration.between(lastHb, now);
            var currentState = detectorState.get(partitionId);

            // Transition based on timeouts
            if (timeSinceHeartbeat.compareTo(config.failureTimeout()) > 0) {
                // Exceeded failure timeout
                if (currentState != PartitionStatus.FAILED) {
                    detectorState.put(partitionId, PartitionStatus.FAILED);
                    faultHandler.reportBarrierTimeout(partitionId);
                    log.warn("Partition {} marked FAILED (timeout: {}ms)",
                            partitionId, timeSinceHeartbeat.toMillis());
                }
            } else if (timeSinceHeartbeat.compareTo(config.suspectTimeout()) > 0) {
                // Exceeded suspect timeout
                if (currentState != PartitionStatus.SUSPECTED &&
                    currentState != PartitionStatus.FAILED) {
                    detectorState.put(partitionId, PartitionStatus.SUSPECTED);
                    faultHandler.reportHeartbeatFailure(partitionId, UUID.randomUUID());
                    log.warn("Partition {} marked SUSPECTED (timeout: {}ms)",
                            partitionId, timeSinceHeartbeat.toMillis());
                }
            } else {
                // Within timeout - mark as healthy
                if (currentState != PartitionStatus.HEALTHY) {
                    detectorState.put(partitionId, PartitionStatus.HEALTHY);
                    faultHandler.markHealthy(partitionId);
                    log.debug("Partition {} marked HEALTHY", partitionId);
                }
            }
        }
    }
}
