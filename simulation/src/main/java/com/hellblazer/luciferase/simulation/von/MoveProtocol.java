package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.*;

import javax.vecmath.Point3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * VON MOVE Protocol implementation.
 * <p>
 * Handles bubble position updates in the VON (Voronoi Overlay Network) overlay.
 * The protocol follows these steps:
 * <ol>
 *   <li>Update position in spatial index</li>
 *   <li>Notify all current neighbors of new position</li>
 *   <li>Check for boundary crossing (AOI threshold)</li>
 *   <li>Discover new neighbors via k-NN query</li>
 *   <li>Drop neighbors outside AOI + buffer range</li>
 * </ol>
 * <p>
 * Key Architectural Points:
 * - Uses spatial index for neighbor discovery (NO Voronoi)
 * - k-nearest neighbors for new neighbor discovery
 * - AOI radius + buffer for boundary detection
 * - Performance target: MOVE notification < 50ms
 * <p>
 * Thread-safe: Delegates to thread-safe SpatialNeighborIndex.
 *
 * @author hal.hildebrand
 */
public class MoveProtocol {

    private final SpatialNeighborIndex index;
    private final Consumer<Event> eventEmitter;
    private final float aoiRadius;

    /**
     * Create a MOVE protocol handler.
     *
     * @param index        Spatial neighbor index
     * @param eventEmitter Callback for VON events
     * @param aoiRadius    Area of Interest radius
     */
    public MoveProtocol(SpatialNeighborIndex index, Consumer<Event> eventEmitter, float aoiRadius) {
        this.index = Objects.requireNonNull(index, "index cannot be null");
        this.eventEmitter = Objects.requireNonNull(eventEmitter, "eventEmitter cannot be null");
        this.aoiRadius = aoiRadius;
    }

    /**
     * Execute MOVE protocol for a bubble's position update.
     * <p>
     * Steps:
     * 1. Update position in index
     * 2. Notify all current neighbors
     * 3. Check for boundary crossing
     * 4. Discover new neighbors via k-NN
     * 5. Drop out-of-range neighbors
     * 6. Emit MOVE event
     *
     * @param mover       Bubble that is moving
     * @param newPosition New position (centroid)
     */
    public void move(Node mover, Point3d newPosition) {
        Objects.requireNonNull(mover, "mover cannot be null");
        Objects.requireNonNull(newPosition, "newPosition cannot be null");

        // Step 1: Update position in index
        index.updatePosition(mover.id(), newPosition);

        // Step 2: Notify all current neighbors of position change
        List<UUID> currentNeighbors = new ArrayList<>(mover.neighbors());
        for (UUID neighborId : currentNeighbors) {
            Node neighbor = index.get(neighborId);
            if (neighbor != null) {
                // All current neighbors receive the same move notification. Boundary-
                // differentiated handling is not implemented; if it is ever required it
                // belongs in notifyMove() or a dedicated notifyBoundaryMove() method.
                neighbor.notifyMove(mover);
            }
        }

        // Step 3 & 4: Check for boundary crossing and discover new neighbors
        // Use k-NN to find potential new neighbors
        List<Node> potentialNeighbors = index.findKNearest(newPosition, 10);

        for (Node candidate : potentialNeighbors) {
            // Skip self
            if (candidate.id().equals(mover.id())) {
                continue;
            }

            // Add new neighbors within AOI that we don't already have
            if (isInAOI(newPosition, candidate.position()) && !mover.neighbors().contains(candidate.id())) {
                mover.addNeighbor(candidate.id());
                candidate.addNeighbor(mover.id());
                candidate.notifyJoin(mover);
            }
        }

        // Step 5: Drop neighbors outside AOI + buffer range
        List<UUID> toRemove = new ArrayList<>();
        for (UUID neighborId : mover.neighbors()) {
            Node neighbor = index.get(neighborId);
            if (neighbor != null) {
                // Check if neighbor is too far away (beyond AOI + buffer)
                double dist = distance(newPosition, neighbor.position());
                float maxDistance = aoiRadius + 10.0f; // AOI + buffer

                if (dist > maxDistance) {
                    toRemove.add(neighborId);
                    // Notify neighbor we're leaving
                    neighbor.removeNeighbor(mover.id());
                }
            }
        }

        // Remove out-of-range neighbors
        for (UUID neighborId : toRemove) {
            mover.removeNeighbor(neighborId);
        }

        // Step 6: Emit MOVE event
        eventEmitter.accept(new Event.Move(mover.id(), newPosition, mover.bounds()));
    }

    /**
     * Check if target position is within AOI radius of source position.
     *
     * @param source Source position
     * @param target Target position
     * @return true if within AOI
     */
    private boolean isInAOI(Point3d source, Point3d target) {
        return distance(source, target) <= aoiRadius;
    }

    /**
     * Calculate Euclidean distance between two points.
     *
     * @param p1 First point
     * @param p2 Second point
     * @return Distance
     */
    private double distance(Point3d p1, Point3d p2) {
        return p1.distance(p2);
    }

    @Override
    public String toString() {
        return String.format("MoveProtocol{indexSize=%d, aoiRadius=%.2f}",
                           index.size(), aoiRadius);
    }
}
