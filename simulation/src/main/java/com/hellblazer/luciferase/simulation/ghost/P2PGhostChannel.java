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
import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.von.Event;
import com.hellblazer.luciferase.simulation.von.Bubble;
import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.MessageFactory;
import com.hellblazer.luciferase.simulation.von.Transport;
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
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * P2P implementation of GhostChannel using Transport for neighbor-to-neighbor ghost synchronization.
 * <p>
 * P2PGhostChannel integrates with the v4.0 VON architecture to send ghost entities directly to
 * P2P neighbors via Bubble's transport layer. This replaces broadcast-based ghost sync with
 * targeted P2P messaging.
 * <p>
 * <strong>Key Features:</strong>
 * <ul>
 *   <li>P2P ghost transmission via Transport (no broadcast)</li>
 *   <li>Batched transmission at bucket boundaries (100ms)</li>
 *   <li>Automatic conversion between SimulationGhostEntity and TransportGhost</li>
 *   <li>Event-based receive handling from Bubble</li>
 *   <li>Same-server optimization via shouldBypass check</li>
 *   <li>Content serialization for EntityType enum</li>
 * </ul>
 * <p>
 * <strong>Architecture:</strong>
 * <pre>
 * Sender Side:
 *   notifyEntityNearBoundary() → queueGhost() → flush() → sendBatch()
 *                                                             ↓
 *                                              Transport.sendToNeighbor(GhostSync)
 *
 * Receiver Side:
 *   Bubble.handleMessage(GhostSync) → Event.GhostSync → P2PGhostChannel.onGhostSyncEvent()
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
     * Clock for deterministic testing
     */
    private volatile Clock clock = Clock.system();

    /**
     * Bubble for P2P communication
     */
    private final Bubble vonBubble;

    /**
     * Factory for creating Message records with timestamp
     */
    private volatile MessageFactory factory;

    /**
     * Pending batches grouped by target bubble
     */
    private final Map<UUID, List<SimulationGhostEntity<ID, Content>>> pendingBatches;

    /**
     * Registered handlers for incoming batches
     */
    private final List<BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>>> handlers;

    /**
     * Stable reference to the event listener so {@code removeEventListener} matches the exact
     * instance passed to {@code addEventListener}. A fresh {@code this::handleEvent} method-ref
     * evaluation produces a distinct, non-equal Consumer, so {@code remove} would silently no-op
     * and leak the listener (Luciferase-zwyf2).
     */
    private final Consumer<Event> eventListenerRef = this::handleEvent;

    /**
     * Current simulation bucket for temporal ordering
     */
    private long currentBucket = 0;

    /**
     * Factory that reconstructs the caller's concrete {@code ID} type from a ghost's
     * serialized entity-id string. Required so received cross-bubble ghosts carry an
     * entityId of the real ID type rather than a private placeholder, which would throw
     * ClassCastException at any type-checking use site (Luciferase-weaqr).
     */
    private final Function<String, ID> idFactory;

    /**
     * Pluggable codec for serializing arbitrary {@code Content} payloads onto the ghost wire
     * schema. The channel handles {@code null} and {@link com.hellblazer.luciferase.simulation.entity.EntityType}
     * natively. Supply a codec via {@link #P2PGhostChannel(Bubble, Function, ContentCodec)} to
     * round-trip any other content type. Without a codec, a non-null, non-EntityType content fails
     * loud (see {@link #serializeContent}/{@link #deserializeContent}) rather than being silently
     * dropped to {@code null} on the wire (Luciferase-8kgil).
     */
    private final ContentCodec<Content> contentCodec;

    /**
     * Count of inbound ghosts dropped because their content could not be decoded (no matching
     * {@link ContentCodec} on this receiver). Surfaced for observability so a misconfigured
     * receiver is visible beyond the per-drop ERROR log rather than failing silently (Luciferase-8kgil).
     */
    private final java.util.concurrent.atomic.AtomicLong droppedGhostCount = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Bidirectional codec for content types beyond {@code null}/{@code EntityType}. The wire carries
     * both {@code content.getClass().getName()} and the {@link #serialize} result, so
     * {@link #deserialize} receives the originating class name to dispatch on.
     *
     * @param <Content> entity content type
     */
    public interface ContentCodec<Content> {
        /**
         * Serialize content to its wire string form.
         *
         * @param content the (non-null, non-EntityType) content to serialize
         * @return the wire string; must be round-trippable by {@link #deserialize}
         */
        String serialize(Content content);

        /**
         * Reconstruct content from the wire class name and value produced by {@link #serialize}.
         *
         * @param contentClass the originating {@code content.getClass().getName()}
         * @param contentValue the value produced by {@link #serialize}
         * @return the reconstructed content
         */
        Content deserialize(String contentClass, String contentValue);
    }

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
        this.factory = new MessageFactory(clock);
    }

    /**
     * Number of inbound ghosts dropped because their content could not be decoded (no matching
     * {@link ContentCodec} on this receiver). A non-zero value signals codec misconfiguration or
     * cross-version content skew (Luciferase-8kgil).
     *
     * @return total dropped-ghost count since construction
     */
    public long droppedGhostCount() {
        return droppedGhostCount.get();
    }

    /**
     * Create P2P ghost channel with Bubble, defaulting the entity-id factory to the
     * project's {@link com.hellblazer.luciferase.simulation.entity.StringEntityID}.
     * <p>
     * Use this only when the caller's {@code ID} type is the project StringEntityID. For
     * any other ID type use {@link #P2PGhostChannel(Bubble, Function)} and supply a
     * matching deserializer; otherwise received ghosts will carry the wrong concrete ID
     * type (Luciferase-weaqr).
     *
     * @param vonBubble Bubble for P2P transport
     */
    @SuppressWarnings("unchecked")
    public P2PGhostChannel(Bubble vonBubble) {
        this(vonBubble, (Function<String, ID>) (Function<String, ?>)
             (Function<String, com.hellblazer.luciferase.simulation.entity.StringEntityID>)
             P2PGhostChannel::deserializeStringEntityId);
    }

    /**
     * Inverse of {@link com.hellblazer.luciferase.simulation.entity.StringEntityID#toDebugString()}
     * (the form the sender serializes into the wire token). StringEntityID renders as
     * {@code "Entity[<value>]"}; this strips the wrapper so the reconstructed id
     * {@code equals()} the original. Tokens not in that form are passed through verbatim.
     */
    private static com.hellblazer.luciferase.simulation.entity.StringEntityID deserializeStringEntityId(String token) {
        var value = token;
        if (token.startsWith("Entity[") && token.endsWith("]")) {
            value = token.substring("Entity[".length(), token.length() - 1);
        }
        return new com.hellblazer.luciferase.simulation.entity.StringEntityID(value);
    }

    /**
     * Create P2P ghost channel with Bubble and an explicit entity-id deserializer.
     *
     * @param vonBubble Bubble for P2P transport
     * @param idFactory Reconstructs the caller's concrete {@code ID} from a ghost's
     *                  serialized entity-id string (must match the sender's ID type)
     */
    public P2PGhostChannel(Bubble vonBubble, Function<String, ID> idFactory) {
        this(vonBubble, idFactory, null);
    }

    /**
     * Create P2P ghost channel with an explicit entity-id deserializer and a content codec for
     * content types beyond {@code null}/{@code EntityType}. Both peers must be constructed with a
     * codec that round-trips the same content types. A sender without a codec fails loud (throws
     * from {@link #sendBatch}); a receiver without a matching codec logs an ERROR, increments
     * {@link #droppedGhostCount()}, and drops only the un-decodable ghost — never silently nulling
     * content nor dropping the whole batch (Luciferase-8kgil).
     *
     * @param vonBubble    Bubble for P2P transport
     * @param idFactory    Reconstructs the caller's concrete {@code ID} from a ghost's serialized
     *                     entity-id string (must match the sender's ID type)
     * @param contentCodec Codec for non-null, non-EntityType content; {@code null} to support only
     *                     {@code null}/{@code EntityType} content (fail-loud on any other type)
     */
    public P2PGhostChannel(Bubble vonBubble, Function<String, ID> idFactory, ContentCodec<Content> contentCodec) {
        this.vonBubble = Objects.requireNonNull(vonBubble, "vonBubble must not be null");
        this.idFactory = Objects.requireNonNull(idFactory, "idFactory must not be null");
        this.contentCodec = contentCodec;
        this.factory = new MessageFactory(clock);
        this.pendingBatches = new ConcurrentHashMap<>();
        this.handlers = new CopyOnWriteArrayList<>();

        // Register for GhostSync events from Bubble
        vonBubble.addEventListener(eventListenerRef);

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
        var transportGhosts = new ArrayList<Message.TransportGhost>(ghosts.size());
        for (var ghost : ghosts) {
            transportGhosts.add(toTransportGhost(ghost));
        }

        // Send via Transport
        var message = factory.createGhostSync(vonBubble.id(), transportGhosts, currentBucket);
        try {
            vonBubble.getTransport().sendToNeighbor(targetBubbleId, message);
            log.debug("Sent {} ghosts to neighbor {} at bucket {}",
                      ghosts.size(), targetBubbleId, currentBucket);
        } catch (Transport.TransportException e) {
            log.warn("Failed to send ghost batch to {}: {}", targetBubbleId, e.getMessage());
        }
    }

    @Override
    public void flush(long bucket) {
        this.currentBucket = bucket;

        for (var entry : pendingBatches.entrySet()) {
            var targetId = entry.getKey();
            // Atomic swap: replace with empty list, get previous list
            var ghostsToSend = pendingBatches.put(targetId, new CopyOnWriteArrayList<>());
            if (ghostsToSend != null && !ghostsToSend.isEmpty()) {
                sendBatch(targetId, new ArrayList<>(ghostsToSend));
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
        vonBubble.removeEventListener(eventListenerRef);
        pendingBatches.clear();
        handlers.clear();
        log.debug("P2PGhostChannel closed for bubble {}", vonBubble.id());
    }

    /**
     * Handle events from Bubble.
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
     * @param event GhostSync event from Bubble
     */
    @SuppressWarnings("unchecked")
    private void onGhostSyncEvent(Event.GhostSync event) {
        var sourceId = event.sourceBubbleId();
        var transportGhosts = event.ghosts();

        if (transportGhosts.isEmpty()) {
            return;
        }

        // Convert from transport format. Deserialization is isolated per ghost: a single
        // un-decodable ghost (e.g. a content type with no registered ContentCodec) must neither
        // (a) silently drop to null content nor (b) abort the whole batch. Note that throwing here
        // would be swallowed at WARN by Bubble.emitEvent's listener catch — so we fail loud at ERROR
        // and a counter, and still deliver the decodable ghosts in the batch (Luciferase-8kgil).
        var ghosts = new ArrayList<SimulationGhostEntity<ID, Content>>(transportGhosts.size());
        for (var tg : transportGhosts) {
            try {
                ghosts.add(fromTransportGhost(tg, sourceId, event.bucket()));
            } catch (RuntimeException e) {
                droppedGhostCount.incrementAndGet();
                log.error("Dropping un-decodable ghost from {} (entityId={}): {}. Register a matching "
                          + "ContentCodec on this receiver to decode it (Luciferase-8kgil).",
                          sourceId, tg.entityId(), e.getMessage());
            }
        }

        // Notify all handlers
        for (var handler : handlers) {
            try {
                handler.accept(sourceId, ghosts);
            } catch (Exception e) {
                log.warn("Handler threw exception processing ghost batch from {}", sourceId, e);
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
    private Message.TransportGhost toTransportGhost(SimulationGhostEntity<ID, Content> ghost) {
        var content = ghost.content();
        var contentClass = content != null ? content.getClass().getName() : "null";
        var contentValue = serializeContent(content);

        return new Message.TransportGhost(
            ghost.entityId().toDebugString(),
            ghost.position(),
            contentClass,
            contentValue,
            ghost.sourceTreeId(),
            ghost.epoch(),
            ghost.version(),
            ghost.timestamp(),
            ghost.velocity()  // real velocity for dead-reckoning (Luciferase-chmxx)
        );
    }

    /**
     * Serialize content for transport.
     * <p>
     * Currently supports EntityType enum serialization via name().
     * For future content types, extend with custom serialization logic.
     *
     * @param content Content to serialize
     * @return Serialized content as String, or null
     */
    private String serializeContent(Content content) {
        if (content == null) {
            return null;
        }

        // Serialize EntityType enum as its name
        if (content instanceof com.hellblazer.luciferase.simulation.entity.EntityType entityType) {
            return entityType.name();
        }

        // Any other type requires an injected codec. Fail loud rather than silently dropping the
        // content to null on the wire, which previously corrupted every non-EntityType ghost on
        // cross-process delivery (Luciferase-8kgil).
        if (contentCodec != null) {
            return contentCodec.serialize(content);
        }
        throw new IllegalStateException(
            "No ContentCodec registered for ghost content type " + content.getClass().getName()
            + "; cross-process ghost delivery would silently drop it to null. Construct "
            + "P2PGhostChannel(Bubble, idFactory, ContentCodec) to serialize this type "
            + "(Luciferase-8kgil).");
    }

    /**
     * Convert TransportGhost back to SimulationGhostEntity.
     * <p>
     * Content is reconstructed for supported types (EntityType enum).
     * For unsupported types, content will be null.
     *
     * @param tg       TransportGhost to convert
     * @param sourceId Source bubble ID
     * @param bucket   Simulation bucket
     * @return SimulationGhostEntity with reconstructed content
     */
    private SimulationGhostEntity<ID, Content> fromTransportGhost(
        Message.TransportGhost tg,
        UUID sourceId,
        long bucket
    ) {
        // Deserialize content
        var content = deserializeContent(tg.contentClass(), tg.contentValue());

        // Reconstruct the caller's concrete ID type via the injected deserializer, so the
        // ghost's entityId is usable by handlers that type-check it (Luciferase-weaqr).
        var entityId = idFactory.apply(tg.entityId());

        // Create ghost entity with reconstructed content
        var internalGhost = new com.hellblazer.luciferase.lucien.forest.ghost.GhostEntityHalo<ID, Content>(
            entityId,
            content,  // Reconstructed content
            tg.position(),
            new com.hellblazer.luciferase.lucien.entity.EntityBounds(tg.position(), 0.5f),
            tg.sourceTreeId()
        );

        // Velocity plumbed from TransportGhost (Luciferase-chmxx): toTransportGhost() copies
        // SimulationGhostEntity.velocity() into the schema; here we read it back so the
        // received ghost carries real velocity for dead-reckoning.
        return new SimulationGhostEntity<>(
            internalGhost,
            sourceId,
            bucket,
            tg.epoch(),
            tg.version(),
            tg.velocity()  // real velocity from sender (Luciferase-chmxx)
        );
    }

    /**
     * Deserialize content from transport representation.
     * <p>
     * Currently supports EntityType enum deserialization via valueOf().
     * For future content types, extend with custom deserialization logic.
     *
     * @param contentClass Fully qualified class name
     * @param contentValue Serialized content value
     * @return Deserialized content, or null
     */
    @SuppressWarnings("unchecked")
    private Content deserializeContent(String contentClass, String contentValue) {
        if (contentClass == null || contentClass.equals("null") || contentValue == null) {
            return null;
        }

        // Deserialize EntityType enum
        if (contentClass.equals(com.hellblazer.luciferase.simulation.entity.EntityType.class.getName())) {
            try {
                return (Content) com.hellblazer.luciferase.simulation.entity.EntityType.valueOf(contentValue);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid EntityType value: {}", contentValue);
                return null;
            }
        }

        // Any other type requires an injected codec. Fail loud rather than silently dropping the
        // content to null, which corrupted every non-EntityType ghost on receipt (Luciferase-8kgil).
        if (contentCodec != null) {
            return contentCodec.deserialize(contentClass, contentValue);
        }
        throw new IllegalStateException(
            "No ContentCodec registered to deserialize ghost content of type " + contentClass
            + "; the received ghost would carry null content. Construct P2PGhostChannel(Bubble, "
            + "idFactory, ContentCodec) on the receiver to reconstruct this type (Luciferase-8kgil).");
    }
}
