package com.hellblazer.luciferase.simulation.von;

import javafx.geometry.Point3D;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * VON JOIN Protocol implementation.
 * <p>
 * Enables new bubbles to join the VON (Voronoi Overlay Network) overlay.
 * The protocol follows these steps:
 * <ol>
 *   <li>Contact entry point (any Fireflies member)</li>
 *   <li>Route to acceptor (closest bubble to join position)</li>
 *   <li>Receive neighbor list from acceptor</li>
 *   <li>Establish sync with neighbors</li>
 *   <li>Notify neighbors of new bubble</li>
 * </ol>
 * <p>
 * Key Architectural Points:
 * - Uses spatial index for routing (NO Voronoi)
 * - Greedy forwarding to closest bubble
 * - Enclosing neighbors via bounds overlap
 * - Performance target: JOIN latency < 100ms
 * <p>
 * Thread-safe: Delegates to thread-safe SpatialNeighborIndex.
 *
 * @author hal.hildebrand
 */
public class VONJoinProtocol {

    private final SpatialNeighborIndex index;
    private final Consumer<VONEvent> eventEmitter;

    /**
     * Create a JOIN protocol handler.
     *
     * @param index        Spatial neighbor index
     * @param eventEmitter Callback for VON events
     */
    public VONJoinProtocol(SpatialNeighborIndex index, Consumer<VONEvent> eventEmitter) {
        this.index = Objects.requireNonNull(index, "index cannot be null");
        this.eventEmitter = Objects.requireNonNull(eventEmitter, "eventEmitter cannot be null");
    }

    /**
     * Execute JOIN protocol for a new bubble.
     * <p>
     * Steps:
     * 1. Find acceptor (closest existing bubble)
     * 2. Get neighbor list from acceptor
     * 3. Add neighbors to joiner
     * 4. Notify neighbors of new joiner
     * 5. Add joiner to index
     * 6. Emit JOIN event
     *
     * @param joiner   Bubble joining the overlay
     * @param position Join position (bubble centroid)
     */
    public void join(VONNode joiner, Point3D position) {
        Objects.requireNonNull(joiner, "joiner cannot be null");
        Objects.requireNonNull(position, "position cannot be null");

        // Check if already in index (idempotent)
        if (index.get(joiner.id()) != null) {
            return;  // Already joined
        }

        // Step 1: Route to acceptor (closest bubble)
        VONNode acceptor = findAcceptor(joiner, position);

        // Step 2: Get neighbor list from acceptor
        Set<VONNode> neighbors = getNeighborList(joiner, acceptor);

        // Step 3 & 4: Establish bidirectional neighbor relationships
        for (VONNode neighbor : neighbors) {
            // Add neighbor to joiner's list
            joiner.addNeighbor(neighbor.id());

            // Notify neighbor of new joiner
            neighbor.notifyJoin(joiner);

            // Add joiner to neighbor's list
            neighbor.addNeighbor(joiner.id());
        }

        // Step 5: Add joiner to index
        index.insert(joiner);

        // Step 6: Emit JOIN event
        eventEmitter.accept(new VONEvent.Join(joiner.id(), position));
    }

    /**
     * Find acceptor bubble for joiner.
     * <p>
     * Uses greedy routing to find closest bubble to join position.
     * If index is empty, joiner becomes the first (solo) bubble.
     *
     * @param joiner   Joining bubble
     * @param position Join position
     * @return Acceptor bubble (closest to position) or joiner if solo
     */
    private VONNode findAcceptor(VONNode joiner, Point3D position) {
        // Find closest bubble to join position
        VONNode closest = index.findClosestTo(position);

        // If index is empty, joiner is the first (solo) bubble
        return closest != null ? closest : joiner;
    }

    /**
     * Get neighbor list for joiner from acceptor.
     * <p>
     * Returns bubbles whose bounds overlap with joiner's bounds.
     * This replaces Voronoi "enclosing neighbors" calculation.
     *
     * @param joiner   Joining bubble
     * @param acceptor Acceptor bubble
     * @return Set of neighbors (overlapping bounds)
     */
    private Set<VONNode> getNeighborList(VONNode joiner, VONNode acceptor) {
        // If joiner is solo (acceptor == joiner), return empty set
        if (acceptor.id().equals(joiner.id())) {
            return Set.of();
        }

        // Find all bubbles with overlapping bounds
        return index.findOverlapping(joiner.bounds());
    }

    @Override
    public String toString() {
        return String.format("VONJoinProtocol{indexSize=%d}", index.size());
    }
}
