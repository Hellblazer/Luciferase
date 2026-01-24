/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.balancing.fault.InFlightOperationTracker;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Decorator that adds fault tolerance capabilities to a DistributedForest.
 *
 * <p>This decorator wraps a {@link ParallelBalancer.DistributedForest} implementation (typically
 * {@link DistributedForestImpl}) and adds:
 * <ul>
 *   <li><b>Pause/Resume Coordination</b>: Coordinates with {@link DefaultParallelBalancer}
 *       to pause in-flight balance operations during recovery</li>
 *   <li><b>Operation Tracking</b>: Tracks balance operations for synchronization with recovery</li>
 *   <li><b>Fault Detection Integration</b>: Routes fault detection events to recovery coordination</li>
 * </ul>
 *
 * <p><b>Decorator Pattern</b>:
 * <ul>
 *   <li>Wraps (not composes) a {@link ParallelBalancer.DistributedForest} delegate</li>
 *   <li>All interface methods delegated to delegate</li>
 *   <li>Adds fault tolerance via factory method and operation tracking</li>
 * </ul>
 *
 * <p>Thread-safe: Uses thread-safe delegation to underlying forest components.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public class FaultTolerantDistributedForest<Key extends SpatialKey<Key>, ID extends EntityID, Content>
    implements ParallelBalancer.DistributedForest<Key, ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(FaultTolerantDistributedForest.class);

    private final ParallelBalancer.DistributedForest<Key, ID, Content> delegate;
    private final InFlightOperationTracker operationTracker;

    /**
     * Create a fault-tolerant decorator wrapping the provided distributed forest.
     *
     * <p>This constructor creates a new operation tracker for fault tolerance coordination.
     * For integration with a specific balancer, use {@link #wrap(ParallelBalancer.DistributedForest, DefaultParallelBalancer)}.
     *
     * @param delegate the distributed forest to wrap
     * @throws NullPointerException if delegate is null
     */
    public FaultTolerantDistributedForest(ParallelBalancer.DistributedForest<Key, ID, Content> delegate) {
        this(delegate, new InFlightOperationTracker());
    }

    /**
     * Create a fault-tolerant decorator wrapping the provided distributed forest with shared tracker.
     *
     * <p>This constructor allows sharing of the operation tracker with a {@link DefaultParallelBalancer}
     * for coordinated pause/resume during recovery.
     *
     * @param delegate the distributed forest to wrap
     * @param operationTracker the operation tracker for fault tolerance coordination
     * @throws NullPointerException if any parameter is null
     */
    public FaultTolerantDistributedForest(ParallelBalancer.DistributedForest<Key, ID, Content> delegate,
                                         InFlightOperationTracker operationTracker) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.operationTracker = Objects.requireNonNull(operationTracker, "operationTracker cannot be null");

        log.debug("Created FaultTolerantDistributedForest wrapping delegate");
    }

    /**
     * Factory method to wrap a distributed forest with fault tolerance using a balancer.
     *
     * <p>This method creates a fault-tolerant decorator that shares the operation tracker
     * with the provided balancer, enabling coordinated pause/resume during recovery.
     *
     * @param delegate the distributed forest to wrap
     * @param balancer the parallel balancer whose operation tracker to share
     * @param <Key> the spatial key type
     * @param <ID> the entity ID type
     * @param <Content> the content type
     * @return a new fault-tolerant decorator wrapping the delegate
     * @throws NullPointerException if any parameter is null
     */
    @SuppressWarnings("unchecked")
    public static <Key extends SpatialKey<Key>, ID extends EntityID, Content>
    FaultTolerantDistributedForest<Key, ID, Content> wrap(
        ParallelBalancer.DistributedForest<Key, ID, Content> delegate,
        DefaultParallelBalancer<?, ?, ?> balancer) {

        Objects.requireNonNull(delegate, "delegate cannot be null");
        Objects.requireNonNull(balancer, "balancer cannot be null");

        var tracker = balancer.getOperationTracker();
        log.debug("Wrapping forest with fault tolerance, sharing balancer's operation tracker");

        return new FaultTolerantDistributedForest<>(delegate, tracker);
    }

    /**
     * Get the wrapped delegate for testing or explicit delegation.
     *
     * <p>This method is primarily for testing and should not be used in production code,
     * as all operations should go through the decorator interface.
     *
     * @return the wrapped delegate
     */
    public ParallelBalancer.DistributedForest<Key, ID, Content> getDelegate() {
        return delegate;
    }

    /**
     * Get the operation tracker used for fault tolerance coordination.
     *
     * @return the operation tracker
     */
    public InFlightOperationTracker getOperationTracker() {
        return operationTracker;
    }

    /**
     * Get the local forest for this partition.
     *
     * <p>Delegates to the wrapped forest.
     *
     * @return the local forest
     */
    @Override
    public Forest<Key, ID, Content> getLocalForest() {
        return delegate.getLocalForest();
    }

    /**
     * Get the distributed ghost manager for inter-partition communication.
     *
     * <p>Delegates to the wrapped forest.
     *
     * @return the ghost manager
     */
    @Override
    public DistributedGhostManager<Key, ID, Content> getGhostManager() {
        return delegate.getGhostManager();
    }

    /**
     * Get the partition registry for distributed coordination.
     *
     * <p>Delegates to the wrapped forest.
     *
     * @return the partition registry
     */
    @Override
    public ParallelBalancer.PartitionRegistry getPartitionRegistry() {
        return delegate.getPartitionRegistry();
    }
}
