package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3d;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for Luciferase-0frcy.128/.129: MoveProtocol.move() previously
 * routed every current-neighbor notification through an if/else where both branches
 * called the identical {@code neighbor.notifyMove(mover)}. The dead branch was removed;
 * this test pins the observable contract — every current neighbor (boundary-zone or
 * regular) receives exactly one notifyMove call.
 *
 * @author hal.hildebrand
 */
public class MoveProtocolBoundaryNotificationTest {

    private static final float AOI_RADIUS = 50.0f;
    private static final float BOUNDARY_BUFFER = 10.0f;

    /**
     * A minimal Node that counts notifyMove invocations. Position is fixed at
     * construction so we control whether it lands in the boundary zone relative
     * to the mover's new position.
     */
    private static final class CountingNode implements Node {
        private final UUID id = UUID.randomUUID();
        private final Point3d position;
        private final Set<UUID> neighbors = new HashSet<>();
        final AtomicInteger moveNotifications = new AtomicInteger();

        CountingNode(Point3d position) {
            this.position = position;
        }

        @Override public UUID id() { return id; }
        @Override public Point3d position() { return position; }
        @Override public BubbleBounds bounds() { return null; }
        @Override public Set<UUID> neighbors() { return neighbors; }
        @Override public void notifyMove(Node neighbor) { moveNotifications.incrementAndGet(); }
        @Override public void notifyLeave(Node neighbor) { }
        @Override public void notifyJoin(Node neighbor) { }
        @Override public void addNeighbor(UUID neighborId) { neighbors.add(neighborId); }
        @Override public void removeNeighbor(UUID neighborId) { neighbors.remove(neighborId); }
    }

    @Test
    public void boundaryAndRegularNeighborsEachGetExactlyOneNotification() {
        var index = new SpatialNeighborIndex(AOI_RADIUS, BOUNDARY_BUFFER);
        var moveProtocol = new MoveProtocol(index, e -> {}, AOI_RADIUS);

        var mover = new CountingNode(new Point3d(50.0, 50.0, 50.0));
        // Regular neighbor: well within AOI of the new position.
        var regular = new CountingNode(new Point3d(55.0, 55.0, 55.0));
        // Boundary-zone neighbor: AOI < dist <= AOI + buffer from the new position.
        var boundary = new CountingNode(new Point3d(105.0, 50.0, 50.0));

        index.insert(mover);
        index.insert(regular);
        index.insert(boundary);

        mover.addNeighbor(regular.id());
        mover.addNeighbor(boundary.id());

        // New position keeps both neighbors as current neighbors of the mover.
        moveProtocol.move(mover, new Point3d(50.0, 50.0, 50.0));

        assertEquals(1, regular.moveNotifications.get(),
                     "Regular neighbor should receive exactly one notifyMove");
        assertEquals(1, boundary.moveNotifications.get(),
                     "Boundary-zone neighbor should receive exactly one notifyMove");
    }
}
