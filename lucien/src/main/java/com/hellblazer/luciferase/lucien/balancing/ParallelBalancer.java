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
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;

/**
 * Interface for parallel distributed tree balancing across multiple partitions.
 *
 * <p>This interface defines a three-phase balancing algorithm:
 * <ol>
 *   <li><b>Phase 1: Local Balance</b> - Uses existing {@link TreeBalancer} to balance
 *       within each partition independently</li>
 *   <li><b>Phase 2: Ghost Exchange</b> - Exchanges ghost layer information with level
 *       data to identify cross-partition imbalances</li>
 *   <li><b>Phase 3: Cross-Partition Balance</b> - Performs O(log P) rounds of refinement
 *       to achieve global 2:1 balance invariant</li>
 * </ol>
 *
 * <p>Based on the p4est parallel AMR algorithm (Burstedde et al., SIAM 2011).
 *
 * <p>Thread-safe: Implementations must support concurrent balance operations.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public interface ParallelBalancer<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /**
     * Perform Phase 1: Local balance within a single partition using existing TreeBalancer.
     *
     * <p>This phase operates independently on each partition without communication,
     * applying local balancing strategies to achieve 2:1 balance within the partition.
     *
     * @param forest the forest to balance locally
     * @return the result of the local balance operation
     * @throws IllegalArgumentException if forest is null
     */
    BalanceResult localBalance(Forest<Key, ID, Content> forest);

    /**
     * Perform Phase 2: Exchange ghost layers with level information.
     *
     * <p>This phase coordinates with the distributed ghost manager to synchronize
     * boundary elements and their refinement levels across partition boundaries.
     * This information is used to detect cross-partition imbalances.
     *
     * @param ghostManager the distributed ghost manager for inter-partition communication
     * @throws IllegalArgumentException if ghostManager is null
     */
    void exchangeGhosts(DistributedGhostManager<Key, ID, Content> ghostManager);

    /**
     * Perform Phase 3: Cross-partition balance in O(log P) rounds.
     *
     * <p>This phase performs iterative refinement across partition boundaries,
     * coordinating through the partition registry to achieve global 2:1 balance.
     * The algorithm terminates when:
     * <ul>
     *   <li>No refinements are needed (converged)</li>
     *   <li>Maximum rounds reached (configured via {@link BalanceConfiguration})</li>
     *   <li>Timeout per round exceeded</li>
     * </ul>
     *
     * @param registry the partition registry for distributed coordination
     * @return the result of the cross-partition balance operation
     * @throws IllegalArgumentException if registry is null
     */
    BalanceResult crossPartitionBalance(PartitionRegistry registry);

    /**
     * Perform a full parallel balance cycle across all three phases.
     *
     * <p>This convenience method executes:
     * <ol>
     *   <li>{@link #localBalance(Forest)}</li>
     *   <li>{@link #exchangeGhosts(DistributedGhostManager)}</li>
     *   <li>{@link #crossPartitionBalance(PartitionRegistry)}</li>
     * </ol>
     *
     * <p>The distributed forest provides access to both the local forest,
     * the ghost manager, and the partition registry.
     *
     * @param distributedForest the distributed forest to balance
     * @return the combined result of all three phases
     * @throws IllegalArgumentException if distributedForest is null
     */
    BalanceResult balance(DistributedForest<Key, ID, Content> distributedForest);

    /**
     * Get the current balance metrics.
     *
     * <p>Metrics are updated during balance operations and can be queried
     * at any time. The returned object reflects the current state and will
     * continue to update during subsequent operations.
     *
     * @return the current metrics tracker
     */
    BalanceMetrics getMetrics();

    /**
     * Distributed forest abstraction providing access to local and distributed components.
     *
     * <p>This interface represents a forest that spans multiple partitions,
     * providing access to the local forest, ghost manager for inter-partition
     * communication, and partition registry for coordination.
     *
     * @param <Key> the spatial key type
     * @param <ID> the entity ID type
     * @param <Content> the content type
     */
    interface DistributedForest<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

        /**
         * Get the local forest for this partition.
         *
         * @return the local forest
         */
        Forest<Key, ID, Content> getLocalForest();

        /**
         * Get the distributed ghost manager for inter-partition communication.
         *
         * @return the ghost manager
         */
        DistributedGhostManager<Key, ID, Content> getGhostManager();

        /**
         * Get the partition registry for distributed coordination.
         *
         * @return the partition registry
         */
        PartitionRegistry getPartitionRegistry();
    }

    /**
     * Partition registry for distributed coordination during parallel balancing.
     *
     * <p>This interface provides methods for:
     * <ul>
     *   <li>Querying partition topology (neighbor relationships)</li>
     *   <li>Coordinating refinement decisions across partitions</li>
     *   <li>Barrier synchronization for round completion</li>
     * </ul>
     *
     * <p>Implementations typically use distributed consensus or coordination
     * services (e.g., Delos, Zookeeper) for reliable operation.
     */
    interface PartitionRegistry {

        /**
         * Get the current partition ID.
         *
         * @return the partition ID
         */
        int getCurrentPartitionId();

        /**
         * Get the total number of partitions in the system.
         *
         * @return the partition count
         */
        int getPartitionCount();

        /**
         * Wait for all partitions to reach a synchronization barrier.
         *
         * <p>This method blocks until all partitions have called it,
         * ensuring that all partitions proceed through rounds in lockstep.
         *
         * @param round the round number for this barrier
         * @throws InterruptedException if interrupted while waiting
         */
        void barrier(int round) throws InterruptedException;

        /**
         * Request a refinement for a boundary element.
         *
         * <p>This method records a refinement request that will be considered
         * during the next round of cross-partition balancing.
         *
         * @param elementKey the key of the element to refine
         */
        void requestRefinement(Object elementKey);

        /**
         * Get the number of refinement requests pending for the current round.
         *
         * @return the pending refinement count
         */
        int getPendingRefinements();
    }
}
