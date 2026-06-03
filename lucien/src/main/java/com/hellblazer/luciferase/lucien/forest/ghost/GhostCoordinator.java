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
package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialIndexCore;
import com.hellblazer.luciferase.lucien.cache.KnnProvider;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The distributed-ghost feature object for a spatial index (RDR-008 P2).
 *
 * <p>Owns the ghost state — type, algorithm, local {@link GhostLayer}, {@link GhostBoundaryDetector}, optional
 * {@link DistributedGhostManager}, and the subclass-supplied {@link NeighborDetector} — plus the local and distributed
 * ghost-layer lifecycle, neighbor/entity queries that span ghosts, and the bulk-insert/adaptation update hooks.
 *
 * <p>Unlike {@code DsocController} (lazy on {@code enableDSOC}), this coordinator is constructed eagerly with the
 * owning façade because the ghost state is always present (an empty {@code GhostLayer(NONE)} from construction, with
 * subclasses calling {@link #setNeighborDetector} during their own initialization). Shared storage and concurrency
 * arrive via {@link SpatialIndexCore}; the one façade query the ghost cluster needs ({@code kNearestNeighbors},
 * extracted in P3) is reached through the {@link KnnProvider} sub-interface (RDR-008 P3 sub-interface split).
 *
 * <p><b>Façade back-reference.</b> {@code GhostBoundaryDetector} and {@code DistributedGhostManager} require a
 * reference to the owning spatial index for {@code getSpatialKeys} / {@code containsSpatialKey} lookups, so the
 * coordinator carries one to pass through. RDR-008 P2 follow-up (bead Luciferase-703) narrowed both collaborators
 * + this back-reference from the concrete {@code AbstractSpatialIndex} to the public {@link SpatialIndex}
 * interface — retiring the original P2 "concrete-façade back-reference concession". The reference still exists
 * (the detector + manager need the spatial-index instance) but no longer leaks the god-class type.
 *
 * <p><b>Default ghost algorithm (Luciferase-9m31).</b> The default {@link GhostAlgorithm} is
 * {@link GhostAlgorithm#MINIMAL} (direct face neighbors only). This assumes a <b>2:1-balanced</b> mesh, where
 * a face neighbor is at most one refinement level away, so direct-neighbor ghosts give full coverage. Callers
 * operating on a non-2:1-balanced mesh (e.g. a partially-refined tree before balance is re-established, or a
 * raw {@code Forest} used without the balancer) must call {@link #setGhostCreationAlgorithm} with
 * {@link GhostAlgorithm#DEEP_COVERAGE} to restore the previous depth-2 coverage. Ghost type/algorithm must be
 * configured before {@link #setupDistributedGhosts}; reconfiguring afterward throws (the distributed manager
 * pins the live detector as the single owner store).
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class GhostCoordinator<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(GhostCoordinator.class);

    private final SpatialIndexCore<Key, ID, Content> core;
    private final KnnProvider<Key, ID>               knnProvider;
    private final SpatialIndex<Key, ID, Content>     facade;

    private GhostType                                            ghostType        = GhostType.NONE;
    private GhostAlgorithm                                       ghostAlgorithm   = GhostAlgorithm.MINIMAL;
    // volatile: read lock-free via the public getGhostLayer() façade and under the read lock in the combined
    // queries; written under the write lock in setGhostType/setGhostCreationAlgorithm/setNeighborDetector
    // (Luciferase-c1ka5 review HIGH-1).
    private volatile GhostBoundaryDetector<Key, ID, Content>     ghostBoundaryDetector;
    private DistributedGhostManager<Key, ID, Content>            distributedGhostManager;
    private NeighborDetector<Key>                                neighborDetector;

    // Local partition rank (Luciferase-8ggq). Captured from setupDistributedGhosts and re-applied to every
    // (re)constructed GhostBoundaryDetector so a post-setup setGhostType/setGhostCreationAlgorithm cannot silently
    // reset the detector's rank to 0 and re-introduce the cross-rank ghost-creation misfire.
    private int                                                  currentRank = 0;

    /**
     * Construct the ghost coordinator with an empty {@code GhostLayer(NONE)}. Subclasses subsequently call
     * {@link #setNeighborDetector} during their own initialization.
     */
    public GhostCoordinator(SpatialIndexCore<Key, ID, Content> core, KnnProvider<Key, ID> knnProvider,
                            SpatialIndex<Key, ID, Content> facade) {
        this.core = core;
        this.knnProvider = knnProvider;
        this.facade = facade;
    }

    // ---- Ghost type + algorithm ---------------------------------------------------------------------------------

    public void setGhostType(GhostType type) {
        core.lock().writeLock().lock();
        try {
            requireNotDistributed("ghost type");
            this.ghostType = Objects.requireNonNull(type);
            // Recreate GhostBoundaryDetector with new ghost type if we have a neighbor detector
            if (this.neighborDetector != null) {
                this.ghostBoundaryDetector = new GhostBoundaryDetector<>(facade, neighborDetector, type, ghostAlgorithm);
                this.ghostBoundaryDetector.setCurrentRank(currentRank); // preserve injected rank (Luciferase-8ggq)
            }
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    public GhostType getGhostType() {
        return ghostType;
    }

    public void setGhostCreationAlgorithm(GhostAlgorithm algorithm) {
        core.lock().writeLock().lock();
        try {
            requireNotDistributed("ghost creation algorithm");
            this.ghostAlgorithm = Objects.requireNonNull(algorithm);
            if (this.ghostBoundaryDetector != null) {
                this.ghostBoundaryDetector = new GhostBoundaryDetector<>(facade, neighborDetector, ghostType, algorithm);
                this.ghostBoundaryDetector.setCurrentRank(currentRank); // preserve injected rank (Luciferase-8ggq)
            }
            log.debug("Set ghost creation algorithm to: {}", algorithm);
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    public GhostAlgorithm getGhostCreationAlgorithm() {
        return ghostAlgorithm;
    }

    /**
     * Guard against reconfiguring the ghost type/algorithm after distributed setup (Luciferase-9m31). Both
     * setters rebuild {@link #ghostBoundaryDetector} as a fresh instance with an empty owner map, but
     * {@link DistributedGhostManager} captured the previous detector by reference in a {@code final} field
     * (and after owner-map unification that detector is the single owner store). Swapping the detector
     * post-setup would silently divert owner writes to the old instance while the coordinator scans the new
     * (empty) one — zero ghost coverage. All production paths configure type/algorithm before
     * {@link #setupDistributedGhosts}; this makes the ordering invariant fail loud rather than silent.
     */
    private void requireNotDistributed(String what) {
        if (distributedGhostManager != null) {
            throw new IllegalStateException(
                "Cannot change " + what + " after setupDistributedGhosts: the distributed ghost manager holds "
                + "the live detector as the single owner store. Configure ghost type/algorithm before "
                + "enabling distributed ghosts.");
        }
    }

    // ---- Ghost layer lifecycle ----------------------------------------------------------------------------------

    public void createGhostLayer() {
        if (ghostType == GhostType.NONE || ghostBoundaryDetector == null) {
            return;
        }
        core.lock().writeLock().lock();
        try {
            log.debug("Creating ghost layer with type: {}", ghostType);
            ghostBoundaryDetector.createGhostLayer();
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    public void updateGhostLayer() {
        if (ghostType == GhostType.NONE || ghostBoundaryDetector == null) {
            return;
        }
        core.lock().writeLock().lock();
        try {
            log.debug("Updating ghost layer");
            // For now, just recreate the entire ghost layer; incremental updates can be a future refinement.
            ghostBoundaryDetector.createGhostLayer();
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    public GhostLayer<Key, ID, Content> getGhostLayer() {
        return currentGhostLayer();
    }

    /**
     * The single live ghost layer (Luciferase-c1ka5). Real ghosts are delivered (via gRPC into
     * {@link DistributedGhostManager}) onto the {@link GhostBoundaryDetector}'s own layer; the coordinator must
     * read that same instance, never a detached split copy. Before a neighbor detector is installed there is no
     * detector yet — return an empty layer of the current type so callers see a non-null, correctly-typed layer.
     */
    private GhostLayer<Key, ID, Content> currentGhostLayer() {
        return ghostBoundaryDetector != null ? ghostBoundaryDetector.getGhostLayer() : new GhostLayer<>(ghostType);
    }

    public NeighborDetector<Key> getNeighborDetector() {
        return neighborDetector;
    }

    /**
     * Subclass-facing: the spatial-index subclass supplies its own neighbor detector during initialization. Triggers
     * lazy creation of the {@link GhostBoundaryDetector} on first call.
     */
    public void setNeighborDetector(NeighborDetector<Key> detector) {
        core.lock().writeLock().lock();
        try {
            this.neighborDetector = detector;
            // Build the detector lazily if it wasn't built yet (e.g. setGhostType ran first, before a detector
            // existed). It is built with whatever ghostType/ghostAlgorithm are currently set, so construction is
            // order-independent (Luciferase-smaik). Propagate the persisted rank too — the sibling setters do, and
            // omitting it here would leave a lazily-built detector at the default rank 0 if a rank was set first.
            if (detector != null && this.ghostBoundaryDetector == null) {
                this.ghostBoundaryDetector = new GhostBoundaryDetector<>(facade, detector, ghostType, ghostAlgorithm);
                this.ghostBoundaryDetector.setCurrentRank(currentRank);
            }
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    // ---- Combined local + ghost queries -------------------------------------------------------------------------

    public List<ID> findEntitiesIncludingGhosts(Key key) {
        var result = new ArrayList<ID>();
        core.lock().readLock().lock();
        try {
            var node = core.spatialIndex().get(key);
            if (node != null) {
                var entityIds = node.getEntityIds();
                if (entityIds != null) {
                    result.addAll(entityIds);
                }
            }
            var currentGhostLayer = currentGhostLayer(); // detector-owned live layer (Luciferase-c1ka5)
            if (currentGhostLayer != null) {
                var ghostElements = currentGhostLayer.getGhostElements(key);
                if (ghostElements != null) {
                    for (var ghost : ghostElements) {
                        if (ghost != null) {
                            var entityId = ghost.getEntityId();
                            if (entityId != null) {
                                result.add(entityId);
                            }
                        }
                    }
                }
            }
            return result;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    public List<AbstractSpatialIndex.NeighborResult<ID, Content>> findNeighborsIncludingGhosts(Point3f position,
                                                                                               float radius) {
        var result = new ArrayList<AbstractSpatialIndex.NeighborResult<ID, Content>>();
        core.lock().readLock().lock();
        try {
            // Local neighbors via the unified façade k-NN seam (P3 will own this provider).
            var localNeighbors = knnProvider.kNearestNeighbors(position, Integer.MAX_VALUE, radius);
            for (var entityId : localNeighbors) {
                var entityContent = core.entityManager().getEntityContent(entityId);
                var entityPosition = core.entityManager().getEntityPosition(entityId);
                if (entityContent != null && entityPosition != null) {
                    float distance = position.distance(entityPosition);
                    result.add(new AbstractSpatialIndex.NeighborResult<>(entityId, entityContent, distance));
                }
            }
            // Ghost neighbors (linear scan for now; spatial range queries over ghosts are a future refinement).
            var currentGhostLayer = currentGhostLayer(); // detector-owned live layer (Luciferase-c1ka5)
            if (currentGhostLayer != null && ghostBoundaryDetector != null) {
                for (var entry : currentGhostLayer.getAllGhostElements()) {
                    float distance = position.distance(entry.getPosition());
                    if (distance <= radius) {
                        result.add(new AbstractSpatialIndex.NeighborResult<>(entry.getEntityId(), entry.getContent(),
                                                                             distance));
                    }
                }
            }
            return result;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    // ---- Update hooks (called by the façade after bulk insert / adaptation) -------------------------------------

    public void triggerGhostUpdateAfterBulkInsert() {
        if (ghostType != GhostType.NONE && ghostBoundaryDetector != null) {
            log.debug("Triggering ghost update after bulk insertion");
            updateGhostLayer();
        }
    }

    public void triggerGhostUpdateAfterAdaptation() {
        if (ghostType != GhostType.NONE && ghostBoundaryDetector != null) {
            log.debug("Triggering ghost update after tree adaptation");
            updateGhostLayer();
            if (distributedGhostManager != null) {
                distributedGhostManager.updateDistributedGhostLayer();
            }
        }
    }

    // ---- Distributed ghost management ---------------------------------------------------------------------------

    public void setupDistributedGhosts(GhostChannel<Key, ID, Content> ghostChannel,
                                       ContentSerializer<Content> contentSerializer, Class<ID> entityIdClass,
                                       int currentRank, long treeId) {
        core.lock().writeLock().lock();
        try {
            if (ghostBoundaryDetector == null) {
                log.warn("Cannot setup distributed ghosts - local ghost manager not initialized");
                return;
            }
            // Inject the local rank into the lazily-built detector now that it is known (Luciferase-8ggq): the
            // ghost-creation guard treats a neighbor as remote when its owner != currentRank. Without this the
            // detector keeps its default rank 0 and misfires on every rank > 0. The rank is persisted on the
            // coordinator; post-setup setGhostType/setGhostCreationAlgorithm now throw (Luciferase-9m31) rather
            // than rebuild a detached detector, so the rank cannot be silently lost.
            this.currentRank = currentRank;
            ghostBoundaryDetector.setCurrentRank(currentRank);
            this.distributedGhostManager = new DistributedGhostManager<>(facade, ghostChannel, ghostBoundaryDetector);
            log.info("Distributed ghost management enabled for rank {} tree {}", currentRank, treeId);
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    public void initializeDistributedGhosts(ServiceDiscovery serviceDiscovery) {
        if (distributedGhostManager != null) {
            distributedGhostManager.initialize(serviceDiscovery);
        } else {
            log.warn("Cannot initialize distributed ghosts - distributed ghost manager not set up");
        }
    }

    public void createDistributedGhostLayer() {
        if (distributedGhostManager != null) {
            distributedGhostManager.createDistributedGhostLayer();
        } else {
            // Fall back to local ghost layer creation
            createGhostLayer();
        }
    }

    public void addDistributedProcess(int rank) {
        if (distributedGhostManager != null) {
            distributedGhostManager.addKnownProcess(rank);
        }
    }

    public void removeDistributedProcess(int rank) {
        if (distributedGhostManager != null) {
            distributedGhostManager.removeKnownProcess(rank);
        }
    }

    public void setElementOwner(Key key, int ownerRank) {
        if (distributedGhostManager != null) {
            distributedGhostManager.setElementOwner(key, ownerRank);
        }
    }

    public void synchronizeDistributedGhosts() {
        if (distributedGhostManager != null) {
            distributedGhostManager.synchronizeWithAllProcesses();
        }
    }

    public void setDistributedGhostAutoSync(boolean enabled) {
        if (distributedGhostManager != null) {
            distributedGhostManager.setAutoSyncEnabled(enabled);
        }
    }

    public Map<String, Object> getDistributedGhostStatistics() {
        if (distributedGhostManager != null) {
            return distributedGhostManager.getStatistics();
        }
        return Map.of();
    }

    public boolean isDistributedGhostsEnabled() {
        return distributedGhostManager != null;
    }

    public void shutdownDistributedGhosts() {
        if (distributedGhostManager != null) {
            distributedGhostManager.shutdown();
            distributedGhostManager = null;
            log.info("Distributed ghost management shut down");
        }
    }
}
