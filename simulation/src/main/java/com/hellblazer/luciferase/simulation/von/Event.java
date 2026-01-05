package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.*;

import javafx.geometry.Point3D;

import java.util.UUID;

/**
 * Sealed interface for VON (Voronoi Overlay Network) lifecycle events.
 * <p>
 * Events represent state changes in the VON overlay:
 * - Move: Node position update notification
 * - Join: New node joining the overlay
 * - Leave: Node departing gracefully
 * - Crash: Node crash detected
 * <p>
 * All events are immutable records for thread-safety.
 *
 * @author hal.hildebrand
 */
public sealed interface Event {

    /**
     * Get the node ID associated with this event.
     *
     * @return Node UUID
     */
    UUID nodeId();

    /**
     * Get the event type for logging/debugging.
     *
     * @return Event type name
     */
    default String eventType() {
        return this.getClass().getSimpleName();
    }

    /**
     * VON MOVE event: Node position updated.
     * <p>
     * Triggered when:
     * - Bubble centroid changes due to entity movement
     * - Bounds expand/contract significantly
     * - Position update exceeds notification threshold
     * <p>
     * Protocol:
     * 1. Node broadcasts new position to all neighbors
     * 2. Neighbors check if node is still in AOI
     * 3. Boundary neighbors trigger discovery
     * 4. Out-of-range neighbors removed
     *
     * @param nodeId      Node that moved
     * @param newPosition New position (bubble centroid)
     */
    record Move(
        UUID nodeId,
        Point3D newPosition
    ) implements Event {

        /**
         * Calculate distance moved from old position.
         *
         * @param oldPosition Previous position
         * @return Distance moved
         */
        public double distance(Point3D oldPosition) {
            return newPosition.distance(oldPosition);
        }
    }

    /**
     * VON JOIN event: New node joining overlay.
     * <p>
     * Triggered when:
     * - New bubble enters simulation
     * - Node reconnects after crash
     * <p>
     * Protocol:
     * 1. Contact entry point (any Fireflies member)
     * 2. Route to acceptor (closest bubble)
     * 3. Receive neighbor list from acceptor
     * 4. Establish sync with neighbors
     *
     * @param nodeId   Node joining
     * @param position Initial position
     */
    record Join(
        UUID nodeId,
        Point3D position
    ) implements Event {
    }

    /**
     * VON LEAVE event: Node departing gracefully.
     * <p>
     * Triggered when:
     * - Bubble merges into another
     * - Simulation shutdown
     * - Graceful server shutdown
     * <p>
     * Protocol:
     * 1. Notify all neighbors of departure
     * 2. Neighbors remove from neighbor lists
     * 3. Node removed from index
     *
     * @param nodeId Node leaving
     */
    record Leave(
        UUID nodeId
    ) implements Event {
    }

    /**
     * VON CRASH event: Node crash detected.
     * <p>
     * Triggered when:
     * - Timeout on heartbeat
     * - Connection loss
     * - Server failure
     * <p>
     * Response:
     * 1. Neighbors detect timeout
     * 2. Treat as LEAVE
     * 3. Log crash for debugging
     *
     * @param nodeId      Node that crashed
     * @param detectedBy  Node that detected crash
     */
    record Crash(
        UUID nodeId,
        UUID detectedBy
    ) implements Event {
    }

    /**
     * Boundary crossing event: Node crossed boundary threshold.
     * <p>
     * Triggers new neighbor discovery via k-nearest neighbors.
     *
     * @param nodeId       Node that crossed boundary
     * @param newNeighbors New neighbors discovered
     */
    record BoundaryCrossing(
        UUID nodeId,
        int newNeighbors
    ) implements Event {
    }
}
