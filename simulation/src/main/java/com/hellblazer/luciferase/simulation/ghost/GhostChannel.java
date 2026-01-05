/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.SimulationGhostEntity;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Abstract channel for ghost batch transmission between servers.
 * <p>
 * GhostChannel provides batched transmission of ghost entities across server boundaries. Ghosts are queued locally and
 * sent in batches at bucket boundaries (100ms intervals) to minimize network overhead.
 * <p>
 * <strong>Batching Strategy:</strong>
 * <ul>
 *   <li>Ghosts are queued via {@link #queueGhost(UUID, SimulationGhostEntity)} but not sent immediately</li>
 *   <li>Batches are flushed at bucket boundaries via {@link #flush(long)}</li>
 *   <li>Reduces network round-trips and improves throughput</li>
 * </ul>
 * <p>
 * <strong>Implementations:</strong>
 * <ul>
 *   <li>{@link InMemoryGhostChannel}: For testing, with optional simulated latency</li>
 *   <li>GrpcGhostChannel: For production (Phase 5)</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var channel = new InMemoryGhostChannel&lt;StringEntityID, Object&gt;();
 *
 * // Register handler for incoming batches
 * channel.onReceive((targetId, ghosts) -&gt; {
 *     for (var ghost : ghosts) {
 *         bubbleGhostManager.handleGhost(ghost);
 *     }
 * });
 *
 * // Queue ghosts during simulation step
 * channel.queueGhost(neighborBubbleId, ghostEntity);
 *
 * // Flush at bucket boundary (every 100ms)
 * channel.flush(currentBucket);
 * </pre>
 *
 * @param <ID>      Entity ID type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public interface GhostChannel<ID extends EntityID, Content> {

    /**
     * Queue a ghost for batch transmission to target bubble. Ghost is NOT sent immediately - it will be batched until
     * {@link #flush(long)} is called.
     *
     * @param targetBubbleId Target bubble to receive this ghost
     * @param ghost          Ghost entity to transmit
     */
    void queueGhost(UUID targetBubbleId, SimulationGhostEntity<ID, Content> ghost);

    /**
     * Send a batch of ghosts to target bubble immediately. This bypasses the queue and sends the batch directly. Called
     * internally by {@link #flush(long)} or can be used for urgent out-of-band transmission.
     *
     * @param targetBubbleId Target bubble ID
     * @param ghosts         Batch of ghosts to send
     */
    void sendBatch(UUID targetBubbleId, List<SimulationGhostEntity<ID, Content>> ghosts);

    /**
     * Flush all pending ghosts for a bucket. Sends batches to all targets with pending ghosts. This should be called at
     * bucket boundaries (every 100ms).
     *
     * @param bucket Simulation bucket number
     */
    void flush(long bucket);

    /**
     * Register handler for incoming ghost batches. Multiple handlers can be registered - all will be notified when a
     * batch arrives.
     *
     * @param handler Handler receiving (sourceBubbleId, ghosts) for each incoming batch
     */
    void onReceive(BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>> handler);

    /**
     * Check if channel is connected to target. For in-memory channels, this is always true. For network channels, this
     * checks if the connection is established.
     *
     * @param targetBubbleId Target bubble to check
     * @return true if connected and ready to send
     */
    boolean isConnected(UUID targetBubbleId);

    /**
     * Get pending ghost count for target. Useful for monitoring and debugging.
     *
     * @param targetBubbleId Target bubble
     * @return Number of ghosts queued but not yet sent
     */
    int getPendingCount(UUID targetBubbleId);

    /**
     * Close channel and release resources. Clears all pending ghosts and handlers.
     */
    void close();
}
