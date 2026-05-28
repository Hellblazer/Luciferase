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

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.BulkOperationConfig;
import com.hellblazer.luciferase.lucien.BulkOperationProcessor;
import com.hellblazer.luciferase.lucien.DeferredSubdivisionManager;
import com.hellblazer.luciferase.lucien.ParallelBulkOperations;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.SpatialNodePool;
import com.hellblazer.luciferase.lucien.StackBasedTreeBuilder;
import com.hellblazer.luciferase.lucien.occlusion.DsocController;

import java.util.Set;

/**
 * Facade-internal infrastructure the {@link EntityLifecycleManager} needs (RDR-008 P6).
 *
 * <p>The entity-lifecycle cluster is the broadest cluster in the {@code AbstractSpatialIndex} decomposition;
 * unlike the narrower clusters (DSOC, ghost, k-NN, cull, collision), it reaches into a great deal of
 * facade-internal infrastructure: bulk-operation configuration, the bulk processor, the deferred-subdivision
 * manager, the node pool, the parallel-operations executor, the stack-based tree builder, the spanning policy,
 * the bulk-loading-mode flag, the deferred-subdivision-node set, the ghost-update hooks, the auto-balance hook,
 * and the volatile DSOC controller (for the {@code updateEntity} seam). Passing each as a separate constructor
 * argument would produce a 17-arg signature; passing the concrete {@code AbstractSpatialIndex} as a back-reference
 * would tightly couple the feature object to the god-class we are decomposing.
 *
 * <p>This host interface is the principled middle ground (RDR-008 P3 refinement applied to the host surface):
 * a narrow, named seam in the cluster's own package. The facade implements it via a private inner class so the
 * underlying fields/methods keep their original visibility, mirroring how {@code EntityLifecycleGeometryImpl}
 * routes the subclass-overridden hooks.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface EntityLifecycleHost<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    // ===== Sizing constants =====

    /** Maximum entities per node before subdivision triggers. */
    int maxEntitiesPerNode();

    /** Maximum subdivision depth allowed by the index. */
    byte maxDepth();

    // ===== Bulk / parallel infrastructure =====

    /** Current bulk-operation configuration (read at each call to pick up {@code configureBulkOperations} swaps). */
    BulkOperationConfig bulkConfig();

    /** The bulk-operation processor used for batch insert preprocessing/grouping. */
    BulkOperationProcessor<Key, ID, Content> bulkProcessor();

    /** The deferred-subdivision manager used when bulk loading defers subdivisions until {@code finalizeBulkLoading}. */
    DeferredSubdivisionManager<Key, ID> subdivisionManager();

    /** The pooled node factory the entity-lifecycle cluster uses for {@code insertAtPosition} + cleanup. */
    SpatialNodePool<ID> nodePool();

    /**
     * Current parallel-bulk-operations executor (read at each call to pick up
     * {@code configureParallelOperations} swaps).
     */
    ParallelBulkOperations<Key, ID, Content> parallelOperations();

    /** Current stack-based tree builder (read at each call to pick up {@code configureTreeBuilder} swaps). */
    StackBasedTreeBuilder<Key, ID, Content> treeBuilder();

    /** The spanning policy (final on the facade). */
    EntitySpanningPolicy spanningPolicy();

    // ===== Bulk-loading mode flag (mutable) =====

    /** Whether bulk-loading mode is currently active (set via {@link #enableBulkLoading} / {@link #finalizeBulkLoading}). */
    boolean bulkLoadingMode();

    /** Replace the bulk-loading-mode flag in place (low-level — most callers use the enable/finalize pair). */
    void setBulkLoadingMode(boolean value);

    /** The shared set of deferred-subdivision spatial keys (cleared during bulk-loading finalization). */
    Set<Long> deferredSubdivisionNodes();

    // ===== Lifecycle methods that delegate to facade state =====

    /** Toggle the bulk-loading mode on (writes the flag under the facade write lock; clears the deferred-set). */
    void enableBulkLoading();

    /** Toggle the bulk-loading mode off, draining any deferred subdivisions and triggering ghost updates. */
    void finalizeBulkLoading();

    /** Replace the stack-based tree builder (matches the public facade entry of the same name). */
    void configureTreeBuilder(StackBasedTreeBuilder.BuildConfig config);

    /** Notify the ghost coordinator that a bulk insert just landed (triggers the configured update strategy). */
    void triggerGhostUpdateAfterBulkInsert();

    /** Run the auto-balancing check after a structural mutation; no-op when auto-balance is disabled. */
    void checkAutoBalance();

    // ===== DSOC seam (P1 obligation carried through P6) =====

    /**
     * Current Dynamic Scene Occlusion Culling controller, or {@code null} when DSOC is not enabled. Read at each
     * call (the field is {@code volatile} on the facade) so the {@code updateEntity} DSOC seam observes any
     * concurrent {@code enableDSOC} publication, mirroring the pre-extraction direct-field-read pattern.
     */
    DsocController<Key, ID, Content> dsocController();

    // ===== Convenience compound subdivision hook (used by insertAtPosition) =====

    /**
     * Record a subdivision deferral on the shared manager (a tiny adapter so the entity-lifecycle cluster doesn't
     * have to type-erase a {@code SpatialNodeImpl} through the {@code subdivisionManager()} accessor).
     */
    void deferSubdivision(Key spatialIndex, SpatialNodeImpl<ID> node, int entityCount, byte level);

    // ===== Stack-based tree builder target =====

    /**
     * The {@link AbstractSpatialIndex} target the {@link StackBasedTreeBuilder} needs (the builder's
     * {@code buildTree} signature takes the concrete abstract base, not the public {@code SpatialIndex} interface,
     * because the builder relies on facade-internal helpers — auto-id generation through the entity manager and
     * the protected lifecycle paths). The host exposes the facade through this accessor so
     * {@code EntityLifecycleManager} avoids a direct {@code AbstractSpatialIndex} import — the coupling stays
     * named and bounded here.
     */
    AbstractSpatialIndex<Key, ID, Content> stackBuilderTarget();
}
