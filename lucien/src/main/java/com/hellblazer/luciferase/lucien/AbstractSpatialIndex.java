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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.FineGrainedLockingStrategy.LockingConfig;
import com.hellblazer.luciferase.lucien.balancing.DefaultBalancingStrategy;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancingStrategy;
import com.hellblazer.luciferase.lucien.cache.KnnGeometry;
import com.hellblazer.luciferase.lucien.cache.KnnSearcher;
import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.cull.CullGeometry;
import com.hellblazer.luciferase.lucien.cull.Culler;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.forest.ghost.*;
import com.hellblazer.luciferase.lucien.internal.EntityCache;
import com.hellblazer.luciferase.lucien.occlusion.*;
import com.hellblazer.luciferase.lucien.internal.ObjectPools;
import com.hellblazer.luciferase.lucien.internal.UnorderedPair;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.visitor.TraversalContext;
import com.hellblazer.luciferase.lucien.visitor.TraversalStrategy;
import com.hellblazer.luciferase.lucien.visitor.TreeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract base class for spatial index implementations. Provides common functionality for entity management,
 * configuration, and basic spatial operations while allowing concrete implementations to specialize the spatial
 * subdivision strategy.
 *
 * <h2>Thread Safety and Locking Strategy</h2>
 * <p>This class uses a {@link ConcurrentNavigableMap} (specifically {@link ConcurrentSkipListMap}) for the spatial
 * index, providing thread-safe concurrent access without explicit locking for most operations. A {@link ReadWriteLock}
 * is still used for complex multi-step operations that require atomicity.</p>
 * <ul>
 *   <li><b>Lock-free operations</b> for single-key access (get, put, remove) via ConcurrentSkipListMap</li>
 *   <li><b>Read locks</b> for complex query operations requiring consistent snapshots</li>
 *   <li><b>Write locks</b> for bulk modifications and tree restructuring operations</li>
 *   <li><b>No concurrent modification exceptions</b> during iteration due to concurrent data structure</li>
 * </ul>
 *
 * <h3>Why Entity Delegation Methods Need Locking</h3>
 * <p>Although methods like {@code containsEntity()}, {@code getEntity()}, and {@code entityCount()} simply
 * delegate to the EntityManager, they must acquire read locks because:</p>
 * <ol>
 *   <li><b>Consistency:</b> Prevents entity state from changing during the operation</li>
 *   <li><b>Atomicity:</b> Ensures batch operations see a consistent snapshot</li>
 *   <li><b>Memory visibility:</b> Guarantees changes made by other threads are visible</li>
 *   <li><b>Race condition prevention:</b> Avoids issues like an entity being removed between
 *       existence check and content retrieval</li>
 * </ol>
 *
 * <p>The overhead of read locks is minimal as they allow multiple concurrent readers, only blocking
 * writers during the operation.</p>
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public abstract class AbstractSpatialIndex<Key extends SpatialKey<Key>, ID extends EntityID, Content>
implements SpatialIndex<Key, ID, Content>,
           com.hellblazer.luciferase.lucien.balancing.ShapeWeightProvider, AutoCloseable {

    /**
     * Record representing a neighbor search result with distance information.
     */
    public record NeighborResult<ID extends EntityID, Content>(ID entityId, Content content, float distance) {}

    private static final Logger log = LoggerFactory.getLogger(AbstractSpatialIndex.class);

    // Common fields
    protected final EntityManager<Key, ID, Content>                  entityManager;
    protected final int                                              maxEntitiesPerNode;
    protected final byte                                             maxDepth;
    protected final EntitySpanningPolicy                             spanningPolicy;
    // Spatial index: Key -> Node containing entity IDs, sorted for efficient range queries
    protected final ConcurrentNavigableMap<Key, SpatialNodeImpl<ID>> spatialIndex;
    // Read-write lock for thread safety (still needed for complex operations)
    protected final ReadWriteLock                                    lock;
    private final   TreeBalancer<Key, ID>                            treeBalancer;
    // Entity data cache for performance. RDR-008: protected so all four structural-nucleus fields
    // (spatialIndex, lock, entityManager, entityCache) share uniform visibility; reached by feature objects via core.
    protected final EntityCache<ID>                                  entityCache;
    // RDR-008 P6: the fine-grained locking strategy used to live here as a volatile field; it now lives inside
    // SpatialIndexCore (the only nucleus field that is mutable), reachable via core.lockingStrategy() /
    // core.setLockingStrategy(...). The migration discharged the P3 substantive-critic Significant#1; KnnSearcher
    // and CollisionEngine no longer take a Supplier<FineGrainedLockingStrategy> ctor arg.
    // Bulk operation support
    // volatile (Luciferase-3vwqb): reconfigured at runtime via configureBulkOperations while readers (bulk ops)
    // may run without holding the write lock; volatile gives atomic publication + visibility of the swapped ref.
    protected volatile BulkOperationConfig                           bulkConfig               = new BulkOperationConfig();
    protected       boolean                                          bulkLoadingMode          = false;
    protected       BulkOperationProcessor<Key, ID, Content>         bulkProcessor;
    protected       DeferredSubdivisionManager<Key, ID>              subdivisionManager;
    protected       SpatialNodePool<ID>                              nodePool;

    // volatile (Luciferase-3vwqb): swapped at runtime via configureParallelOperations; volatile publishes the
    // fully-constructed ParallelBulkOperations atomically to readers that don't hold the write lock.
    protected volatile ParallelBulkOperations<Key, ID, Content>      parallelOperations;

    protected       SubdivisionStrategy<Key, ID, Content>            subdivisionStrategy;
    protected       StackBasedTreeBuilder<Key, ID, Content>          treeBuilder;
    
    // k-NN caching (Phase 2: 20-30× speedup for cached hits)
    private final   java.util.concurrent.atomic.AtomicLong           spatialVersion = new java.util.concurrent.atomic.AtomicLong(0);
    private final   com.hellblazer.luciferase.lucien.cache.KNNCache<Key, ID> knnCache;
    // RDR-008: shared storage+concurrency nucleus handed to feature-object collaborators. Additive view over the six
    // nucleus fields (spatialIndex, lock, spatialVersion, knnCache, entityManager, entityCache), which remain the
    // authoritative declarations on this façade; constructed once below after those fields are initialized.
    protected final SpatialIndexCore<Key, ID, Content>                       core;
    // RDR-008 P1: Dynamic Scene Occlusion Culling cluster, encapsulated in DsocController (null until enableDSOC).
    // volatile: enableDSOC may run concurrently with frustumCullVisible/isDSOCEnabled; publish the reference safely.
    private volatile DsocController<Key, ID, Content>                        dsoc;
    // RDR-008 P2: distributed-ghost cluster, encapsulated in GhostCoordinator (always present; eager init in ctor).
    protected final GhostCoordinator<Key, ID, Content>                       ghost;
    // RDR-008 P3: k-NN cluster, encapsulated in KnnSearcher (always present; eager init in ctor). KnnSearcher
    // implements KnnProvider — the façade's public k-NN API now delegates to it, and other feature objects that
    // consume k-NN (e.g. GhostCoordinator) take this directly as their KnnProvider.
    protected final KnnSearcher<Key, ID, Content>                            knn;
    // RDR-008 P4: bundled frustum/plane/ray cull cluster, encapsulated in Culler (always present; eager init in
    // ctor). Culler implements FrustumCullProvider — DsocController takes it as the standard-cull fallback so the
    // FrustumGeometry consumer surface stays narrow.
    protected final Culler<Key, ID, Content>                                 culler;
    // RDR-008 P5: collision-detection cluster, encapsulated in CollisionEngine (always present; eager init in
    // ctor). RDR-008 P6 relocated the fine-grained locking strategy into SpatialIndexCore, so the prior
    // Supplier<FineGrainedLockingStrategy> ctor arg is gone — findCollisionsFineGrained reads core.lockingStrategy()
    // directly at call time, picking up any configureFineGrainedLocking replacement via the volatile field.
    protected final com.hellblazer.luciferase.lucien.collision.CollisionEngine<Key, ID, Content> collisions;
    // RDR-008 P6: entity-lifecycle cluster, encapsulated in EntityLifecycleManager (always present; eager init in
    // ctor). The broadest cluster in the decomposition — takes both an EntityLifecycleGeometry callback (subclass-
    // overridden hooks + cached entity accessors) and an EntityLifecycleHost interface (facade-internal
    // infrastructure: bulk config/processor/builder, parallel ops, node pool, deferred-subdivision manager,
    // spanning policy, the DSOC controller for the P1 updateEntity seam, the ghost-update hook, the auto-balance
    // hook). The host interface is the principled middle ground after P3 refinement; it's narrower than the
    // concrete-facade back-reference GhostCoordinator carries from P2 (see GhostCoordinator's P2-concession note).
    protected final com.hellblazer.luciferase.lucien.entity.EntityLifecycleManager<Key, ID, Content> entityLifecycle;
    // RDR-008 P6 follow-up (Luciferase-ts8) review cleanup: cache the stack-builder host as a final field instead
    // of allocating a fresh StackBuilderHostImpl at every call site (bulk-insert paths + EntityLifecycleHostImpl.
    // stackBuilderTarget()). The wrapper is stateless and identity-stable, so a single instance suffices.
    private final   StackBuilderHostImpl                                     stackBuilderHost         = new StackBuilderHostImpl();
    // Tree balancing support
    private         TreeBalancingStrategy<ID>                        balancingStrategy;
    private         boolean                                          autoBalancingEnabled     = false;
    private         long                                             lastBalancingTime        = 0;

    /**
     * Constructor with common parameters
     */
    protected AbstractSpatialIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                                   EntitySpanningPolicy spanningPolicy) {
        this.entityManager = new EntityManager<>(idGenerator);
        this.maxEntitiesPerNode = maxEntitiesPerNode;
        this.maxDepth = maxDepth;
        this.spanningPolicy = Objects.requireNonNull(spanningPolicy);
        this.spatialIndex = new ConcurrentSkipListMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.balancingStrategy = new DefaultBalancingStrategy<>();
        this.treeBalancer = createTreeBalancer();
        this.bulkProcessor = new BulkOperationProcessor<>(this);
        this.subdivisionManager = new DeferredSubdivisionManager<>();
        this.nodePool = new SpatialNodePool<>(this::createNode);
        this.parallelOperations = new ParallelBulkOperations<>(this, bulkProcessor,
                                                               ParallelBulkOperations.defaultConfig());
        this.subdivisionStrategy = createDefaultSubdivisionStrategy();
        this.treeBuilder = new StackBasedTreeBuilder<>(StackBasedTreeBuilder.defaultConfig());
        this.entityCache = new EntityCache<>(10000); // Cache up to 10k entities
        this.knnCache = new com.hellblazer.luciferase.lucien.cache.KNNCache<>(); // k-NN result caching
        // RDR-008 P0+P6: wrap the now-initialized seven-field nucleus (six immutable + the mutable
        // lockingStrategy) in a single shared view for feature-object collaborators. The initial locking strategy
        // is constructed inline; configureFineGrainedLocking later mutates the volatile field inside core.
        var initialLockingStrategy = new FineGrainedLockingStrategy<ID, Content>(this,
                                                                                 FineGrainedLockingStrategy.defaultConfig());
        this.core = new SpatialIndexCore<>(spatialIndex, lock, spatialVersion, knnCache, entityManager, entityCache,
                                           initialLockingStrategy);

        // RDR-008 P3: k-NN cluster lives in KnnSearcher (implements KnnProvider). Constructed before
        // GhostCoordinator so the latter can take it directly as its KnnProvider. The mutable fine-grained
        // locking strategy is read from core at call time, so no Supplier ctor arg is needed (P6 migration).
        this.knn = new KnnSearcher<>(core, new KnnGeometryImpl());

        // RDR-008 P4: bundled frustum/plane/ray cull cluster lives in Culler (implements FrustumCullProvider).
        // Constructed before the ghost coordinator so DSOC's lazy construction (enableDSOC) can take it as the
        // standard-cull fallback. The CullGeometryImpl callback stores `this` but the ctor doesn't dispatch through
        // it (storage-only).
        this.culler = new Culler<>(core, new CullGeometryImpl());

        // RDR-008 P5: collision-detection cluster lives in CollisionEngine. The CollisionGeometryImpl callback
        // stores `this` but the ctor doesn't dispatch through it (storage-only). The fine-grained locking
        // strategy is read from core at call time (P6 migration replaced the prior Supplier ctor arg).
        this.collisions = new com.hellblazer.luciferase.lucien.collision.CollisionEngine<>(core,
                                                                                            new CollisionGeometryImpl());

        // RDR-008 P6: entity-lifecycle cluster lives in EntityLifecycleManager. The EntityLifecycleGeometryImpl
        // callback and EntityLifecycleHostImpl host both store `this` but the ctor doesn't dispatch through them
        // (storage-only) — the this-escape invariant.
        this.entityLifecycle = new com.hellblazer.luciferase.lucien.entity.EntityLifecycleManager<>(core,
                                                                                                    new EntityLifecycleGeometryImpl(),
                                                                                                    new EntityLifecycleHostImpl());

        // RDR-008 P2: distributed-ghost cluster lives in GhostCoordinator; constructed eagerly so subclasses can
        // call setNeighborDetector during their own initialization.
        // INVARIANT for future phases: GhostCoordinator's ctor (and the ctors of any other feature object created
        // here — including KnnSearcher, Culler, CollisionEngine, and EntityLifecycleManager above) MUST NOT invoke
        // any method on the supplied FrustumGeometryImpl / KnnGeometryImpl / CullGeometryImpl /
        // CollisionGeometryImpl / EntityLifecycleGeometryImpl / EntityLifecycleHostImpl, on the KnnProvider /
        // FrustumCullProvider, or on `this` directly — `this` is still partially constructed at this point, and
        // any virtual dispatch into a not-yet-initialized subclass is a classic this-escape hazard. Storing the
        // references is fine; calling through them is not.  (KnnSearcher's, Culler's, CollisionEngine's, and
        // EntityLifecycleManager's ctors store only; P6 removed the lockingStrategy supplier that previously
        // captured `this`.)
        // The `knn` argument below satisfies KnnProvider<Key,ID> (P3-main moved this role off the façade); the
        // second `this` is the façade back-reference the ghost subsystem (GhostBoundaryDetector +
        // DistributedGhostManager) needs in their ctors.
        this.ghost = new GhostCoordinator<>(core, knn, this);
    }

    @Override
    public Stream<SpatialIndex.SpatialNode<Key, ID>> boundedBy(Spatial volume) {
        validateSpatialConstraints(volume);

        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            var results = spatialRangeQuery(bounds, false).filter(
            entry -> isNodeContainedInVolume(entry.getKey(), volume)).map(
            entry -> new SpatialIndex.SpatialNode<Key, ID>(entry.getKey(),
                                                           new HashSet<>(entry.getValue().getEntityIds()))).toList();
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<SpatialIndex.SpatialNode<Key, ID>> bounding(Spatial volume) {
        validateSpatialConstraints(volume);

        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            var results = spatialRangeQuery(bounds, true).filter(
            entry -> doesNodeIntersectVolume(entry.getKey(), volume)).map(
            entry -> new SpatialIndex.SpatialNode<Key, ID>(entry.getKey(),
                                                           new HashSet<>(entry.getValue().getEntityIds()))).toList();
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Build tree using stack-based approach for better cache locality
     */
    public StackBasedTreeBuilder.BuildResult buildTreeStackBased(List<Point3f> positions, List<Content> contents,
                                                                 byte startLevel) {
        if (positions.size() != contents.size()) {
            throw new IllegalArgumentException("Positions and contents must have the same size");
        }

        lock.writeLock().lock();
        try {
            // Clear existing tree if needed
            if (!spatialIndex.isEmpty()) {
                spatialIndex.clear();
                // spatialIndex.clear() is handled above
                entityManager.clear();
            }

            // Build tree (RDR-008 P6 follow-up Luciferase-ts8: builder takes StackBuilderHost, not the concrete
            // facade — protect against the god-class type leak; cached stackBuilderHost field, not a per-call
            // allocation, per the review-cleanup pass)
            return treeBuilder.buildTree(stackBuilderHost, positions, contents, startLevel);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ===== Common Entity Management Methods =====

    @Override
    public Optional<CollisionPair<ID, Content>> checkCollision(ID entityId1, ID entityId2) {
        return collisions.checkCollision(entityId1, entityId2);
    }

    /**
     * Clear the entity cache (useful for benchmarking or memory pressure)
     */
    public void clearCache() {
        lock.writeLock().lock();
        try {
            entityCache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void configureBulkOperations(BulkOperationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Bulk operation config cannot be null");
        }
        this.bulkConfig = config;
    }

    /**
     * Configure fine-grained locking strategy. RDR-008 P6: the strategy now lives inside {@link SpatialIndexCore}
     * (the only mutable nucleus field), so the replacement goes through {@link SpatialIndexCore#setLockingStrategy};
     * the surrounding write-lock acquisition is preserved to sequence the swap against pending mutations.
     */
    public void configureFineGrainedLocking(LockingConfig config) {
        lock.writeLock().lock();
        try {
            core.setLockingStrategy(new FineGrainedLockingStrategy<>(this, config));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Configure parallel bulk operations. Shuts down the OLD pool before the volatile swap to avoid leaking the
     * replaced pool (Luciferase-7wzml.60).
     */
    public void configureParallelOperations(ParallelBulkOperations.ParallelConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Parallel config cannot be null");
        }
        var old = this.parallelOperations;
        this.parallelOperations = new ParallelBulkOperations<>(this, bulkProcessor, config);
        if (old != null) {
            old.close(); // shutdown + awaitTermination; safe to call after the swap
        }
    }

    /**
     * Configure subdivision strategy
     */
    public void configureSubdivisionStrategy(SubdivisionStrategy<Key, ID, Content> strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Subdivision strategy cannot be null");
        }
        this.subdivisionStrategy = strategy;
    }

    /**
     * Configure stack-based tree builder
     */
    public void configureTreeBuilder(StackBasedTreeBuilder.BuildConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Tree builder config cannot be null");
        }
        this.treeBuilder = new StackBasedTreeBuilder<>(config);
    }

    /**
     * Check if an entity exists in the spatial index. RDR-008 P6: delegates to
     * {@link com.hellblazer.luciferase.lucien.entity.EntityLifecycleManager}.
     */
    @Override
    public boolean containsEntity(ID entityId) {
        return entityLifecycle.containsEntity(entityId);
    }

    /**
     * Disable optimistic concurrency and use conservative locking Better for write-heavy workloads or when consistency
     * is critical
     */
    public void disableOptimisticConcurrency() {
        configureFineGrainedLocking(FineGrainedLockingStrategy.conservativeConfig());
    }

    @Override
    public void enableBulkLoading() {
        lock.writeLock().lock();
        try {
            this.bulkLoadingMode = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Enable optimistic concurrency for batch read operations Reduces contention for read-heavy workloads
     */
    public void enableOptimisticConcurrency() {
        configureFineGrainedLocking(FineGrainedLockingStrategy.highConcurrencyConfig());
    }

    /**
     * Get all entities within a spatial region.
     *
     * This implementation: 1. Finds all nodes that intersect the region 2. Collects all entities from those nodes 3.
     * Optionally filters by exact position (if precise is true)
     *
     * @param region the spatial region to query
     * @return list of entity IDs in the region
     */
    @Override
    public List<ID> entitiesInRegion(Spatial.Cube region) {
        // Validate region based on implementation constraints
        validateSpatialConstraints(new Point3f(region.originX(), region.originY(), region.originZ()));

        var uniqueEntities = new HashSet<ID>();

        lock.readLock().lock();
        try {
            // Convert to volume bounds
            var bounds = new VolumeBounds(region.originX(), region.originY(), region.originZ(),
                                          region.originX() + region.extent(), region.originY() + region.extent(),
                                          region.originZ() + region.extent());

            // Use spatial range query to find all intersecting nodes
            var nodeList = spatialRangeQuery(bounds, true).collect(java.util.stream.Collectors.toList());

            // Collect all entities from intersecting nodes
            nodeList.forEach(entry -> {
                if (!entry.getValue().isEmpty()) {
                    uniqueEntities.addAll(entry.getValue().getEntityIds());
                }
            });

            // IMPORTANT: For spanning entities, we also need to check ALL entities that have bounds
            // This is because a spanning entity might be stored in nodes outside the query region
            // but still have bounds that intersect with the query region
            if (spanningPolicy.isSpanningEnabled()) {
                // Check all entities with bounds
                for (var entry : spatialIndex.entrySet()) {
                    for (var entityId : entry.getValue().getEntityIds()) {
                        if (!uniqueEntities.contains(entityId)) {
                            var entityBounds = entityManager.getEntityBounds(entityId);
                            if (entityBounds != null && entityBounds.intersectsCube(region.originX(), region.originY(),
                                                                                    region.originZ(),
                                                                                    region.extent())) {
                                uniqueEntities.add(entityId);
                            }
                        }
                    }
                }
            }

            // Filter by exact intersection - check entity bounds vs query region
            return uniqueEntities.stream().filter(entityId -> {
                // Check if entity bounds intersect with query region
                var entityBounds = entityManager.getEntityBounds(entityId);
                if (entityBounds != null) {
                    // Entity has bounds - check bounds intersection
                    return entityBounds.intersectsCube(region.originX(), region.originY(), region.originZ(),
                                                       region.extent());
                } else {
                    // Entity is a point - check position intersection
                    var pos = entityManager.getEntityPosition(entityId);
                    if (pos == null) {
                        return false;
                    }
                    return pos.x >= region.originX() && pos.x <= region.originX() + region.extent()
                    && pos.y >= region.originY() && pos.y <= region.originY() + region.extent()
                    && pos.z >= region.originZ() && pos.z <= region.originZ() + region.extent();
                }
            }).collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the total number of entities in the spatial index. RDR-008 P6: delegates to
     * {@link com.hellblazer.luciferase.lucien.entity.EntityLifecycleManager}.
     */
    @Override
    public int entityCount() {
        return entityLifecycle.entityCount();
    }

    /** Empty the entire index. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public void clear() {
        entityLifecycle.clear();
    }

    @Override
    public void finalizeBulkLoading() {
        lock.writeLock().lock();
        try {
            this.bulkLoadingMode = false;

            // Process all deferred subdivisions using the manager
            if (bulkConfig.isDeferSubdivision()) {
                var result = subdivisionManager.processAll(
                new DeferredSubdivisionManager.SubdivisionProcessor<Key, ID, SpatialNodeImpl<ID>>() {
                    @Override
                    public Result subdivideNode(Key nodeIndex, SpatialNodeImpl<ID> node, byte level) {
                        var initialCount = spatialIndex.size();
                        var entityCount = node.getEntityCount();

                        // Only subdivide if still over threshold
                        if (entityCount > maxEntitiesPerNode && level < maxDepth) {
                            handleNodeSubdivision(nodeIndex, level, node);
                            var newNodes = spatialIndex.size() - initialCount;
                            return new Result(true, newNodes, entityCount);
                        }
                        return new Result(false, 0, 0);
                    }
                });

                // Log deferred subdivision results
                if (result.nodesProcessed > 0) {
                    log.debug("Deferred subdivisions: {} processed, {} subdivided, {} new nodes in {}ms",
                              result.nodesProcessed, result.nodesSubdivided, result.newNodesCreated,
                              result.getProcessingTimeMs());
                }
            }

            // Trigger ghost updates after tree adaptation
            triggerGhostUpdateAfterAdaptation();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<CollisionPair<ID, Content>> findAllCollisions() {
        return collisions.findAllCollisions();
    }

    @Override
    public List<CollisionPair<ID, Content>> findCollisions(ID entityId) {
        return collisions.findCollisions(entityId);
    }

    // ===== Common Insert Operations =====

    /**
     * Find collisions using fine-grained locking for better concurrency. Alternative to {@link #findCollisions}
     * when high read concurrency is needed.
     */
    public List<CollisionPair<ID, Content>> findCollisionsFineGrained(ID entityId) {
        return collisions.findCollisionsFineGrained(entityId);
    }

    @Override
    public List<CollisionPair<ID, Content>> findCollisionsInRegion(Spatial region) {
        return collisions.findCollisionsInRegion(region);
    }

    /**
     * Find all entities completely inside the frustum. Composes on top of {@link #frustumCullVisible} so the result
     * inherits the DSOC routing (when enabled) — calling {@link Culler} directly would silently bypass DSOC.
     */
    public List<FrustumIntersection<ID, Content>> frustumCullInside(Frustum3D frustum, Point3f cameraPosition) {
        return frustumCullVisible(frustum, cameraPosition).stream()
                                                          .filter(FrustumIntersection::isCompletelyInside)
                                                          .collect(Collectors.toList());
    }

    /**
     * Find all entities intersecting the frustum boundary. Composes on top of {@link #frustumCullVisible} so the
     * result inherits the DSOC routing (when enabled).
     */
    public List<FrustumIntersection<ID, Content>> frustumCullIntersecting(Frustum3D frustum, Point3f cameraPosition) {
        return frustumCullVisible(frustum, cameraPosition).stream()
                                                          .filter(FrustumIntersection::isPartiallyVisible)
                                                          .collect(Collectors.toList());
    }

    /**
     * Find all entities visible in the frustum. Preserves the P1 DSOC seam: when a {@link DsocController} is present
     * the cull goes through DSOC (auto-disable, occlusion, perf measurement); otherwise it runs the standard cull
     * directly via {@link Culler}. The composed entries below ({@link #frustumCullInside},
     * {@link #frustumCullIntersecting}, {@link #frustumCullWithinDistance}) flow through this method, never through
     * the {@code Culler} delegators, so they all inherit the DSOC routing decision.
     */
    public List<FrustumIntersection<ID, Content>> frustumCullVisible(Frustum3D frustum, Point3f cameraPosition) {
        if (frustum == null) {
            throw new NullPointerException("Frustum cannot be null");
        }
        if (dsoc != null) {
            return dsoc.frustumCullVisible(frustum, cameraPosition);
        }
        return culler.frustumCullVisibleStandard(frustum, cameraPosition);
    }

    /**
     * Simple ID-only frustum visibility query. <strong>Intentionally bypasses DSOC</strong>: the pre-extraction
     * single-arg form was always non-DSOC, and DSOC culling requires a camera position and {@code Content} retrieval
     * that the ID-only contract deliberately excludes. For DSOC-aware culling use
     * {@link #frustumCullVisible(Frustum3D, Point3f)}.
     */
    @Override
    public List<ID> frustumCullVisible(Frustum3D frustum) {
        return culler.frustumCullVisibleIds(frustum);
    }

    /**
     * Find all entities within {@code maxDistance} of the camera visible in the frustum. Composes on top of
     * {@link #frustumCullVisible} so the result inherits the DSOC routing (when enabled).
     */
    public List<FrustumIntersection<ID, Content>> frustumCullWithinDistance(Frustum3D frustum, Point3f cameraPosition,
                                                                            float maxDistance) {
        return frustumCullVisible(frustum, cameraPosition).stream()
                                                          .filter(i -> i.distanceFromCamera() <= maxDistance)
                                                          .collect(Collectors.toList());
    }

    // ===== Common Remove Operations =====

    /**
     * Get balancing statistics for the tree.
     */
    public TreeBalancingStrategy.TreeBalancingStats getBalancingStats() {
        lock.readLock().lock();
        try {
            var totalNodes = 0;
            var underpopulatedNodes = 0;
            var overpopulatedNodes = 0;
            var emptyNodes = 0;
            var maxDepth = 0;
            var totalEntities = 0L;

            // SFC-optimized balancing stats: Process nodes in spatial order for better cache locality
            // This improves memory access patterns during statistics calculation
            for (var nodeIndex : spatialIndex.keySet()) {
                var node = spatialIndex.get(nodeIndex);
                if (node == null) {
                    continue;
                }

                var level = nodeIndex.getLevel();
                maxDepth = Math.max(maxDepth, level);

                if (node.isEmpty()) {
                    emptyNodes++;
                    totalNodes++;
                } else {
                    totalNodes++;
                    var entityCount = node.getEntityCount();
                    totalEntities += entityCount;

                    var mergeThreshold = balancingStrategy.getMergeThreshold(level, maxEntitiesPerNode);
                    var splitThreshold = balancingStrategy.getSplitThreshold(level, maxEntitiesPerNode);

                    if (entityCount < mergeThreshold) {
                        underpopulatedNodes++;
                    } else if (entityCount > splitThreshold) {
                        overpopulatedNodes++;
                    }
                }
            }

            var averageLoad = totalNodes > 0 ? (double) totalEntities / totalNodes : 0;

            // Calculate variance using SFC ordering for improved cache performance
            var variance = 0.0;
            if (totalNodes > 0) {
                for (var nodeIndex : spatialIndex.keySet()) {
                    var node = spatialIndex.get(nodeIndex);
                    if (node != null) {
                        var diff = node.getEntityCount() - averageLoad;
                        variance += diff * diff;
                    }
                }
                variance /= totalNodes;
            }

            return new TreeBalancingStrategy.TreeBalancingStats(totalNodes, underpopulatedNodes, overpopulatedNodes,
                                                                emptyNodes, maxDepth, averageLoad, variance);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get cache statistics for monitoring
     */
    public EntityCache.CacheStats getCacheStats() {
        return entityCache.getStats();
    }

    /** Get k-NN performance metrics. RDR-008 P3: delegates to {@link KnnSearcher}. */
    public KnnSearcher.KNNPerformanceMetrics getKNNPerformanceMetrics() {
        return knn.getKNNPerformanceMetrics();
    }

    @Override
    public CollisionShape getCollisionShape(ID entityId) {
        return collisions.getCollisionShape(entityId);
    }

    /** Batch content retrieval. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public List<Content> getEntities(List<ID> entityIds) {
        return entityLifecycle.getEntities(entityIds);
    }

    // ===== Common Update Operations =====

    /** Snapshot of every entity's current position. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public Map<ID, Point3f> getEntitiesWithPositions() {
        return entityLifecycle.getEntitiesWithPositions();
    }

    // ===== Common Query Operations =====

    /** Content lookup by ID. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public Content getEntity(ID entityId) {
        return entityLifecycle.getEntity(entityId);
    }

    /** Cached + read-locked bounds lookup. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public EntityBounds getEntityBounds(ID entityId) {
        return entityLifecycle.getEntityBounds(entityId);
    }

    /** Cached + read-locked position lookup. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public Point3f getEntityPosition(ID entityId) {
        return entityLifecycle.getEntityPosition(entityId);
    }

    /** Number of nodes the entity spans. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public int getEntitySpanCount(ID entityId) {
        return entityLifecycle.getEntitySpanCount(entityId);
    }

    /**
     * The spatial keys at which an entity is stored (Luciferase-fhc9), delegating to the entity manager. Each
     * key carries the entity's refinement level; used by load balancing to preserve level on migration.
     *
     * <p>Read-lock guarded (Luciferase-1q51y): the sibling accessors all go through the entity-lifecycle read lock,
     * but this one delegated straight to the entity manager. A concurrent {@code updateEntity} (which clears then
     * re-inserts the location set under the write lock) could otherwise be observed mid-move, returning a
     * transiently EMPTY set — which load balancing / ghost range queries would route on. The entity manager already
     * returns a defensive snapshot, so the lock only needs to bracket the read.</p>
     */
    @Override
    public Set<Key> getEntityLocations(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.getEntityLocations(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get memory usage statistics for capacity planning
     */
    public MemoryStats getMemoryStats() {
        lock.readLock().lock();
        try {
            var nodeCount = spatialIndex.size();
            var totalEntities = (long) entityManager.getEntityCount();
            var avgEntitiesPerNode = nodeCount > 0 ? (float) totalEntities / nodeCount : 0;

            var estimatedMemory = NodeEstimator.estimateMemoryUsage(nodeCount, (int) Math.ceil(avgEntitiesPerNode));

            return new MemoryStats(nodeCount, totalEntities, avgEntitiesPerNode, estimatedMemory);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the total number of nodes in this spatial index.
     *
     * @return the number of nodes
     */
    public int getNodeCount() {
        lock.readLock().lock();
        try {
            return spatialIndex.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get parallel operations performance statistics
     */
    public Map<String, Object> getParallelPerformanceStats() {
        return parallelOperations.getPerformanceStatistics();
    }

    @Override
    public EntityStats getStats() {
        lock.readLock().lock();
        try {
            var nodeCount = 0;
            var entityCount = entityManager.getEntityCount();
            var totalEntityReferences = 0;
            var maxDepth = 0;

            for (var entry : getSpatialIndex().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    nodeCount++;
                }
                totalEntityReferences += entry.getValue().getEntityCount();

                // Calculate depth from spatial index
                maxDepth = Math.max(maxDepth, entry.getKey().getLevel());
            }

            return new EntityStats(nodeCount, entityCount, totalEntityReferences, maxDepth);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get current subdivision strategy
     */
    public SubdivisionStrategy<Key, ID, Content> getSubdivisionStrategy() {
        return subdivisionStrategy;
    }

    @Override
    public boolean hasNode(Key spatialIndex) {
        lock.readLock().lock();
        try {
            var node = getSpatialIndex().get(spatialIndex);
            return node != null && !node.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * O(log N) entity-ID lookup by spatial key (Luciferase-7wzml.2 H1). Backed by a direct
     * {@link java.util.concurrent.ConcurrentSkipListMap#get} — no stream scan needed.
     */
    @Override
    public Set<ID> getEntityIdsAt(Key key) {
        lock.readLock().lock();
        try {
            var node = getSpatialIndex().get(key);
            if (node == null || node.isEmpty()) {
                return java.util.Collections.emptySet();
            }
            return node.getEntityIdsAsSet();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Auto-id insert (no bounds). RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public ID insert(Point3f position, byte level, Content content) {
        return entityLifecycle.insert(position, level, content);
    }

    /** Explicit-id insert (no bounds). RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public void insert(ID entityId, Point3f position, byte level, Content content) {
        entityLifecycle.insert(entityId, position, level, content);
    }

    /**
     * Explicit-id insert with bounds — dispatches to spanning when both bounds and the spanning policy are present.
     * RDR-008 P6: delegates to {@code EntityLifecycleManager}.
     */
    @Override
    public void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds) {
        entityLifecycle.insert(entityId, position, level, content, bounds);
    }

    // ===== Common k-NN Search Implementation =====

    /** Bulk insert with full {@link EntityData} records. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    public void insertAll(List<EntityData<ID, Content>> entities) {
        entityLifecycle.insertAll(entities);
    }

    /** Bulk position+content batch insert. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public List<ID> insertBatch(List<Point3f> positions, List<Content> contents, byte level) {
        return entityLifecycle.insertBatch(positions, contents, level);
    }

    /** Parallel bulk insert. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    public ParallelBulkOperations.ParallelOperationResult<ID> insertBatchParallel(List<Point3f> positions,
                                                                                  List<Content> contents, byte level)
    throws InterruptedException {
        return entityLifecycle.insertBatchParallel(positions, contents, level);
    }

    /** Bulk insert with explicit bounds. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public List<ID> insertBatchWithSpanning(List<EntityBounds> bounds, List<Content> contents, byte level) {
        return entityLifecycle.insertBatchWithSpanning(bounds, contents, level);
    }

    /**
     * Check if automatic balancing is enabled.
     */
    public boolean isAutoBalancingEnabled() {
        lock.readLock().lock();
        try {
            return autoBalancingEnabled;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find k nearest neighbors to a query point using spatial locality optimization.
     * <p>RDR-008 P3: delegates to {@link KnnSearcher}; the cluster (cache, version pinning, SFC range pruning,
     * expanding-radius fallback, full-domain sweep, legacy BFS fallback) lives there.
     * <p>Luciferase-us4zr: held under the global read lock. {@link KnnSearcher} traverses the index via the
     * fine-grained per-node locking strategy, which is independent of this {@code lock}; all write paths (single
     * insert/remove and batch) take {@code lock.writeLock()}, so without this outer read guard a concurrent kNN
     * could observe torn/partial state mid-write. Range queries ({@link #entitiesInRegion}) and collision already
     * take this read lock — kNN was the outlier. The lock is reentrant, so KnnSearcher's nested reads are fine.
     */
    public List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance) {
        lock.readLock().lock();
        try {
            return knn.kNearestNeighbors(queryPoint, k, maxDistance);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get a stream of leaf nodes (nodes with no children).
     *
     * @return Stream of leaf nodes
     */
    public Stream<SpatialIndex.SpatialNode<Key, ID>> leafStream() {
        lock.readLock().lock();
        try {
            // Materialize inside lock to avoid lazy stream evaluating after lock release
            var results = spatialIndex.entrySet()
                                      .stream()
                                      .filter(entry -> !entry.getValue().isEmpty() && !hasChildren(entry.getKey()))
                                      .map(entry -> new SpatialIndex.SpatialNode<>(entry.getKey(),
                                                                                   new HashSet<>(entry.getValue().getEntityIds())))
                                      .collect(Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get a stream of nodes at a specific level.
     *
     * @param level The spatial level
     * @return Stream of nodes at the specified level
     */
    public Stream<SpatialIndex.SpatialNode<Key, ID>> levelStream(byte level) {
        lock.readLock().lock();
        try {
            // Materialize inside lock to avoid lazy stream evaluating after lock release
            var results = spatialIndex.keySet()
                                      .stream()
                                      .filter(index -> index.getLevel() == level)
                                      .map(index -> Map.entry(index, spatialIndex.get(index)))
                                      .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                                      .map(entry -> new SpatialIndex.SpatialNode<>(entry.getKey(),
                                                                                   new HashSet<>(entry.getValue().getEntityIds())))
                                      .collect(Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Point-precise + level-precise entity lookup. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public List<ID> lookup(Point3f position, byte level) {
        return entityLifecycle.lookup(position, level);
    }

    public int nodeCount() {
        lock.readLock().lock();
        try {
            return (int) getSpatialIndex().values().stream().filter(node -> !node.isEmpty()).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get a stream of all non-empty nodes in the spatial index.
     *
     * @return Stream of spatial nodes
     */
    public Stream<SpatialIndex.SpatialNode<Key, ID>> nodeStream() {
        lock.readLock().lock();
        try {
            // Materialize inside lock to avoid lazy stream evaluating after lock release
            var results = spatialIndex.entrySet()
                                      .stream()
                                      .filter(entry -> !entry.getValue().isEmpty())
                                      .map(entry -> new SpatialIndex.SpatialNode<>(entry.getKey(),
                                                                                   new HashSet<>(entry.getValue().getEntityIds())))
                                      .collect(Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<SpatialIndex.SpatialNode<Key, ID>> nodes() {
        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            var results = getSpatialIndex().entrySet()
                                           .stream()
                                           .filter(entry -> !entry.getValue().isEmpty())
                                           .map(entry -> new SpatialIndex.SpatialNode<>(entry.getKey(), new HashSet<>(
                                           entry.getValue().getEntityIds())))
                                           .collect(Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Find all entities intersecting the plane within {@code tolerance}. RDR-008 P4: delegates to {@link Culler}. */
    public List<PlaneIntersection<ID, Content>> planeIntersectAll(Plane3D plane, float tolerance) {
        return culler.planeIntersectAll(plane, tolerance);
    }

    // ===== Common Spatial Query Base =====

    /** Find all entities exactly intersecting the plane. RDR-008 P4: delegates to {@link Culler}. */
    public List<PlaneIntersection<ID, Content>> planeIntersectAll(Plane3D plane) {
        return culler.planeIntersectAll(plane);
    }

    /** Find all entities on the negative side of the plane. RDR-008 P4: delegates to {@link Culler}. */
    public List<PlaneIntersection<ID, Content>> planeIntersectNegativeSide(Plane3D plane) {
        return culler.planeIntersectNegativeSide(plane);
    }

    /** Find all entities on the positive side of the plane. RDR-008 P4: delegates to {@link Culler}. */
    public List<PlaneIntersection<ID, Content>> planeIntersectPositiveSide(Plane3D plane) {
        return culler.planeIntersectPositiveSide(plane);
    }

    /** Find all entities within {@code maxDistance} of the plane. RDR-008 P4: delegates to {@link Culler}. */
    public List<PlaneIntersection<ID, Content>> planeIntersectWithinDistance(Plane3D plane, float maxDistance) {
        return culler.planeIntersectWithinDistance(plane, maxDistance);
    }

    /**
     * Pre-allocate nodes based on sample positions. Analyzes the sample to predict node distribution for the full
     * dataset.
     *
     * @param samplePositions    Sample positions representing the distribution
     * @param totalExpectedCount Total number of entities expected
     * @param level              The tree level for analysis
     */
    public void preAllocateAdaptive(List<Point3f> samplePositions, int totalExpectedCount, byte level) {
        if (samplePositions.isEmpty()) {
            return;
        }

        lock.writeLock().lock();
        try {
            // Analyze sample distribution
            var uniqueMortonCodes = new HashSet<Key>();
            for (var pos : samplePositions) {
                var morton = calculateSpatialIndex(pos, level);
                uniqueMortonCodes.add(morton);
            }

            // Estimate total nodes needed
            var estimatedNodes = NodeEstimator.estimateFromSamples(totalExpectedCount, samplePositions.size(),
                                                                   uniqueMortonCodes.size(), maxEntitiesPerNode);

            // Pre-allocate the unique nodes found in sample
            var created = 0;
            for (var morton : uniqueMortonCodes) {
                if (!spatialIndex.containsKey(morton)) {
                    var node = createNode();
                    spatialIndex.put(morton, node);
                    // morton is already added to spatialIndex above
                    created++;
                }
            }

            // Note: HashMap pre-sizing would require recreating the map with initial capacity
            // For now, just log the recommendation
            var remainingCapacity = estimatedNodes - created;
            if (remainingCapacity > 0) {
                log.debug("Recommendation: Pre-size HashMap for {} additional nodes", remainingCapacity);
            }

            log.debug("Pre-allocated {} nodes adaptively (sample size: {}, estimated total: {})", created,
                      samplePositions.size(), estimatedNodes);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Pre-allocate nodes based on expected entity count and distribution. This can significantly improve bulk insertion
     * performance by reducing allocation overhead.
     *
     * @param expectedEntityCount Expected number of entities to insert
     * @param distribution        Spatial distribution pattern of entities
     */
    public void preAllocateNodes(int expectedEntityCount, NodeEstimator.SpatialDistribution distribution) {
        lock.writeLock().lock();
        try {
            // Estimate required nodes
            var estimatedNodes = NodeEstimator.estimateNodeCount(expectedEntityCount, maxEntitiesPerNode, maxDepth,
                                                                 distribution);

            // Pre-size the HashMap to avoid rehashing
            // Note: We would need to recreate the HashMap with initial capacity since
            // Java's HashMap doesn't have ensureCapacity. For now, we'll just log the recommendation.
            var recommendedCapacity = (int) (estimatedNodes / 0.75f) + 1; // Account for load factor

            // Pre-allocate TreeSet capacity if possible
            // Note: TreeSet doesn't have pre-allocation, but we can optimize by
            // using a more efficient set implementation if needed

            log.debug("Pre-allocated capacity for {} nodes (estimated from {} entities)", estimatedNodes,
                      expectedEntityCount);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Pre-allocate nodes for a uniform grid at a specific level. Useful when you know entities will be distributed
     * uniformly.
     *
     * @param level             The tree level to pre-allocate
     * @param nodesPerDimension Number of nodes per dimension (total = n³)
     */
    public void preAllocateUniformGrid(byte level, int nodesPerDimension) {
        if (level > maxDepth) {
            throw new IllegalArgumentException("Level " + level + " exceeds maxDepth " + maxDepth);
        }

        lock.writeLock().lock();
        try {
            var totalNodes = NodeEstimator.estimateUniformGridNodes(level, nodesPerDimension);

            // Pre-create nodes in a grid pattern
            var cellSize = getCellSizeAtLevel(level);
            var created = 0;

            for (int x = 0; x < nodesPerDimension && created < totalNodes; x++) {
                for (int y = 0; y < nodesPerDimension && created < totalNodes; y++) {
                    for (int z = 0; z < nodesPerDimension && created < totalNodes; z++) {
                        var position = new Point3f(x * cellSize + cellSize / 2, y * cellSize + cellSize / 2,
                                                   z * cellSize + cellSize / 2);

                        var mortonIndex = calculateSpatialIndex(position, level);

                        // Only create if doesn't exist
                        if (!spatialIndex.containsKey(mortonIndex)) {
                            var node = createNode();
                            spatialIndex.put(mortonIndex, node);
                            // mortonIndex is already added to spatialIndex above
                            created++;
                        }
                    }
                }
            }

            log.debug("Pre-allocated {} nodes in uniform grid at level {}", created, level);

        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<RayIntersection<ID, Content>> rayIntersectAll(Ray3D ray) {
        validateSpatialConstraints(ray.origin());
        return culler.rayIntersectAll(ray);
    }

    // ===== Ray Intersection Abstract Methods =====

    @Override
    public Optional<RayIntersection<ID, Content>> rayIntersectFirst(Ray3D ray) {
        validateSpatialConstraints(ray.origin());
        return culler.rayIntersectFirst(ray);
    }

    @Override
    public List<RayIntersection<ID, Content>> rayIntersectWithin(Ray3D ray, float maxDistance) {
        validateSpatialConstraints(ray.origin());
        return culler.rayIntersectWithin(ray, maxDistance);
    }

    /**
     * Manually trigger tree rebalancing.
     */
    public TreeBalancer.RebalancingResult rebalanceTree() {
        // treeBalancer is never null: structures with no tree to balance (e.g. SFCArrayIndex) supply a
        // NoOpTreeBalancer (Luciferase-7sv7), which returns a benign no-op result here.
        lock.writeLock().lock();
        try {
            return treeBalancer.rebalanceTree();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ===== Ray Intersection Implementation =====

    /** Parallel batch removal. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    public CompletableFuture<Integer> removeBatchParallel(List<ID> entityIds) {
        return entityLifecycle.removeBatchParallel(entityIds);
    }

    /** Remove an entity by ID. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    @Override
    public boolean removeEntity(ID entityId) {
        return entityLifecycle.removeEntity(entityId);
    }

    /**
     * Enable or disable automatic balancing.
     */
    public void setAutoBalancingEnabled(boolean enabled) {
        lock.writeLock().lock();
        try {
            this.autoBalancingEnabled = enabled;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Set the balancing strategy.
     */
    public void setBalancingStrategy(TreeBalancingStrategy<ID> strategy) {
        lock.writeLock().lock();
        try {
            this.balancingStrategy = Objects.requireNonNull(strategy);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void setCollisionShape(ID entityId, CollisionShape shape) {
        collisions.setCollisionShape(entityId, shape);
    }

    /**
     * Shutdown parallel operations (cleanup resources)
     */
    public void shutdownParallelOperations() {
        if (parallelOperations != null) {
            parallelOperations.shutdown();
        }
    }

    /**
     * Release all resources held by this index: parallel operation pools and distributed ghost subsystem.
     * Implements {@link AutoCloseable} so try-with-resources works (Luciferase-7wzml.60).
     */
    @Override
    public void close() {
        var ops = parallelOperations;
        if (ops != null) {
            ops.close();
        }
        ghost.shutdownDistributedGhosts();
    }

    // ===== Plane Intersection Abstract Methods =====

    /**
     * Get the number of non-empty nodes in this spatial index.
     *
     * @return the number of non-empty nodes
     */
    public int size() {
        // Read-lock for a consistent count (Luciferase-xiv5u): the class documents consistent reads, but this
        // streamed values() with no lock, so a concurrent mutation could be counted half-applied.
        lock.readLock().lock();
        try {
            return (int) spatialIndex.values().stream().filter(node -> !node.isEmpty()).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void traverse(TreeVisitor<Key, ID, Content> visitor, TraversalStrategy strategy) {
        lock.readLock().lock();
        try {
            // Count total nodes and entities
            var totalNodes = nodeCount();
            var totalEntities = entityCount();

            visitor.beginTraversal(totalNodes, totalEntities);

            var context = new TraversalContext<Key, ID>();

            // Get root nodes based on implementation
            var rootNodes = getRootNodes();

            for (var rootIndex : rootNodes) {
                if (context.isCancelled()) {
                    break;
                }
                traverseNode(rootIndex, visitor, strategy, context, null, 0);
            }

            visitor.endTraversal(context.getNodesVisited(), context.getEntitiesVisited());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void traverseFrom(TreeVisitor<Key, ID, Content> visitor, TraversalStrategy strategy, Key startNodeIndex) {
        lock.readLock().lock();
        try {
            if (!hasNode(startNodeIndex)) {
                visitor.beginTraversal(0, 0);
                visitor.endTraversal(0, 0);
                return;
            }

            visitor.beginTraversal(-1, -1); // Unknown totals

            var context = new TraversalContext<Key, ID>();
            traverseNode(startNodeIndex, visitor, strategy, context, null, 0);

            visitor.endTraversal(context.getNodesVisited(), context.getEntitiesVisited());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void traverseRegion(TreeVisitor<Key, ID, Content> visitor, Spatial region, TraversalStrategy strategy) {
        lock.readLock().lock();
        try {
            validateSpatialConstraints(region);

            var bounds = getVolumeBounds(region);
            if (bounds == null) {
                visitor.beginTraversal(0, 0);
                visitor.endTraversal(0, 0);
                return;
            }

            // Find nodes in region
            var nodesInRegion = spatialRangeQuery(bounds, true).map(Map.Entry::getKey).collect(Collectors.toList());

            visitor.beginTraversal(nodesInRegion.size(), -1);

            var context = new TraversalContext<Key, ID>();

            for (var nodeIndex : nodesInRegion) {
                if (context.isCancelled()) {
                    break;
                }

                if (!context.isVisited(nodeIndex)) {
                    traverseNode(nodeIndex, visitor, strategy, context, null, 0);
                }
            }

            visitor.endTraversal(context.getNodesVisited(), context.getEntitiesVisited());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Plane Intersection Implementation =====

    /**
     * Clear pre-allocated but empty nodes to free memory. Useful after bulk loading if many pre-allocated nodes weren't
     * used.
     */
    public void trimEmptyNodes() {
        lock.writeLock().lock();
        try {
            var emptyNodes = new ArrayList<Key>();

            // Find empty nodes
            for (var entry : spatialIndex.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    emptyNodes.add(entry.getKey());
                }
            }

            // Remove empty nodes
            for (var key : emptyNodes) {
                spatialIndex.remove(key);
                // key is already removed from spatialIndex above
            }

            if (!emptyNodes.isEmpty()) {
                log.debug("Trimmed {} empty pre-allocated nodes", emptyNodes.size());
            }

        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Parallel batch updates. RDR-008 P6: delegates to {@code EntityLifecycleManager}. */
    public CompletableFuture<List<ID>> updateBatchParallel(List<ID> entityIds, List<Point3f> newPositions, byte level) {
        return entityLifecycle.updateBatchParallel(entityIds, newPositions, level);
    }

    /**
     * Update an entity's position. Preserves the P1 DSOC seam through {@code EntityLifecycleManager} (which reads
     * the {@code dsoc} controller via {@code EntityLifecycleHostImpl} at each call). RDR-008 P6: delegates to
     * {@code EntityLifecycleManager}.
     */
    @Override
    public void updateEntity(ID entityId, Point3f newPosition, byte level) {
        entityLifecycle.updateEntity(entityId, newPosition, level);
    }

    // Removed ensureAncestorNodes - not needed in pointerless SFC implementation

    /**
     * Add neighboring nodes to the k-NN search queue
     *
     * @param nodeIndex    current node index
     * @param toVisit      queue of nodes to visit
     * @param visitedNodes set of already visited nodes
     */
    protected abstract void addNeighboringNodes(Key nodeIndex, Queue<Key> toVisit, Set<Key> visitedNodes);

    /**
     * Calculate the spatial index for a position at a given level
     */
    protected abstract Key calculateSpatialIndex(Point3f position, byte level);

    /**
     * Check and perform automatic balancing if needed.
     */
    protected void checkAutoBalance() {
        if (!autoBalancingEnabled) {
            return;
        }

        var currentTime = System.currentTimeMillis();
        if (currentTime - lastBalancingTime < balancingStrategy.getMinRebalancingInterval()) {
            return;
        }

        var stats = getBalancingStats();
        if (balancingStrategy.shouldRebalanceTree(stats)) {
            lastBalancingTime = currentTime;
            treeBalancer.rebalanceTree();
        }
    }

    // ===== Frustum Culling Implementation =====

    /**
     * Clean up empty nodes from the spatial index
     */
    protected void cleanupEmptyNode(Key spatialIndex, SpatialNodeImpl<ID> node) {
        if (node.isEmpty() && !hasChildren(spatialIndex)) {
            getSpatialIndex().remove(spatialIndex);
            onNodeRemoved(spatialIndex);
            // Return node to pool for reuse
            nodePool.release(node);
        }
    }

    /**
     * Create the default subdivision strategy for this spatial index. Subclasses should override to provide their
     * specific strategy.
     */
    protected abstract SubdivisionStrategy<Key, ID, Content> createDefaultSubdivisionStrategy();

    /**
     * Create a new node instance
     */
    protected SpatialNodeImpl<ID> createNode() {
        if (dsoc != null && dsoc.isEnabled()) {
            return new OcclusionAwareSpatialNode<>(maxEntitiesPerNode);
        }
        return new SpatialNodeImpl<>(maxEntitiesPerNode);
    }

    /**
     * Façade implementation of the {@link FrustumGeometry} seam (RDR-008 P3, sub-interface split): supplies
     * {@code DsocController} the frustum-cluster façade operations it needs (traversal hooks, node bounds, cached
     * entity position). Private inner class preserves the underlying methods' original visibility. P4 re-homed the
     * standard non-DSOC cull fallback into the {@code Culler} feature object, exposed to DSOC through a separate
     * {@code FrustumCullProvider} seam.
     *
     * <p>NOTE: {@link CullGeometryImpl} mirrors three method signatures defined here — {@code getFrustumTraversalOrder},
     * {@code doesFrustumIntersectNode}, {@code getCachedEntityPosition}; {@link KnnGeometryImpl},
     * {@link CollisionGeometryImpl}, and {@link CullGeometryImpl} additionally share {@code getCachedEntityPosition}.
     * Each cluster's sub-interface is intentionally independent (cluster-scoped) so they cannot share a base type
     * without crossing package boundaries. If any of these signatures changes, every mirroring inner class needs
     * the same change — neither compiler nor IDE will warn of the asymmetry.
     */
    private final class FrustumGeometryImpl implements FrustumGeometry<Key, ID, Content> {
        @Override
        public Stream<Key> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
            return AbstractSpatialIndex.this.getFrustumTraversalOrder(frustum, cameraPosition);
        }

        @Override
        public boolean doesFrustumIntersectNode(Key nodeIndex, Frustum3D frustum) {
            return AbstractSpatialIndex.this.doesFrustumIntersectNode(nodeIndex, frustum);
        }

        @Override
        public EntityBounds computeNodeBounds(Key nodeIndex) {
            return AbstractSpatialIndex.this.computeNodeBounds(nodeIndex);
        }

        @Override
        public Point3f getCachedEntityPosition(ID entityId) {
            return AbstractSpatialIndex.this.getCachedEntityPosition(entityId);
        }
    }

    /**
     * Façade implementation of the {@link CullGeometry} seam (RDR-008 P4): supplies {@code Culler} the cluster's
     * seven subclass-overridden traversal/intersect/distance hooks, the two cached entity accessors, and the
     * spanning-policy flag. Private inner class preserves the underlying methods' original visibility.
     *
     * <p>NOTE: this class duplicates method signatures with sibling inner classes — three with
     * {@link FrustumGeometryImpl} ({@code getFrustumTraversalOrder}, {@code doesFrustumIntersectNode},
     * {@code getCachedEntityPosition}) and one with {@link CollisionGeometryImpl}
     * ({@code getCachedEntityBounds}). Each cluster's sub-interface is intentionally independent (P3 refinement)
     * and cannot share a base type without crossing package boundaries. Update every mirroring inner class if any
     * of these signatures changes.
     */
    private final class CullGeometryImpl implements CullGeometry<Key, ID, Content> {
        @Override
        public Stream<Key> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
            return AbstractSpatialIndex.this.getFrustumTraversalOrder(frustum, cameraPosition);
        }

        @Override
        public boolean doesFrustumIntersectNode(Key nodeIndex, Frustum3D frustum) {
            return AbstractSpatialIndex.this.doesFrustumIntersectNode(nodeIndex, frustum);
        }

        @Override
        public Stream<Key> getPlaneTraversalOrder(Plane3D plane) {
            return AbstractSpatialIndex.this.getPlaneTraversalOrder(plane);
        }

        @Override
        public boolean doesPlaneIntersectNode(Key nodeIndex, Plane3D plane) {
            return AbstractSpatialIndex.this.doesPlaneIntersectNode(nodeIndex, plane);
        }

        @Override
        public Stream<Key> getRayTraversalOrder(Ray3D ray) {
            return AbstractSpatialIndex.this.getRayTraversalOrder(ray);
        }

        @Override
        public boolean doesRayIntersectNode(Key nodeIndex, Ray3D ray) {
            return AbstractSpatialIndex.this.doesRayIntersectNode(nodeIndex, ray);
        }

        @Override
        public float getRayNodeIntersectionDistance(Key nodeIndex, Ray3D ray) {
            return AbstractSpatialIndex.this.getRayNodeIntersectionDistance(nodeIndex, ray);
        }

        @Override
        public Point3f getCachedEntityPosition(ID entityId) {
            return AbstractSpatialIndex.this.getCachedEntityPosition(entityId);
        }

        @Override
        public EntityBounds getCachedEntityBounds(ID entityId) {
            return AbstractSpatialIndex.this.getCachedEntityBounds(entityId);
        }

        @Override
        public boolean isSpanningEnabled() {
            return AbstractSpatialIndex.this.spanningPolicy.isSpanningEnabled();
        }
    }

    /**
     * Façade implementation of the {@link com.hellblazer.luciferase.lucien.collision.CollisionGeometry} seam
     * (RDR-008 P5): supplies {@code CollisionEngine} the collision-cluster façade operations it needs (two
     * subclass-overridden traversal hooks + the façade-resident range query + the two cached entity accessors).
     * Private inner class preserves the underlying methods' original visibility.
     *
     * <p>NOTE: this class duplicates the cached-entity accessor signatures with {@link FrustumGeometryImpl},
     * {@link CullGeometryImpl}, and {@link KnnGeometryImpl}, and the {@code findNodesIntersectingBounds(VolumeBounds)}
     * and {@code addNeighboringNodes} signatures with {@link KnnGeometryImpl}. Each cluster's sub-interface is
     * intentionally independent (P3 refinement) and cannot share a base type without crossing package boundaries.
     * Update sibling inner classes if any of these duplicated signatures changes — neither compiler nor IDE will
     * warn of the asymmetry.
     */
    private final class CollisionGeometryImpl
    implements com.hellblazer.luciferase.lucien.collision.CollisionGeometry<Key, ID, Content> {

        @Override
        public java.util.Set<Key> findNodesIntersectingBounds(VolumeBounds bounds) {
            return AbstractSpatialIndex.this.findNodesIntersectingBounds(bounds);
        }

        @Override
        public void addNeighboringNodes(Key nodeIndex, java.util.Queue<Key> toVisit, java.util.Set<Key> visitedNodes) {
            AbstractSpatialIndex.this.addNeighboringNodes(nodeIndex, toVisit, visitedNodes);
        }

        @Override
        public java.util.stream.Stream<java.util.Map.Entry<Key, SpatialNodeImpl<ID>>> spatialRangeQuery(
        VolumeBounds bounds, boolean includeIntersecting) {
            return AbstractSpatialIndex.this.spatialRangeQuery(bounds, includeIntersecting);
        }

        @Override
        public Point3f getCachedEntityPosition(ID entityId) {
            return AbstractSpatialIndex.this.getCachedEntityPosition(entityId);
        }

        @Override
        public EntityBounds getCachedEntityBounds(ID entityId) {
            return AbstractSpatialIndex.this.getCachedEntityBounds(entityId);
        }
    }

    /**
     * Façade implementation of the {@link com.hellblazer.luciferase.lucien.entity.EntityLifecycleGeometry} seam
     * (RDR-008 P6): supplies {@code EntityLifecycleManager} the entity-lifecycle subclass-overridden hooks plus
     * the cached entity accessors. Private inner class preserves the underlying methods' original visibility.
     *
     * <p>NOTE: this class duplicates signatures with sibling inner classes —
     * {@code calculateSpatialIndex}, {@code getCellSizeAtLevel}, and {@code validateSpatialConstraints} are
     * shared with {@link KnnGeometryImpl}. The entity-lifecycle-specific insertion hooks
     * ({@code hasChildren}, {@code handleNodeSubdivision}, {@code onNodeRemoved}, {@code insertWithSpanning},
     * {@code cleanupEmptyNode}) appear only here. Node creation is NOT on the {@link
     * com.hellblazer.luciferase.lucien.entity.EntityLifecycleGeometry} interface — it reaches the facade through
     * the {@link SpatialNodePool} factory captured at construction time ({@code this::createNode}, ctor),
     * preserving the DSOC-aware {@link #createNode()} dispatch through the pool. Each cluster's sub-interface is
     * intentionally independent (P3 refinement) and cannot share a base type without crossing package
     * boundaries. Update sibling inner classes if any of these duplicated signatures changes — neither compiler
     * nor IDE will warn of the asymmetry.
     */
    private final class EntityLifecycleGeometryImpl
    implements com.hellblazer.luciferase.lucien.entity.EntityLifecycleGeometry<Key, ID, Content> {

        @Override
        public Key calculateSpatialIndex(Point3f position, byte level) {
            return AbstractSpatialIndex.this.calculateSpatialIndex(position, level);
        }

        @Override
        public float getCellSizeAtLevel(byte level) {
            return AbstractSpatialIndex.this.getCellSizeAtLevel(level);
        }

        @Override
        public void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
            AbstractSpatialIndex.this.insertWithSpanning(entityId, bounds, level);
        }

        @Override
        public void validateSpatialConstraints(Point3f position) {
            AbstractSpatialIndex.this.validateSpatialConstraints(position);
        }

        @Override
        public boolean hasChildren(Key spatialIndex) {
            return AbstractSpatialIndex.this.hasChildren(spatialIndex);
        }

        @Override
        public void handleNodeSubdivision(Key spatialIndex, byte level, SpatialNodeImpl<ID> node) {
            AbstractSpatialIndex.this.handleNodeSubdivision(spatialIndex, level, node);
        }

        @Override
        public void onNodeRemoved(Key spatialIndex) {
            AbstractSpatialIndex.this.onNodeRemoved(spatialIndex);
        }

        @Override
        public void cleanupEmptyNode(Key spatialIndex, SpatialNodeImpl<ID> node) {
            AbstractSpatialIndex.this.cleanupEmptyNode(spatialIndex, node);
        }
    }

    /**
     * Façade implementation of the {@link com.hellblazer.luciferase.lucien.entity.EntityLifecycleHost} seam
     * (RDR-008 P6): exposes the facade-internal infrastructure {@code EntityLifecycleManager} needs (bulk
     * config/processor/builder, parallel ops, node pool, deferred-subdivision manager, spanning policy, the DSOC
     * controller for the P1 updateEntity seam, the ghost-update hook, the auto-balance hook). Private inner class
     * preserves the underlying fields' original visibility. The accessors all return the latest reference at the
     * time of the call so mutable facade state (bulkConfig, parallelOperations, treeBuilder, the dsoc volatile)
     * is seen in real-time by the feature object.
     */
    private final class EntityLifecycleHostImpl
    implements com.hellblazer.luciferase.lucien.entity.EntityLifecycleHost<Key, ID, Content> {

        @Override
        public int maxEntitiesPerNode() {
            return AbstractSpatialIndex.this.maxEntitiesPerNode;
        }

        @Override
        public byte maxDepth() {
            return AbstractSpatialIndex.this.maxDepth;
        }

        @Override
        public BulkOperationConfig bulkConfig() {
            return AbstractSpatialIndex.this.bulkConfig;
        }

        @Override
        public BulkOperationProcessor<Key, ID, Content> bulkProcessor() {
            return AbstractSpatialIndex.this.bulkProcessor;
        }

        @Override
        public DeferredSubdivisionManager<Key, ID> subdivisionManager() {
            return AbstractSpatialIndex.this.subdivisionManager;
        }

        @Override
        public SpatialNodePool<ID> nodePool() {
            return AbstractSpatialIndex.this.nodePool;
        }

        @Override
        public ParallelBulkOperations<Key, ID, Content> parallelOperations() {
            return AbstractSpatialIndex.this.parallelOperations;
        }

        @Override
        public StackBasedTreeBuilder<Key, ID, Content> treeBuilder() {
            return AbstractSpatialIndex.this.treeBuilder;
        }

        @Override
        public EntitySpanningPolicy spanningPolicy() {
            return AbstractSpatialIndex.this.spanningPolicy;
        }

        @Override
        public boolean bulkLoadingMode() {
            return AbstractSpatialIndex.this.bulkLoadingMode;
        }

        @Override
        public void setBulkLoadingMode(boolean value) {
            AbstractSpatialIndex.this.bulkLoadingMode = value;
        }

        @Override
        public void enableBulkLoading() {
            AbstractSpatialIndex.this.enableBulkLoading();
        }

        @Override
        public void finalizeBulkLoading() {
            AbstractSpatialIndex.this.finalizeBulkLoading();
        }

        @Override
        public void configureTreeBuilder(StackBasedTreeBuilder.BuildConfig config) {
            AbstractSpatialIndex.this.configureTreeBuilder(config);
        }

        @Override
        public void triggerGhostUpdateAfterBulkInsert() {
            AbstractSpatialIndex.this.triggerGhostUpdateAfterBulkInsert();
        }

        @Override
        public void checkAutoBalance() {
            AbstractSpatialIndex.this.checkAutoBalance();
        }

        @Override
        public DsocController<Key, ID, Content> dsocController() {
            return AbstractSpatialIndex.this.dsoc;
        }

        @Override
        public void deferSubdivision(Key spatialIndex, SpatialNodeImpl<ID> node, int entityCount, byte level) {
            AbstractSpatialIndex.this.subdivisionManager.deferSubdivision(spatialIndex, node, entityCount, level);
        }

        @Override
        public com.hellblazer.luciferase.lucien.StackBuilderHost<Key, ID, Content> stackBuilderTarget() {
            return stackBuilderHost;
        }
    }

    /**
     * Façade implementation of the {@link com.hellblazer.luciferase.lucien.StackBuilderHost} seam (RDR-008 P6
     * follow-up, bead {@code Luciferase-ts8}): supplies {@link StackBasedTreeBuilder} the six facade-internal
     * methods it consumes ({@code calculateSpatialIndex}, {@code createNode}, {@code getEntityManager},
     * {@code getSpatialIndex}, {@code getMaxDepth}, {@code getMaxEntitiesPerNode}). Private inner class preserves
     * each underlying method's original visibility.
     */
    private final class StackBuilderHostImpl implements com.hellblazer.luciferase.lucien.StackBuilderHost<Key, ID, Content> {

        @Override
        public Key calculateSpatialIndex(Point3f position, byte level) {
            return AbstractSpatialIndex.this.calculateSpatialIndex(position, level);
        }

        @Override
        public SpatialNodeImpl<ID> createNode() {
            return AbstractSpatialIndex.this.createNode();
        }

        @Override
        public com.hellblazer.luciferase.lucien.entity.EntityManager<Key, ID, Content> getEntityManager() {
            return AbstractSpatialIndex.this.getEntityManager();
        }

        @Override
        public java.util.Map<Key, SpatialNodeImpl<ID>> getSpatialIndex() {
            return AbstractSpatialIndex.this.getSpatialIndex();
        }

        @Override
        public byte getMaxDepth() {
            return AbstractSpatialIndex.this.getMaxDepth();
        }

        @Override
        public int getMaxEntitiesPerNode() {
            return AbstractSpatialIndex.this.getMaxEntitiesPerNode();
        }
    }

    /**
     * Façade implementation of the {@link KnnGeometry} seam (RDR-008 P3): supplies {@code KnnSearcher} the k-NN-
     * cluster façade operations it needs (subclass geometry hooks + input validation). Private inner class preserves
     * the underlying methods' original visibility.
     */
    private final class KnnGeometryImpl implements KnnGeometry<Key, ID> {
        @Override
        public Key calculateSpatialIndex(Point3f position, byte level) {
            return AbstractSpatialIndex.this.calculateSpatialIndex(position, level);
        }

        @Override
        public float estimateNodeDistance(Key nodeIndex, Point3f queryPoint) {
            return AbstractSpatialIndex.this.estimateNodeDistance(nodeIndex, queryPoint);
        }

        @Override
        public boolean shouldContinueKNNSearch(Key nodeIndex, Point3f queryPoint,
                                               java.util.PriorityQueue<com.hellblazer.luciferase.lucien.entity.EntityDistance<ID>> candidates) {
            return AbstractSpatialIndex.this.shouldContinueKNNSearch(nodeIndex, queryPoint, candidates);
        }

        @Override
        public float getCellSizeAtLevel(byte level) {
            return AbstractSpatialIndex.this.getCellSizeAtLevel(level);
        }

        @Override
        public java.util.Set<Key> findNodesIntersectingBounds(VolumeBounds bounds) {
            return AbstractSpatialIndex.this.findNodesIntersectingBounds(bounds);
        }

        @Override
        public boolean knnRequiresFullDomainSweep() {
            return AbstractSpatialIndex.this.knnRequiresFullDomainSweep();
        }

        @Override
        public void addNeighboringNodes(Key nodeIndex, java.util.Queue<Key> toVisit, java.util.Set<Key> visitedNodes) {
            AbstractSpatialIndex.this.addNeighboringNodes(nodeIndex, toVisit, visitedNodes);
        }

        @Override
        public void validateSpatialConstraints(Point3f position) {
            AbstractSpatialIndex.this.validateSpatialConstraints(position);
        }

        @Override
        public Point3f getCachedEntityPosition(ID entityId) {
            return AbstractSpatialIndex.this.getCachedEntityPosition(entityId);
        }

        @Override
        public byte maxDepth() {
            return AbstractSpatialIndex.this.maxDepth;
        }
    }

    /**
     * Create a spatial volume from bounds for filtering
     *
     * @param bounds the volume bounds
     * @return spatial volume
     */
    protected Spatial createSpatialFromBounds(VolumeBounds bounds) {
        return new Spatial.aabb(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                                bounds.maxZ());
    }

    /**
     * Create a tree balancer instance. Default implementation provides basic balancing. Subclasses can override to
     * provide specialized balancing.
     */
    protected TreeBalancer<Key, ID> createTreeBalancer() {
        return new DefaultTreeBalancer();
    }

    /**
     * Check if a frustum intersects with the given node
     *
     * @param nodeIndex the node's spatial index
     * @param frustum   the frustum to test
     * @return true if the frustum intersects the node
     */
    protected abstract boolean doesFrustumIntersectNode(Key nodeIndex, Frustum3D frustum);

    /**
     * Check if a node's bounds intersect with a volume
     */
    protected abstract boolean doesNodeIntersectVolume(Key nodeIndex, Spatial volume);

    /**
     * Test if a plane intersects with a node
     *
     * @param nodeIndex the node's spatial index
     * @param plane     the plane to test
     * @return true if the plane intersects the node
     */
    protected abstract boolean doesPlaneIntersectNode(Key nodeIndex, Plane3D plane);

    // ===== Collision Detection Implementation =====

    /**
     * Test if a ray intersects with a node
     *
     * @param nodeIndex the node's spatial index
     * @param ray       the ray to test
     * @return true if the ray intersects the node
     */
    protected abstract boolean doesRayIntersectNode(Key nodeIndex, Ray3D ray);

    /**
     * Estimate the distance from a query point to the center of a spatial node. This is used for k-NN search
     * optimization to find the nearest starting nodes.
     *
     * @param nodeIndex  the spatial node index
     * @param queryPoint the query point
     * @return estimated distance from query point to node center
     */
    protected abstract float estimateNodeDistance(Key nodeIndex, Point3f queryPoint);

    /**
     * Filter nodes in SFC order using the given predicate and collect them into a list. This utility method combines
     * spatial ordering with filtering for better performance.
     *
     * @param nodePredicate predicate to test each node
     * @return list of node indices that match the predicate, in SFC order
     */
    protected List<Key> filterNodesInSFCOrder(java.util.function.BiPredicate<Key, SpatialNodeImpl<ID>> nodePredicate) {
        var filteredNodes = new ArrayList<Key>();
        for (var nodeIndex : spatialIndex.keySet()) {
            SpatialNodeImpl<ID> node = spatialIndex.get(nodeIndex);
            if (node != null && !node.isEmpty() && nodePredicate.test(nodeIndex, node)) {
                filteredNodes.add(nodeIndex);
            }
        }
        return filteredNodes;
    }

    /**
     * Find minimum containing level for bounds
     */
    protected byte findMinimumContainingLevel(VolumeBounds bounds) {
        var maxExtent = bounds.maxExtent();

        // Find the level where cell size >= maxExtent
        for (var level = (byte) 0; level <= maxDepth; level++) {
            if (getCellSizeAtLevel(level) >= maxExtent) {
                return level;
            }
        }
        return maxDepth;
    }

    /**
     * Find all nodes that intersect with the given entity bounds. This is used for collision detection with bounded
     * entities.
     *
     * @param bounds the entity bounds to check
     * @return set of node indices that intersect with the bounds
     */
    protected Set<Key> findNodesIntersectingBounds(EntityBounds bounds) {
        var intersectingNodes = new HashSet<Key>();

        // Convert EntityBounds to VolumeBounds for spatial query
        var volumeBounds = new VolumeBounds(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX(),
                                            bounds.getMaxY(), bounds.getMaxZ());

        // Use spatial range query to find intersecting nodes
        spatialRangeQuery(volumeBounds, true).forEach(entry -> intersectingNodes.add(entry.getKey()));

        return intersectingNodes;
    }

    /**
     * Find all nodes that intersect with the given bounds. This method should be implemented efficiently by subclasses
     * using their specific spatial data structures (e.g., using sorted indices for range queries).
     *
     * @param bounds the volume bounds to check
     * @return set of node keys that intersect with the bounds
     */
    protected abstract Set<Key> findNodesIntersectingBounds(VolumeBounds bounds);

    /**
     * Get the cell size at a given level, in the same (world) coordinate space as entity positions
     * and query distances (to be implemented by subclasses).
     *
     * <p>Returns {@code float} rather than {@code int} so indices using normalized fractional
     * coordinates (e.g. Prism's [0,1) world) report a meaningful sub-unit cell size instead of
     * truncating it to 0. Integer-coordinate indices (Octree, Tetree, SFCArrayIndex) return their
     * integer cell size widened to {@code float} — exact for all levels (cell sizes are powers of
     * two below 2^24). Several distance/extent comparisons (k-NN search radius, level selection,
     * spanning) depend on this being world-space, not a truncated integer.</p>
     */
    protected abstract float getCellSizeAtLevel(byte level);

    /**
     * Whether {@code kNearestNeighbors} must perform a final full-domain sweep when the
     * expanding-radius fallback leaves it short of {@code k}.
     *
     * <p>Default {@code false}: the integer-coordinate indices (Octree, Tetree, SFCArrayIndex) reach
     * completeness through their dedicated SFC range-pruning path ({@code performKNNSFCRangePruning}
     * and its Morton/Tetree variants), which derives the Morton/TM interval of an AABB covering the
     * whole {@code maxDistance} sphere and so already sees every in-range neighbor — the
     * expanding-radius search is only a fallback there, not the guarantee. A domain-spanning sweep
     * for them would be redundant, and worse: a large/unbounded search box drives their
     * {@code findNodesIntersectingBounds} (LITMAX/BIGMIN interval walk) non-terminating. Indices whose
     * coordinate space defeats the incremental expansion AND lack a fractional SFC-range path (e.g.
     * Prism's normalized [0,1) coordinates route to the generic BFS + expanding-radius fallback, and
     * a sub-unit initial radius cannot span the world within the cap) override this to {@code true};
     * their {@code findNodesIntersectingBounds} is an O(n) scan, so the sweep is cheap and bounded.
     * (Luciferase-h65)</p>
     */
    protected boolean knnRequiresFullDomainSweep() {
        return false;
    }

    /**
     * Get child nodes of a given node. Default implementation returns empty list. Subclasses should override to provide
     * actual parent-child relationships.
     */
    protected List<Key> getChildNodes(Key nodeIndex) {
        return Collections.emptyList();
    }

    /**
     * Get nodes that should be traversed for frustum culling, ordered by distance from camera
     *
     * @param frustum        the frustum to test
     * @param cameraPosition the camera position for distance sorting
     * @return stream of node indices ordered by distance from camera
     */
    protected abstract Stream<Key> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition);

    /**
     * Get the spatial bounds of a node
     */
    protected abstract Spatial getNodeBounds(Key index);

    /**
     * Get nodes that should be traversed for plane intersection, ordered by distance from plane
     *
     * @param plane the plane to test
     * @return stream of node indices ordered by distance from plane
     */
    protected abstract Stream<Key> getPlaneTraversalOrder(Plane3D plane);

    /**
     * Get the distance from ray origin to node intersection
     *
     * @param nodeIndex the node's spatial index
     * @param ray       the ray to test
     * @return distance to node entry point, or Float.MAX_VALUE if no intersection
     */
    protected abstract float getRayNodeIntersectionDistance(Key nodeIndex, Ray3D ray);

    /**
     * Get nodes that should be traversed for ray intersection, ordered by distance
     *
     * @param ray the ray to test
     * @return stream of node indices ordered by ray intersection distance
     */
    protected abstract Stream<Key> getRayTraversalOrder(Ray3D ray);

    /**
     * Get root nodes for traversal. Default implementation returns all nodes at the minimum level. Subclasses can
     * override for specific root node logic.
     */
    protected Set<Key> getRootNodes() {
        var roots = new HashSet<Key>();
        var minLevel = Byte.MAX_VALUE;

        // Find minimum level
        for (Key index : spatialIndex.keySet()) {
            var level = index.getLevel();
            if (level < minLevel) {
                minLevel = level;
                roots.clear();
                roots.add(index);
            } else if (level == minLevel) {
                roots.add(index);
            }
        }

        return roots;
    }

    /**
     * Get the sorted spatial indices for SFC-ordered operations. This provides spatially-ordered access to improve
     * cache locality.
     *
     * @return NavigableSet containing spatial indices in SFC order
     */
    protected NavigableSet<Key> getSortedSpatialIndices() {
        return spatialIndex.navigableKeySet();
    }

    /**
     * Occupied spatial keys in the SFC subrange {@code [fromKey, toKey]} (Luciferase-3uwx). Backed by the
     * navigable {@code spatialIndex} {@link ConcurrentNavigableMap#subMap} view, this is {@code O(log n + k)}
     * for {@code k} keys in range — the pruning primitive for owner-range ghost descent. The returned set is a
     * snapshot copy, so iteration is safe against concurrent index mutation.
     */
    @Override
    public NavigableSet<Key> spatialKeysInRange(Key fromKey, boolean fromInclusive, Key toKey,
                                                boolean toInclusive) {
        return new TreeSet<>(spatialIndex.subMap(fromKey, fromInclusive, toKey, toInclusive).keySet());
    }

    /**
     * Get the spatial index storage map
     */
    protected Map<Key, SpatialNodeImpl<ID>> getSpatialIndex() {
        return spatialIndex;
    }

    /**
     * Get the range of spatial indices that could intersect with the given bounds This method should be overridden by
     * subclasses for specific optimizations
     *
     * @param bounds the volume bounds
     * @return navigable set of spatial indices
     */
    protected NavigableSet<Key> getSpatialIndexRange(VolumeBounds bounds) {
        // Default implementation: return all indices
        // Subclasses should override for better performance
        return new TreeSet<>(spatialIndex.keySet());
    }

    /**
     * 1     * 1 Get volume bounds helper
     */
    protected VolumeBounds getVolumeBounds(Spatial volume) {
        return VolumeBounds.from(volume);
    }

    /**
     * Refine the node at {@code key} by one level on demand (Luciferase-m27q, 2:1 balance B10c).
     *
     * <p>Locates the node and invokes the subclass {@link #handleNodeSubdivision} hook with geometric force under
     * the write lock, independent of the entity-count threshold and the deferred bulk-loading queue. Returns
     * whether the subdivision produced finer nodes (a no-op only when the key is absent or already at
     * {@link #maxDepth}). Unlike the load-balancing path, this FORCES a one-level refine even when every entity
     * maps to a single child octant — the 2:1 constraint is geometric, so the finer child cell must exist
     * (Luciferase-7gnh2). Ghost re-synchronization is the caller's responsibility (the 2:1-balance round triggers
     * it once per round after draining all local refinements).
     */
    @Override
    public boolean subdivide(Key key) {
        lock.writeLock().lock();
        try {
            var node = spatialIndex.get(key);
            if (node == null) {
                log.debug("subdivide: no node at key {}", key);
                return false;
            }
            var level = key.getLevel();
            if (level >= maxDepth) {
                log.debug("subdivide: node at key {} already at max depth {}", key, maxDepth);
                return false;
            }
            var before = spatialIndex.size();
            // 2:1 balance (Luciferase-7gnh2) needs a GEOMETRIC one-level refine: it must create the finer child
            // cell even when every entity maps to a single child octant (where the load-balancing path declines,
            // since splitting wouldn't redistribute). Otherwise such a cell can never satisfy 2:1 against a finer
            // neighbour and the balance loop spins to maxRounds. Force geometric here; the auto-balance/insert
            // paths still call the unforced 3-arg hook.
            handleNodeSubdivision(key, level, node, true);
            var created = spatialIndex.size() - before;
            log.debug("subdivide: refined node at key {} (level {}); {} child node(s) created", key, level, created);
            return created > 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Hook for subclasses to handle node subdivision
     */
    protected void handleNodeSubdivision(Key spatialIndex, byte level, SpatialNodeImpl<ID> node) {
        // Default: no subdivision. Subclasses can override
    }

    /**
     * Subdivision hook with an explicit geometric-force flag (Luciferase-7gnh2). When {@code forceGeometric} is
     * true (the on-demand 2:1-balance {@link #subdivide(SpatialKey)} path), the node must be refined one level
     * even if every entity maps to a single child octant — so the finer child cell exists to satisfy the 2:1
     * constraint against a finer neighbour. When false (auto-balance / insert), subclasses keep their
     * load-balancing semantics (decline a split that wouldn't redistribute). The default delegates to the
     * unforced 3-arg hook; tree subclasses override to honour the flag.
     */
    protected void handleNodeSubdivision(Key spatialIndex, byte level, SpatialNodeImpl<ID> node,
                                         boolean forceGeometric) {
        handleNodeSubdivision(spatialIndex, level, node);
    }

    /**
     * Check if a node has children (i.e. is an internal, subdivided node). Public per the {@link SpatialIndex}
     * contract (Luciferase-hthxs); subclasses that track subdivision override it.
     */
    @Override
    public boolean hasChildren(Key spatialIndex) {
        return false; // Default: no children tracking
    }

    /**
     * Hook for subclasses to handle entity spanning. RDR-008 P6: the surrounding spanning-dispatch logic
     * (insertWithAdaptiveSpanning/AdvancedSpanning/BalancedSpanning/MemoryEfficientSpanning/PerformanceOptimizedSpanning)
     * moved into {@code EntityLifecycleManager}; this protected method is kept as the subclass extension point —
     * all four concrete spatial indices (Octree, Tetree, Prism, SFCArrayIndex) override it. The default
     * implementation falls back to a single-node insertion via the entity manager's current position; the feature
     * object reaches it through {@code EntityLifecycleGeometryImpl.insertWithSpanning} → virtual dispatch.
     */
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Default: single node insertion. Subclasses can override for spanning
        var position = entityManager.getEntityPosition(entityId);
        if (position != null) {
            entityLifecycle.insertAtPosition(entityId, position, level);
        }
    }

    /**
     * Check if a node's bounds are contained within a volume
     */
    protected abstract boolean isNodeContainedInVolume(Key nodeIndex, Spatial volume);

    // ===== Tree Traversal Implementation =====

    /**
     * Hook for subclasses when a node is removed
     */
    protected void onNodeRemoved(Key spatialIndex) {
        // spatialIndex key is already removed from the ConcurrentSkipListMap above
    }

    /**
     * Process all entities in SFC order with the given consumer. This utility method provides a convenient way to
     * iterate over all entities in spatial order for improved cache performance.
     *
     * @param entityProcessor function to process each entity ID with its containing node index
     */
    protected void processEntitiesInSFCOrder(java.util.function.BiConsumer<Key, ID> entityProcessor) {
        // Snapshot (key,node) under the read lock (Luciferase-xiv5u) to avoid the keySet()+per-key get() TOCTOU: a
        // concurrent remove would otherwise null the node and silently skip work. Process the snapshot outside the
        // lock so user consumers don't run under it. SFC order is preserved (ConcurrentSkipListMap keySet is sorted).
        for (var entry : snapshotNonEmptyNodesInSFCOrder()) {
            for (ID entityId : entry.getValue().getEntityIds()) {
                entityProcessor.accept(entry.getKey(), entityId);
            }
        }
    }

    /**
     * Process all nodes in SFC order with the given consumer. This utility method provides a convenient way to iterate
     * over all nodes in spatial order for improved cache performance.
     *
     * @param nodeProcessor function to process each non-empty node
     */
    protected void processNodesInSFCOrder(java.util.function.BiConsumer<Key, SpatialNodeImpl<ID>> nodeProcessor) {
        // Consistent snapshot under the read lock (Luciferase-xiv5u) — see processEntitiesInSFCOrder.
        for (var entry : snapshotNonEmptyNodesInSFCOrder()) {
            nodeProcessor.accept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * An SFC-ordered snapshot of the non-empty (key, node) <em>references</em>, taken under the read lock so the
     * key set is consistent against concurrent writers — fixing the keySet()+per-key get() TOCTOU (Luciferase-xiv5u).
     * Returned so callers can process without holding the lock during user-supplied consumers.
     *
     * <p>Scope of the guarantee: only the set of node references is frozen. Entity membership within each node is read
     * later via {@code getEntityIds()} (a CopyOnWriteArrayList view), so it reflects the node's state at iteration
     * time, not at snapshot time — a node emptied or removed by a concurrent writer after the snapshot simply yields
     * zero entities (silently skipped), never a null-node skip or a CME.</p>
     */
    private java.util.List<java.util.Map.Entry<Key, SpatialNodeImpl<ID>>> snapshotNonEmptyNodesInSFCOrder() {
        lock.readLock().lock();
        try {
            var out = new java.util.ArrayList<java.util.Map.Entry<Key, SpatialNodeImpl<ID>>>();
            for (var nodeIndex : spatialIndex.keySet()) {
                SpatialNodeImpl<ID> node = spatialIndex.get(nodeIndex);
                if (node != null && !node.isEmpty()) {
                    out.add(java.util.Map.entry(nodeIndex, node));
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if k-NN search should continue based on current candidates
     *
     * @param nodeIndex  current node index
     * @param queryPoint the query point
     * @param candidates current candidate entities
     * @return true if search should continue
     */
    protected abstract boolean shouldContinueKNNSearch(Key nodeIndex, Point3f queryPoint,
                                                       PriorityQueue<EntityDistance<ID>> candidates);

    // shouldSpanEntity moved into EntityLifecycleManager (RDR-008 P6). No subclass overrode it.

    /**
     * Perform spatial range query with optimization
     *
     * @param bounds              the volume bounds to query
     * @param includeIntersecting whether to include intersecting nodes
     * @return stream of node entries that match the query
     */
    protected Stream<Map.Entry<Key, SpatialNodeImpl<ID>>> spatialRangeQuery(VolumeBounds bounds,
                                                                            boolean includeIntersecting) {
        // Get range of spatial indices that could contain or intersect the bounds
        var candidateIndices = getSpatialIndexRange(bounds);

        return candidateIndices.stream()
            .map(index -> {
                SpatialNodeImpl<ID> node = getSpatialIndex().get(index);
                return node != null ? Map.entry(index, node) : null;
            })
            .filter(entry -> entry != null && !entry.getValue().isEmpty())
            .filter(entry -> {
                // Final precise filtering
                if (includeIntersecting) {
                    return doesNodeIntersectVolume(entry.getKey(), createSpatialFromBounds(bounds));
                } else {
                    return isNodeContainedInVolume(entry.getKey(), createSpatialFromBounds(bounds));
                }
            });
    }

    /**
     * Validate spatial constraints (e.g., positive coordinates for Tetree). Default implementation does no validation.
     */
    protected void validateSpatialConstraints(Point3f position) {
        // Default: no spatial constraints
    }

    /**
     * Validate spatial constraints for volumes. Default implementation does no validation.
     */
    protected void validateSpatialConstraints(Spatial volume) {
        // Default: no spatial constraints
    }

    ID generateId() {
        return entityManager.generateEntityId();
    }

    // Package-private accessors for StackBasedTreeBuilder
    EntityManager<Key, ID, Content> getEntityManager() {
        return entityManager;
    }

    public byte getMaxDepth() {
        return maxDepth;
    }

    // ===== Common Region Query Implementation =====

    public int getMaxEntitiesPerNode() {
        return maxEntitiesPerNode;
    }

    // ===== Bulk Operations Implementation =====
    // ===== Parallel Operations API =====
    // RDR-008 P6: determineBatchInsertionLevel + determineMortonSortStrategy moved into EntityLifecycleManager.

    // ===== Memory Pre-allocation Methods =====

    /**
     * Internal method to get entity bounds with caching
     */
    private EntityBounds getCachedEntityBounds(ID entityId) {
        var bounds = entityCache.getBounds(entityId);
        if (bounds == null) {
            bounds = entityManager.getEntityBounds(entityId);
            if (bounds != null) {
                var position = entityManager.getEntityPosition(entityId);
                entityCache.put(entityId, position, bounds);
            }
        }
        return bounds;
    }

    /**
     * Internal method to get entity position with caching
     */
    private Point3f getCachedEntityPosition(ID entityId) {
        var position = entityCache.getPosition(entityId);
        if (position == null) {
            position = entityManager.getEntityPosition(entityId);
            if (position != null) {
                var bounds = entityManager.getEntityBounds(entityId);
                entityCache.put(entityId, position, bounds);
            }
        }
        return position;
    }

    // RDR-008 P6: insertDirectEntities, insertGroupedEntities, logBatchPerformance, performStackBasedBulkInsert,
    // preprocessBatchEntities all moved into EntityLifecycleManager. K-NN search helpers continue below.

    /**
     * Process nodes in breadth-first order
     */
    private void processBreadthFirstQueue(TreeVisitor<Key, ID, Content> visitor, TraversalStrategy strategy,
                                          TraversalContext<Key, ID> context) {
        Key nodeIndex;
        while ((nodeIndex = context.popNode()) != null) {
            var level = context.getNodeLevel(nodeIndex);
            traverseNode(nodeIndex, visitor, strategy, context, null, level);
        }
    }

    // RDR-008 P6: shouldUseStackBasedBuilder moved into EntityLifecycleManager.

    /**
     * Traverse a single node and its children recursively
     */
    private void traverseNode(Key nodeIndex, TreeVisitor<Key, ID, Content> visitor, TraversalStrategy strategy,
                              TraversalContext<Key, ID> context, Key parentIndex, int level) {

        if (context.isCancelled() || context.isVisited(nodeIndex)) {
            return;
        }

        // Check max depth
        if (visitor.getMaxDepth() >= 0 && level > visitor.getMaxDepth()) {
            return;
        }

        // Get node. An empty node with NO children is a dead leaf -> skip. But an empty INTERNAL node (its
        // entities were redistributed to children on subdivision — including the forced single-child 2:1 case,
        // Luciferase-7gnh2) must still be descended into, or its children and their entities are silently dropped
        // from visitor-based traversal.
        SpatialNodeImpl<ID> node = spatialIndex.get(nodeIndex);
        if (node == null || (node.isEmpty() && !hasChildren(nodeIndex))) {
            return;
        }

        // Create SpatialNode wrapper
        var spatialNode = new SpatialIndex.SpatialNode<>(nodeIndex, new HashSet<>(node.getEntityIds()));

        // Mark as visited
        context.markVisited(nodeIndex);

        // Pre-order visit
        var shouldContinue = visitor.visitNode(spatialNode, level, parentIndex);

        // Visit entities if requested
        if (shouldContinue && visitor.shouldVisitEntities()) {
            for (ID entityId : node.getEntityIds()) {
                Content content = entityManager.getEntityContent(entityId);
                visitor.visitEntity(entityId, content, nodeIndex, level);
                context.incrementEntitiesVisited();
            }
        }

        // Visit children if requested
        if (shouldContinue) {
            var children = getChildNodes(nodeIndex);
            var childCount = 0;

            for (Key childIndex : children) {
                if (context.isCancelled()) {
                    break;
                }

                // Apply traversal strategy
                switch (strategy) {
                    case DEPTH_FIRST, PRE_ORDER -> {
                        traverseNode(childIndex, visitor, strategy, context, nodeIndex, level + 1);
                        childCount++;
                    }
                    case BREADTH_FIRST, LEVEL_ORDER -> {
                        context.pushNode(childIndex, level + 1);
                        childCount++;
                    }
                    case POST_ORDER -> {
                        // Queue for post-order processing
                        context.pushNode(childIndex, level + 1);
                        childCount++;
                    }
                    case IN_ORDER -> {
                        // For spatial trees, treat as pre-order
                        traverseNode(childIndex, visitor, strategy, context, nodeIndex, level + 1);
                        childCount++;
                    }
                }
            }

            // Process breadth-first queue
            if (strategy == TraversalStrategy.BREADTH_FIRST || strategy == TraversalStrategy.LEVEL_ORDER) {
                processBreadthFirstQueue(visitor, strategy, context);
            }

            // Post-order visit
            visitor.leaveNode(spatialNode, level, childCount);
        }
    }

    // RDR-008 P6: validateBatchInputs moved into EntityLifecycleManager.

    /**
     * Memory usage statistics
     */
    public record MemoryStats(int nodeCount, long entityCount, float avgEntitiesPerNode, long estimatedMemoryBytes) {
        public double estimatedMemoryMB() {
            return estimatedMemoryBytes / (1024.0 * 1024.0);
        }

        public double memoryPerEntity() {
            return entityCount > 0 ? (double) estimatedMemoryBytes / entityCount : 0;
        }
    }

    /**
     * Default tree balancer implementation.
     */
    protected class DefaultTreeBalancer implements TreeBalancer<Key, ID> {

        @Override
        public BalancingAction checkNodeBalance(Key nodeIndex) {
            SpatialNodeImpl<ID> node = spatialIndex.get(nodeIndex);
            if (node == null) {
                return BalancingAction.NONE;
            }

            byte level = nodeIndex.getLevel();
            int entityCount = node.getEntityCount();

            // Check split condition
            if (balancingStrategy.shouldSplit(entityCount, level, maxEntitiesPerNode)) {
                return BalancingAction.SPLIT;
            }

            // Check merge condition
            var siblingCounts = getSiblingEntityCounts(nodeIndex);
            if (balancingStrategy.shouldMerge(entityCount, level, siblingCounts)) {
                return BalancingAction.MERGE;
            }

            return BalancingAction.NONE;
        }

        @Override
        public TreeBalancingStrategy.TreeBalancingStats getBalancingStats() {
            return AbstractSpatialIndex.this.getBalancingStats();
        }

        @Override
        public boolean isAutoBalancingEnabled() {
            return AbstractSpatialIndex.this.isAutoBalancingEnabled();
        }

        @Override
        public boolean mergeNodes(Set<Key> nodeIndices, Key parentIndex) {
            // Default implementation does not support merging
            // Subclasses should override for actual merging logic
            return false;
        }

        @Override
        public int rebalanceSubtree(Key rootNodeIndex) {
            return rebalanceSubtreeImpl(rootNodeIndex, new HashSet<>());
        }

        @Override
        public RebalancingResult rebalanceTree() {
            var startTime = System.currentTimeMillis();
            var nodesCreated = 0;
            var nodesRemoved = 0;
            var nodesMerged = 0;
            var nodesSplit = 0;
            var entitiesRelocated = 0;

            try {
                // Get root nodes and rebalance each subtree
                var roots = getRootNodes();
                for (var root : roots) {
                    var modifications = rebalanceSubtree(root);
                    // Track modifications (simplified)
                    nodesSplit += modifications;
                }

                long timeTaken = System.currentTimeMillis() - startTime;
                return new RebalancingResult(nodesCreated, nodesRemoved, nodesMerged, nodesSplit, entitiesRelocated,
                                             timeTaken, true);
            } catch (Exception e) {
                long timeTaken = System.currentTimeMillis() - startTime;
                return new RebalancingResult(0, 0, 0, 0, 0, timeTaken, false);
            }
        }

        @Override
        public void setAutoBalancingEnabled(boolean enabled) {
            AbstractSpatialIndex.this.setAutoBalancingEnabled(enabled);
        }

        @Override
        public void setBalancingStrategy(TreeBalancingStrategy<ID> strategy) {
            AbstractSpatialIndex.this.setBalancingStrategy(strategy);
        }

        @Override
        public List<Key> splitNode(Key nodeIndex, byte nodeLevel) {
            // Default implementation does not support splitting
            // Subclasses should override for actual splitting logic
            return Collections.emptyList();
        }

        /**
         * Find sibling nodes for the given node.
         */
        protected Set<Key> findSiblings(Key nodeIndex) {
            // Default implementation: no siblings
            // Subclasses should override based on their structure
            return Collections.emptySet();
        }

        /**
         * Get entity counts of sibling nodes.
         */
        protected int[] getSiblingEntityCounts(Key nodeIndex) {
            var siblings = findSiblings(nodeIndex);
            var counts = new int[siblings.size()];
            var i = 0;
            for (var sibling : siblings) {
                SpatialNodeImpl<ID> node = spatialIndex.get(sibling);
                counts[i++] = node != null ? node.getEntityCount() : 0;
            }
            return counts;
        }

        private int rebalanceSubtreeImpl(Key rootNodeIndex, Set<Key> visited) {
            // Prevent infinite recursion
            if (!visited.add(rootNodeIndex)) {
                return 0; // Already processed this node
            }

            int modifications = 0;

            SpatialNodeImpl<ID> node = spatialIndex.get(rootNodeIndex);
            if (node == null) {
                return 0;
            }

            byte level = rootNodeIndex.getLevel();
            int entityCount = node.getEntityCount();

            // Check if node needs balancing
            var action = checkNodeBalance(rootNodeIndex);

            switch (action) {
                case SPLIT -> {
                    if (level < maxDepth) {
                        var children = splitNode(rootNodeIndex, level);
                        modifications += children.size();
                    }
                }
                case MERGE -> {
                    // Find siblings for merging
                    var siblings = findSiblings(rootNodeIndex);
                    if (!siblings.isEmpty()) {
                        var parent = rootNodeIndex.parent();
                        if (mergeNodes(siblings, parent)) {
                            modifications++;
                        }
                    }
                }
                case REDISTRIBUTE -> {
                    // Redistribution not implemented in default balancer
                }
                case NONE -> {
                    // No action needed
                }
            }

            // Recursively balance children
            var children = getChildNodes(rootNodeIndex);
            for (var child : children) {
                modifications += rebalanceSubtreeImpl(child, visited);
            }

            return modifications;
        }
    }
    
    // ========================================
    // Ghost Layer Configuration and Operations
    // ========================================
    // RDR-008 P2: the ghost cluster lives in GhostCoordinator; the methods below are thin delegators that
    // preserve the long-standing public API and the protected setNeighborDetector seam subclasses call.

    /** Sets the ghost type. */
    public void setGhostType(GhostType type) {
        ghost.setGhostType(type);
    }

    /** Gets the current ghost type. */
    public GhostType getGhostType() {
        return ghost.getGhostType();
    }

    /** Gets the ghost layer for this spatial index. */
    public GhostLayer<Key, ID, Content> getGhostLayer() {
        return ghost.getGhostLayer();
    }

    /** Gets the neighbor detector for this spatial index. */
    public NeighborDetector<Key> getNeighborDetector() {
        return ghost.getNeighborDetector();
    }

    /**
     * Per-shape partition weight {@code N_shape(level)} (RDR-010 pi1.6, Knapp Eq 5.1). Default is the
     * 1:8-refinement count {@code 8^level} shared by the hexahedral ({@link com.hellblazer.luciferase.lucien.octree.Octree})
     * and tetrahedral ({@link com.hellblazer.luciferase.lucien.tetree.Tetree}) shapes. {@link com.hellblazer.luciferase.lucien.pyramid.PyramidIndex}
     * overrides this with {@code 2·8^level − 6^level}.
     *
     * @see com.hellblazer.luciferase.lucien.balancing.ShapeWeightProvider
     */
    @Override
    public long elementCount(int level) {
        return com.hellblazer.luciferase.lucien.balancing.ShapeWeightProvider.eightToThe(level);
    }

    /** Subclasses call this during initialization to supply their tree-specific neighbor detector. */
    protected void setNeighborDetector(NeighborDetector<Key> detector) {
        ghost.setNeighborDetector(detector);
    }

    /**
     * Sets the ghost creation algorithm for this spatial index.
     *
     * @param algorithm the ghost creation algorithm to use
     */
    public void setGhostCreationAlgorithm(GhostAlgorithm algorithm) {
        ghost.setGhostCreationAlgorithm(algorithm);
    }
    
    /**
     * Gets the current ghost creation algorithm.
     * 
     * @return the current ghost creation algorithm
     */
    public GhostAlgorithm getGhostCreationAlgorithm() {
        return ghost.getGhostCreationAlgorithm();
    }
    
    /**
     * Creates or updates the ghost layer based on the current ghost type.
     * This method analyzes the local elements and creates ghost elements
     * for neighboring elements owned by other processes.
     */
    public void createGhostLayer() {
        ghost.createGhostLayer();
    }
    
    /**
     * Updates the existing ghost layer, typically called after
     * modifications to the spatial index.
     */
    public void updateGhostLayer() {
        ghost.updateGhostLayer();
    }

    /**
     * Gets all spatial keys currently in the spatial index.
     * Used by ghost layer management to iterate through elements.
     * 
     * @return set of all spatial keys
     */
    public Set<Key> getSpatialKeys() {
        lock.readLock().lock();
        try {
            return new HashSet<>(spatialIndex.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Checks if a spatial key exists in the index.
     * Used by ghost layer management to test element existence.
     * 
     * @param key the spatial key to check
     * @return true if the key exists in the spatial index
     */
    public boolean containsSpatialKey(Key key) {
        return spatialIndex.containsKey(key);
    }

    /**
     * Finds entities at the given spatial key, including ghost elements.
     * 
     * @param key the spatial key to search
     * @return list of entity IDs including both local and ghost entities
     */
    public List<ID> findEntitiesIncludingGhosts(Key key) {
        return ghost.findEntitiesIncludingGhosts(key);
    }
    
    /**
     * Finds neighbors within the specified distance, including ghost elements.
     * 
     * @param position the center position
     * @param radius the search radius
     * @return list of neighbor results including both local and ghost neighbors
     */
    public List<NeighborResult<ID, Content>> findNeighborsIncludingGhosts(Point3f position, float radius) {
        return ghost.findNeighborsIncludingGhosts(position, radius);
    }
    
    // ========================================
    // Ghost Update Hooks
    // ========================================
    
    /**
     * Called after bulk insertions to trigger ghost updates if enabled.
     */
    protected void triggerGhostUpdateAfterBulkInsert() {
        ghost.triggerGhostUpdateAfterBulkInsert();
    }
    
    /**
     * Called after tree adaptation to trigger ghost updates if enabled.
     */
    protected void triggerGhostUpdateAfterAdaptation() {
        ghost.triggerGhostUpdateAfterAdaptation();
    }
    
    // ========================================
    // Distributed Ghost Management
    // ========================================
    
    /**
     * Sets up distributed ghost management with the provided ghost channel.
     *
     * <p>{@code contentSerializer} and {@code entityIdClass} are not read by the current
     * implementation; they are retained for API stability and prospective deserialization
     * wiring when the gRPC clients move to a distributed module (RDR-007 Phase 1).
     *
     * @param ghostChannel the pre-built ghost channel for batched cross-process transmission
     * @param contentSerializer the content serializer (currently unused; see note above)
     * @param entityIdClass the entity ID class for deserialization (currently unused; see note above)
     * @param currentRank the rank of this process
     * @param treeId the tree identifier
     */
    public void setupDistributedGhosts(GhostChannel<Key, ID, Content> ghostChannel,
                                       ContentSerializer<Content> contentSerializer, Class<ID> entityIdClass,
                                       int currentRank, long treeId) {
        ghost.setupDistributedGhosts(ghostChannel, contentSerializer, entityIdClass, currentRank, treeId);
    }

    /**
     * Initialize the distributed ghost layer.
     * This should be called after all processes are ready.
     *
     * @param serviceDiscovery the service discovery to find other processes
     */
    public void initializeDistributedGhosts(ServiceDiscovery serviceDiscovery) {
        ghost.initializeDistributedGhosts(serviceDiscovery);
    }
    
    /**
     * Create or update the distributed ghost layer.
     * This coordinates with other processes to exchange ghost elements.
     */
    public void createDistributedGhostLayer() {
        ghost.createDistributedGhostLayer();
    }
    
    /**
     * Add a known process for distributed ghost communication.
     * 
     * @param rank the process rank to add
     */
    public void addDistributedProcess(int rank) {
        ghost.addDistributedProcess(rank);
    }
    
    /**
     * Remove a process from distributed ghost communication.
     * 
     * @param rank the process rank to remove
     */
    public void removeDistributedProcess(int rank) {
        ghost.removeDistributedProcess(rank);
    }
    
    /**
     * Set element ownership information for distributed ghost detection.
     * 
     * @param key the spatial key
     * @param ownerRank the rank of the process that owns this element
     */
    public void setElementOwner(Key key, int ownerRank) {
        ghost.setElementOwner(key, ownerRank);
    }
    
    /**
     * Synchronize ghost elements with all known processes.
     */
    public void synchronizeDistributedGhosts() {
        ghost.synchronizeDistributedGhosts();
    }
    
    /**
     * Enable or disable automatic distributed ghost synchronization.
     * 
     * @param enabled true to enable auto-sync, false to disable
     */
    public void setDistributedGhostAutoSync(boolean enabled) {
        ghost.setDistributedGhostAutoSync(enabled);
    }
    
    /**
     * Get distributed ghost statistics.
     * 
     * @return map of statistics, or empty map if distributed ghosts not enabled
     */
    public Map<String, Object> getDistributedGhostStatistics() {
        return ghost.getDistributedGhostStatistics();
    }
    
    /**
     * Check if distributed ghost management is enabled.
     * 
     * @return true if distributed ghosts are enabled
     */
    public boolean isDistributedGhostsEnabled() {
        return ghost.isDistributedGhostsEnabled();
    }
    
    /**
     * Shutdown distributed ghost management.
     */
    public void shutdownDistributedGhosts() {
        ghost.shutdownDistributedGhosts();
    }
    
    
    /**
     * Compute the bounds for a spatial node.
     * This method should be overridden by subclasses to provide
     * implementation-specific bounds calculation.
     * 
     * @param key the spatial key of the node
     * @return the entity bounds for the node, or null if not computable
     */
    public EntityBounds computeNodeBounds(Key key) {
        // Default implementation returns null
        // Subclasses (Octree, Tetree) should override this
        return null;
    }

    /**
     * Content-authoritative occupied-cell AABB (Luciferase-36lp): the union of {@link #getNodeBounds(Key)} over
     * every occupied key. Independent of any forest-maintained tree bounds, so it is correct regardless of how
     * entities were inserted. Returns {@code null} for an empty index or when no node bounds are resolvable
     * (callers must treat {@code null} as "do not route / do not skip").
     */
    @Override
    public VolumeBounds getOccupiedBounds() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (var key : spatialIndex.keySet()) {
            var spatial = getNodeBounds(key);
            if (spatial == null) {
                continue;
            }
            var vb = VolumeBounds.from(spatial);
            if (vb == null) {
                continue;
            }
            any = true;
            minX = Math.min(minX, vb.minX());
            minY = Math.min(minY, vb.minY());
            minZ = Math.min(minZ, vb.minZ());
            maxX = Math.max(maxX, vb.maxX());
            maxY = Math.max(maxY, vb.maxY());
            maxZ = Math.max(maxZ, vb.maxZ());
        }
        return any ? new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }
    
    /**
     * Enable Dynamic Scene Occlusion Culling (DSOC) for this spatial index
     * 
     * @param config DSOC configuration
     * @param bufferWidth Z-buffer width
     * @param bufferHeight Z-buffer height
     */
    public void enableDSOC(DSOCConfiguration config, int bufferWidth, int bufferHeight) {
        // RDR-008 P1: the DSOC cluster is encapsulated in DsocController.
        this.dsoc = new DsocController<>(core, new FrustumGeometryImpl(), culler, config, bufferWidth, bufferHeight);
    }
    
    /**
     * Enable DSOC with default buffer size
     */
    public void enableDSOC(DSOCConfiguration config) {
        enableDSOC(config, 1024, 1024);
    }
    
    /**
     * Check if DSOC is enabled and not auto-disabled
     */
    public boolean isDSOCEnabled() {
        return dsoc != null && dsoc.isEnabled();
    }
    
    /**
     * Update camera matrices for occlusion culling
     */
    public void updateCamera(float[] viewMatrix, float[] projectionMatrix, Point3f cameraPosition) {
        if (dsoc != null) {
            dsoc.updateCamera(viewMatrix, projectionMatrix, cameraPosition);
        }
    }
    
    /**
     * Advance to next frame (for DSOC)
     */
    public long nextFrame() {
        return dsoc != null ? dsoc.nextFrame() : 0;
    }
    
    /**
     * Get current frame number
     */
    public long getCurrentFrame() {
        return dsoc != null ? dsoc.getCurrentFrame() : 0;
    }
    
    /**
     * Get DSOC statistics
     * 
     * @return Map of statistics
     */
    public Map<String, Object> getDSOCStatistics() {
        if (dsoc != null) {
            return dsoc.getStatistics();
        }
        Map<String, Object> stats = new HashMap<>();
        stats.put("dsocEnabled", false);
        return stats;
    }
    
    /**
     * Get entities that need position updates
     * 
     * @return Set of entity IDs needing updates
     */
    public Set<ID> getEntitiesNeedingUpdate() {
        return dsoc != null ? dsoc.getEntitiesNeedingUpdate() : new HashSet<>();
    }
    
    /**
     * Reset DSOC statistics
     */
    public void resetDSOCStatistics() {
        if (dsoc != null) {
            dsoc.resetStatistics();
        }
    }
    
    /**
     * Force Z-buffer activation for testing
     */
    public void forceZBufferActivation() {
        if (dsoc != null) {
            dsoc.forceZBufferActivation();
        }
    }

}
