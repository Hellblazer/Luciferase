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

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.balancing.fault.GhostSyncCallback;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages distributed ghost layers across multiple processes.
 *
 * <p>This class coordinates ghost element creation, synchronization, and updates
 * between distributed spatial index processes. It uses {@link GhostChannel}
 * for batched communication and integrates with the local ghost boundary detection.
 *
 * <p>Simplified from original implementation by delegating communication to
 * {@link GhostChannel}, reducing LOC from 430 to ~320.
 *
 * @param <Key> the type of spatial key used by the spatial index
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 *
 * @author Hal Hildebrand
 */
public class DistributedGhostManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(DistributedGhostManager.class);

    // SpatialIndex back-reference used by createGhostsForBoundaryElement to populate real content/position
    // (Luciferase-7wzml.2). Previously unused (placeholder values were transmitted); the null-check in the
    // constructor still serves as a contract guard. RDR-008 P2 follow-up (Luciferase-703) narrowed the type
    // from AbstractSpatialIndex to SpatialIndex.
    private final SpatialIndex<Key, ID, Content> spatialIndex;
    private final GhostChannel<Key, ID, Content> ghostChannel;
    private final GhostBoundaryDetector<Key, ID, Content> localGhostManager;

    // Configuration
    private final int currentRank;
    private final long treeId;

    // Process management
    private final Set<Integer> knownRanks;

    // Synchronization control
    private volatile boolean autoSyncEnabled = true;
    private volatile long lastSyncTime = 0;
    private volatile long syncIntervalMs = 30000; // 30 seconds default

    // Clock — injected for deterministic testing; defaults to wall-clock
    private volatile Clock clock = Clock.system();

    // Fault detection callback for sync operations
    private volatile GhostSyncCallback syncCallback = null;
    
    /**
     * Create a distributed ghost manager.
     *
     * @param spatialIndex the spatial index to manage ghosts for
     * @param ghostChannel the ghost communication channel
     * @param localGhostManager the local ghost manager
     */
    public DistributedGhostManager(SpatialIndex<Key, ID, Content> spatialIndex,
                                  GhostChannel<Key, ID, Content> ghostChannel,
                                  GhostBoundaryDetector<Key, ID, Content> localGhostManager) {
        this.spatialIndex = Objects.requireNonNull(spatialIndex);
        this.ghostChannel = Objects.requireNonNull(ghostChannel);
        this.localGhostManager = Objects.requireNonNull(localGhostManager);
        this.currentRank = ghostChannel.getCurrentRank();
        this.treeId = ghostChannel.getTreeId();

        this.knownRanks = new CopyOnWriteArraySet<>();

        log.info("Created distributed ghost manager for rank {} tree {}",
                currentRank, treeId);
    }
    
    /**
     * Inject a clock for deterministic testing.
     *
     * @param clock the clock to use (must not be null)
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
    public void initialize(ServiceDiscovery serviceDiscovery) {
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
     * Rebuild ONLY the local ghost layer (the two-phase clear-then-populate over the spatial index), without any
     * network sync (Luciferase-cfg4o). The caller is expected to hold the spatial-index write lock so the rebuild is
     * atomic against concurrent mutation; the (potentially blocking) sync is split out into {@link #synchronizeIfDue()}
     * so it never runs under the lock.
     */
    public void rebuildLocalGhostLayer() {
        localGhostManager.createGhostLayer();
    }

    /**
     * Run the auto-sync flush if enabled and due — intended to be called WITHOUT the index lock held
     * (Luciferase-cfg4o), since it blocks on network round-trips.
     */
    public void synchronizeIfDue() {
        if (autoSyncEnabled && shouldPerformSync()) {
            synchronizeWithAllProcesses();
        }
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
        // Synchronous single-rank sync: queue + flush, then wait for this rank's flush to complete.
        queueAndFlush(targetRank).join();
    }

    /**
     * Queue this rank's boundary ghosts for {@code targetRank} and start the flush, returning the flush future
     * <em>without</em> blocking (Luciferase-9m31). Lets {@link #synchronizeWithAllProcesses()} fan out to all
     * ranks concurrently and join the combined future once, instead of serializing one flush at a time.
     *
     * @param targetRank the rank of the target process
     * @return a future that completes when the flush to {@code targetRank} finishes
     */
    private CompletableFuture<Void> queueAndFlush(int targetRank) {
        log.debug("Synchronizing ghost elements with rank {}", targetRank);

        // Get boundary elements from local ghost manager
        var boundaryElements = localGhostManager.getBoundaryElements();

        // Queue ghosts for transmission through channel
        for (var key : boundaryElements) {
            for (var ghostElement : createGhostsForBoundaryElement(key)) {
                ghostChannel.queueGhost(targetRank, ghostElement);
            }
        }

        // Fire the fault-detection sync callback on completion (Luciferase-963vw): it was registered via
        // registerSyncCallback but never invoked, so the integration was dead. Hooking it here covers both the
        // single-target (synchronizeWithProcess) and the fan-out (synchronizeWithAllProcesses) paths.
        return ghostChannel.flushToTarget(targetRank).whenComplete((result, ex) -> {
            var cb = syncCallback;
            if (cb == null) {
                return;
            }
            // Isolate callback exceptions (Luciferase-963vw review): whenComplete swallows them, so a buggy callback
            // would otherwise appear as a silent no-op while the flush future still completes normally. Log instead.
            try {
                if (ex == null) {
                    cb.onSyncSuccess(targetRank);
                } else {
                    var cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null
                                ? ex.getCause() : ex;
                    cb.onSyncFailure(targetRank, cause instanceof Exception e ? e : new RuntimeException(cause));
                }
            } catch (RuntimeException callbackEx) {
                log.error("GhostSyncCallback threw for rank {}: {}", targetRank, callbackEx.getMessage(), callbackEx);
            }
        });
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

        // Fan out to all known remote processes concurrently (Luciferase-9m31): queue + flush each rank, then
        // wait on the combined future rather than blocking on each rank's flush in turn. Preserves the
        // synchronous completion contract (the method returns once every rank's flush has completed).
        var flushes = new ArrayList<CompletableFuture<Void>>();
        for (var rank : knownRanks) {
            if (rank != currentRank) {
                flushes.add(queueAndFlush(rank));
            }
        }
        CompletableFuture.allOf(flushes.toArray(new CompletableFuture[0])).join();

        lastSyncTime = clock.currentTimeMillis();
        log.info("Synchronization complete for {} processes", flushes.size());
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
     * <p><b>Owner-map unification (Luciferase-9m31).</b> Delegates to
     * {@link GhostBoundaryDetector#setElementOwner} — the detector's owner map is the single source of truth.
     * The local boundary scan ({@link GhostBoundaryDetector#createGhostLayer}) reads that same map, so owners
     * set here <em>do</em> drive local cross-shape ghost creation. (Previously the manager kept a separate
     * map that the scan never read, so external Forest-partition ownership set via the manager silently had no
     * effect on the local scan.)
     *
     * @param key the spatial key
     * @param ownerRank the rank of the process that owns this element
     */
    public void setElementOwner(Key key, int ownerRank) {
        localGhostManager.setElementOwner(key, ownerRank);
    }

    /**
     * Get the owner rank for a spatial key, read through the unified owner map (Luciferase-9m31).
     *
     * <p>Delegates to {@link GhostBoundaryDetector#getElementOwner}, so the default for an unregistered key is
     * the detector's convention (rank 0), not the manager's former {@code currentRank} default. The two now
     * agree by construction. Callers that classify partition seams must, as the detector does, exclude
     * locally-present keys before consulting this default (see {@link GhostBoundaryDetector#getElementOwner}).
     *
     * @param key the spatial key
     * @return the registered owner rank, or 0 if none was registered
     */
    public int getElementOwner(Key key) {
        return localGhostManager.getElementOwner(key);
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
        stats.put("trackedElements", localGhostManager.getTrackedOwnerCount());
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

        // Clear internal state (owners live on the detector after unification — Luciferase-9m31)
        knownRanks.clear();
        localGhostManager.clearElementOwners();
    }

    // Private helper methods

    /**
     * Create ghost elements for a locally-owned boundary element by looking up real entity data from the
     * spatial index (Luciferase-7wzml.2). Returns one {@link GhostElement} per entity stored at {@code key}.
     *
     * <p>Previous implementation used placeholder values ({@code (Content) new byte[0]}, position (0,0,0),
     * and {@code currentRank} as the owner) — the unchecked cast is gone and content/position are populated
     * from the live index. {@code ownerRank} remains {@code currentRank} because boundary elements are locally
     * owned by definition (they are present in the local index and at the partition seam).
     *
     * <p>Returns an empty list if the key is not found in the index (race: element removed between boundary
     * scan and transmission). The caller skips empty results.
     *
     * @param key a locally-owned spatial key identified as a partition-boundary element
     * @return list of ghost elements (one per entity at this key), or empty if the key is absent
     */
    private List<GhostElement<Key, ID, Content>> createGhostsForBoundaryElement(Key key) {
        // O(log N) direct key lookup — replaces former O(N) nodes().filter().findFirst() scan (Luciferase-7wzml.2 H1)
        var entityIds = spatialIndex.getEntityIdsAt(key);
        if (entityIds.isEmpty()) {
            log.debug("Boundary key {} has no entities in the local index — skipping ghost transmission", key);
            return List.of();
        }
        var result = new ArrayList<GhostElement<Key, ID, Content>>();
        for (var entityId : entityIds) {
            var position = spatialIndex.getEntityPosition(entityId);
            if (position == null) {
                log.debug("Entity {} at boundary key {} has no position — skipping ghost", entityId, key);
                continue;
            }
            var content = spatialIndex.getEntity(entityId);
            if (content == null) {
                // Entity removed between boundary scan and transmission — skip, do not propagate null (Luciferase-7wzml.2 H2)
                log.debug("Entity {} at boundary key {} has no content (removed mid-flight) — skipping ghost", entityId,
                          key);
                continue;
            }
            result.add(new GhostElement<>(key, entityId, content, position, currentRank, treeId));
        }
        return result;
    }

    /**
     * Register a sync callback for fault detection during ghost synchronization.
     *
     * <p>The callback will be invoked on sync success/failure events to enable
     * fault detection and recovery coordination. Use {@link com.hellblazer.luciferase.lucien.balancing.SimpleGhostSyncAdapter}
     * to adapt sync events to fault handler notifications.
     *
     * @param callback the sync callback to register (typically SimpleGhostSyncAdapter)
     */
    public void registerSyncCallback(GhostSyncCallback callback) {
        this.syncCallback = callback;
        if (callback != null) {
            log.debug("Registered sync callback for rank {}", currentRank);
        }
    }

    /**
     * Get the registered sync callback.
     *
     * @return the sync callback, or null if not registered
     */
    public GhostSyncCallback getSyncCallback() {
        return syncCallback;
    }

    private boolean shouldPerformSync() {
        return autoSyncEnabled &&
               (clock.currentTimeMillis() - lastSyncTime) > syncIntervalMs;
    }
}
