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

package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.von.Event;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import com.hellblazer.luciferase.simulation.von.VonMessage;
import com.hellblazer.luciferase.simulation.von.VonMessageFactory;
import com.hellblazer.luciferase.simulation.von.VonTransport;
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
 * P2P implementation of GhostChannel using VonTransport for neighbor-to-neighbor ghost synchronization.
 * <p>
 * P2PGhostChannel integrates with the v4.0 VON architecture to send ghost entities directly to
 * P2P neighbors via VonBubble's transport layer. This replaces broadcast-based ghost sync with
 * targeted P2P messaging.
 * <p>
 * <strong>Key Features:</strong>
 * <ul>
 *   <li>P2P ghost transmission via VonTransport (no broadcast)</li>
 *   <li>Batched transmission at bucket boundaries (100ms)</li>
 *   <li>Automatic conversion between SimulationGhostEntity and TransportGhost</li>
 *   <li>Event-based receive handling from VonBubble</li>
 *   <li>Same-server optimization via shouldBypass check</li>
 * </ul>
 * <p>
 * <strong>Architecture:</strong>
 * <pre>
 * Sender Side:
 *   notifyEntityNearBoundary() → queueGhost() → flush() → sendBatch()
 *                                                             ↓
 *                                              VonTransport.sendToNeighbor(GhostSync)
 *
 * Receiver Side:
 *   VonBubble.handleMessage(GhostSync) → Event.GhostSync → P2PGhostChannel.onGhostSyncEvent()
 *                                                                   ↓
 *                                              handlers.accept(sourceBubbleId, ghosts)
 * </pre>
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 * var channel = new P2PGhostChannel&lt;&gt;(vonBubble);
 *
 * // Register handler for incoming ghosts
 * channel.onReceive((fromId, ghosts) -&gt; processGhosts(ghosts));
 *
 * // Queue ghosts during simulation
 * channel.queueGhost(neighborId, ghost);
 *
 * // Flush at bucket boundary
 * channel.flush(bucket);
 * </pre>
 *
 * @param <ID>      Entity ID type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class P2PGhostChannel<ID extends EntityID, Content> implements GhostChannel<ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(P2PGhostChannel.class);

    /**
     * VonBubble for P2P communication
     */
    private final VonBubble vonBubble;

    /**
     * Factory for creating VonMessage records with timestamp
     */
    private final VonMessageFactory factory;

    /**
     * Pending batches grouped by target bubble
     */
    private final Map<UUID, List<SimulationGhostEntity<ID, Content>>> pendingBatches;

    /**
     * Registered handlers for incoming batches
     */
    private final List<BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>>> handlers;

    /**
     * Current simulation bucket for temporal ordering
     */
    private long currentBucket = 0;

    /**
     * Create P2P ghost channel with VonBubble.
     *
     * @param vonBubble VonBubble for P2P transport
     */
    public P2PGhostChannel(VonBubble vonBubble) {
        this.vonBubble = Objects.requireNonNull(vonBubble, "vonBubble must not be null");
        this.factory = VonMessageFactory.system();
        this.pendingBatches = new ConcurrentHashMap<>();
        this.handlers = new CopyOnWriteArrayList<>();

        // Register for GhostSync events from VonBubble
        vonBubble.addEventListener(this::handleEvent);

        log.debug("P2PGhostChannel created for bubble {}", vonBubble.id());
    }

    @Override
    public void queueGhost(UUID targetBubbleId, SimulationGhostEntity<ID, Content> ghost) {
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        Objects.requireNonNull(ghost, "ghost must not be null");

        // Only queue for P2P neighbors
        if (!vonBubble.neighbors().contains(targetBubbleId)) {
            log.trace("Ignoring ghost for non-neighbor {}", targetBubbleId);
            return;
        }

        pendingBatches.computeIfAbsent(targetBubbleId, k -> new CopyOnWriteArrayList<>()).add(ghost);
        log.trace("Queued ghost for {} (pending: {})", targetBubbleId, getPendingCount(targetBubbleId));
    }

    @Override
    public void sendBatch(UUID targetBubbleId, List<SimulationGhostEntity<ID, Content>> ghosts) {
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        Objects.requireNonNull(ghosts, "ghosts must not be null");

        if (ghosts.isEmpty()) {
            return;
        }

        // Only send to P2P neighbors
        if (!vonBubble.neighbors().contains(targetBubbleId)) {
            log.trace("Skipping ghost batch for non-neighbor {}", targetBubbleId);
            return;
        }

        // Convert to transport format
        var transportGhosts = new ArrayList<VonMessage.TransportGhost>(ghosts.size());
        for (var ghost : ghosts) {
            transportGhosts.add(toTransportGhost(ghost));
        }

        // Send via VonTransport
        var message = factory.createGhostSync(vonBubble.id(), transportGhosts, currentBucket);
        try {
            vonBubble.getTransport().sendToNeighbor(targetBubbleId, message);
            log.debug("Sent {} ghosts to neighbor {} at bucket {}",
                      ghosts.size(), targetBubbleId, currentBucket);
        } catch (VonTransport.TransportException e) {
            log.warn("Failed to send ghost batch to {}: {}", targetBubbleId, e.getMessage());
        }
    }

    @Override
    public void flush(long bucket) {
        this.currentBucket = bucket;

        for (var entry : pendingBatches.entrySet()) {
            var targetId = entry.getKey();
            var ghosts = entry.getValue();
            if (!ghosts.isEmpty()) {
                // Send copy to avoid concurrent modification
                sendBatch(targetId, new ArrayList<>(ghosts));
                ghosts.clear();
            }
        }

        log.trace("Flushed ghost batches at bucket {}", bucket);
    }

    @Override
    public void onReceive(BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>> handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        handlers.add(handler);
    }

    @Override
    public boolean isConnected(UUID targetBubbleId) {
        return vonBubble.neighbors().contains(targetBubbleId);
    }

    @Override
    public int getPendingCount(UUID targetBubbleId) {
        return pendingBatches.getOrDefault(targetBubbleId, List.of()).size();
    }

    @Override
    public void close() {
        vonBubble.removeEventListener(this::handleEvent);
        pendingBatches.clear();
        handlers.clear();
        log.debug("P2PGhostChannel closed for bubble {}", vonBubble.id());
    }

    /**
     * Handle events from VonBubble.
     * <p>
     * Processes GhostSync events and dispatches to registered handlers.
     *
     * @param event VON event
     */
    private void handleEvent(Event event) {
        if (event instanceof Event.GhostSync ghostSync) {
            onGhostSyncEvent(ghostSync);
        }
    }

    /**
     * Process incoming GhostSync event.
     * <p>
     * Converts TransportGhosts back to SimulationGhostEntities and notifies handlers.
     *
     * @param event GhostSync event from VonBubble
     */
    @SuppressWarnings("unchecked")
    private void onGhostSyncEvent(Event.GhostSync event) {
        var sourceId = event.sourceBubbleId();
        var transportGhosts = event.ghosts();

        if (transportGhosts.isEmpty()) {
            return;
        }

        // Convert from transport format
        // Note: This is a simplified reconstruction - full content requires serialization
        var ghosts = new ArrayList<SimulationGhostEntity<ID, Content>>(transportGhosts.size());
        for (var tg : transportGhosts) {
            ghosts.add(fromTransportGhost(tg, sourceId, event.bucket()));
        }

        // Notify all handlers
        for (var handler : handlers) {
            try {
                handler.accept(sourceId, ghosts);
            } catch (Exception e) {
                log.warn("Handler threw exception processing ghost batch from {}: {}", sourceId, e.getMessage());
            }
        }

        log.debug("Processed {} ghosts from {} at bucket {}", ghosts.size(), sourceId, event.bucket());
    }

    /**
     * Convert SimulationGhostEntity to TransportGhost for P2P transmission.
     *
     * @param ghost SimulationGhostEntity to convert
     * @return TransportGhost for transmission
     */
    private VonMessage.TransportGhost toTransportGhost(SimulationGhostEntity<ID, Content> ghost) {
        return new VonMessage.TransportGhost(
            ghost.entityId().toDebugString(),
            ghost.position(),
            ghost.content() != null ? ghost.content().getClass().getName() : "null",
            ghost.sourceTreeId(),
            ghost.epoch(),
            ghost.version(),
            ghost.timestamp()
        );
    }

    /**
     * Convert TransportGhost back to SimulationGhostEntity.
     * <p>
     * Note: Content is not reconstructed (would require serialization).
     * For full reconstruction, extend this with content serialization.
     *
     * @param tg       TransportGhost to convert
     * @param sourceId Source bubble ID
     * @param bucket   Simulation bucket
     * @return SimulationGhostEntity (with null content)
     */
    @SuppressWarnings("unchecked")
    private SimulationGhostEntity<ID, Content> fromTransportGhost(
        VonMessage.TransportGhost tg,
        UUID sourceId,
        long bucket
    ) {
        // Create a minimal ghost entity for the internal structure
        // Note: This creates a placeholder since we don't have full serialization
        var internalGhost = new com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager.GhostEntity<ID, Content>(
            (ID) new StringEntityID(tg.entityId()),  // Use StringEntityID as placeholder
            null,  // Content not transmitted - would need serialization
            tg.position(),
            new com.hellblazer.luciferase.lucien.entity.EntityBounds(tg.position(), 0.5f),
            tg.sourceTreeId()
        );

        return new SimulationGhostEntity<>(
            internalGhost,
            sourceId,
            bucket,
            tg.epoch(),
            tg.version()
        );
    }

    /**
     * Simple string-based EntityID for transport reconstruction.
     */
    private record StringEntityID(String id) implements EntityID {
        @Override
        public String toDebugString() {
            return id;
        }

        @Override
        public int compareTo(EntityID other) {
            return id.compareTo(other.toDebugString());
        }
    }
}
