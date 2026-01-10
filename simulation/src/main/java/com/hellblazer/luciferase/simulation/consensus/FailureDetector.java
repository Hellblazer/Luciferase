/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Heartbeat-based failure detector for coordinator election.
 *
 * Monitors node health via heartbeat messages. Reports nodes as failed if
 * configured number of consecutive heartbeats are missed. Thread-safe.
 *
 * PROTOCOL:
 * - Node sends AppendHeartbeat every heartbeat_interval milliseconds
 * - If heartbeat not received for heartbeat_timeout milliseconds, mark as failed
 * - After max_failures consecutive failures, report node as DEAD
 *
 * @author hal.hildebrand
 */
public class FailureDetector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FailureDetector.class);

    private final UUID nodeId;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final int maxConsecutiveFailures;

    private final ConcurrentHashMap<UUID, NodeHealthState> healthMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running;

    /**
     * Create failure detector with configurable timeouts.
     *
     * @param nodeId Current node UUID
     * @param heartbeatIntervalMs How often heartbeat should be sent (ms)
     * @param heartbeatTimeoutMs Timeout before marking node as failed (ms)
     * @param maxConsecutiveFailures Number of failures before reporting DEAD
     */
    public FailureDetector(UUID nodeId, long heartbeatIntervalMs, long heartbeatTimeoutMs,
                          int maxConsecutiveFailures) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.running = new AtomicBoolean(true);
        this.executor = Executors.newScheduledThreadPool(1, r -> {
            var thread = new Thread(r, "FailureDetector-" + nodeId);
            thread.setDaemon(true);
            return thread;
        });

        // Start monitoring task
        startMonitoring();

        log.info("FailureDetector started for node {} (interval={}ms, timeout={}ms)",
                 nodeId, heartbeatIntervalMs, heartbeatTimeoutMs);
    }

    /**
     * Report a heartbeat from a remote node.
     *
     * @param fromNodeId Node sending heartbeat
     */
    public void recordHeartbeat(UUID fromNodeId) {
        Objects.requireNonNull(fromNodeId, "fromNodeId must not be null");

        if (!running.get()) {
            return;
        }

        var state = healthMap.computeIfAbsent(fromNodeId, id -> new NodeHealthState(id));
        state.lastHeartbeat = Instant.now();
        state.consecutiveFailures = 0; // Reset failure counter
        state.status = NodeStatus.ALIVE;

        log.debug("Heartbeat from node {}", fromNodeId);
    }

    /**
     * Check if node is suspected failed (hasn't heartbeated recently).
     *
     * @param nodeIdToCheck Node to check
     * @return true if node has not heartbeated within timeout
     */
    public boolean isSuspected(UUID nodeIdToCheck) {
        Objects.requireNonNull(nodeIdToCheck, "nodeIdToCheck must not be null");

        var state = healthMap.get(nodeIdToCheck);
        if (state == null) {
            return false; // Unknown nodes are not suspected
        }

        return state.status == NodeStatus.SUSPECTED || state.status == NodeStatus.DEAD;
    }

    /**
     * Check if node is confirmed dead (exceeded max failures).
     *
     * @param nodeIdToCheck Node to check
     * @return true if node is confirmed dead
     */
    public boolean isDead(UUID nodeIdToCheck) {
        Objects.requireNonNull(nodeIdToCheck, "nodeIdToCheck must not be null");

        var state = healthMap.get(nodeIdToCheck);
        if (state == null) {
            return false;
        }

        return state.status == NodeStatus.DEAD;
    }

    /**
     * Get status of all known nodes.
     *
     * @return Map of node IDs to health status
     */
    public Map<UUID, NodeStatus> getNodeStatuses() {
        var statuses = new HashMap<UUID, NodeStatus>();
        for (var entry : healthMap.entrySet()) {
            statuses.put(entry.getKey(), entry.getValue().status);
        }
        return statuses;
    }

    /**
     * Register a node to monitor (proactively rather than on first heartbeat).
     *
     * @param nodeId Node to monitor
     */
    public void registerNode(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        healthMap.putIfAbsent(nodeId, new NodeHealthState(nodeId));
    }

    /**
     * Clear failure history for node (e.g., after successful reconnection).
     *
     * @param nodeId Node to clear
     */
    public void clearFailures(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        var state = healthMap.get(nodeId);
        if (state != null) {
            state.consecutiveFailures = 0;
            state.status = NodeStatus.ALIVE;
        }
    }

    /**
     * Close detector and stop monitoring.
     */
    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("FailureDetector closed for node {}", nodeId);
        }
    }

    // ========== Private Helper Methods ==========

    private void startMonitoring() {
        executor.scheduleAtFixedRate(this::checkHeartbeats, heartbeatIntervalMs,
                                     heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void checkHeartbeats() {
        if (!running.get()) {
            return;
        }

        var now = Instant.now();

        for (var state : healthMap.values()) {
            if (state.lastHeartbeat == null) {
                // Never received heartbeat - still in initial state
                continue;
            }

            var timeSinceHeartbeat = now.toEpochMilli() - state.lastHeartbeat.toEpochMilli();

            if (timeSinceHeartbeat > heartbeatTimeoutMs) {
                // Heartbeat timeout exceeded
                state.consecutiveFailures++;

                if (state.consecutiveFailures >= maxConsecutiveFailures) {
                    state.status = NodeStatus.DEAD;
                    log.warn("Node {} marked as DEAD (failures={})", state.nodeId, state.consecutiveFailures);
                } else {
                    state.status = NodeStatus.SUSPECTED;
                    log.debug("Node {} marked as SUSPECTED (failures={})", state.nodeId, state.consecutiveFailures);
                }
            }
        }
    }

    /**
     * Node health status.
     */
    public enum NodeStatus {
        ALIVE, SUSPECTED, DEAD
    }

    /**
     * Internal node health tracking.
     */
    private static class NodeHealthState {
        final UUID nodeId;
        volatile Instant lastHeartbeat;
        volatile int consecutiveFailures;
        volatile NodeStatus status;

        NodeHealthState(UUID nodeId) {
            this.nodeId = nodeId;
            this.lastHeartbeat = null;
            this.consecutiveFailures = 0;
            this.status = NodeStatus.ALIVE;
        }
    }
}
