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

package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostCommunicationManager;
import com.hellblazer.luciferase.lucien.forest.ghost.grpc.SimpleServiceDiscovery;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages distributed ghost layers across multiple processes.
 * 
 * This class coordinates ghost element creation, synchronization, and updates
 * between distributed spatial index processes using gRPC communication.
 * It integrates the spatial index core with the ghost communication infrastructure.
 * 
 * @param <Key> the type of spatial key used by the spatial index
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 * 
 * @author Hal Hildebrand
 */
public class DistributedGhostManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(DistributedGhostManager.class);
    
    private final AbstractSpatialIndex<Key, ID, Content> spatialIndex;
    private final GhostCommunicationManager<Key, ID, Content> communicationManager;
    private final ElementGhostManager<Key, ID, Content> localGhostManager;
    private final ContentSerializer<Content> contentSerializer;
    private final Class<ID> entityIdClass;
    
    // Configuration
    private final int currentRank;
    private final long treeId;
    private final GhostType ghostType;
    private final GhostAlgorithm ghostAlgorithm;
    
    // Process management
    private final Set<Integer> knownRanks;
    private final Map<Key, Integer> elementOwners;
    private final Map<Integer, Set<Key>> remoteElements;
    
    // Synchronization control
    private volatile boolean autoSyncEnabled = true;
    private volatile long lastSyncTime = 0;
    private volatile long syncIntervalMs = 30000; // 30 seconds default
    
    /**
     * Create a distributed ghost manager.
     * 
     * @param spatialIndex the spatial index to manage ghosts for
     * @param communicationManager the gRPC communication manager
     * @param localGhostManager the local ghost manager
     * @param contentSerializer the content serializer
     * @param entityIdClass the entity ID class for deserialization
     * @param currentRank the rank of this process
     * @param treeId the tree identifier
     */
    public DistributedGhostManager(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                                  GhostCommunicationManager<Key, ID, Content> communicationManager,
                                  ElementGhostManager<Key, ID, Content> localGhostManager,
                                  ContentSerializer<Content> contentSerializer,
                                  Class<ID> entityIdClass,
                                  int currentRank,
                                  long treeId) {
        this.spatialIndex = Objects.requireNonNull(spatialIndex);
        this.communicationManager = Objects.requireNonNull(communicationManager);
        this.localGhostManager = Objects.requireNonNull(localGhostManager);
        this.contentSerializer = Objects.requireNonNull(contentSerializer);
        this.entityIdClass = Objects.requireNonNull(entityIdClass);
        this.currentRank = currentRank;
        this.treeId = treeId;
        this.ghostType = spatialIndex.getGhostType();
        this.ghostAlgorithm = spatialIndex.getGhostCreationAlgorithm();
        
        this.knownRanks = new CopyOnWriteArraySet<>();
        this.elementOwners = new ConcurrentHashMap<>();
        this.remoteElements = new ConcurrentHashMap<>();
        
        // Register our ghost layer with the communication manager
        communicationManager.addGhostLayer(treeId, spatialIndex.getGhostLayer());
        
        log.info("Created distributed ghost manager for rank {} tree {} with type {} algorithm {}", 
                currentRank, treeId, ghostType, ghostAlgorithm);
    }
    
    /**
     * Initialize the distributed ghost layer by discovering other processes
     * and performing initial synchronization.
     */
    public void initialize() {
        log.info("Initializing distributed ghost layer for rank {}", currentRank);
        
        // Discover other processes
        discoverProcesses();
        
        // Perform initial synchronization
        if (!knownRanks.isEmpty()) {
            performInitialSync();
        }
        
        log.info("Distributed ghost layer initialized with {} known processes", knownRanks.size());
    }
    
    /**
     * Create or update the distributed ghost layer.
     * This coordinates with other processes to exchange ghost elements.
     */
    public void createDistributedGhostLayer() {
        log.info("Creating distributed ghost layer for rank {}", currentRank);
        
        // First create local ghost layer to identify boundary elements
        localGhostManager.createGhostLayer();
        
        // Exchange ghost information with other processes
        if (!knownRanks.isEmpty()) {
            exchangeGhostElements();
        }
        
        log.info("Distributed ghost layer creation complete");
    }
    
    /**
     * Update the distributed ghost layer after spatial index modifications.
     */
    public void updateDistributedGhostLayer() {
        log.info("Updating distributed ghost layer for rank {}", currentRank);
        
        // Update local ghost layer
        localGhostManager.createGhostLayer(); // Recreate for now
        
        // Synchronize with other processes if auto-sync is enabled
        if (autoSyncEnabled && shouldPerformSync()) {
            synchronizeWithAllProcesses();
        }
        
        log.info("Distributed ghost layer update complete");
    }
    
    /**
     * Synchronize ghost elements with a specific process.
     * 
     * @param targetRank the rank of the target process
     * @return true if synchronization was successful
     */
    public boolean synchronizeWithProcess(int targetRank) {
        if (targetRank == currentRank) {
            return true; // No need to sync with ourselves
        }
        
        log.debug("Synchronizing ghost elements with rank {}", targetRank);
        
        try {
            var response = communicationManager.syncGhosts(targetRank, List.of(treeId), ghostType);
            if (response != null) {
                processGhostSyncResponse(response, targetRank);
                return true;
            }
        } catch (Exception e) {
            log.error("Failed to synchronize with rank {}: {}", targetRank, e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Synchronize with all known processes.
     */
    public void synchronizeWithAllProcesses() {
        if (knownRanks.isEmpty()) {
            log.debug("No known processes to synchronize with");
            return;
        }
        
        log.info("Synchronizing with {} known processes", knownRanks.size());
        
        var futures = new ArrayList<CompletableFuture<Boolean>>();
        
        for (var rank : knownRanks) {
            if (rank != currentRank) {
                var future = CompletableFuture.supplyAsync(() -> synchronizeWithProcess(rank));
                futures.add(future);
            }
        }
        
        // Wait for all synchronizations to complete
        var results = futures.stream()
                            .map(f -> {
                                try {
                                    return f.get(10, TimeUnit.SECONDS);
                                } catch (Exception e) {
                                    log.warn("Sync operation failed: {}", e.getMessage());
                                    return false;
                                }
                            })
                            .collect(Collectors.toList());
        
        var successCount = (int) results.stream().mapToInt(r -> r ? 1 : 0).sum();
        log.info("Synchronization complete: {}/{} processes successful", successCount, results.size());
        
        lastSyncTime = System.currentTimeMillis();
    }
    
    /**
     * Add a known process rank for ghost communication.
     * 
     * @param rank the process rank to add
     */
    public void addKnownProcess(int rank) {
        if (rank != currentRank) {
            knownRanks.add(rank);
            log.debug("Added known process rank: {}", rank);
        }
    }
    
    /**
     * Remove a process rank (e.g., if it becomes unavailable).
     * 
     * @param rank the process rank to remove
     */
    public void removeKnownProcess(int rank) {
        knownRanks.remove(rank);
        remoteElements.remove(rank);
        log.debug("Removed process rank: {}", rank);
    }
    
    /**
     * Set element ownership information for distributed ghost detection.
     * 
     * @param key the spatial key
     * @param ownerRank the rank of the process that owns this element
     */
    public void setElementOwner(Key key, int ownerRank) {
        elementOwners.put(key, ownerRank);
        
        // Track remote elements by process
        if (ownerRank != currentRank) {
            remoteElements.computeIfAbsent(ownerRank, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }
    
    /**
     * Get the owner rank for a spatial key.
     * 
     * @param key the spatial key
     * @return the owner rank, or current rank if not found
     */
    public int getElementOwner(Key key) {
        return elementOwners.getOrDefault(key, currentRank);
    }
    
    /**
     * Enable or disable automatic synchronization.
     * 
     * @param enabled true to enable auto-sync, false to disable
     */
    public void setAutoSyncEnabled(boolean enabled) {
        this.autoSyncEnabled = enabled;
        log.info("Auto-sync {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Set the synchronization interval in milliseconds.
     * 
     * @param intervalMs the sync interval in milliseconds
     */
    public void setSyncInterval(long intervalMs) {
        this.syncIntervalMs = intervalMs;
        log.info("Sync interval set to {} ms", intervalMs);
    }
    
    /**
     * Get statistics about the distributed ghost layer.
     * 
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        var stats = new HashMap<String, Object>();
        stats.put("currentRank", currentRank);
        stats.put("treeId", treeId);
        stats.put("ghostType", ghostType);
        stats.put("ghostAlgorithm", ghostAlgorithm);
        stats.put("knownProcesses", knownRanks.size());
        stats.put("trackedElements", elementOwners.size());
        stats.put("autoSyncEnabled", autoSyncEnabled);
        stats.put("lastSyncTime", lastSyncTime);
        stats.put("syncIntervalMs", syncIntervalMs);
        
        // Add remote element counts per process
        var remoteElementCounts = new HashMap<Integer, Integer>();
        for (var entry : remoteElements.entrySet()) {
            remoteElementCounts.put(entry.getKey(), entry.getValue().size());
        }
        stats.put("remoteElementsPerProcess", remoteElementCounts);
        
        return stats;
    }
    
    /**
     * Shutdown the distributed ghost manager.
     */
    public void shutdown() {
        log.info("Shutting down distributed ghost manager for rank {}", currentRank);
        
        // Remove our ghost layer from the communication manager
        communicationManager.removeGhostLayer(treeId);
        
        // Clear internal state
        knownRanks.clear();
        elementOwners.clear();
        remoteElements.clear();
    }
    
    // Private helper methods
    
    private void discoverProcesses() {
        // Get all known endpoints from service discovery
        var endpoints = communicationManager.getServiceDiscovery().getAllEndpoints();
        
        for (var rank : endpoints.keySet()) {
            if (rank != currentRank) {
                addKnownProcess(rank);
            }
        }
        
        log.debug("Discovered {} processes from service discovery", knownRanks.size());
    }
    
    private void performInitialSync() {
        log.info("Performing initial synchronization with {} processes", knownRanks.size());
        synchronizeWithAllProcesses();
    }
    
    private void exchangeGhostElements() {
        // For each known process, request ghost elements for our boundary elements
        var boundaryElements = localGhostManager.getBoundaryElements();
        
        if (boundaryElements.isEmpty()) {
            log.debug("No boundary elements to exchange");
            return;
        }
        
        log.debug("Exchanging ghost elements for {} boundary elements", boundaryElements.size());
        
        for (var rank : knownRanks) {
            if (rank != currentRank) {
                requestGhostElementsFromProcess(rank, boundaryElements);
            }
        }
    }
    
    private void requestGhostElementsFromProcess(int targetRank, Set<Key> boundaryKeys) {
        try {
            // Convert boundary keys to list for request
            var keyList = new ArrayList<>(boundaryKeys);
            
            var response = communicationManager.requestGhosts(targetRank, treeId, ghostType, keyList);
            if (response != null) {
                processGhostRequestResponse(response, targetRank);
            }
        } catch (Exception e) {
            log.error("Failed to request ghost elements from rank {}: {}", targetRank, e.getMessage());
        }
    }
    
    private void processGhostRequestResponse(com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostBatch response, 
                                           int sourceRank) {
        log.debug("Processing ghost request response from rank {} with {} elements", 
                 sourceRank, response.getElementsCount());
        
        for (var elementProto : response.getElementsList()) {
            try {
                var ghostElement = GhostElement.<Key, ID, Content>fromProtobuf(
                    elementProto, contentSerializer, entityIdClass);
                
                // Add to our ghost layer
                spatialIndex.getGhostLayer().addGhostElement(ghostElement);
                
                // Track element ownership
                setElementOwner(ghostElement.getSpatialKey(), sourceRank);
                
            } catch (Exception e) {
                log.error("Failed to deserialize ghost element from rank {}: {}", sourceRank, e.getMessage());
            }
        }
    }
    
    private void processGhostSyncResponse(com.hellblazer.luciferase.lucien.forest.ghost.proto.SyncResponse response,
                                        int sourceRank) {
        log.debug("Processing ghost sync response from rank {} with {} total elements", 
                 sourceRank, response.getTotalElements());
        
        for (var batch : response.getBatchesList()) {
            processGhostRequestResponse(batch, sourceRank);
        }
    }
    
    private boolean shouldPerformSync() {
        return autoSyncEnabled && 
               (System.currentTimeMillis() - lastSyncTime) > syncIntervalMs;
    }
}