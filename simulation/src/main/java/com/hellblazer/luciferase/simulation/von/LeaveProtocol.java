package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.*;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * VON LEAVE Protocol implementation.
 * <p>
 * Handles graceful bubble shutdown and crash detection in the VON overlay.
 * The protocol follows these steps:
 * <ol>
 *   <li>Notify all neighbors of departure</li>
 *   <li>Neighbors update their neighbor lists (remove leaver)</li>
 *   <li>Remove leaver from spatial index</li>
 *   <li>Emit LEAVE event</li>
 * </ol>
 * <p>
 * Crash Detection:
 * - Timeout-based detection by neighbors
 * - Treat crash as forced leave
 * - Emit CRASH event with detector ID
 * <p>
 * Thread-safe: Delegates to thread-safe SpatialNeighborIndex.
 *
 * @author hal.hildebrand
 */
public class LeaveProtocol {

    private final SpatialNeighborIndex index;
    private final Consumer<Event> eventEmitter;

    /**
     * Create a LEAVE protocol handler.
     *
     * @param index        Spatial neighbor index
     * @param eventEmitter Callback for VON events
     */
    public LeaveProtocol(SpatialNeighborIndex index, Consumer<Event> eventEmitter) {
        this.index = Objects.requireNonNull(index, "index cannot be null");
        this.eventEmitter = Objects.requireNonNull(eventEmitter, "eventEmitter cannot be null");
    }

    /**
     * Execute LEAVE protocol for graceful bubble shutdown.
     * <p>
     * Steps:
     * 1. Notify all neighbors of departure
     * 2. Neighbors remove leaver from their lists
     * 3. Remove from index
     * 4. Emit LEAVE event
     *
     * @param leaver Bubble that is leaving
     */
    public void leave(Node leaver) {
        Objects.requireNonNull(leaver, "leaver cannot be null");

        // Step 1: Notify all neighbors of departure
        for (UUID neighborId : leaver.neighbors()) {
            Node neighbor = index.get(neighborId);
            if (neighbor != null) {
                // Notify neighbor we're leaving
                neighbor.notifyLeave(leaver);

                // Neighbor removes us from their list
                neighbor.removeNeighbor(leaver.id());
            }
        }

        // Step 2: Remove from index
        index.remove(leaver.id());

        // Step 3: Emit LEAVE event
        eventEmitter.accept(new Event.Leave(leaver.id()));
    }

    /**
     * Handle crash detection (timeout-based).
     * <p>
     * When a neighbor detects that a node has crashed (via timeout),
     * treat it as a forced leave and emit CRASH event.
     *
     * @param crashedId  Node that crashed
     * @param detectedBy Node that detected the crash
     */
    public void handleCrash(UUID crashedId, UUID detectedBy) {
        Objects.requireNonNull(crashedId, "crashedId cannot be null");
        Objects.requireNonNull(detectedBy, "detectedBy cannot be null");

        Node crashed = index.get(crashedId);
        if (crashed != null) {
            // Treat crash as forced leave
            leave(crashed);

            // Emit CRASH event (in addition to LEAVE event from leave())
            eventEmitter.accept(new Event.Crash(crashedId, detectedBy));
        }
    }

    @Override
    public String toString() {
        return String.format("LeaveProtocol{indexSize=%d}", index.size());
    }
}
