package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import javafx.geometry.Point3D;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Adapter wrapping EnhancedBubble to implement VON Node interface.
 * <p>
 * This adapter bridges the bubble lifecycle (Phase 1) with the VON discovery
 * protocol (Phase 2). It delegates spatial operations to the wrapped bubble
 * while emitting VON-specific events for protocol coordination.
 * <p>
 * Key Architectural Points:
 * - Bubbles ARE VON nodes (no separate entity)
 * - Position = tetrahedral centroid of bubble bounds
 * - Neighbors = bubbles with overlapping bounds OR within AOI radius
 * - Event emission for protocol coordination
 * <p>
 * Thread-safe: EnhancedBubble provides thread-safety guarantees.
 *
 * @author hal.hildebrand
 */
public class BubbleNode implements Node {

    private final EnhancedBubble bubble;
    private final Consumer<Event> eventEmitter;

    /**
     * Create a VON node adapter for an EnhancedBubble.
     *
     * @param bubble       Underlying bubble
     * @param eventEmitter Callback for VON events
     */
    public BubbleNode(EnhancedBubble bubble, Consumer<Event> eventEmitter) {
        this.bubble = Objects.requireNonNull(bubble, "bubble cannot be null");
        this.eventEmitter = Objects.requireNonNull(eventEmitter, "eventEmitter cannot be null");
    }

    @Override
    public UUID id() {
        return bubble.id();
    }

    @Override
    public Point3D position() {
        return bubble.centroid();
    }

    @Override
    public BubbleBounds bounds() {
        return bubble.bounds();
    }

    @Override
    public Set<UUID> neighbors() {
        return bubble.getVonNeighbors();
    }

    @Override
    public void notifyMove(Node neighbor) {
        Objects.requireNonNull(neighbor, "neighbor cannot be null");

        // Emit MOVE event for this neighbor
        eventEmitter.accept(new Event.Move(neighbor.id(), neighbor.position()));

        // TODO: Check if neighbor is still in AOI radius
        // TODO: Remove neighbor if out of range
        // This will be implemented in VONMoveProtocol (Task 4)
    }

    @Override
    public void notifyLeave(Node neighbor) {
        Objects.requireNonNull(neighbor, "neighbor cannot be null");

        // Remove neighbor from this bubble's neighbor list
        removeNeighbor(neighbor.id());

        // Emit LEAVE event
        eventEmitter.accept(new Event.Leave(neighbor.id()));
    }

    @Override
    public void notifyJoin(Node neighbor) {
        Objects.requireNonNull(neighbor, "neighbor cannot be null");

        // Add neighbor to this bubble's neighbor list
        addNeighbor(neighbor.id());

        // Emit JOIN event
        eventEmitter.accept(new Event.Join(neighbor.id(), neighbor.position()));
    }

    @Override
    public void addNeighbor(UUID neighborId) {
        bubble.addVonNeighbor(neighborId);
    }

    @Override
    public void removeNeighbor(UUID neighborId) {
        bubble.removeVonNeighbor(neighborId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BubbleNode that)) return false;

        // Equality based on bubble ID, not wrapper instance
        return bubble.id().equals(that.bubble.id());
    }

    @Override
    public int hashCode() {
        return bubble.id().hashCode();
    }

    @Override
    public String toString() {
        return String.format("BubbleNode{id=%s, position=%s, neighbors=%d}",
                           bubble.id(), position(), neighbors().size());
    }
}
