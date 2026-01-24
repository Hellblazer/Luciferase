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
 * <p>This class coordinates ghost element creation, synchronization, and updates
 * between distributed spatial index processes. It uses {@link GrpcGhostChannel}
 * for batched communication and integrates with the local ghost boundary detection.
 *
 * <p>Simplified from original implementation by delegating communication to
 * {@link GrpcGhostChannel}, reducing LOC from 430 to ~320.
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
    private final GrpcGhostChannel<Key, ID, Content> ghostChannel;
    private final GhostBoundaryDetector<Key, ID, Content> localGhostManager;

    // Configuration
    private final int currentRank;
    private final long treeId;

    // Process management
    private final Set<Integer> knownRanks;
    private final Map<Key, Integer> elementOwners;

    // Synchronization control
    private volatile boolean autoSyncEnabled = true;
    private volatile long lastSyncTime = 0;
    private volatile long syncIntervalMs = 30000; // 30 seconds default
    
    /**
     * Create a distributed ghost manager.
     *
     * @param spatialIndex the spatial index to manage ghosts for
     * @param ghostChannel the ghost communication channel
     * @param localGhostManager the local ghost manager
     */
    public DistributedGhostManager(AbstractSpatialIndex<Key, ID, Content> spatialIndex,
                                  GrpcGhostChannel<Key, ID, Content> ghostChannel,
                                  GhostBoundaryDetector<Key, ID, Content> localGhostManager) {
        this.spatialIndex = Objects.requireNonNull(spatialIndex);
        this.ghostChannel = Objects.requireNonNull(ghostChannel);
        this.localGhostManager = Objects.requireNonNull(localGhostManager);
        this.currentRank = ghostChannel.getCurrentRank();
        this.treeId = ghostChannel.getTreeId();

        this.knownRanks = new CopyOnWriteArraySet<>();
        this.elementOwners = new ConcurrentHashMap<>();

        log.info("Created distributed ghost manager for rank {} tree {}",
                currentRank, treeId);
    }
    
    /**
     * Get the ghost layer for this distributed manager.
     * @return the ghost layer for boundary violation checking
     */
    public GhostLayer<Key, ID, Content> getGhostLayer() {
        return localGhostManager.getGhostLayer();
    }

    /**
     * Initialize the distributed ghost layer by discovering other processes
     * and performing initial synchronization.
     *
     * @param serviceDiscovery the service discovery to find other processes
     */
    public void initialize(com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostServiceClient.ServiceDiscovery serviceDiscovery) {
        log.info("Initializing distributed ghost layer for rank {}", currentRank);

        // Discover other processes
        var endpoints = serviceDiscovery.getAllEndpoints();
        for (var rank : endpoints.keySet()) {
            if (rank != currentRank) {
                addKnownProcess(rank);
            }
        }

        // Perform initial synchronization
        if (!knownRanks.isEmpty()) {
            synchronizeWithAllProcesses();
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

        // Synchronize with all known processes
        if (!knownRanks.isEmpty()) {
            synchronizeWithAllProcesses();
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
     * Synchronize ghost elements with a specific process by sending boundary ghosts.
     *
     * @param targetRank the rank of the target process
     */
    public void synchronizeWithProcess(int targetRank) {
        if (targetRank == currentRank) {
            return; // No need to sync with ourselves
        }

        log.debug("Synchronizing ghost elements with rank {}", targetRank);

        // Get boundary elements from local ghost manager
        var boundaryElements = localGhostManager.getBoundaryElements();

        // Queue ghosts for transmission through channel
        for (var key : boundaryElements) {
            var ghostElement = createGhostForBoundaryElement(key);
            if (ghostElement != null) {
                ghostChannel.queueGhost(targetRank, ghostElement);
            }
        }

        // Flush immediately for synchronous behavior
        ghostChannel.flushToTarget(targetRank).join();
    }
    
    /**
     * Synchronize with all known processes asynchronously.
     */
    public void synchronizeWithAllProcesses() {
        if (knownRanks.isEmpty()) {
            log.debug("No known processes to synchronize with");
            return;
        }

        log.info("Synchronizing with {} known processes", knownRanks.size());

        // Queue ghosts for all known processes
        for (var rank : knownRanks) {
            if (rank != currentRank) {
                synchronizeWithProcess(rank);
            }
        }

        lastSyncTime = System.currentTimeMillis();
        log.info("Synchronization queued for {} processes", knownRanks.size());
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
     * Pause automatic ghost synchronization during recovery.
     * Prevents new sync operations from starting.
     */
    public void pauseAutoSync() {
        setAutoSyncEnabled(false);
        log.info("Ghost auto-sync paused for recovery");
    }

    /**
     * Resume automatic ghost synchronization after recovery.
     * Re-enables periodic sync operations.
     */
    public void resumeAutoSync() {
        setAutoSyncEnabled(true);
        log.info("Ghost auto-sync resumed after recovery");
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
        stats.put("ghostType", ghostChannel.getGhostType());
        stats.put("knownProcesses", knownRanks.size());
        stats.put("trackedElements", elementOwners.size());
        stats.put("autoSyncEnabled", autoSyncEnabled);
        stats.put("lastSyncTime", lastSyncTime);
        stats.put("syncIntervalMs", syncIntervalMs);
        stats.put("pendingGhosts", ghostChannel.getTotalPendingCount());

        return stats;
    }
    
    /**
     * Shutdown the distributed ghost manager.
     */
    public void shutdown() {
        log.info("Shutting down distributed ghost manager for rank {}", currentRank);

        // Clear ghost channel
        ghostChannel.clear();

        // Clear internal state
        knownRanks.clear();
        elementOwners.clear();
    }

    // Private helper methods

    /**
     * Create a ghost element for a boundary element.
     * This method creates a minimal ghost placeholder - actual ghost data
     * should be fetched via gRPC from the owning process.
     */
    private GhostElement<Key, ID, Content> createGhostForBoundaryElement(Key key) {
        // Create a minimal ghost element with the spatial key
        // Actual entity data will be populated by gRPC communication
        // For now, use placeholder values

        @SuppressWarnings("unchecked")
        ID placeholderId = (ID) new com.hellblazer.luciferase.lucien.entity.UUIDEntityID(
            java.util.UUID.nameUUIDFromBytes(("boundary-" + key.toString()).getBytes())
        );

        @SuppressWarnings("unchecked")
        Content placeholderContent = (Content) new byte[0];

        var position = new javax.vecmath.Point3f(0, 0, 0);

        return new GhostElement<>(
            key,
            placeholderId,
            placeholderContent,
            position,
            currentRank,
            treeId
        );
    }

    private boolean shouldPerformSync() {
        return autoSyncEnabled &&
               (System.currentTimeMillis() - lastSyncTime) > syncIntervalMs;
    }
}