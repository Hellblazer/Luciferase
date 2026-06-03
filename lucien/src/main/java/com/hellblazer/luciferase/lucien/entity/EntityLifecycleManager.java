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
package com.hellblazer.luciferase.lucien.entity;

import com.hellblazer.luciferase.lucien.BulkOperationProcessor;
import com.hellblazer.luciferase.lucien.LevelSelector;
import com.hellblazer.luciferase.lucien.ParallelBulkOperations;
import com.hellblazer.luciferase.lucien.SpatialIndexCore;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.internal.ObjectPools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The entity-lifecycle feature object for a spatial index (RDR-008 P6 — the final phase).
 *
 * <p>Owns the broadest cluster — insertions (single and bulk, with and without spanning, sequential and parallel),
 * removals (single and parallel), updates (single and parallel; carries the P1 DSOC seam), the entity-introspection
 * accessors (contains/count/get/position/bounds/span-count/clear/lookup), and the spanning policy dispatch. Of all
 * six clusters this one touches every nucleus field (spatialIndex, lock, spatialVersion, knnCache, entityManager,
 * entityCache) and a great deal of facade-internal infrastructure (bulk config/processor/builder, parallel
 * operations, node pool, deferred-subdivision manager, the DSOC controller, the auto-balance + ghost-update
 * hooks) — so it is the only feature object that takes both an {@link EntityLifecycleGeometry} callback (the
 * subclass-overridden geometry/topology hooks) and an {@link EntityLifecycleHost} interface (the facade-internal
 * infrastructure). The host interface is the principled middle ground (RDR-008 P3 refinement applied broadly):
 * narrower than handing this object the concrete {@code AbstractSpatialIndex} as GhostCoordinator did in P2, and
 * still keeps the facade implementation hidden behind a named seam.
 *
 * <h2>Invariants preserved verbatim from the pre-extraction facade</h2>
 *
 * <ul>
 *     <li><b>spatialVersion bump under the write-lock.</b> Every mutation ({@link #insert}, {@link #removeEntity},
 *     {@link #updateEntity}) increments {@link SpatialIndexCore#spatialVersion()} <em>inside</em> the write-lock
 *     try/finally. The k-NN cache (P3) reads the same counter under the read-lock for invalidation; the
 *     write-lock-release happens-before the subsequent read-lock-acquire per the Java Memory Model monitor
 *     semantics, guaranteeing visibility without any extra synchronization.</li>
 *     <li><b>P1 DSOC seam in {@link #updateEntity}.</b> The two callbacks
 *     ({@code dsoc.tryDeferUpdate(entityId, newPosition)} early-return and {@code dsoc.markVisibleOnUpdate(entityId)}
 *     post-update) survive verbatim — the DSOC controller is read from {@link EntityLifecycleHost#dsocController()}
 *     at call time so a concurrent {@code enableDSOC} is observed via the host's volatile field.</li>
 *     <li><b>Tetree.insertBatch is unaffected.</b> {@link #insertBatch} keeps the public entry point structure;
 *     the Tetree subclass's {@code insertBatch} override toggles its private flag then calls {@code super.insertBatch}
 *     — that {@code super} resolves to the facade's delegator into this method, identical to the pre-extraction
 *     dispatch.</li>
 *     <li><b>Region/range queries and stream accessors stay in the facade.</b> RDR-008 §Decision item 1 locks
 *     {@code entitiesInRegion}, {@code spatialRangeQuery}, {@code getSpatialIndexRange}, {@code leafStream},
 *     {@code levelStream}, {@code nodeStream}, and {@code nodes()} as facade-resident; this feature object does
 *     NOT carry them.</li>
 * </ul>
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class EntityLifecycleManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(EntityLifecycleManager.class);

    private final SpatialIndexCore<Key, ID, Content>          core;
    private final EntityLifecycleGeometry<Key, ID, Content>   callback;
    private final EntityLifecycleHost<Key, ID, Content>       host;

    /**
     * Construct the entity-lifecycle manager. References are stored only — the constructor does not invoke any
     * method on {@code core}, {@code callback}, or {@code host}, so it is safe to construct from inside the
     * facade ctor before the facade is fully initialized (the this-escape invariant documented on
     * {@code AbstractSpatialIndex}).
     */
    public EntityLifecycleManager(SpatialIndexCore<Key, ID, Content> core,
                                  EntityLifecycleGeometry<Key, ID, Content> callback,
                                  EntityLifecycleHost<Key, ID, Content> host) {
        this.core = core;
        this.callback = callback;
        this.host = host;
    }

    // ===== Read-only entity accessors =====

    /** Whether the entity is currently in the index. Read-locked for consistency with concurrent mutations. */
    public boolean containsEntity(ID entityId) {
        core.lock().readLock().lock();
        try {
            return core.entityManager().containsEntity(entityId);
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Total entity count. Read-locked for a coherent snapshot across concurrent mutations. */
    public int entityCount() {
        core.lock().readLock().lock();
        try {
            return core.entityManager().getEntityCount();
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Batch content retrieval — read-locked so all retrievals share one snapshot. */
    public List<Content> getEntities(List<ID> entityIds) {
        core.lock().readLock().lock();
        try {
            return core.entityManager().getEntitiesContent(entityIds);
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Snapshot of every entity's current position, read-locked. */
    public Map<ID, Point3f> getEntitiesWithPositions() {
        core.lock().readLock().lock();
        try {
            return core.entityManager().getEntitiesWithPositions();
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Read-locked content lookup by ID. */
    public Content getEntity(ID entityId) {
        core.lock().readLock().lock();
        try {
            return core.entityManager().getEntityContent(entityId);
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Cached + read-locked bounds lookup; populates the entity cache on a miss. */
    public EntityBounds getEntityBounds(ID entityId) {
        core.lock().readLock().lock();
        try {
            // Check cache first
            var cachedBounds = core.entityCache().getBounds(entityId);
            if (cachedBounds != null) {
                return cachedBounds;
            }

            // Cache miss - get from entity manager
            var bounds = core.entityManager().getEntityBounds(entityId);
            if (bounds != null) {
                // Update cache
                var position = core.entityManager().getEntityPosition(entityId);
                core.entityCache().put(entityId, position, bounds);
            }
            return bounds;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Cached + read-locked position lookup; populates the entity cache on a miss. */
    public Point3f getEntityPosition(ID entityId) {
        core.lock().readLock().lock();
        try {
            // Check cache first
            var cachedPosition = core.entityCache().getPosition(entityId);
            if (cachedPosition != null) {
                return cachedPosition;
            }

            // Cache miss - get from entity manager
            var position = core.entityManager().getEntityPosition(entityId);
            if (position != null) {
                // Update cache
                var bounds = core.entityManager().getEntityBounds(entityId);
                core.entityCache().put(entityId, position, bounds);
            }
            return position;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Number of nodes the entity spans (one for point entities, possibly many for spanning entities). */
    public int getEntitySpanCount(ID entityId) {
        core.lock().readLock().lock();
        try {
            return core.entityManager().getEntitySpanCount(entityId);
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /**
     * Point-precise + level-precise entity lookup. Recurses to deeper levels when the addressed node has been
     * subdivided (matches the pre-extraction facade behavior — uses the subclass-overridden
     * {@link EntityLifecycleGeometry#hasChildren} to decide).
     */
    public List<ID> lookup(Point3f position, byte level) {
        callback.validateSpatialConstraints(position);

        core.lock().readLock().lock();
        try {
            var spatialIndex = callback.calculateSpatialIndex(position, level);
            var node = core.spatialIndex().get(spatialIndex);

            if (node == null) {
                return Collections.emptyList();
            }

            // If the node has been subdivided, look in child nodes
            if (callback.hasChildren(spatialIndex) || node.isEmpty()) {
                var childLevel = (byte) (level + 1);
                if (childLevel <= host.maxDepth()) {
                    return lookup(position, childLevel);
                }
            }

            return new ArrayList<>(node.getEntityIds());
        } finally {
            core.lock().readLock().unlock();
        }
    }

    // ===== Insertion =====

    /** Auto-id insert at the given level + content (no bounds). */
    public ID insert(Point3f position, byte level, Content content) {
        core.lock().writeLock().lock();
        try {
            var entityId = core.entityManager().generateEntityId();
            insert(entityId, position, level, content);
            return entityId;
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    /** Explicit-id insert at the given level + content (no bounds). */
    public void insert(ID entityId, Point3f position, byte level, Content content) {
        core.lock().writeLock().lock();
        try {
            insert(entityId, position, level, content, null);
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    /**
     * Explicit-id insert with optional bounds — dispatches to spanning when both bounds and the spanning policy
     * are present, otherwise single-node insertion. Increments {@link SpatialIndexCore#spatialVersion()} under
     * the write lock and invalidates the cell-granular k-NN cache.
     */
    public void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds) {
        core.lock().writeLock().lock();
        try {
            // Validate spatial constraints
            callback.validateSpatialConstraints(position);

            // Create or update entity
            core.entityManager().createOrUpdateEntity(entityId, content, position, bounds);

            // If spanning is enabled and entity has bounds, check for spanning
            if (host.spanningPolicy().isSpanningEnabled() && bounds != null) {
                // Use advanced spanning logic
                if (shouldSpanEntity(bounds, level)) {
                    insertWithAdvancedSpanning(entityId, bounds, level);
                } else {
                    // Standard single-node insertion even with bounds
                    insertAtPosition(entityId, position, level);
                }
            } else {
                // Standard single-node insertion
                insertAtPosition(entityId, position, level);
            }

            // k-NN cache invalidation: increment version and invalidate affected cells
            // Use level 15 for cache granularity (cell size = 64) to distinguish nearby queries
            core.spatialVersion().incrementAndGet();
            var spatialKey = callback.calculateSpatialIndex(position, (byte) 15);
            core.knnCache().invalidatePosition(spatialKey);
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    /**
     * Bulk insert with full {@link EntityData} records (handles both bounded and unbounded). Routes through
     * single-insert for small batches; for batches at/above {@link com.hellblazer.luciferase.lucien.BulkOperationConfig#getBatchSize()}
     * it flips bulk-loading on, drives the inserts, and flushes via {@code finalizeBulkLoading}.
     */
    public void insertAll(List<EntityData<ID, Content>> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }

        // Use bulk mode if enough entities
        if (entities.size() >= host.bulkConfig().getBatchSize()) {
            host.enableBulkLoading();
            try {
                for (var data : entities) {
                    if (data.bounds() != null) {
                        insert(data.id(), data.position(), data.level(), data.content(), data.bounds());
                    } else {
                        insert(data.id(), data.position(), data.level(), data.content());
                    }
                }
            } finally {
                host.finalizeBulkLoading();
            }
        } else {
            // Insert individually for small batches
            for (var data : entities) {
                if (data.bounds() != null) {
                    insert(data.id(), data.position(), data.level(), data.content(), data.bounds());
                } else {
                    insert(data.id(), data.position(), data.level(), data.content());
                }
            }
        }
    }

    /**
     * Bulk position+content batch insert. Uses the stack-based tree builder for sufficiently large batches,
     * otherwise the bulk-processor's group/direct strategy. Triggers ghost updates on completion.
     */
    public List<ID> insertBatch(List<Point3f> positions, List<Content> contents, byte level) {
        validateBatchInputs(positions, contents);
        if (positions.isEmpty()) {
            return Collections.emptyList();
        }

        var effectiveLevel = determineBatchInsertionLevel(positions, level);
        var startTime = System.nanoTime();
        var insertedIds = ObjectPools.<ID>borrowArrayList(positions.size());
        try {

            core.lock().writeLock().lock();
            try {
                // Check if we should use stack-based builder for this bulk operation
                if (shouldUseStackBasedBuilder(positions.size())) {
                    return performStackBasedBulkInsert(positions, contents, effectiveLevel);
                }

                // Enable bulk loading mode if configured
                var wasInBulkMode = host.bulkLoadingMode();
                if (host.bulkConfig().isDeferSubdivision() && !host.bulkLoadingMode()) {
                    host.enableBulkLoading();
                }

                // Preprocess entities with spatial optimization
                var mortonEntities = preprocessBatchEntities(positions, contents, effectiveLevel);

                // Insert entities using appropriate strategy
                if (positions.size() > host.bulkConfig().getBatchSize()) {
                    insertGroupedEntities(mortonEntities, effectiveLevel, insertedIds);
                } else {
                    insertDirectEntities(mortonEntities, level, insertedIds);
                }

                // Restore bulk mode state
                if (!wasInBulkMode && host.bulkConfig().isDeferSubdivision()) {
                    host.finalizeBulkLoading();
                }

            } finally {
                core.lock().writeLock().unlock();
            }

            logBatchPerformance(positions.size(), startTime);

            // Trigger ghost updates after successful bulk insertion
            host.triggerGhostUpdateAfterBulkInsert();

            // Return a copy to avoid returning pooled object
            return new ArrayList<>(insertedIds);
        } finally {
            ObjectPools.returnArrayList(insertedIds);
        }
    }

    /** Parallel bulk insert via the {@link ParallelBulkOperations} executor. */
    public ParallelBulkOperations.ParallelOperationResult<ID> insertBatchParallel(List<Point3f> positions,
                                                                                  List<Content> contents, byte level)
    throws InterruptedException {
        return host.parallelOperations().insertBatchParallel(positions, contents, level);
    }

    /**
     * Bulk insert with explicit bounds per entity — uses the spanning policy to decide single-node vs.
     * spanning placement. Drives the bulk-loading mode flag via the host like {@link #insertBatch}.
     */
    public List<ID> insertBatchWithSpanning(List<EntityBounds> bounds, List<Content> contents, byte level) {
        if (bounds == null || contents == null) {
            throw new IllegalArgumentException("Bounds and contents cannot be null");
        }
        if (bounds.size() != contents.size()) {
            throw new IllegalArgumentException("Bounds and contents must have the same size");
        }
        if (bounds.isEmpty()) {
            return Collections.emptyList();
        }

        var startTime = System.nanoTime();
        List<ID> insertedIds = new ArrayList<>(bounds.size());

        core.lock().writeLock().lock();
        try {
            // Enable bulk loading mode if configured
            var wasInBulkMode = host.bulkLoadingMode();
            if (host.bulkConfig().isDeferSubdivision() && !host.bulkLoadingMode()) {
                host.enableBulkLoading();
            }

            // Process each entity with bounds
            for (int i = 0; i < bounds.size(); i++) {
                var entityBounds = bounds.get(i);
                var content = contents.get(i);

                // Calculate center position
                var center = new Point3f((entityBounds.getMinX() + entityBounds.getMaxX()) / 2,
                                         (entityBounds.getMinY() + entityBounds.getMaxY()) / 2,
                                         (entityBounds.getMinZ() + entityBounds.getMaxZ()) / 2);

                // Generate ID and store entity
                var entityId = core.entityManager().generateEntityId();
                insertedIds.add(entityId);
                core.entityManager().createOrUpdateEntity(entityId, content, center, entityBounds);

                // Handle spanning if configured
                var entitySize = Math.max(entityBounds.getMaxX() - entityBounds.getMinX(),
                                          Math.max(entityBounds.getMaxY() - entityBounds.getMinY(),
                                                   entityBounds.getMaxZ() - entityBounds.getMinZ()));
                var nodeSize = callback.getCellSizeAtLevel(level);
                if (host.spanningPolicy().shouldSpan(entitySize, nodeSize)) {
                    callback.insertWithSpanning(entityId, entityBounds, level);
                } else {
                    insertAtPosition(entityId, center, level);
                }
            }

            // Restore bulk mode state
            if (!wasInBulkMode && host.bulkConfig().isDeferSubdivision()) {
                host.finalizeBulkLoading();
            }

        } finally {
            core.lock().writeLock().unlock();
        }

        var elapsedTime = System.nanoTime() - startTime;

        // Log performance if significant batch
        if (bounds.size() > 1000) {
            double rate = bounds.size() * 1_000_000_000.0 / elapsedTime;
            log.debug("Bulk inserted {} entities with spanning in {}ms ({} entities/sec)", bounds.size(),
                      String.format("%.2f", elapsedTime / 1_000_000.0), String.format("%.0f", rate));
        }

        return insertedIds;
    }

    // ===== Removal =====

    /**
     * Remove an entity by ID. Cleans the entity from every spatial node it occupies, releases empty nodes back
     * to the pool, runs the auto-balance check, bumps {@link SpatialIndexCore#spatialVersion()}, and invalidates
     * the cell-granular k-NN cache.
     */
    public boolean removeEntity(ID entityId) {
        core.lock().writeLock().lock();
        try {
            // Get all locations where this entity appears
            var locations = core.entityManager().getEntityLocations(entityId);

            // Remove from entity storage
            var removed = core.entityManager().removeEntity(entityId);
            if (removed == null) {
                return false;
            }

            // Invalidate cache
            core.entityCache().remove(entityId);

            if (!locations.isEmpty()) {
                // Remove from each node
                for (var spatialIndex : locations) {
                    var node = core.spatialIndex().get(spatialIndex);
                    if (node != null) {
                        node.removeEntity(entityId);

                        // Remove empty nodes
                        callback.cleanupEmptyNode(spatialIndex, node);
                    }
                }
            }

            // Check for auto-balancing after removal
            host.checkAutoBalance();

            // k-NN cache invalidation: increment version and invalidate affected cells
            // Use level 15 for cache granularity (cell size = 64) to distinguish nearby queries
            core.spatialVersion().incrementAndGet();
            var entityPosition = removed.getPosition();
            if (entityPosition != null) {
                var level15Key = callback.calculateSpatialIndex(entityPosition, (byte) 15);
                core.knnCache().invalidatePosition(level15Key);
            }

            return true;
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    /** Parallel batch removal via the {@link ParallelBulkOperations} executor. */
    public CompletableFuture<Integer> removeBatchParallel(List<ID> entityIds) {
        return host.parallelOperations().removeBatchParallel(entityIds);
    }

    // ===== Update =====

    /**
     * Update an entity's position to {@code newPosition} at {@code level}. Carries the P1 DSOC seam: when DSOC is
     * enabled, {@code dsoc.tryDeferUpdate} may short-circuit this call (deferred update behind a still-valid
     * temporal bounding volume) and {@code dsoc.markVisibleOnUpdate} is invoked on the post-update path.
     * Increments {@link SpatialIndexCore#spatialVersion()} under the write lock and invalidates the cell-granular
     * k-NN cache for both the old and new positions.
     */
    public void updateEntity(ID entityId, Point3f newPosition, byte level) {
        core.lock().writeLock().lock();
        try {
            callback.validateSpatialConstraints(newPosition);

            // RDR-008 P1: DSOC may defer this move behind a still-valid temporal bounding volume. Read the
            // controller via the host at each call so a concurrent enableDSOC publication is observed.
            var dsoc = host.dsocController();
            if (dsoc != null && dsoc.tryDeferUpdate(entityId, newPosition)) {
                return; // Skip normal update
            }

            // Get the old position to calculate movement delta
            var oldPosition = core.entityManager().getEntityPosition(entityId);
            if (oldPosition == null) {
                throw new IllegalArgumentException("Entity not found: " + entityId);
            }

            // Calculate movement delta
            var delta = new Vector3f();
            delta.sub(newPosition, oldPosition);

            // Update entity position
            core.entityManager().updateEntityPosition(entityId, newPosition);

            // Invalidate cache
            core.entityCache().remove(entityId);

            // Update collision shape position if present
            var shape = core.entityManager().getEntityCollisionShape(entityId);
            if (shape != null) {
                shape.translate(delta);
                // Update bounds from the translated collision shape
                core.entityManager().setEntityCollisionShape(entityId, shape);
            } else {
                // Update entity bounds if no collision shape
                var oldBounds = core.entityManager().getEntityBounds(entityId);
                if (oldBounds != null) {
                    // Translate the bounds
                    var newMin = new Point3f(oldBounds.getMinX() + delta.x, oldBounds.getMinY() + delta.y,
                                             oldBounds.getMinZ() + delta.z);
                    var newMax = new Point3f(oldBounds.getMaxX() + delta.x, oldBounds.getMaxY() + delta.y,
                                             oldBounds.getMaxZ() + delta.z);
                    var newBounds = new EntityBounds(newMin, newMax);
                    core.entityManager().setEntityBounds(entityId, newBounds);
                }
            }

            // Remove from all current locations
            var oldLocations = core.entityManager().getEntityLocations(entityId);
            for (var spatialIndex : oldLocations) {
                var node = core.spatialIndex().get(spatialIndex);
                if (node != null) {
                    node.removeEntity(entityId);

                    // Remove empty nodes
                    callback.cleanupEmptyNode(spatialIndex, node);
                }
            }
            core.entityManager().clearEntityLocations(entityId);

            // Re-insert at new position
            insertAtPosition(entityId, newPosition, level);

            // k-NN cache invalidation: increment version and invalidate affected cells
            // Use level 15 for cache granularity (cell size = 64) to distinguish nearby queries
            core.spatialVersion().incrementAndGet();
            // Invalidate old position at level 15
            if (oldPosition != null) {
                var oldLevel15Key = callback.calculateSpatialIndex(oldPosition, (byte) 15);
                core.knnCache().invalidatePosition(oldLevel15Key);
            }
            // Invalidate new location at level 15
            var newSpatialKey = callback.calculateSpatialIndex(newPosition, (byte) 15);
            core.knnCache().invalidatePosition(newSpatialKey);

            // Update visibility state if DSOC is enabled (RDR-008 P1).
            if (dsoc != null) {
                dsoc.markVisibleOnUpdate(entityId);
            }
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    /** Parallel batch update via the {@link ParallelBulkOperations} executor. */
    public CompletableFuture<List<ID>> updateBatchParallel(List<ID> entityIds, List<Point3f> newPositions, byte level) {
        return host.parallelOperations().updateBatchParallel(entityIds, newPositions, level);
    }

    // ===== Clear =====

    /** Empty the entire index — drops every node and every entity under the write lock. */
    public void clear() {
        core.lock().writeLock().lock();
        try {
            core.spatialIndex().clear();
            core.entityManager().clear();
            var subdivisionManager = host.subdivisionManager();
            if (subdivisionManager != null) {
                subdivisionManager.clear();
            }
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    // ===== Internal helpers (verbatim port from the pre-extraction facade) =====

    /**
     * Spanning predicate — verbatim port of the pre-extraction facade's protected {@code shouldSpanEntity}. Reads
     * spanning policy + current sizes through the host; pure logic, no subclass involvement (no subclass
     * overrode {@code shouldSpanEntity}).
     */
    private boolean shouldSpanEntity(EntityBounds bounds, byte level) {
        if (bounds == null || !host.spanningPolicy().isSpanningEnabled()) {
            return false;
        }

        // Calculate entity size
        var entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                  bounds.getMaxZ() - bounds.getMinZ());

        // Get node size at this level
        var nodeSize = callback.getCellSizeAtLevel(level);

        // Use advanced spanning logic
        return host.spanningPolicy().shouldSpanAdvanced(entitySize, nodeSize, core.spatialIndex().size(),
                                                       core.entityManager().getEntityCount(), level);
    }


    /**
     * Insert at a single position (no spanning). Drives into deeper levels when the addressed node has been
     * subdivided; under bulk-loading mode the subdivision is deferred to {@code finalizeBulkLoading}. Runs the
     * auto-balance check after insertion.
     *
     * <p>Package-public on purpose: the facade's default {@code insertWithSpanning} fallback (the body for the
     * case where a subclass does not override spanning) needs to delegate here. The four concrete spatial
     * indices (Octree/Tetree/Prism/SFCArrayIndex) all override {@code insertWithSpanning} so the fallback is
     * dead-code under normal use; the call remains so a future subclass that omits the override behaves like the
     * pre-extraction facade.
     */
    public void insertAtPosition(ID entityId, Point3f position, byte level) {
        var spatialIndex = callback.calculateSpatialIndex(position, level);

        // Get or create node directly - no need for ancestor nodes in SFC-based implementation. Verbatim
        // port of the pre-extraction insertAtPosition: go through nodePool.acquire() so pool-warmed nodes are
        // reused (SpatialNodePoolIntegrationTest pins this). The pool's factory in turn calls the facade's
        // createNode() which is DSOC-aware.
        var node = core.spatialIndex().computeIfAbsent(spatialIndex, k -> {
            // spatialIndex key is already in the ConcurrentSkipListMap
            return host.nodePool().acquire();
        });

        // If the node has been subdivided, we need to insert into the appropriate child
        if (callback.hasChildren(spatialIndex) && !node.isEmpty()) {
            var childLevel = (byte) (level + 1);
            if (childLevel <= host.maxDepth()) {
                insertAtPosition(entityId, position, childLevel);
                return;
            }
        }

        // Add entity to node
        var shouldSplit = node.addEntity(entityId);

        // Track entity location
        core.entityManager().addEntityLocation(entityId, spatialIndex);

        // Handle subdivision if needed
        if (shouldSplit && level < host.maxDepth() && !callback.hasChildren(spatialIndex)) {
            if (host.bulkLoadingMode()) {
                // Defer subdivision during bulk loading
                host.deferSubdivision(spatialIndex, node, node.getEntityCount(), level);
            } else {
                // Immediate subdivision
                callback.handleNodeSubdivision(spatialIndex, level, node);
            }
        }

        // Check for auto-balancing after insertion
        host.checkAutoBalance();
    }

    /** Adaptive spanning dispatch — picks among memory/performance/balanced strategies based on system load. */
    void insertWithAdaptiveSpanning(ID entityId, EntityBounds bounds, byte level, int maxSpanNodes) {
        // Adapt spanning strategy based on current system state
        var currentNodeCount = core.spatialIndex().size();
        var entityCount = core.entityManager().getEntityCount();

        if (entityCount > 0) {
            var avgNodesPerEntity = (float) currentNodeCount / entityCount;

            if (avgNodesPerEntity > 100) {
                // High memory usage - use conservative spanning
                insertWithMemoryEfficientSpanning(entityId, bounds, level, maxSpanNodes / 2);
            } else if (avgNodesPerEntity < 10) {
                // Low memory usage - use aggressive spanning
                insertWithPerformanceOptimizedSpanning(entityId, bounds, level, maxSpanNodes);
            } else {
                // Balanced spanning
                insertWithBalancedSpanning(entityId, bounds, level, maxSpanNodes);
            }
        } else {
            // First entity - use balanced approach
            insertWithBalancedSpanning(entityId, bounds, level, maxSpanNodes);
        }
    }

    /**
     * Top-level spanning dispatcher driven by the spanning policy's optimization flag. Routes to one of the four
     * variants below, which all bottom out in {@code callback.insertWithSpanning(...)} (subclass extension point).
     */
    void insertWithAdvancedSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Calculate entity size for policy decisions
        var entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                  bounds.getMaxZ() - bounds.getMinZ());
        var nodeSize = callback.getCellSizeAtLevel(level);

        // Calculate maximum span nodes based on policy
        var maxSpanNodes = host.spanningPolicy().calculateMaxSpanNodes(entitySize, nodeSize, core.spatialIndex().size());

        // Apply spanning optimization strategy
        switch (host.spanningPolicy().getOptimization()) {
            case MEMORY_EFFICIENT -> insertWithMemoryEfficientSpanning(entityId, bounds, level, maxSpanNodes);
            case PERFORMANCE_FOCUSED -> insertWithPerformanceOptimizedSpanning(entityId, bounds, level, maxSpanNodes);
            case ADAPTIVE -> insertWithAdaptiveSpanning(entityId, bounds, level, maxSpanNodes);
            default -> insertWithBalancedSpanning(entityId, bounds, level, maxSpanNodes);
        }
    }

    /** Balanced spanning — bottoms out in the subclass spanning hook via the callback. */
    void insertWithBalancedSpanning(ID entityId, EntityBounds bounds, byte level, int maxSpanNodes) {
        // Use standard spanning implementation
        callback.insertWithSpanning(entityId, bounds, level);
    }

    /** Memory-efficient spanning — single-node insertion at center, spanning only for very large entities. */
    void insertWithMemoryEfficientSpanning(ID entityId, EntityBounds bounds, byte level, int maxSpanNodes) {
        // Use conservative spanning to minimize memory usage
        var center = bounds.getCenter();

        // Start with center node
        insertAtPosition(entityId, center, level);

        // Only span to immediately adjacent nodes if entity is very large
        var entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                  bounds.getMaxZ() - bounds.getMinZ());
        var nodeSize = callback.getCellSizeAtLevel(level);

        if (entitySize > nodeSize * 2.0f && maxSpanNodes > 1) {
            // Delegate to subclass for specific spanning implementation
            callback.insertWithSpanning(entityId, bounds, level);
        }
    }

    /** Performance-optimized spanning — aggressive spanning via the subclass hook. */
    void insertWithPerformanceOptimizedSpanning(ID entityId, EntityBounds bounds, byte level, int maxSpanNodes) {
        // Use aggressive spanning for better query performance
        callback.insertWithSpanning(entityId, bounds, level);
    }

    // ===== Private bulk helpers =====

    private byte determineBatchInsertionLevel(List<Point3f> positions, byte level) {
        if (!host.bulkConfig().isUseDynamicLevelSelection()) {
            return level;
        }

        var optimalLevel = LevelSelector.selectOptimalLevel(positions, host.maxEntitiesPerNode());
        if (optimalLevel != level) {
            log.debug("Dynamic level selection: changing from level {} to {} for {} entities", level, optimalLevel,
                      positions.size());
        }
        return optimalLevel;
    }

    private boolean determineMortonSortStrategy(List<Point3f> positions, byte level) {
        var shouldUseMortonSort = host.bulkConfig().isPreSortByMorton();
        if (host.bulkConfig().isUseDynamicLevelSelection()) {
            shouldUseMortonSort = shouldUseMortonSort && LevelSelector.shouldUseMortonSort(positions, level);
        }
        return shouldUseMortonSort;
    }

    private void insertDirectEntities(List<BulkOperationProcessor.SfcEntity<Key, Content>> mortonEntities, byte level,
                                      List<ID> insertedIds) {
        for (var entity : mortonEntities) {
            var entityId = core.entityManager().generateEntityId();
            insertedIds.add(entityId);
            core.entityManager().createOrUpdateEntity(entityId, entity.content, entity.position, null);
            insertAtPosition(entityId, entity.position, level);
        }
    }

    private void insertGroupedEntities(List<BulkOperationProcessor.SfcEntity<Key, Content>> mortonEntities, byte level,
                                       List<ID> insertedIds) {
        var grouped = host.bulkProcessor().groupByNode(mortonEntities, level);

        // Pre-generate IDs for better performance
        var idsNeeded = mortonEntities.size();
        var preGeneratedIds = ObjectPools.<ID>borrowArrayList(idsNeeded);
        try {
            for (int i = 0; i < idsNeeded; i++) {
                preGeneratedIds.add(core.entityManager().generateEntityId());
            }

            int idIndex = 0;
            for (var entry : grouped.getGroups().entrySet()) {
                for (var entity : entry.getValue()) {
                    var entityId = preGeneratedIds.get(idIndex++);
                    insertedIds.add(entityId);
                    core.entityManager().createOrUpdateEntity(entityId, entity.content, entity.position, null);
                    insertAtPosition(entityId, entity.position, level);
                }
            }
        } finally {
            ObjectPools.returnArrayList(preGeneratedIds);
        }
    }

    private void logBatchPerformance(int batchSize, long startTime) {
        if (batchSize > 1000) {
            var elapsedTime = System.nanoTime() - startTime;
            var rate = batchSize * 1_000_000_000.0 / elapsedTime;
            log.debug("Bulk inserted {} entities in {}ms ({} entities/sec)", batchSize,
                      String.format("%.2f", elapsedTime / 1_000_000.0), String.format("%.0f", rate));
        }
    }

    /**
     * Stack-based bulk insert. Used when the batch size crosses the stack-builder threshold. Requires a facade
     * reference for the {@code buildTree} call — the host's {@code treeBuilder()} returns the current builder
     * (read-each-call to pick up any {@code configureTreeBuilder} swap).
     */
    private List<ID> performStackBasedBulkInsert(List<Point3f> positions, List<Content> contents, byte level) {
        host.configureTreeBuilder(host.bulkConfig().getStackBuilderConfig());

        // RDR-008 P6 follow-up (Luciferase-ts8): the builder now takes a {@link
        // com.hellblazer.luciferase.lucien.StackBuilderHost} (narrow named seam) instead of the concrete
        // {@code AbstractSpatialIndex}. Resolving the builder reference and the target through the host re-reads
        // the current values, picking up any configureTreeBuilder swap that just happened above.
        var buildResult = host.treeBuilder().buildTree(stackBasedBuilderTarget(), positions, contents, level);

        log.debug("Stack-based bulk insertion completed: {} entities in {}ms, {} nodes created",
                  buildResult.entitiesProcessed, buildResult.timeTaken, buildResult.nodesCreated);

        if (buildResult.insertedIds.isEmpty() && buildResult.entitiesProcessed > 0) {
            log.warn("StackBasedTreeBuilder was configured not to track IDs but caller expects ID list. "
                     + "This can cause memory issues for large datasets. Consider using entityCount() instead of tracking individual IDs.");
            return Collections.emptyList();
        }
        return buildResult.insertedIds;
    }

    /**
     * Resolves the {@link com.hellblazer.luciferase.lucien.StackBuilderHost} target the stack-based builder
     * needs. RDR-008 P6 follow-up (bead Luciferase-ts8) narrowed the builder's signature from the concrete
     * {@code AbstractSpatialIndex<Key,ID,Content>} to this named seam, removing the {@code AbstractSpatialIndex}
     * import from the {@code lucien.entity} package.
     */
    private com.hellblazer.luciferase.lucien.StackBuilderHost<Key, ID, Content> stackBasedBuilderTarget() {
        return host.stackBuilderTarget();
    }

    private List<BulkOperationProcessor.SfcEntity<Key, Content>> preprocessBatchEntities(List<Point3f> positions,
                                                                                         List<Content> contents,
                                                                                         byte level) {

        var useParallel = host.bulkConfig().isEnableParallel() && positions.size() >= host.bulkConfig().getParallelThreshold();
        var shouldUseMortonSort = determineMortonSortStrategy(positions, level);

        if (useParallel) {
            return host.bulkProcessor().preprocessBatchParallel(positions, contents, level, shouldUseMortonSort,
                                                                host.bulkConfig().getParallelThreshold());
        } else {
            return host.bulkProcessor().preprocessBatch(positions, contents, level, shouldUseMortonSort);
        }
    }

    private boolean shouldUseStackBasedBuilder(int batchSize) {
        return host.bulkConfig().isUseStackBasedBuilder() && batchSize >= host.bulkConfig().getStackBuilderThreshold();
    }

    private void validateBatchInputs(List<Point3f> positions, List<Content> contents) {
        if (positions == null || contents == null) {
            throw new IllegalArgumentException("Positions and contents cannot be null");
        }
        if (positions.size() != contents.size()) {
            throw new IllegalArgumentException("Positions and contents must have the same size");
        }
    }
}
