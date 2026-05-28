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

import com.hellblazer.luciferase.lucien.cache.KNNCache;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityManager;
import com.hellblazer.luciferase.lucien.internal.EntityCache;

import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * The shared storage + concurrency nucleus of {@link AbstractSpatialIndex}.
 *
 * <p>This is the six-field nucleus that every cluster of the spatial index touches: the sorted node map, the
 * read-write lock guarding it, the spatial version counter, the k-NN result cache, the entity manager, and the entity
 * data cache. RDR-008 decomposes the {@code AbstractSpatialIndex} god-class into a façade plus cohesive feature-object
 * collaborators ({@code DsocController}, {@code KnnSearcher}, {@code CollisionEngine}, …); each collaborator needs the
 * same shared state, and handing it one {@code SpatialIndexCore} reference is cleaner — and keeps the concurrency
 * primitives auditable in one place — than wiring six fields into every constructor.
 *
 * <p><b>P0 (RDR-008) introduction shape.</b> This type is introduced as an additive <i>view</i> over the façade's
 * existing nucleus fields: {@code AbstractSpatialIndex} retains its (mostly {@code protected}) fields and constructs a
 * single {@code SpatialIndexCore} wrapping the <i>same</i> object references in its constructor. No field is moved out
 * of the façade and no internal access is rewritten in P0, so behaviour is unchanged and the four subclasses
 * (Octree/Tetree/Prism/SFCArrayIndex), which reach the {@code protected} nucleus fields directly, compile untouched.
 * Subsequent phases relocate each feature cluster into its own collaborator, which receives this core for nucleus
 * access; the nucleus fields themselves stay on the façade.
 *
 * <p>The wrapped references are shared mutable collaborators (the map, lock, caches, and counters are live, concurrent
 * objects); this holder is an identity aggregate over them, not a value object — it deliberately does not override
 * {@code equals}/{@code hashCode}.
 *
 * <p><b>P6 (RDR-008) lockingStrategy migration.</b> The fine-grained locking strategy lives here too (RDR-008 P3
 * substantive-critic Significant#1, discharged at P6). It is the only nucleus field that is <i>mutable</i>:
 * {@link AbstractSpatialIndex#configureFineGrainedLocking} replaces the reference at runtime (the
 * conservative/high-concurrency presets and any caller-provided {@code LockingConfig}). The field is therefore
 * {@code volatile} so concurrent readers (the k-NN cluster's {@code findCollisionsFineGrained}, the collision
 * cluster's same-named entry) see a coherent reference, mirroring the pre-extraction façade volatile-supplier
 * pattern. {@code KnnSearcher} and {@code CollisionEngine} consume {@link #lockingStrategy()} directly; the
 * pre-P6 {@code Supplier<FineGrainedLockingStrategy>} constructor argument is gone.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class SpatialIndexCore<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private final ConcurrentNavigableMap<Key, SpatialNodeImpl<ID>> spatialIndex;
    private final ReadWriteLock                                    lock;
    private final AtomicLong                                       spatialVersion;
    private final KNNCache<Key, ID>                                knnCache;
    private final EntityManager<Key, ID, Content>                  entityManager;
    private final EntityCache<ID>                                  entityCache;
    // volatile: configureFineGrainedLocking replaces this reference; concurrent k-NN / collision callers read it
    // outside the read-write lock (the strategy itself owns its own per-node locks), so the publication must be
    // safe. Same publication contract as the pre-P6 façade volatile field + Supplier seam (RDR-008 P3
    // substantive-critic Significant#1, discharged at P6 by relocating the field here).
    private volatile FineGrainedLockingStrategy<ID, Content>       lockingStrategy;

    /**
     * Wrap the seven nucleus collaborators (six immutable references + the mutable fine-grained locking strategy).
     * All arguments are required; the references are shared with the owning {@link AbstractSpatialIndex} façade and
     * must not be {@code null}.
     */
    public SpatialIndexCore(ConcurrentNavigableMap<Key, SpatialNodeImpl<ID>> spatialIndex, ReadWriteLock lock,
                            AtomicLong spatialVersion, KNNCache<Key, ID> knnCache,
                            EntityManager<Key, ID, Content> entityManager, EntityCache<ID> entityCache,
                            FineGrainedLockingStrategy<ID, Content> lockingStrategy) {
        this.spatialIndex = Objects.requireNonNull(spatialIndex, "spatialIndex");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.spatialVersion = Objects.requireNonNull(spatialVersion, "spatialVersion");
        this.knnCache = Objects.requireNonNull(knnCache, "knnCache");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.entityCache = Objects.requireNonNull(entityCache, "entityCache");
        this.lockingStrategy = Objects.requireNonNull(lockingStrategy, "lockingStrategy");
    }

    /** The sorted spatial index: spatial key to the node holding the entity IDs at that key. */
    public ConcurrentNavigableMap<Key, SpatialNodeImpl<ID>> spatialIndex() {
        return spatialIndex;
    }

    /** The read-write lock guarding multi-step operations over the spatial index. */
    public ReadWriteLock lock() {
        return lock;
    }

    /** Monotonic version counter bumped on structural mutation; read by the k-NN cache for invalidation. */
    public AtomicLong spatialVersion() {
        return spatialVersion;
    }

    /** The k-NN result cache, invalidated against {@link #spatialVersion()}. */
    public KNNCache<Key, ID> knnCache() {
        return knnCache;
    }

    /** The entity manager owning entity lifecycle and entity-to-location mappings. */
    public EntityManager<Key, ID, Content> entityManager() {
        return entityManager;
    }

    /** The entity data cache used to reduce repeated entity lookups. */
    public EntityCache<ID> entityCache() {
        return entityCache;
    }

    /**
     * The current fine-grained locking strategy. {@code volatile}-read: callers that hold this reference past the
     * single read point may observe stale state if {@link #setLockingStrategy} ran concurrently — re-read at each
     * use point if that matters (the k-NN and collision feature objects re-read at the start of every fine-grained
     * call, matching the pre-extraction façade idiom).
     */
    public FineGrainedLockingStrategy<ID, Content> lockingStrategy() {
        return lockingStrategy;
    }

    /**
     * Replace the fine-grained locking strategy. Called by {@code AbstractSpatialIndex.configureFineGrainedLocking}
     * under the façade's write lock so the replacement is sequenced against pending mutations; the volatile write
     * publishes the new reference to subsequent volatile reads from any thread.
     */
    public void setLockingStrategy(FineGrainedLockingStrategy<ID, Content> lockingStrategy) {
        this.lockingStrategy = Objects.requireNonNull(lockingStrategy, "lockingStrategy");
    }
}
