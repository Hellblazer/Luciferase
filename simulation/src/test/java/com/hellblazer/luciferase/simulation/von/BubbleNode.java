package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import javafx.geometry.Point3D;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Test utility adapter wrapping EnhancedBubble to implement VON Node interface.
 * <p>
 * <strong>NOTE: This is a TEST-ONLY utility.</strong> Production code should use
 * {@link Bubble} which extends EnhancedBubble and implements Node directly with
 * full P2P transport integration.
 * <p>
 * This lightweight adapter is provided for unit tests that need to test VON protocol
 * logic without requiring Transport infrastructure. It wraps an existing EnhancedBubble
 * and provides a simple event emission callback for test assertions.
 * <p>
 * <strong>Migration Path:</strong> Tests using BubbleNode should eventually migrate to
 * using Bubble with MockTransport to test production code paths.
 * <p>
 * Thread-safe: EnhancedBubble provides thread-safety guarantees.
 *
 * @author hal.hildebrand
 * @deprecated Test-only utility. Use {@link Bubble} for production code.
 */
@Deprecated(since = "v4.0", forRemoval = true)
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
        eventEmitter.accept(new Event.Move(neighbor.id(), neighbor.position(), neighbor.bounds()));

        // Note: AOI radius checking should be implemented in production Bubble class if needed
    }

    @Override
    public void notifyLeave(Node neighbor) {
        Objects.requireNonNull(neighbor, "neighbor cannot be null");

        // Remove neighbor from this bubble's neighbor list
        removeNeighbor(neighbor.id());

        // Emit LEAVE event
        eventEmitter.accept(new Event.Leave(neighbor.id(), neighbor.position()));
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
