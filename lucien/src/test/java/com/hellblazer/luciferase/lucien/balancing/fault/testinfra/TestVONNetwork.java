package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for VON overlay network in integration tests.
 * <p>
 * Provides minimal VON network functionality for testing without
 * requiring full VON infrastructure.
 */
public class TestVONNetwork {

    private final int nodeCount;
    private final Map<UUID, Set<UUID>> topology;

    /**
     * Create test VON network.
     *
     * @param nodeCount number of nodes
     * @throws IllegalArgumentException if nodeCount <= 0
     */
    public TestVONNetwork(int nodeCount) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("nodeCount must be positive");
        }
        this.nodeCount = nodeCount;
        this.topology = new ConcurrentHashMap<>();

        // Initialize nodes with empty neighbor sets
        for (var i = 0; i < nodeCount; i++) {
            topology.put(UUID.randomUUID(), ConcurrentHashMap.newKeySet());
        }
    }

    /**
     * Get number of nodes in network.
     *
     * @return node count
     */
    public int getNodeCount() {
        return nodeCount;
    }

    /**
     * Get all node IDs.
     *
     * @return set of node UUIDs
     */
    public Set<UUID> getNodeIds() {
        return Set.copyOf(topology.keySet());
    }

    /**
     * Get neighbors for a node.
     *
     * @param nodeId node UUID
     * @return set of neighbor UUIDs (empty if node not found)
     */
    public Set<UUID> getNeighbors(UUID nodeId) {
        var neighbors = topology.get(nodeId);
        if (neighbors == null) {
            return Set.of();
        }
        return Set.copyOf(neighbors);
    }

    /**
     * Add neighbor relationship.
     *
     * @param nodeId node UUID
     * @param neighborId neighbor UUID
     */
    public void addNeighbor(UUID nodeId, UUID neighborId) {
        topology.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet())
                .add(neighborId);
    }

    /**
     * Remove neighbor relationship.
     *
     * @param nodeId node UUID
     * @param neighborId neighbor UUID
     */
    public void removeNeighbor(UUID nodeId, UUID neighborId) {
        var neighbors = topology.get(nodeId);
        if (neighbors != null) {
            neighbors.remove(neighborId);
        }
    }
}
