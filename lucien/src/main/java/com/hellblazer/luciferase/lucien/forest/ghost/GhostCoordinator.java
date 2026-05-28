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
import com.hellblazer.luciferase.lucien.SpatialGeometry;
import com.hellblazer.luciferase.lucien.SpatialIndexCore;
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
 * extracted in P3) is reached through the unified {@link SpatialGeometry} seam.
 *
 * <p><b>Concrete-façade back-reference.</b> {@code GhostBoundaryDetector} and {@code DistributedGhostManager} both
 * take the concrete {@code AbstractSpatialIndex} in their constructors, so the coordinator holds a façade reference
 * solely to pass to them. That's an honest deviation from the otherwise-clean feature-object decoupling, tracked as a
 * follow-up (interface-inversion of those collaborators is separable work into {@code forest.ghost}/{@code Forest}).
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class GhostCoordinator<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(GhostCoordinator.class);

    private final SpatialIndexCore<Key, ID, Content>    core;
    private final SpatialGeometry<Key, ID, Content>     geometry;
    private final AbstractSpatialIndex<Key, ID, Content> facade;

    private GhostType                                            ghostType        = GhostType.NONE;
    private GhostAlgorithm                                       ghostAlgorithm   = GhostAlgorithm.CONSERVATIVE;
    private GhostLayer<Key, ID, Content>                         ghostLayer;
    private GhostBoundaryDetector<Key, ID, Content>              ghostBoundaryDetector;
    private DistributedGhostManager<Key, ID, Content>            distributedGhostManager;
    private NeighborDetector<Key>                                neighborDetector;

    /**
     * Construct the ghost coordinator with an empty {@code GhostLayer(NONE)}. Subclasses subsequently call
     * {@link #setNeighborDetector} during their own initialization.
     */
    public GhostCoordinator(SpatialIndexCore<Key, ID, Content> core, SpatialGeometry<Key, ID, Content> geometry,
                            AbstractSpatialIndex<Key, ID, Content> facade) {
        this.core = core;
        this.geometry = geometry;
        this.facade = facade;
        this.ghostLayer = new GhostLayer<>(GhostType.NONE);
    }

    // ---- Ghost type + algorithm ---------------------------------------------------------------------------------

    public void setGhostType(GhostType type) {
        core.lock().writeLock().lock();
        try {
            this.ghostType = Objects.requireNonNull(type);
            this.ghostLayer = new GhostLayer<>(type);
            // Recreate GhostBoundaryDetector with new ghost type if we have a neighbor detector
            if (this.neighborDetector != null) {
                this.ghostBoundaryDetector = new GhostBoundaryDetector<>(facade, neighborDetector, type, ghostAlgorithm);
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
            this.ghostAlgorithm = Objects.requireNonNull(algorithm);
            if (this.ghostBoundaryDetector != null) {
                this.ghostBoundaryDetector = new GhostBoundaryDetector<>(facade, neighborDetector, ghostType, algorithm);
            }
            log.debug("Set ghost creation algorithm to: {}", algorithm);
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    public GhostAlgorithm getGhostCreationAlgorithm() {
        return ghostAlgorithm;
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
        return ghostLayer;
    }

    public NeighborDetector<Key> getNeighborDetector() {
        return neighborDetector;
    }

    /**
     * Subclass-facing: the spatial-index subclass supplies its own neighbor detector during initialization. Triggers
     * lazy creation of the {@link GhostBoundaryDetector} on first call.
     */
    public void setNeighborDetector(NeighborDetector<Key> detector) {
        this.neighborDetector = detector;
        if (detector != null && this.ghostBoundaryDetector == null) {
            this.ghostBoundaryDetector = new GhostBoundaryDetector<>(facade, detector, ghostType, ghostAlgorithm);
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
            var currentGhostLayer = ghostLayer; // capture to avoid race
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
            var localNeighbors = geometry.kNearestNeighbors(position, Integer.MAX_VALUE, radius);
            for (var entityId : localNeighbors) {
                var entityContent = core.entityManager().getEntityContent(entityId);
                var entityPosition = core.entityManager().getEntityPosition(entityId);
                if (entityContent != null && entityPosition != null) {
                    float distance = position.distance(entityPosition);
                    result.add(new AbstractSpatialIndex.NeighborResult<>(entityId, entityContent, distance));
                }
            }
            // Ghost neighbors (linear scan for now; spatial range queries over ghosts are a future refinement).
            if (ghostLayer != null && ghostBoundaryDetector != null) {
                for (var entry : ghostLayer.getAllGhostElements()) {
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
