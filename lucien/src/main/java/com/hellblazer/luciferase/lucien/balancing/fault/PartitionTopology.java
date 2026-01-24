package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Maps between partition UUIDs and integer ranks (Phase 4.1).
 *
 * <p>Provides bidirectional UUID ↔ rank mapping for fault detection and recovery.
 * FaultHandler uses UUID for partition identification, while InMemoryPartitionRegistry
 * uses int rank. This interface bridges the two representations.
 *
 * <p><b>Thread Safety</b>: Implementations must support concurrent reads.
 *
 * <p><b>Versioning</b>: Topology changes increment a version counter for consistency checks.
 *
 * <p><b>Usage Example</b>:
 * <pre>
 * PartitionTopology topology = new InMemoryPartitionTopology();
 * UUID partition0 = UUID.randomUUID();
 * topology.register(partition0, 0);
 *
 * // Lookup rank by UUID
 * Optional&lt;Integer&gt; rank = topology.rankFor(partition0); // Optional[0]
 *
 * // Lookup UUID by rank
 * Optional&lt;UUID&gt; uuid = topology.partitionFor(0); // Optional[partition0]
 * </pre>
 *
 * @see InMemoryPartitionTopology
 */
public interface PartitionTopology {

    /**
     * Get the integer rank for a partition UUID.
     *
     * @param partitionId the partition UUID
     * @return the rank, or empty if partition unknown
     */
    Optional<Integer> rankFor(UUID partitionId);

    /**
     * Get the partition UUID for an integer rank.
     *
     * @param rank the partition rank
     * @return the UUID, or empty if rank unknown
     */
    Optional<UUID> partitionFor(int rank);

    /**
     * Register a new partition mapping.
     *
     * <p>If the partition is already registered at the same rank, this operation
     * is idempotent (no version increment).
     *
     * @param partitionId the partition UUID
     * @param rank the integer rank
     * @throws IllegalStateException if rank already mapped to a different partition
     */
    void register(UUID partitionId, int rank);

    /**
     * Remove a partition from the topology.
     *
     * <p>If the partition is not registered, this is a no-op (no version increment).
     *
     * @param partitionId the partition UUID
     */
    void unregister(UUID partitionId);

    /**
     * Get total number of registered partitions.
     *
     * @return total partition count
     */
    int totalPartitions();

    /**
     * Get set of currently active ranks.
     *
     * @return immutable snapshot of active ranks
     */
    Set<Integer> activeRanks();

    /**
     * Get topology version (increments on any change).
     *
     * <p>Version increments on:
     * <ul>
     *   <li>register() - if new mapping added</li>
     *   <li>unregister() - if mapping removed</li>
     * </ul>
     *
     * <p>Version does NOT increment on:
     * <ul>
     *   <li>Idempotent re-registration</li>
     *   <li>Unregistering unknown partition</li>
     * </ul>
     *
     * @return current topology version (starts at 0)
     */
    long topologyVersion();
}
