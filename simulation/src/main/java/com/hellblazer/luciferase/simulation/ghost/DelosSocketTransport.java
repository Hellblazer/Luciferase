/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.events.EventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Delos-based network transport for cross-bubble ghost delivery (Phase 7B.2).
 *
 * <p><strong>⚠️ DEPRECATED:</strong> This implementation is incomplete and superseded by
 * {@link P2PGhostChannel}, which uses the VON transport abstraction for production
 * distributed ghost synchronization. DelosSocketTransport contains 7+ "TODO Phase 7B.2"
 * placeholders and uses simulated network connections instead of actual Delos integration.
 *
 * <p><strong>Migration Path:</strong>
 * <ul>
 *   <li>Testing: Use {@link InMemoryGhostChannel} (deterministic, no network)</li>
 *   <li>Production: Use {@link P2PGhostChannel} (VON-based P2P distribution)</li>
 * </ul>
 *
 * <p><strong>Removal Schedule:</strong> Month 2 of stabilization sprint (after P2PGhostChannel
 * validation complete). See ADR 001 § Ghost Channel Implementations for decision rationale.
 *
 * <p><strong>Original Intent:</strong> DelosSocketTransport was prototyped to implement
 * GhostChannel interface with network-based transmission using Delos SocketTransport and
 * EntityUpdateEvent serialization. However, the VON architecture's VonTransport abstraction
 * (used by P2PGhostChannel) provides equivalent functionality with better integration.
 *
 * <p><strong>Incomplete Features:</strong>
 * <ul>
 *   <li>⏳ Actual Delos SocketTransport integration (TODO)</li>
 *   <li>⏳ Real network connections (currently simulated via connectTo())</li>
 *   <li>⏳ Production-grade error handling and recovery</li>
 *   <li>⏳ Velocity tracking in EntityData for dead reckoning</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @deprecated Use {@link P2PGhostChannel} for distributed multi-bubble ghost synchronization,
 *             or {@link InMemoryGhostChannel} for testing. This class will be removed in Month 2.
 *             See simulation/doc/ADR_001_MIGRATION_CONSENSUS_ARCHITECTURE.md § Ghost Channel
 *             Implementations for migration guidance.
 */
@Deprecated(forRemoval = true)
public class DelosSocketTransport implements GhostChannel<StringEntityID, EntityData> {

    private static final Logger log = LoggerFactory.getLogger(DelosSocketTransport.class);

    /**
     * This bubble's identifier (source for outgoing ghosts)
     */
    private final UUID bubbleId;

    /**
     * Serializer for EntityUpdateEvent wire protocol
     */
    private final EventSerializer serializer;

    /**
     * Pending batches grouped by target bubble (queued but not yet sent)
     */
    private final Map<UUID, List<SimulationGhostEntity<StringEntityID, EntityData>>> pendingBatches;

    /**
     * Registered handlers for incoming ghost batches
     */
    private final List<BiConsumer<UUID, List<SimulationGhostEntity<StringEntityID, EntityData>>>> handlers;

    /**
     * Connected remote transports (for simulated network - Phase 7B.2 testing)
     * TODO Phase 7B.2: Replace with actual Delos SocketTransport connections
     */
    private final List<DelosSocketTransport> connections;

    /**
     * Create DelosSocketTransport for specified bubble.
     *
     * @param bubbleId This bubble's unique identifier
     */
    public DelosSocketTransport(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "bubbleId must not be null");

        this.bubbleId = bubbleId;
        this.serializer = new EventSerializer();
        this.pendingBatches = new ConcurrentHashMap<>();
        this.handlers = new CopyOnWriteArrayList<>();
        this.connections = new CopyOnWriteArrayList<>();
    }

    /**
     * Queue a ghost for batch transmission to target bubble.
     * Ghost is NOT sent immediately - it will be batched until {@link #flush(long)} is called.
     *
     * @param targetBubbleId Target bubble to receive this ghost
     * @param ghost          Ghost entity to transmit
     * @throws NullPointerException if targetBubbleId or ghost is null
     */
    @Override
    public void queueGhost(UUID targetBubbleId, SimulationGhostEntity<StringEntityID, EntityData> ghost) {
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        Objects.requireNonNull(ghost, "ghost must not be null");

        pendingBatches.computeIfAbsent(targetBubbleId, k -> new CopyOnWriteArrayList<>()).add(ghost);

        log.debug("Queued ghost {} for target bubble {}, pending count: {}",
                  ghost.entityId().getValue(), targetBubbleId, getPendingCount(targetBubbleId));
    }

    /**
     * Send a batch of ghosts to target bubble immediately.
     * This bypasses the queue and sends the batch directly via network.
     *
     * <p><strong>Implementation (Phase 7B.2):</strong>
     * <ul>
     *   <li>Convert each ghost to EntityUpdateEvent</li>
     *   <li>Serialize via EventSerializer</li>
     *   <li>Transmit via simulated network (testing)</li>
     *   <li>TODO: Replace with actual Delos SocketTransport.send()</li>
     * </ul>
     *
     * @param targetBubbleId Target bubble ID
     * @param ghosts         Batch of ghosts to send
     * @throws NullPointerException if targetBubbleId or ghosts is null
     */
    @Override
    public void sendBatch(UUID targetBubbleId, List<SimulationGhostEntity<StringEntityID, EntityData>> ghosts) {
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        Objects.requireNonNull(ghosts, "ghosts must not be null");

        if (ghosts.isEmpty()) {
            return;
        }

        log.debug("Sending batch of {} ghosts from {} to {}", ghosts.size(), bubbleId, targetBubbleId);

        // Convert ghosts to EntityUpdateEvents and serialize
        var serializedEvents = new ArrayList<byte[]>(ghosts.size());

        for (var ghost : ghosts) {
            try {
                // Convert SimulationGhostEntity to EntityUpdateEvent
                var event = ghostToEvent(ghost);

                // Serialize to binary format
                var bytes = serializer.toBytes(event);
                serializedEvents.add(bytes);

                log.trace("Serialized ghost {} to {} bytes", ghost.entityId().getValue(), bytes.length);

            } catch (Exception e) {
                log.error("Failed to serialize ghost {}", ghost.entityId().getValue(), e);
                // Continue with remaining ghosts
            }
        }

        // Phase 7B.2: Simulate network transmission for testing
        // TODO Phase 7B.2: Replace with actual Delos SocketTransport.send(bytes)
        simulateNetworkTransmission(targetBubbleId, serializedEvents, ghosts);
    }

    /**
     * Flush all pending ghosts for a bucket.
     * Sends batches to all targets with pending ghosts.
     * This should be called at bucket boundaries (every 100ms).
     *
     * @param bucket Simulation bucket number
     */
    @Override
    public void flush(long bucket) {
        log.debug("Flushing pending batches for bucket {} from bubble {}", bucket, bubbleId);

        for (var entry : pendingBatches.entrySet()) {
            var targetId = entry.getKey();
            var ghosts = entry.getValue(); // CopyOnWriteArrayList - clear() is safe

            if (!ghosts.isEmpty()) {
                // Send copy to avoid concurrent modification
                sendBatch(targetId, new ArrayList<>(ghosts));
                ghosts.clear(); // Safe: creates new list, concurrent adds go to new list
            }
        }
    }

    /**
     * Register handler for incoming ghost batches.
     * Multiple handlers can be registered - all will be notified when a batch arrives.
     *
     * @param handler Handler receiving (sourceBubbleId, ghosts) for each incoming batch
     * @throws NullPointerException if handler is null
     */
    @Override
    public void onReceive(BiConsumer<UUID, List<SimulationGhostEntity<StringEntityID, EntityData>>> handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        handlers.add(handler);
        log.debug("Registered ghost reception handler for bubble {}", bubbleId);
    }

    /**
     * Check if channel is connected to target.
     *
     * <p>Phase 7B.2: Always returns true (simulated network always connected).
     * TODO Phase 7B.2: Check actual Delos connection status.
     *
     * @param targetBubbleId Target bubble to check
     * @return true if connected and ready to send
     */
    @Override
    public boolean isConnected(UUID targetBubbleId) {
        return true; // Phase 7B.2: Simulated network always connected
        // TODO Phase 7B.2: return delosTransport.isConnected(targetBubbleId);
    }

    /**
     * Get pending ghost count for target.
     * Useful for monitoring and debugging.
     *
     * @param targetBubbleId Target bubble
     * @return Number of ghosts queued but not yet sent
     */
    @Override
    public int getPendingCount(UUID targetBubbleId) {
        return pendingBatches.getOrDefault(targetBubbleId, List.of()).size();
    }

    /**
     * Close channel and release resources.
     * Clears all pending ghosts and handlers.
     */
    @Override
    public void close() {
        log.debug("Closing DelosSocketTransport for bubble {}", bubbleId);
        pendingBatches.clear();
        handlers.clear();
        connections.clear();
        // TODO Phase 7B.2: Close actual Delos SocketTransport
    }

    // ========== Phase 7B.2 Testing Support (Simulated Network) ==========

    /**
     * Connect to remote transport for simulated network transmission (testing only).
     * In production, Delos manages connections automatically.
     *
     * @param remote Remote transport to connect to
     */
    public void connectTo(DelosSocketTransport remote) {
        Objects.requireNonNull(remote, "remote must not be null");
        connections.add(remote);
        log.debug("Connected bubble {} to bubble {}", bubbleId, remote.bubbleId);
    }

    // ========== Internal Helper Methods ==========

    /**
     * Convert SimulationGhostEntity to EntityUpdateEvent for serialization.
     *
     * <p><strong>Phase 7B.2 Limitation:</strong>
     * Velocity is not yet tracked in EntityData. Using zero velocity as placeholder.
     * Phase 7B.3 will add velocity tracking for dead reckoning.
     *
     * @param ghost Ghost entity to convert
     * @return EntityUpdateEvent ready for serialization
     */
    private EntityUpdateEvent ghostToEvent(SimulationGhostEntity<StringEntityID, EntityData> ghost) {
        // TODO Phase 7B.3: Extract velocity from EntityData when available
        var velocity = new Point3f(0f, 0f, 0f); // Placeholder - Phase 7B.3 will add velocity tracking

        return new EntityUpdateEvent(
            ghost.entityId(),
            ghost.position(),
            velocity,
            ghost.timestamp(),
            ghost.bucket() // Use bucket as Lamport clock for causality
        );
    }

    /**
     * Convert EntityUpdateEvent back to SimulationGhostEntity after deserialization.
     *
     * @param event EntityUpdateEvent from network
     * @param sourceBubbleId Source bubble that sent this event
     * @return SimulationGhostEntity for local ghost management
     */
    @SuppressWarnings("rawtypes") // EntityData used as raw type to match EnhancedBubble pattern
    private SimulationGhostEntity<StringEntityID, EntityData> eventToGhost(
        EntityUpdateEvent event,
        UUID sourceBubbleId
    ) {
        var entityId = event.entityId();
        var position = event.position();

        // Create EntityData (content) - velocity will be used in Phase 7B.3
        // Note: Using raw type to match GhostChannel<StringEntityID, EntityData> signature (EnhancedBubble pattern)
        EntityData content = new EntityData<>(entityId, position, (byte) 10, null);

        // Create GhostEntity wrapper
        GhostZoneManager.GhostEntity<StringEntityID, EntityData> ghostEntity =
            new GhostZoneManager.GhostEntity<>(
                entityId,
                content,
                position,
                new com.hellblazer.luciferase.lucien.entity.EntityBounds(position, 0.1f), // small radius
                "remote-" + sourceBubbleId // sourceTreeId
            );
        // Note: GhostEntity sets timestamp internally, we use event.timestamp() for SimulationGhostEntity

        // Wrap in SimulationGhostEntity with metadata
        return new SimulationGhostEntity<>(
            ghostEntity,
            sourceBubbleId,
            event.lamportClock(), // bucket (using lamport clock as bucket)
            0L, // epoch (not transmitted in Phase 7B.2)
            1L  // version
        );
    }

    /**
     * Simulate network transmission for testing (Phase 7B.2).
     * Deserializes events and delivers to connected remote transports.
     *
     * <p>TODO Phase 7B.2: Replace with actual Delos SocketTransport.send()
     *
     * @param targetBubbleId Target bubble ID
     * @param serializedEvents Serialized EntityUpdateEvent bytes
     * @param originalGhosts Original ghosts (for validation in tests)
     */
    private void simulateNetworkTransmission(
        UUID targetBubbleId,
        List<byte[]> serializedEvents,
        List<SimulationGhostEntity<StringEntityID, EntityData>> originalGhosts
    ) {
        // Find connected transport matching target bubble ID
        for (var remote : connections) {
            if (remote.bubbleId.equals(targetBubbleId)) {
                // Deserialize events and deliver to remote
                var deserializedGhosts = new ArrayList<SimulationGhostEntity<StringEntityID, EntityData>>();

                for (var bytes : serializedEvents) {
                    try {
                        var event = serializer.fromBytes(bytes);
                        var ghost = eventToGhost(event, bubbleId); // bubbleId = source of this transmission
                        deserializedGhosts.add(ghost);
                    } catch (Exception e) {
                        log.error("Failed to deserialize event during simulated transmission", e);
                    }
                }

                // Deliver to remote handlers
                remote.receiveGhosts(bubbleId, deserializedGhosts);
                return;
            }
        }

        log.warn("No connected transport found for target bubble {}", targetBubbleId);
    }

    /**
     * Receive ghosts from remote bubble and notify handlers.
     * Called internally during simulated network transmission.
     *
     * @param sourceBubbleId Source bubble that sent these ghosts
     * @param ghosts Received ghost batch
     */
    private void receiveGhosts(UUID sourceBubbleId, List<SimulationGhostEntity<StringEntityID, EntityData>> ghosts) {
        log.debug("Received {} ghosts from bubble {} at bubble {}", ghosts.size(), sourceBubbleId, bubbleId);

        // Notify all registered handlers (isolated exception handling)
        for (var handler : handlers) {
            try {
                handler.accept(sourceBubbleId, ghosts);
            } catch (Exception e) {
                // Log but don't propagate - other handlers must still execute
                log.warn("Handler threw exception processing ghost batch from {}", sourceBubbleId, e);
            }
        }
    }
}
