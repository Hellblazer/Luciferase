package com.hellblazer.luciferase.lucien.balancing.fault;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory implementation of PartitionTopology (Phase 4.1).
 *
 * <p>Uses ConcurrentHashMap for bidirectional UUID ↔ rank mapping with
 * AtomicLong for version tracking.
 *
 * <p><b>Thread Safety</b>: All operations are thread-safe. Read operations
 * are lock-free, write operations use synchronized methods to ensure
 * bidirectional consistency.
 *
 * <p><b>Memory Usage</b>: O(n) where n is the number of registered partitions.
 *
 * @see PartitionTopology
 */
public class InMemoryPartitionTopology implements PartitionTopology {

    private final Map<UUID, Integer> uuidToRank = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> rankToUuid = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong(0);

    /**
     * Creates a new empty topology with version 0.
     */
    public InMemoryPartitionTopology() {
    }

    @Override
    public Optional<Integer> rankFor(UUID partitionId) {
        return Optional.ofNullable(uuidToRank.get(partitionId));
    }

    @Override
    public Optional<UUID> partitionFor(int rank) {
        return Optional.ofNullable(rankToUuid.get(rank));
    }

    @Override
    public synchronized void register(UUID partitionId, int rank) {
        // Check if this is idempotent re-registration
        var existingRank = uuidToRank.get(partitionId);
        if (existingRank != null && existingRank == rank) {
            // Idempotent: same UUID at same rank, no-op
            return;
        }

        // Check if rank is already mapped to a different partition
        var existingUuid = rankToUuid.get(rank);
        if (existingUuid != null && !existingUuid.equals(partitionId)) {
            throw new IllegalStateException("Rank " + rank + " already mapped to partition " + existingUuid);
        }

        // Register the mapping
        uuidToRank.put(partitionId, rank);
        rankToUuid.put(rank, partitionId);
        version.incrementAndGet();
    }

    @Override
    public synchronized void unregister(UUID partitionId) {
        var rank = uuidToRank.remove(partitionId);
        if (rank != null) {
            rankToUuid.remove(rank);
            version.incrementAndGet();
        }
        // If partition not registered, this is a no-op (no version increment)
    }

    @Override
    public int totalPartitions() {
        return uuidToRank.size();
    }

    @Override
    public Set<Integer> activeRanks() {
        // Return immutable snapshot
        return new HashSet<>(rankToUuid.keySet());
    }

    @Override
    public long topologyVersion() {
        return version.get();
    }
}
