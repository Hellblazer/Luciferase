/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * In-memory implementation of GhostChannel for testing and single-server deployments.
 * <p>
 * InMemoryGhostChannel provides a lightweight channel implementation that operates entirely in memory with no network
 * overhead. It supports optional simulated latency to mimic network behavior in tests.
 * <p>
 * <strong>Features:</strong>
 * <ul>
 *   <li>Thread-safe batching using ConcurrentHashMap</li>
 *   <li>Optional simulated latency for realistic testing</li>
 *   <li>Multiple handlers support via CopyOnWriteArrayList</li>
 *   <li>Always connected (no network failures)</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 * // No latency (instant delivery)
 * var channel = new InMemoryGhostChannel&lt;&gt;();
 *
 * // With 50ms simulated latency
 * var channel = new InMemoryGhostChannel&lt;&gt;(50);
 *
 * // Register handlers
 * channel.onReceive((from, ghosts) -&gt; processGhosts(ghosts));
 *
 * // Queue and flush
 * channel.queueGhost(targetId, ghost);
 * channel.flush(bucket);
 * </pre>
 *
 * @param <ID>      Entity ID type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class InMemoryGhostChannel<ID extends EntityID, Content> implements GhostChannel<ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(InMemoryGhostChannel.class);

    /**
     * Pending batches grouped by target bubble
     */
    private final Map<UUID, List<SimulationGhostEntity<ID, Content>>> pendingBatches;

    /**
     * Registered handlers for incoming batches
     */
    private final List<BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>>> handlers;

    /**
     * Simulated network latency in milliseconds
     */
    private final long simulatedLatencyMs;

    /**
     * Create channel with no simulated latency.
     */
    public InMemoryGhostChannel() {
        this(0);
    }

    /**
     * Create channel with simulated latency.
     *
     * @param simulatedLatencyMs Latency to simulate in milliseconds
     */
    public InMemoryGhostChannel(long simulatedLatencyMs) {
        this.pendingBatches = new ConcurrentHashMap<>();
        this.handlers = new CopyOnWriteArrayList<>();
        this.simulatedLatencyMs = simulatedLatencyMs;
    }

    @Override
    public void queueGhost(UUID targetBubbleId, SimulationGhostEntity<ID, Content> ghost) {
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        Objects.requireNonNull(ghost, "ghost must not be null");
        pendingBatches.computeIfAbsent(targetBubbleId, k -> new CopyOnWriteArrayList<>()).add(ghost);
    }

    @Override
    public void sendBatch(UUID targetBubbleId, List<SimulationGhostEntity<ID, Content>> ghosts) {
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        Objects.requireNonNull(ghosts, "ghosts must not be null");

        // Simulate network latency if configured
        if (simulatedLatencyMs > 0) {
            try {
                Thread.sleep(simulatedLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // Exit early on interruption
            }
        }

        // Notify all handlers (isolated exception handling)
        for (var handler : handlers) {
            try {
                handler.accept(targetBubbleId, ghosts);
            } catch (Exception e) {
                // Log but don't propagate - other handlers must still execute
                log.warn("Handler threw exception processing ghost batch for target {}", targetBubbleId, e);
            }
        }
    }

    @Override
    public void flush(long bucket) {
        for (var entry : pendingBatches.entrySet()) {
            var targetId = entry.getKey();
            var ghosts = entry.getValue(); // CopyOnWriteArrayList - clear() won't affect concurrent adds
            if (!ghosts.isEmpty()) {
                // Send copy to avoid concurrent modification
                sendBatch(targetId, new ArrayList<>(ghosts));
                ghosts.clear(); // Safe: creates new list, concurrent adds go to new list
            }
        }
    }

    @Override
    public void onReceive(BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>> handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        handlers.add(handler);
    }

    @Override
    public boolean isConnected(UUID targetBubbleId) {
        return true;  // Always connected in-memory
    }

    @Override
    public int getPendingCount(UUID targetBubbleId) {
        return pendingBatches.getOrDefault(targetBubbleId, List.of()).size();
    }

    @Override
    public void close() {
        pendingBatches.clear();
        handlers.clear();
    }
}
