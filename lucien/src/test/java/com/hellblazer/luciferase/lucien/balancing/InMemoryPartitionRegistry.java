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

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CyclicBarrier;

/**
 * In-memory partition registry for multi-partition testing.
 *
 * <p>Provides:
 * <ul>
 *   <li>Partition registration and lookup</li>
 *   <li>CyclicBarrier coordination for refinement rounds</li>
 *   <li>Ghost boundary exchange coordination</li>
 *   <li>Per-round synchronization</li>
 * </ul>
 *
 * <p>Thread-safe for concurrent partition access during balancing.
 *
 * @author hal.hildebrand
 */
public class InMemoryPartitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPartitionRegistry.class);

    private final int partitionCount;
    private final Map<Integer, Partition> partitions = new HashMap<>();
    private final CyclicBarrier coordinationBarrier;
    private volatile int currentRound = 0;

    public InMemoryPartitionRegistry(int partitionCount) {
        this.partitionCount = partitionCount;
        this.coordinationBarrier = new CyclicBarrier(partitionCount, this::advanceRound);
        log.info("Created partition registry with {} partitions and coordination barrier", partitionCount);
    }

    /**
     * Register a partition with its forest and local balancer.
     *
     * @param rank the partition rank (0 to partitionCount-1)
     * @param forest the forest for this partition
     * @param balancer the local balancer for this partition
     */
    public void registerPartition(int rank, Forest<MortonKey, LongEntityID, String> forest,
                                  ParallelBalancer balancer) {
        var partition = new Partition(rank, forest, balancer);
        partitions.put(rank, partition);
        log.debug("Registered partition rank {}", rank);
    }

    /**
     * Wait for all partitions to reach the barrier (end of current refinement round).
     *
     * @throws InterruptedException if barrier wait is interrupted
     */
    public void barrier() throws InterruptedException {
        try {
            coordinationBarrier.await();
        } catch (Exception e) {
            log.error("Barrier wait failed", e);
            throw new InterruptedException("Barrier synchronization failed: " + e.getMessage());
        }
    }

    /**
     * Get a partition by rank.
     *
     * @param rank the partition rank
     * @return the partition, or null if not registered
     */
    public Partition getPartition(int rank) {
        return partitions.get(rank);
    }

    /**
     * Get all registered partitions.
     *
     * @return unmodifiable collection of partitions
     */
    public Collection<Partition> getAllPartitions() {
        return Collections.unmodifiableCollection(partitions.values());
    }

    /**
     * Get current refinement round number.
     *
     * @return the round number
     */
    public int getCurrentRound() {
        return currentRound;
    }

    /**
     * Check if all partitions are balanced (no refinements needed).
     *
     * @return true if all balanced
     */
    public boolean isBalanced() {
        // TODO: Implement balance check based on ParallelBalancer metrics
        return false;
    }

    /**
     * Get total refinement rounds completed.
     */
    public int getTotalRounds() {
        return currentRound;
    }

    /**
     * Advance to next refinement round (called by barrier action).
     */
    private void advanceRound() {
        currentRound++;
        log.debug("Advanced to refinement round {}", currentRound);
    }

    /**
     * Represents a single partition with its forest and balancer.
     */
    public record Partition(
        int rank,
        Forest<MortonKey, LongEntityID, String> forest,
        ParallelBalancer balancer
    ) {
        /**
         * Get the balance metrics for this partition.
         */
        public BalanceMetrics getMetrics() {
            return balancer.getMetrics();
        }
    }
}
