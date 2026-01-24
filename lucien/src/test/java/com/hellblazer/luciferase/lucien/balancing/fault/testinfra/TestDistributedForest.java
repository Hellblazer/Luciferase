package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for distributed forest in integration tests.
 * <p>
 * Provides minimal distributed forest functionality for testing without
 * requiring full spatial index infrastructure.
 */
public class TestDistributedForest {

    private final int totalPartitions;
    private final Map<UUID, Boolean> partitionHealth;

    /**
     * Create test distributed forest.
     *
     * @param totalPartitions number of partitions
     * @throws IllegalArgumentException if totalPartitions <= 0
     */
    public TestDistributedForest(int totalPartitions) {
        if (totalPartitions <= 0) {
            throw new IllegalArgumentException("totalPartitions must be positive");
        }
        this.totalPartitions = totalPartitions;
        this.partitionHealth = new ConcurrentHashMap<>();

        // Initialize all partitions as healthy
        for (var i = 0; i < totalPartitions; i++) {
            partitionHealth.put(UUID.randomUUID(), true);
        }
    }

    /**
     * Get total number of partitions.
     *
     * @return partition count
     */
    public int getTotalPartitions() {
        return totalPartitions;
    }

    /**
     * Get all partition IDs.
     *
     * @return set of partition UUIDs
     */
    public Set<UUID> getPartitionIds() {
        return Set.copyOf(partitionHealth.keySet());
    }

    /**
     * Get all partition IDs as ordered list.
     * <p>
     * Order is deterministic but arbitrary (based on internal map ordering).
     *
     * @return list of partition UUIDs
     */
    public List<UUID> getPartitionIdsAsList() {
        return new java.util.ArrayList<>(partitionHealth.keySet());
    }

    /**
     * Get active (healthy) partition IDs.
     *
     * @return set of healthy partition UUIDs
     */
    public Set<UUID> getActivePartitions() {
        return partitionHealth.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Check if partition is healthy.
     *
     * @param partitionId partition UUID
     * @return true if healthy, false otherwise
     */
    public boolean isPartitionHealthy(UUID partitionId) {
        return partitionHealth.getOrDefault(partitionId, false);
    }

    /**
     * Mark partition as failed.
     *
     * @param partitionId partition UUID
     */
    public void markPartitionFailed(UUID partitionId) {
        partitionHealth.put(partitionId, false);
    }

    /**
     * Mark partition as healthy.
     *
     * @param partitionId partition UUID
     */
    public void markPartitionHealthy(UUID partitionId) {
        partitionHealth.put(partitionId, true);
    }
}
