package com.hellblazer.luciferase.simulation.bubble;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks VON (Voronoi Overlay Network) neighbors for distributed bubble discovery.
 * Thread-safe via ConcurrentHashMap.newKeySet().
 *
 * @author hal.hildebrand
 */
public class BubbleVonCoordinator {

    private final Set<UUID> vonNeighbors;

    /**
     * Create a VON coordinator with an empty neighbor set.
     */
    public BubbleVonCoordinator() {
        this.vonNeighbors = ConcurrentHashMap.newKeySet();
    }

    /**
     * Get the set of VON neighbors.
     *
     * @return Set of neighbor bubble UUIDs
     */
    public Set<UUID> getVonNeighbors() {
        return vonNeighbors;
    }

    /**
     * Add a VON neighbor.
     *
     * @param neighborId Neighbor bubble UUID
     */
    public void addVonNeighbor(UUID neighborId) {
        vonNeighbors.add(neighborId);
    }

    /**
     * Remove a VON neighbor.
     *
     * @param neighborId Neighbor bubble UUID to remove
     */
    public void removeVonNeighbor(UUID neighborId) {
        vonNeighbors.remove(neighborId);
    }
}
