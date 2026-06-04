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

import com.hellblazer.luciferase.common.grpc.GrpcCredentialFactory;
import com.hellblazer.luciferase.common.grpc.GrpcServerHardening;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.ServiceDiscovery;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.*;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages ghost communication between distributed spatial index processes.
 * 
 * This class coordinates both server (receiving requests) and client (sending requests)
 * aspects of ghost communication. It provides a unified interface for all ghost-related
 * network operations and manages the lifecycle of gRPC connections.
 * 
 * @param <Key> the type of spatial key used by the spatial index
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 * 
 * @author Hal Hildebrand
 */
public class GhostCommunicationManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(GhostCommunicationManager.class);
    
    // Configuration
    private final int currentRank;
    private final String bindAddress;
    private final int port;
    private final ContentSerializer<Content> contentSerializer;
    private final Class<ID> entityIdClass;
    
    // gRPC components
    private final Server server;
    private final GhostExchangeServiceImpl<Key, ID, Content> serviceImpl;
    private final GhostServiceClient<Key, ID, Content> client;
    private final ServiceDiscovery serviceDiscovery;
    
    // Ghost layer management
    private final Map<Long, GhostLayer<Key, ID, Content>> ghostLayers;
    private final AtomicBoolean running;
    
    /**
     * Creates a new ghost communication manager.
     * 
     * @param currentRank the rank of this process
     * @param bindAddress the address to bind the server to
     * @param port the port to bind the server to
     * @param contentSerializer serializer for content objects
     * @param entityIdClass class for entity ID deserialization
     * @param serviceDiscovery service discovery mechanism
     */
    public GhostCommunicationManager(int currentRank,
                                   String bindAddress,
                                   int port,
                                   ContentSerializer<Content> contentSerializer,
                                   Class<ID> entityIdClass,
                                   ServiceDiscovery serviceDiscovery) {
        this(currentRank, bindAddress, port, contentSerializer, entityIdClass, serviceDiscovery,
             null, GrpcCredentialFactory.insecureChannel());
    }

    /**
     * Creates a new ghost communication manager with explicit transport credentials (RDR-005).
     *
     * @param currentRank the rank of this process
     * @param bindAddress the address to bind the server to
     * @param port the port to bind the server to
     * @param contentSerializer serializer for content objects
     * @param entityIdClass class for entity ID deserialization
     * @param serviceDiscovery service discovery mechanism
     * @param serverAuth server transport credentials + the matching {@code PeerAuthInterceptor} as one
     *        unit (from {@code GrpcCredentialFactory.serverAuth(...)}); {@code null} for an insecure
     *        (plaintext, no auth) server suitable for in-process/test use
     * @param channelCredentials outbound channel credentials for the embedded client —
     *        {@code GrpcCredentialFactory.insecureChannel()} or {@code .mtlsChannel(...)}
     */
    public GhostCommunicationManager(int currentRank,
                                   String bindAddress,
                                   int port,
                                   ContentSerializer<Content> contentSerializer,
                                   Class<ID> entityIdClass,
                                   ServiceDiscovery serviceDiscovery,
                                   GrpcCredentialFactory.ServerAuth serverAuth,
                                   ChannelCredentials channelCredentials) {
        this(currentRank, bindAddress, port, contentSerializer, entityIdClass, serviceDiscovery, serverAuth,
             channelCredentials, GrpcServerHardening.DEFAULT_MAX_INBOUND_MESSAGE_BYTES);
    }

    /**
     * Creates a new ghost communication manager with explicit transport credentials (RDR-005) and an explicit
     * inbound message-size bound (RDR-013, Luciferase-06ujn).
     *
     * @param serverAuth server transport credentials + matching {@code PeerAuthInterceptor}, or {@code null} for
     *        an insecure (plaintext, no auth) server
     * @param channelCredentials outbound channel credentials for the embedded client
     * @param maxInboundMessageBytes explicit inbound message-size limit applied to the gRPC server (DoS bound);
     *        must be positive — use {@link GrpcServerHardening#DEFAULT_MAX_INBOUND_MESSAGE_BYTES} unless a tighter
     *        bound is required on a hostile network
     */
    public GhostCommunicationManager(int currentRank,
                                   String bindAddress,
                                   int port,
                                   ContentSerializer<Content> contentSerializer,
                                   Class<ID> entityIdClass,
                                   ServiceDiscovery serviceDiscovery,
                                   GrpcCredentialFactory.ServerAuth serverAuth,
                                   ChannelCredentials channelCredentials,
                                   int maxInboundMessageBytes) {
        this.currentRank = currentRank;
        this.bindAddress = bindAddress;
        this.port = port;
        this.contentSerializer = contentSerializer;
        this.entityIdClass = entityIdClass;
        this.serviceDiscovery = serviceDiscovery;
        this.ghostLayers = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);

        // Create ghost layer provider
        var ghostLayerProvider = new GhostLayerProviderImpl();

        // Create service implementation
        this.serviceImpl = new GhostExchangeServiceImpl<>(
            ghostLayerProvider, contentSerializer, entityIdClass);

        // Create gRPC server (RDR-005: insecure by default). When a ServerAuth is supplied, its
        // (trust-any) mTLS credentials and a PeerAuthInterceptor are installed together, so bare creds
        // cannot be passed without *an* interceptor. The interceptor's PeerVerifier must be a real
        // cryptographic verifier (the deferred FirefliesPeerVerifier) for the server to actually
        // authenticate peers — prefer GrpcCredentialFactory.serverAuth(key, cert, verifier) over
        // hand-rolling a ServerAuth, which could pair trust-any creds with a non-verifying interceptor.
        var serverCredentials = serverAuth == null
            ? GrpcCredentialFactory.insecureServer() : serverAuth.credentials();
        var serverBuilder = Grpc.newServerBuilderForPort(port, serverCredentials);
        serverBuilder.addService(serviceImpl);
        if (serverAuth != null) {
            serverBuilder.intercept(serverAuth.interceptor());
        }
        // RDR-013 / Luciferase-06ujn: explicit, tunable inbound size bound (DoS surface) instead of relying on
        // gRPC's invisible 4 MiB default.
        GrpcServerHardening.applyInboundLimit(serverBuilder, maxInboundMessageBytes);
        this.server = serverBuilder.build();

        // Create client with the outbound channel credentials
        this.client = new GhostServiceClient<>(
            currentRank, contentSerializer, entityIdClass, serviceDiscovery, channelCredentials);

        log.info("GhostCommunicationManager created for rank {} on {}:{}",
                currentRank, bindAddress, port);
    }
    
    /**
     * Starts the ghost communication services.
     * 
     * @throws IOException if the server fails to start
     */
    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            // Start gRPC server
            server.start();
            
            // Register our endpoint
            serviceDiscovery.registerEndpoint(currentRank, bindAddress + ":" + port);
            
            log.info("GhostCommunicationManager started for rank {} on {}:{}", 
                    currentRank, bindAddress, port);
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down GhostCommunicationManager due to JVM shutdown");
                shutdown();
            }));
        }
    }
    
    /**
     * Stops the ghost communication services.
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            log.info("Shutting down GhostCommunicationManager for rank {}", currentRank);
            
            // Shutdown client
            client.shutdown();
            
            // Shutdown service implementation
            serviceImpl.shutdown();
            
            // Shutdown server
            server.shutdown();
            try {
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            log.info("GhostCommunicationManager shutdown complete for rank {}", currentRank);
        }
    }
    
    /**
     * Adds a ghost layer for a specific tree.
     * 
     * @param treeId the tree identifier
     * @param ghostLayer the ghost layer
     */
    public void addGhostLayer(long treeId, GhostLayer<Key, ID, Content> ghostLayer) {
        ghostLayers.put(treeId, ghostLayer);
        log.debug("Added ghost layer for tree {}", treeId);
    }
    
    /**
     * Removes a ghost layer for a specific tree.
     * 
     * @param treeId the tree identifier
     * @return the removed ghost layer, or null if none existed
     */
    public GhostLayer<Key, ID, Content> removeGhostLayer(long treeId) {
        var removed = ghostLayers.remove(treeId);
        if (removed != null) {
            log.debug("Removed ghost layer for tree {}", treeId);
        }
        return removed;
    }
    
    /**
     * Gets a ghost layer for a specific tree.
     * 
     * @param treeId the tree identifier
     * @return the ghost layer, or null if none exists
     */
    public GhostLayer<Key, ID, Content> getGhostLayer(long treeId) {
        return ghostLayers.get(treeId);
    }
    
    /**
     * Requests ghost elements from a remote process.
     * 
     * @param targetRank the rank of the target process
     * @param treeId the tree ID to request ghosts for
     * @param ghostType the type of ghosts to request
     * @param boundaryKeys specific boundary keys to request (optional)
     * @return the ghost batch response, or null if request fails
     */
    public GhostBatch requestGhosts(int targetRank, long treeId, com.hellblazer.luciferase.lucien.forest.ghost.GhostType ghostType, 
                                   List<Key> boundaryKeys) {
        return client.requestGhosts(targetRank, treeId, ghostType, boundaryKeys);
    }
    
    /**
     * Requests ghost elements asynchronously.
     * 
     * @param targetRank the rank of the target process
     * @param treeId the tree ID to request ghosts for
     * @param ghostType the type of ghosts to request
     * @param boundaryKeys specific boundary keys to request (optional)
     * @return CompletableFuture with the ghost batch response
     */
    public CompletableFuture<GhostBatch> requestGhostsAsync(int targetRank, long treeId, 
                                                           com.hellblazer.luciferase.lucien.forest.ghost.GhostType ghostType, List<Key> boundaryKeys) {
        return client.requestGhostsAsync(targetRank, treeId, ghostType, boundaryKeys);
    }
    
    /**
     * Synchronizes ghosts with a remote process.
     * 
     * @param targetRank the rank of the target process
     * @param treeIds list of tree IDs to synchronize
     * @param ghostType the type of ghosts to synchronize
     * @return the synchronization response, or null if request fails
     */
    public SyncResponse syncGhosts(int targetRank, List<Long> treeIds, com.hellblazer.luciferase.lucien.forest.ghost.GhostType ghostType) {
        return client.syncGhosts(targetRank, treeIds, ghostType);
    }
    
    /**
     * Synchronizes ghosts with multiple remote processes.
     * 
     * @param targetRanks the ranks of the target processes
     * @param treeIds list of tree IDs to synchronize
     * @param ghostType the type of ghosts to synchronize
     * @return map of rank to synchronization response
     */
    public Map<Integer, SyncResponse> syncGhostsWithMultiple(Set<Integer> targetRanks, 
                                                           List<Long> treeIds, 
                                                           com.hellblazer.luciferase.lucien.forest.ghost.GhostType ghostType) {
        var futures = new ConcurrentHashMap<Integer, CompletableFuture<SyncResponse>>();
        
        // Start all sync operations concurrently
        for (var rank : targetRanks) {
            if (rank != currentRank) {
                futures.put(rank, CompletableFuture.supplyAsync(() -> 
                    syncGhosts(rank, treeIds, ghostType)));
            }
        }
        
        // Collect results
        var results = new ConcurrentHashMap<Integer, SyncResponse>();
        futures.forEach((rank, future) -> {
            try {
                var response = future.get(30, TimeUnit.SECONDS);
                if (response != null) {
                    results.put(rank, response);
                }
            } catch (Exception e) {
                log.error("Failed to sync with rank {}: {}", rank, e.getMessage());
            }
        });
        
        return results;
    }
    
    /**
     * Gets statistics from a remote process.
     * 
     * @param targetRank the rank of the target process
     * @return the statistics response, or null if request fails
     */
    public StatsResponse getRemoteStats(int targetRank) {
        return client.getRemoteStats(targetRank);
    }
    
    /**
     * Gets comprehensive statistics from all known processes.
     * 
     * @return map of rank to statistics response
     */
    public Map<Integer, StatsResponse> getAllRemoteStats() {
        var endpoints = serviceDiscovery.getAllEndpoints();
        var results = new ConcurrentHashMap<Integer, StatsResponse>();
        
        var futures = new ConcurrentHashMap<Integer, CompletableFuture<StatsResponse>>();
        endpoints.keySet().stream()
            .filter(rank -> rank != currentRank)
            .forEach(rank -> futures.put(rank, CompletableFuture.supplyAsync(() -> 
                getRemoteStats(rank))));
        
        futures.forEach((rank, future) -> {
            try {
                var stats = future.get(10, TimeUnit.SECONDS);
                if (stats != null) {
                    results.put(rank, stats);
                }
            } catch (Exception e) {
                log.debug("Failed to get stats from rank {}: {}", rank, e.getMessage());
            }
        });
        
        return results;
    }
    
    /**
     * Gets local statistics for this process.
     * 
     * @return local statistics
     */
    public Map<String, Object> getLocalStats() {
        var stats = new ConcurrentHashMap<String, Object>();
        
        // Add basic info
        stats.put("rank", currentRank);
        stats.put("port", port);
        stats.put("running", running.get());
        stats.put("ghostLayerCount", ghostLayers.size());
        stats.put("contentType", contentSerializer.getContentType());
        
        // Add service stats
        stats.putAll(serviceImpl.getServiceStats());
        
        // Add client stats
        stats.putAll(client.getClientStats());
        
        // Add ghost layer stats
        long totalGhosts = 0;
        long totalRemotes = 0;
        for (var layer : ghostLayers.values()) {
            totalGhosts += layer.getNumGhostElements();
            totalRemotes += layer.getNumRemoteElements();
        }
        stats.put("totalGhostElements", totalGhosts);
        stats.put("totalRemoteElements", totalRemotes);
        
        return stats;
    }
    
    /**
     * Checks if the communication manager is running.
     * 
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Gets the current process rank.
     * 
     * @return the process rank
     */
    public int getCurrentRank() {
        return currentRank;
    }
    
    /**
     * Gets the server port.
     * 
     * @return the server port
     */
    public int getPort() {
        return port;
    }
    
    /**
     * Gets the service discovery mechanism.
     * 
     * @return the service discovery
     */
    public ServiceDiscovery getServiceDiscovery() {
        return serviceDiscovery;
    }
    
    /**
     * Implementation of GhostLayerProvider for the service.
     */
    private class GhostLayerProviderImpl implements GhostExchangeServiceImpl.GhostLayerProvider<Key, ID, Content> {
        
        @Override
        public GhostLayer<Key, ID, Content> getGhostLayer(long treeId) {
            return ghostLayers.get(treeId);
        }
        
        @Override
        public int getCurrentRank() {
            return currentRank;
        }
        
        @Override
        public void addGhostElement(GhostElement<Key, ID, Content> element) {
            var layer = ghostLayers.get(element.getGlobalTreeId());
            if (layer != null) {
                layer.addGhostElement(element);
            } else {
                log.warn("No ghost layer found for tree ID: {}", element.getGlobalTreeId());
            }
        }
        
        @Override
        public void updateGhostElement(GhostElement<Key, ID, Content> element) {
            // For now, treating update the same as add
            // In a more sophisticated implementation, this could update existing elements
            addGhostElement(element);
        }
        
        @Override
        public boolean removeGhostElement(String entityId, long treeId) {
            var layer = ghostLayers.get(treeId);
            if (layer == null) {
                log.warn("removeGhostElement: no ghost layer for treeId={}", treeId);
                return false;
            }
            // Deserialise the wire entityId to a typed ID using the SAME contract used everywhere
            // else in the ghost gRPC path (ProtobufConverters.createEntityId / ghostElementFromProtobuf).
            // Matching on the typed ID's equals() is robust against toString() format divergence
            // (UUID case normalisation, LongEntityID decoration, custom EntityID implementations).
            // Falling back to raw-string comparison would silently fail whenever the wire format
            // differs from the stored ID's toString() (Luciferase-7wzml.1 / RDR-004 D3 class).
            final ID typedId;
            try {
                typedId = ProtobufConverters.createEntityId(entityId, entityIdClass);
            } catch (ProtobufConverters.UnsupportedEntityIdTypeException e) {
                log.error("removeGhostElement: unsupported entityIdClass {} — cannot deserialise wire entityId={}",
                          entityIdClass, entityId, e);
                return false;
            } catch (IllegalArgumentException e) {
                log.warn("removeGhostElement: malformed wire entityId='{}' for class {}: {}",
                         entityId, entityIdClass.getSimpleName(), e.getMessage());
                return false;
            }
            // Collect all matching elements first (getAllGhostElements holds a read-lock snapshot)
            // then remove each one. GhostLayer.removeGhostElement returns true on actual removal.
            var toRemove = layer.getAllGhostElements()
                                .stream()
                                .filter(e -> typedId.equals(e.getEntityId()))
                                .toList();
            if (toRemove.isEmpty()) {
                log.debug("removeGhostElement: no element with entityId={} in treeId={}", entityId, treeId);
                return false;
            }
            boolean removed = false;
            for (var element : toRemove) {
                removed |= layer.removeGhostElement(element.getSpatialKey(), element);
            }
            return removed;
        }
        
        @Override
        public StatsResponse getGlobalStats() {
            var responseBuilder = StatsResponse.newBuilder();
            
            int totalGhosts = 0;
            int totalRemotes = 0;
            Map<Integer, Integer> ghostsPerRank = new ConcurrentHashMap<>();
            Map<Integer, Integer> remotesPerRank = new ConcurrentHashMap<>();
            
            for (var layer : ghostLayers.values()) {
                totalGhosts += layer.getNumGhostElements();
                totalRemotes += layer.getNumRemoteElements();
                
                // Collect per-rank statistics
                for (var element : layer.getAllGhostElements()) {
                    ghostsPerRank.merge(element.getOwnerRank(), 1, Integer::sum);
                }
                
                for (var rank : layer.getRemoteRanks()) {
                    var remoteElements = layer.getRemoteElements(rank);
                    remotesPerRank.put(rank, remoteElements.size());
                }
            }
            
            return responseBuilder
                .setTotalGhostElements(totalGhosts)
                .setTotalRemoteElements(totalRemotes)
                .putAllGhostsPerRank(ghostsPerRank)
                .putAllRemotesPerRank(remotesPerRank)
                .build();
        }
    }
}