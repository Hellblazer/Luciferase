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

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Client for communicating with remote GhostExchange services using virtual threads.
 * 
 * This client provides synchronous and asynchronous methods for requesting ghost
 * elements, streaming updates, and performing bulk synchronization with remote
 * processes. It uses connection pooling and virtual threads for scalability.
 * 
 * @param <Key> the type of spatial key used by the spatial index
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 * 
 * @author Hal Hildebrand
 */
public class GhostServiceClient<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(GhostServiceClient.class);
    
    // Connection management
    private final Map<Integer, ManagedChannel> channels;
    private final Map<Integer, GhostExchangeGrpc.GhostExchangeBlockingStub> blockingStubs;
    private final Map<Integer, GhostExchangeGrpc.GhostExchangeStub> asyncStubs;
    
    // Configuration
    private final int currentRank;
    private final ContentSerializer<Content> contentSerializer;
    private final Class<ID> entityIdClass;
    private final ServiceDiscovery serviceDiscovery;
    
    // Virtual thread executor for concurrent operations
    private final ExecutorService virtualExecutor;
    
    // Statistics
    private final AtomicLong requestCount;
    private final AtomicLong syncCount;
    private final AtomicLong streamCount;
    private final AtomicLong failureCount;
    
    // Active streaming connections
    private final Map<String, StreamingConnection> activeStreams;
    
    /**
     * Creates a new ghost service client.
     * 
     * @param currentRank the rank of this process
     * @param contentSerializer serializer for content objects
     * @param entityIdClass class for entity ID deserialization
     * @param serviceDiscovery service discovery mechanism
     */
    public GhostServiceClient(int currentRank,
                             ContentSerializer<Content> contentSerializer,
                             Class<ID> entityIdClass,
                             ServiceDiscovery serviceDiscovery) {
        this.currentRank = currentRank;
        this.contentSerializer = contentSerializer;
        this.entityIdClass = entityIdClass;
        this.serviceDiscovery = serviceDiscovery;
        this.channels = new ConcurrentHashMap<>();
        this.blockingStubs = new ConcurrentHashMap<>();
        this.asyncStubs = new ConcurrentHashMap<>();
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.requestCount = new AtomicLong(0);
        this.syncCount = new AtomicLong(0);
        this.streamCount = new AtomicLong(0);
        this.failureCount = new AtomicLong(0);
        this.activeStreams = new ConcurrentHashMap<>();
        
        log.info("GhostServiceClient initialized for rank {} with content type: {}", 
                currentRank, contentSerializer.getContentType());
    }
    
    /**
     * Requests ghost elements from a remote process synchronously.
     * 
     * @param targetRank the rank of the target process
     * @param treeId the tree ID to request ghosts for
     * @param ghostType the type of ghosts to request
     * @param boundaryKeys specific boundary keys to request (optional)
     * @return the ghost batch response, or null if request fails
     */
    public GhostBatch requestGhosts(int targetRank, long treeId, com.hellblazer.luciferase.lucien.forest.ghost.GhostType ghostType, 
                                   List<Key> boundaryKeys) {
        requestCount.incrementAndGet();
        
        try {
            var stub = getBlockingStub(targetRank);
            if (stub == null) {
                log.warn("No connection available to rank {}", targetRank);
                failureCount.incrementAndGet();
                return null;
            }
            
            var requestBuilder = GhostRequest.newBuilder()
                .setRequesterRank(currentRank)
                .setRequesterTreeId(treeId)
                .setGhostType(convertGhostType(ghostType));
            
            // Add boundary keys if specified
            if (boundaryKeys != null && !boundaryKeys.isEmpty()) {
                for (var key : boundaryKeys) {
                    requestBuilder.addBoundaryKeys(ProtobufConverters.spatialKeyToProtobuf(key));
                }
            }
            
            var request = requestBuilder.build();
            var response = stub.requestGhosts(request);
            
            log.debug("Received {} ghost elements from rank {}", 
                     response.getElementsCount(), targetRank);
            
            return response;
            
        } catch (StatusRuntimeException e) {
            log.error("Failed to request ghosts from rank {}: {}", targetRank, e.getStatus());
            failureCount.incrementAndGet();
            return null;
        }
    }
    
    /**
     * Requests ghost elements asynchronously using virtual threads.
     * 
     * @param targetRank the rank of the target process
     * @param treeId the tree ID to request ghosts for
     * @param ghostType the type of ghosts to request
     * @param boundaryKeys specific boundary keys to request (optional)
     * @return CompletableFuture with the ghost batch response
     */
    public CompletableFuture<GhostBatch> requestGhostsAsync(int targetRank, long treeId, 
                                                           com.hellblazer.luciferase.lucien.forest.ghost.GhostType ghostType, List<Key> boundaryKeys) {
        return CompletableFuture.supplyAsync(() -> 
            requestGhosts(targetRank, treeId, ghostType, boundaryKeys), virtualExecutor);
    }
    
    /**
     * Performs bulk ghost synchronization with a remote process.
     * 
     * @param targetRank the rank of the target process
     * @param treeIds list of tree IDs to synchronize
     * @param ghostType the type of ghosts to synchronize
     * @return the synchronization response, or null if request fails
     */
    public SyncResponse syncGhosts(int targetRank, List<Long> treeIds, com.hellblazer.luciferase.lucien.forest.ghost.GhostType ghostType) {
        syncCount.incrementAndGet();
        
        try {
            var stub = getBlockingStub(targetRank);
            if (stub == null) {
                log.warn("No connection available to rank {}", targetRank);
                failureCount.incrementAndGet();
                return null;
            }
            
            var request = SyncRequest.newBuilder()
                .setRequesterRank(currentRank)
                .addAllTreeIds(treeIds)
                .setGhostType(convertGhostType(ghostType))
                .setSince(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(0) // For now, sync all
                    .build())
                .build();
            
            var response = stub.syncGhosts(request);
            
            log.debug("Synchronized {} total elements from rank {} across {} trees", 
                     response.getTotalElements(), targetRank, treeIds.size());
            
            return response;
            
        } catch (StatusRuntimeException e) {
            log.error("Failed to sync ghosts with rank {}: {}", targetRank, e.getStatus());
            failureCount.incrementAndGet();
            return null;
        }
    }
    
    /**
     * Starts a streaming connection for real-time ghost updates.
     * 
     * @param targetRank the rank of the target process
     * @param updateHandler handler for processing incoming updates
     * @param errorHandler handler for processing errors
     * @return stream ID for managing the connection
     */
    public String startStreaming(int targetRank, 
                               Consumer<GhostUpdate> updateHandler,
                               Consumer<Throwable> errorHandler) {
        streamCount.incrementAndGet();
        
        var streamId = "stream-" + targetRank + "-" + System.currentTimeMillis();
        
        virtualExecutor.submit(() -> {
            try {
                var stub = getAsyncStub(targetRank);
                if (stub == null) {
                    log.warn("No async connection available to rank {}", targetRank);
                    errorHandler.accept(new RuntimeException("No connection to rank " + targetRank));
                    return;
                }
                
                var responseObserver = new StreamObserver<GhostAck>() {
                    @Override
                    public void onNext(GhostAck ack) {
                        if (!ack.getSuccess()) {
                            log.warn("Ghost update failed for entity {}: {}", 
                                   ack.getEntityId(), ack.getErrorMessage());
                        }
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        log.error("Streaming error to rank {}: {}", targetRank, t.getMessage());
                        activeStreams.remove(streamId);
                        errorHandler.accept(t);
                    }
                    
                    @Override
                    public void onCompleted() {
                        log.debug("Streaming to rank {} completed", targetRank);
                        activeStreams.remove(streamId);
                    }
                };
                
                var requestObserver = stub.streamGhostUpdates(responseObserver);
                var connection = new StreamingConnection(targetRank, requestObserver, updateHandler);
                activeStreams.put(streamId, connection);
                
                log.debug("Started streaming connection {} to rank {}", streamId, targetRank);
                
            } catch (Exception e) {
                log.error("Failed to start streaming to rank {}: {}", targetRank, e.getMessage(), e);
                errorHandler.accept(e);
            }
        });
        
        return streamId;
    }
    
    /**
     * Sends a ghost update through an active stream.
     * 
     * @param streamId the stream identifier
     * @param update the ghost update to send
     * @return true if sent successfully, false otherwise
     */
    public boolean sendStreamUpdate(String streamId, GhostUpdate update) {
        var connection = activeStreams.get(streamId);
        if (connection == null) {
            log.warn("No active stream found with ID: {}", streamId);
            return false;
        }
        
        try {
            connection.requestObserver.onNext(update);
            return true;
        } catch (Exception e) {
            log.error("Failed to send stream update on {}: {}", streamId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Closes a streaming connection.
     * 
     * @param streamId the stream identifier
     */
    public void closeStream(String streamId) {
        var connection = activeStreams.remove(streamId);
        if (connection != null) {
            try {
                connection.requestObserver.onCompleted();
                log.debug("Closed streaming connection: {}", streamId);
            } catch (Exception e) {
                log.warn("Error closing stream {}: {}", streamId, e.getMessage());
            }
        }
    }
    
    /**
     * Gets statistics from a remote process.
     * 
     * @param targetRank the rank of the target process
     * @return the statistics response, or null if request fails
     */
    public StatsResponse getRemoteStats(int targetRank) {
        try {
            var stub = getBlockingStub(targetRank);
            if (stub == null) {
                return null;
            }
            
            var request = StatsRequest.newBuilder()
                .setRequesterRank(currentRank)
                .build();
            
            return stub.getGhostStats(request);
            
        } catch (StatusRuntimeException e) {
            log.error("Failed to get stats from rank {}: {}", targetRank, e.getStatus());
            return null;
        }
    }
    
    /**
     * Gets or creates a blocking stub for the specified rank.
     */
    private GhostExchangeGrpc.GhostExchangeBlockingStub getBlockingStub(int targetRank) {
        return blockingStubs.computeIfAbsent(targetRank, rank -> {
            var channel = getChannel(rank);
            return channel != null ? GhostExchangeGrpc.newBlockingStub(channel) : null;
        });
    }
    
    /**
     * Gets or creates an async stub for the specified rank.
     */
    private GhostExchangeGrpc.GhostExchangeStub getAsyncStub(int targetRank) {
        return asyncStubs.computeIfAbsent(targetRank, rank -> {
            var channel = getChannel(rank);
            return channel != null ? GhostExchangeGrpc.newStub(channel) : null;
        });
    }
    
    /**
     * Gets or creates a channel for the specified rank.
     */
    private ManagedChannel getChannel(int targetRank) {
        return channels.computeIfAbsent(targetRank, rank -> {
            var endpoint = serviceDiscovery.getEndpoint(rank);
            if (endpoint == null) {
                log.warn("No endpoint found for rank {}", rank);
                return null;
            }
            
            log.debug("Creating channel to rank {} at {}", rank, endpoint);
            return ManagedChannelBuilder.forTarget(endpoint)
                .usePlaintext() // For development - use TLS in production
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
        });
    }
    
    /**
     * Gets client statistics for monitoring.
     * 
     * @return map of statistics
     */
    public Map<String, Object> getClientStats() {
        return Map.of(
            "rank", currentRank,
            "requestCount", requestCount.get(),
            "syncCount", syncCount.get(),
            "streamCount", streamCount.get(),
            "failureCount", failureCount.get(),
            "activeStreams", activeStreams.size(),
            "activeChannels", channels.size(),
            "contentType", contentSerializer.getContentType()
        );
    }
    
    /**
     * Shuts down the client and closes all connections.
     */
    public void shutdown() {
        log.info("Shutting down GhostServiceClient for rank {}", currentRank);
        
        // Close all active streams
        activeStreams.keySet().forEach(this::closeStream);
        
        // Shutdown all channels
        channels.values().forEach(channel -> {
            try {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        
        channels.clear();
        blockingStubs.clear();
        asyncStubs.clear();
        
        // Shutdown virtual thread executor
        virtualExecutor.shutdown();
    }
    
    /**
     * Represents an active streaming connection.
     */
    private static class StreamingConnection {
        final int targetRank;
        final StreamObserver<GhostUpdate> requestObserver;
        final Consumer<GhostUpdate> updateHandler;
        
        StreamingConnection(int targetRank, 
                          StreamObserver<GhostUpdate> requestObserver,
                          Consumer<GhostUpdate> updateHandler) {
            this.targetRank = targetRank;
            this.requestObserver = requestObserver;
            this.updateHandler = updateHandler;
        }
    }
    
    /**
     * Converts domain GhostType to protobuf GhostType.
     */
    private com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType convertGhostType(
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType ghostType) {
        return switch (ghostType) {
            case NONE -> com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType.NONE;
            case FACES -> com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType.FACES;
            case EDGES -> com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType.EDGES;
            case VERTICES -> com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType.VERTICES;
        };
    }
    
    /**
     * Interface for service discovery mechanism.
     */
    public interface ServiceDiscovery {
        
        /**
         * Gets the gRPC endpoint for a specific process rank.
         * 
         * @param rank the process rank
         * @return the endpoint (host:port), or null if not found
         */
        String getEndpoint(int rank);
        
        /**
         * Registers this process's endpoint.
         * 
         * @param rank the process rank
         * @param endpoint the endpoint (host:port)
         */
        void registerEndpoint(int rank, String endpoint);
        
        /**
         * Gets all known endpoints.
         * 
         * @return map of rank to endpoint
         */
        Map<Integer, String> getAllEndpoints();
    }
}