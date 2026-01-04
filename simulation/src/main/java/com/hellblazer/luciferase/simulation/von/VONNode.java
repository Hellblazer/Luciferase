package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.BubbleBounds;
import javafx.geometry.Point3D;

import java.util.Set;
import java.util.UUID;

/**
 * Interface for VON (Voronoi Overlay Network) nodes.
 * <p>
 * In the Distributed Animation Architecture v4.0, bubbles ARE VON nodes.
 * This interface defines the contract for nodes participating in the
 * spatial overlay network used for neighbor discovery and synchronization.
 * <p>
 * Key Architectural Point:
 * - Uses Tetree k-nearest neighbors INSTEAD OF Voronoi diagram calculation
 * - Neighbor discovery via spatial index queries (EnhancedBubble.kNearestNeighbors)
 * - Bounds overlap detection replaces Voronoi enclosing neighbor test
 * <p>
 * Thread-safe: Implementations must support concurrent access.
 *
 * @author hal.hildebrand
 */
public interface VONNode {

    /**
     * Get the unique identifier for this VON node.
     *
     * @return Node UUID (same as bubble ID)
     */
    UUID id();

    /**
     * Get the current position of this node.
     * <p>
     * For bubbles, this is the tetrahedral centroid of bounds
     * computed from entity positions.
     *
     * @return Current position (bubble centroid)
     */
    Point3D position();

    /**
     * Get the spatial bounds of this node.
     * <p>
     * Uses TetreeKey + RDGCS coordinates (NOT AABB).
     *
     * @return Tetrahedral bounds
     */
    BubbleBounds bounds();

    /**
     * Get the set of known VON neighbors.
     * <p>
     * Neighbors are bubbles whose bounds overlap or are within AOI radius.
     *
     * @return Immutable set of neighbor UUIDs
     */
    Set<UUID> neighbors();

    /**
     * Notify this node that a neighbor has moved.
     * <p>
     * Triggered when neighbor's position changes. This node should:
     * 1. Update internal tracking of neighbor position
     * 2. Check if neighbor is still in AOI
     * 3. Remove neighbor if out of range
     *
     * @param neighbor Neighbor that moved
     */
    void notifyMove(VONNode neighbor);

    /**
     * Notify this node that a neighbor is leaving.
     * <p>
     * Triggered when neighbor is shutting down gracefully. This node should:
     * 1. Remove neighbor from neighbor list
     * 2. Emit VONEvent.Leave
     *
     * @param neighbor Neighbor that is leaving
     */
    void notifyLeave(VONNode neighbor);

    /**
     * Notify this node that a new neighbor has joined.
     * <p>
     * Triggered when new node enters this node's AOI. This node should:
     * 1. Add neighbor to neighbor list
     * 2. Emit VONEvent.Join
     *
     * @param neighbor New neighbor
     */
    void notifyJoin(VONNode neighbor);

    /**
     * Add a neighbor to this node's neighbor set.
     * <p>
     * Idempotent - adding the same neighbor twice has no effect.
     *
     * @param neighborId Neighbor UUID to add
     */
    void addNeighbor(UUID neighborId);

    /**
     * Remove a neighbor from this node's neighbor set.
     * <p>
     * Idempotent - removing a non-existent neighbor has no effect.
     *
     * @param neighborId Neighbor UUID to remove
     */
    void removeNeighbor(UUID neighborId);
}
