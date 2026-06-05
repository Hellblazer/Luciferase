/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Implementation of the GhostExchange gRPC service using virtual threads.
 * 
 * This service handles distributed ghost element exchange between processes
 * in a spatial index system. It uses virtual threads for scalable concurrent
 * processing and synchronous call patterns for simplified logic.
 * 
 * @param <Key> the type of spatial key used by the spatial index
 * @param <ID> the type of entity identifier  
 * @param <Content> the type of content stored in entities
 * 
 * @author Hal Hildebrand
 */
public class GhostExchangeServiceImpl<Key extends SpatialKey<Key>, ID extends EntityID, Content> 
        extends GhostExchangeGrpc.GhostExchangeImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(GhostExchangeServiceImpl.class);
    
    // Ghost layer provider function
    private final GhostLayerProvider<Key, ID, Content> ghostLayerProvider;
    
    // Content serializer for this service instance
    private final ContentSerializer<Content> contentSerializer;
    
    // Entity ID class for deserialization
    private final Class<ID> entityIdClass;

    // RDR-013 / Luciferase-06ujn: cap concurrent streaming sessions. maxInboundMessageSize bounds each message,
    // but does not limit the NUMBER of open StreamGhostUpdates streams — many cheap streams would otherwise pin
    // unbounded virtual-thread tasks + activeStreams entries (connection-level DoS).
    private static final int MAX_ACTIVE_STREAMS = 1024;
    
    // Virtual thread executor for concurrent operations
    private final ExecutorService virtualExecutor;
    
    // Statistics tracking
    private final AtomicLong requestCount;
    private final AtomicLong streamUpdateCount;
    private final AtomicLong syncRequestCount;

    // Clock for deterministic time — injectable for testing (Luciferase-7wzml.86)
    private volatile Clock clock = Clock.system();

    // Active streaming sessions
    private final Map<String, StreamSession> activeStreams;

    // Atomic counter for exact cap enforcement — incremented BEFORE map.put() so no two concurrent
    // callers can both observe count < cap and both be admitted (TOCTOU-free). Mirrors BalanceCoordinatorServer.
    private final AtomicInteger activeStreamCount = new AtomicInteger(0);
    
    /**
     * Creates a new ghost exchange service implementation.
     * 
     * @param ghostLayerProvider provider for accessing ghost layers
     * @param contentSerializer serializer for content objects
     * @param entityIdClass class for entity ID deserialization
     */
    public GhostExchangeServiceImpl(GhostLayerProvider<Key, ID, Content> ghostLayerProvider,
                                   ContentSerializer<Content> contentSerializer,
                                   Class<ID> entityIdClass) {
        this.ghostLayerProvider = ghostLayerProvider;
        this.contentSerializer = contentSerializer;
        this.entityIdClass = entityIdClass;
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.requestCount = new AtomicLong(0);
        this.streamUpdateCount = new AtomicLong(0);
        this.syncRequestCount = new AtomicLong(0);
        this.activeStreams = new ConcurrentHashMap<>();
        
        log.info("GhostExchangeService initialized with content type: {}",
                contentSerializer.getContentType());
    }

    /**
     * Replaces the clock used for Timestamp generation.  Inject a {@code TestClock} in tests
     * to produce deterministic, single-read timestamps (Luciferase-7wzml.86).
     *
     * @param clock the clock to use; must not be null
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Handles ghost element requests from remote processes.
     * 
     * @param request the ghost request
     * @param responseObserver observer for sending the response
     */
    @Override
    public void requestGhosts(GhostRequest request, StreamObserver<GhostBatch> responseObserver) {
        requestCount.incrementAndGet();
        
        // Use virtual thread for processing to avoid blocking the gRPC thread pool
        virtualExecutor.submit(() -> {
            try {
                log.debug("Processing ghost request from rank {} for tree {}", 
                         request.getRequesterRank(), request.getRequesterTreeId());
                
                var ghostLayer = ghostLayerProvider.getGhostLayer(request.getRequesterTreeId());
                if (ghostLayer == null) {
                    log.warn("No ghost layer found for tree ID: {}", request.getRequesterTreeId());
                    responseObserver.onNext(GhostBatch.newBuilder()
                        .setSourceRank(ghostLayerProvider.getCurrentRank())
                        .setSourceTreeId(request.getRequesterTreeId())
                        .build());
                    responseObserver.onCompleted();
                    return;
                }
                
                // Create response batch with relevant ghosts
                var batch = createGhostBatch(request, ghostLayer);
                responseObserver.onNext(batch);
                responseObserver.onCompleted();
                
                log.debug("Sent {} ghost elements to rank {}", 
                         batch.getElementsCount(), request.getRequesterRank());
                
            } catch (Exception e) {
                log.error("Error processing ghost request from rank {}: {}", 
                         request.getRequesterRank(), e.getMessage(), e);
                responseObserver.onError(e);
            }
        });
    }
    
    /**
     * Handles streaming ghost updates for real-time synchronization.
     *
     * <p>The concurrent-session cap is enforced atomically via {@link #activeStreamCount}:
     * the counter is incremented <em>before</em> the map put, so no two racing callers can
     * both observe {@code count < cap} and both be admitted (TOCTOU-free).
     * On rejection the counter is decremented immediately before returning. On stream close
     * (either {@code onCompleted} or {@code onError}) the counter is decremented exactly once
     * via {@code remove(sessionId) != null} to guard against double-decrement.
     * Mirrors BalanceCoordinatorServer.streamBalanceUpdates (Luciferase-7wzml.6 / Luciferase-zufll).
     *
     * @param responseObserver observer for sending acknowledgments
     * @return observer for receiving updates
     */
    @Override
    public StreamObserver<GhostUpdate> streamGhostUpdates(StreamObserver<GhostAck> responseObserver) {
        // RDR-013 / Luciferase-zufll: atomic cap — increment FIRST, then check.
        // If over cap, decrement and reject without touching the map (no leak-before-put).
        int count = activeStreamCount.incrementAndGet();
        if (count > MAX_ACTIVE_STREAMS) {
            activeStreamCount.decrementAndGet();
            log.warn("Rejecting ghost stream: {} active streams at cap {}", count - 1, MAX_ACTIVE_STREAMS);
            responseObserver.onError(Status.RESOURCE_EXHAUSTED
                                         .withDescription("too many concurrent ghost streams")
                                         .asRuntimeException());
            return new StreamObserver<GhostUpdate>() {
                @Override public void onNext(GhostUpdate update) { }
                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
        }
        var sessionId = "stream-" + streamUpdateCount.incrementAndGet();
        var session = new StreamSession(sessionId, responseObserver);
        activeStreams.put(sessionId, session);

        log.debug("Started streaming session: {}", sessionId);

        return new StreamObserver<GhostUpdate>() {
            @Override
            public void onNext(GhostUpdate update) {
                virtualExecutor.submit(() -> processStreamUpdate(session, update));
            }

            @Override
            public void onError(Throwable t) {
                log.error("Streaming error in session {}: {}", sessionId, t.getMessage(), t);
                if (activeStreams.remove(sessionId) != null) {
                    activeStreamCount.decrementAndGet();
                }
            }

            @Override
            public void onCompleted() {
                log.debug("Streaming session completed: {}", sessionId);
                if (activeStreams.remove(sessionId) != null) {
                    activeStreamCount.decrementAndGet();
                }
                responseObserver.onCompleted();
            }
        };
    }
    
    /**
     * Handles bulk ghost synchronization requests.
     * 
     * @param request the synchronization request
     * @param responseObserver observer for sending the response
     */
    @Override
    public void syncGhosts(SyncRequest request, StreamObserver<SyncResponse> responseObserver) {
        syncRequestCount.incrementAndGet();
        
        virtualExecutor.submit(() -> {
            try {
                log.debug("Processing sync request from rank {} for {} trees", 
                         request.getRequesterRank(), request.getTreeIdsCount());
                
                var responseBuilder = SyncResponse.newBuilder();
                int totalElements = 0;
                
                long now = clock.currentTimeMillis();

                // Process each requested tree
                for (var treeId : request.getTreeIdsList()) {
                    var ghostLayer = ghostLayerProvider.getGhostLayer(treeId);
                    if (ghostLayer != null) {
                        var batch = ProtobufConverters.ghostLayerToProtobufBatch(
                            ghostLayer,
                            ghostLayerProvider.getCurrentRank(),
                            treeId,
                            contentSerializer,
                            now);
                        responseBuilder.addBatches(batch);
                        totalElements += batch.getElementsCount();
                    }
                }

                var response = responseBuilder
                    .setTotalElements(totalElements)
                    .setSyncTime(com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(now / 1000)
                        .setNanos((int) ((now % 1000) * 1_000_000))
                        .build())
                    .build();
                
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                
                log.debug("Completed sync for rank {} with {} total elements", 
                         request.getRequesterRank(), totalElements);
                
            } catch (Exception e) {
                log.error("Error processing sync request from rank {}: {}", 
                         request.getRequesterRank(), e.getMessage(), e);
                responseObserver.onError(e);
            }
        });
    }
    
    /**
     * Provides ghost statistics for monitoring and debugging.
     * 
     * @param request the statistics request
     * @param responseObserver observer for sending the response
     */
    @Override
    public void getGhostStats(StatsRequest request, StreamObserver<StatsResponse> responseObserver) {
        virtualExecutor.submit(() -> {
            try {
                var stats = ghostLayerProvider.getGlobalStats();
                responseObserver.onNext(stats);
                responseObserver.onCompleted();
                
            } catch (Exception e) {
                log.error("Error getting ghost stats for rank {}: {}", 
                         request.getRequesterRank(), e.getMessage(), e);
                responseObserver.onError(e);
            }
        });
    }
    
    /**
     * Creates a ghost batch for a specific request.
     */
    private GhostBatch createGhostBatch(GhostRequest request, GhostLayer<Key, ID, Content> ghostLayer)
            throws ContentSerializer.SerializationException {

        long now = clock.currentTimeMillis();

        if (request.getBoundaryKeysCount() > 0) {
            // Request for specific boundary keys
            var batchBuilder = GhostBatch.newBuilder()
                .setSourceRank(ghostLayerProvider.getCurrentRank())
                .setSourceTreeId(request.getRequesterTreeId())
                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(now / 1000)
                    .setNanos((int) ((now % 1000) * 1_000_000))
                    .build());

            for (var keyProto : request.getBoundaryKeysList()) {
                var key = ProtobufConverters.spatialKeyFromProtobuf(keyProto);
                var elements = ghostLayer.getGhostElements((Key) key);
                for (var element : elements) {
                    batchBuilder.addElements(ProtobufConverters.ghostElementToProtobuf(element, contentSerializer, now));
                }
            }

            return batchBuilder.build();
        } else {
            // Request for all ghosts
            return ProtobufConverters.ghostLayerToProtobufBatch(
                ghostLayer,
                ghostLayerProvider.getCurrentRank(),
                request.getRequesterTreeId(),
                contentSerializer,
                now);
        }
    }
    
    /**
     * Processes a streaming ghost update.
     */
    private void processStreamUpdate(StreamSession session, GhostUpdate update) {
        try {
            boolean success = false;
            String errorMessage = null;
            String entityId = null;
            
            switch (update.getUpdateTypeCase()) {
                case INSERT -> {
                    var element = ProtobufConverters.<Key, ID, Content>ghostElementFromProtobuf(
                        update.getInsert(), contentSerializer, entityIdClass);
                    ghostLayerProvider.addGhostElement(element);
                    entityId = element.getEntityId().toString();
                    success = true;
                    log.debug("Inserted ghost element: {}", entityId);
                }
                case UPDATE -> {
                    var element = ProtobufConverters.<Key, ID, Content>ghostElementFromProtobuf(
                        update.getUpdate(), contentSerializer, entityIdClass);
                    ghostLayerProvider.updateGhostElement(element);
                    entityId = element.getEntityId().toString();
                    success = true;
                    log.debug("Updated ghost element: {}", entityId);
                }
                case REMOVE -> {
                    var removal = update.getRemove();
                    entityId = removal.getEntityId();
                    success = ghostLayerProvider.removeGhostElement(entityId, removal.getSourceTreeId());
                    if (success) {
                        log.debug("Removed ghost element: {}", entityId);
                    } else {
                        errorMessage = "Ghost element not found or layer absent: entityId=" + entityId
                                       + " treeId=" + removal.getSourceTreeId();
                        log.warn("REMOVE failed — element not found: entityId={} treeId={}", entityId,
                                 removal.getSourceTreeId());
                    }
                }
                case UPDATETYPE_NOT_SET -> {
                    errorMessage = "Update type not set";
                    log.warn("Received ghost update with no type set");
                }
            }
            
            // Send acknowledgment
            var ack = GhostAck.newBuilder()
                .setEntityId(entityId != null ? entityId : "unknown")
                .setSuccess(success)
                .setErrorMessage(errorMessage != null ? errorMessage : "")
                .build();
            
            session.responseObserver.onNext(ack);

        } catch (ProtobufConverters.UnsupportedEntityIdTypeException e) {
            // Whole-stream configuration error (Luciferase-m2k3u): the misconfigured entityIdClass fails EVERY
            // element identically, so surface it loudly via onError and terminate the stream rather than emitting a
            // silent per-element NACK loop that drops the whole batch (RDR-004 D3 / 7pias class).
            log.error("Unsupported EntityID type in stream session {}: {}", session.sessionId, e.getMessage(), e);
            session.responseObserver.onError(e);
            // Remove the terminated session (Luciferase-m2k3u review): this server-originated onError does not
            // trigger the client-callback removal path, so without this the session leaks in activeStreams and
            // shutdown() would call onCompleted() on an already-errored observer (gRPC terminal-event violation).
            // Decrement counter only if remove() returns non-null (guard against double-decrement — Luciferase-zufll).
            if (activeStreams.remove(session.sessionId) != null) {
                activeStreamCount.decrementAndGet();
            }
        } catch (Exception e) {
            log.error("Error processing stream update in session {}: {}",
                     session.sessionId, e.getMessage(), e);

            var ack = GhostAck.newBuilder()
                .setEntityId("unknown")
                .setSuccess(false)
                .setErrorMessage(e.getMessage())
                .build();

            session.responseObserver.onNext(ack);
        }
    }
    
    /**
     * Gets service statistics for monitoring.
     * 
     * @return map of statistics
     */
    public Map<String, Object> getServiceStats() {
        return Map.of(
            "requestCount", requestCount.get(),
            "streamUpdateCount", streamUpdateCount.get(),
            "syncRequestCount", syncRequestCount.get(),
            "activeStreams", activeStreams.size(),
            "contentType", contentSerializer.getContentType()
        );
    }
    
    /**
     * Shuts down the service and cleans up resources.
     */
    public void shutdown() {
        log.info("Shutting down GhostExchangeService");
        
        // Close all active streams.
        // Safety: processStreamUpdate's onError path calls activeStreams.remove(sessionId) BEFORE
        // calling responseObserver.onError(), so any session that already received onError is gone
        // from the map before this forEach iterates it. The forEach therefore never calls
        // onCompleted() on a stream that has already received a terminal onError event.
        activeStreams.values().forEach(session -> {
            try {
                session.responseObserver.onCompleted();
            } catch (Exception e) {
                log.warn("Error closing stream session {}: {}", session.sessionId, e.getMessage());
            }
        });
        activeStreams.clear();
        // Reset to 0 rather than addAndGet(-cleared): a stream admitted concurrently between the
        // forEach above and this clear() would be erased by clear() without a paired decrement
        // (its onCompleted/onError finds remove()==null and skips), leaving the counter negative.
        // After shutdown the map is empty, so the count is definitively 0. Mirrors BalanceCoordinatorServer.
        activeStreamCount.set(0);

        // Shutdown virtual thread executor
        virtualExecutor.shutdown();
    }
    
    /**
     * Represents an active streaming session.
     */
    private static class StreamSession {
        final String sessionId;
        final StreamObserver<GhostAck> responseObserver;
        
        StreamSession(String sessionId, StreamObserver<GhostAck> responseObserver) {
            this.sessionId = sessionId;
            this.responseObserver = responseObserver;
        }
    }
    
    /**
     * Interface for providing access to ghost layers and related operations.
     */
    public interface GhostLayerProvider<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
        
        /**
         * Gets the ghost layer for a specific tree ID.
         * 
         * @param treeId the tree identifier
         * @return the ghost layer, or null if not found
         */
        GhostLayer<Key, ID, Content> getGhostLayer(long treeId);
        
        /**
         * Gets the current process rank.
         * 
         * @return the process rank
         */
        int getCurrentRank();
        
        /**
         * Adds a ghost element to the appropriate layer.
         * 
         * @param element the ghost element to add
         */
        void addGhostElement(GhostElement<Key, ID, Content> element);
        
        /**
         * Updates an existing ghost element.
         * 
         * @param element the updated ghost element
         */
        void updateGhostElement(GhostElement<Key, ID, Content> element);
        
        /**
         * Removes a ghost element.
         *
         * @param entityId the entity ID to remove
         * @param treeId   the tree ID
         * @return {@code true} if at least one element with the given entityId was removed,
         *         {@code false} if the layer was absent or no matching element was found
         */
        boolean removeGhostElement(String entityId, long treeId);
        
        /**
         * Gets global statistics across all ghost layers.
         * 
         * @return global statistics response
         */
        StatsResponse getGlobalStats();
    }
}