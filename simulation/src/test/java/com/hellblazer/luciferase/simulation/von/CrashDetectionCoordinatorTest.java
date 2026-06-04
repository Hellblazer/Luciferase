/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.125: crash detection must have a production call path.
 * {@link LeaveProtocol#handleCrash} was a dead API — nothing wired Fireflies view-change notifications
 * to it, so a crashed neighbor stayed permanently stale in the VON neighbor set. {@link
 * CrashDetectionCoordinator} closes the gap by listening for member departures on a {@link
 * FirefliesViewMonitor} and dispatching {@code handleCrash} for each departed node.
 *
 * @author hal.hildebrand
 */
class CrashDetectionCoordinatorTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 10;
    private static final float AOI_RADIUS = 50.0f;
    private static final float BOUNDARY_BUFFER = 10.0f;

    private SpatialNeighborIndex index;
    private LeaveProtocol leaveProtocol;
    private List<Event> capturedEvents;
    private FakeMembershipView membershipView;
    private FirefliesViewMonitor viewMonitor;

    @BeforeEach
    void setup() {
        index = new SpatialNeighborIndex(AOI_RADIUS, BOUNDARY_BUFFER);
        capturedEvents = new ArrayList<>();
        leaveProtocol = new LeaveProtocol(index, capturedEvents::add);
        membershipView = new FakeMembershipView();
        viewMonitor = new FirefliesViewMonitor(membershipView);
    }

    @Test
    void viewChangeDeparturesTriggerCrashHandling() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var detector = UUID.randomUUID();
            try (var coordinator = new CrashDetectionCoordinator<UUID>(
                    viewMonitor, leaveProtocol, detector, java.util.function.Function.identity())) {

                // A crashed neighbor present in the index.
                var crashed = createBubble(new Point3f(50, 50, 50), 5);
                var crashedNode = new BubbleNode(crashed, e -> {});
                index.insert(crashedNode);

                // A surviving neighbor that knows about the crashed node.
                var survivor = createBubble(new Point3f(60, 60, 60), 5);
                var survivorNode = new BubbleNode(survivor, e -> {});
                index.insert(survivorNode);
                survivorNode.addNeighbor(crashedNode.id());
                crashedNode.addNeighbor(survivorNode.id());

                capturedEvents.clear();

                // Fireflies reports the crashed node leaving the view.
                membershipView.fireViewChange(List.of(), List.of(crashedNode.id()));

                // handleCrash() was invoked: crashed node removed from index, CRASH event emitted.
                assertNull(index.get(crashedNode.id()),
                           "crashed node must be removed from the index via handleCrash()");
                assertFalse(survivorNode.neighbors().contains(crashedNode.id()),
                            "survivor must drop the crashed node from its neighbor set");
                assertTrue(capturedEvents.stream().anyMatch(
                                   e -> e instanceof Event.Crash c && c.nodeId().equals(crashedNode.id())),
                           "a CRASH event must be emitted for the departed node (Luciferase-0frcy.125)");
            }
        });
    }

    @Test
    void selfDepartureIsIgnored() {
        var self = UUID.randomUUID();
        try (var coordinator = new CrashDetectionCoordinator<UUID>(
                viewMonitor, leaveProtocol, self, java.util.function.Function.identity())) {
            membershipView.fireViewChange(List.of(), List.of(self));
            assertTrue(capturedEvents.isEmpty(), "self departure must not be reported as a crash");
        }
    }

    private EnhancedBubble createBubble(Point3f center, int entityCount) {
        var bubble = new EnhancedBubble(UUID.randomUUID(), SPATIAL_LEVEL, TARGET_FRAME_MS);
        var content = new Object();
        for (int i = 0; i < entityCount; i++) {
            float x = Math.max(1.0f, center.x + (i % 10) * 0.1f);
            float y = Math.max(1.0f, center.y + (i / 10) * 0.1f);
            float z = Math.max(1.0f, center.z);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }
        return bubble;
    }

    /** Minimal MembershipView test double keyed on node UUIDs. */
    private static final class FakeMembershipView implements MembershipView<UUID> {
        private final List<Consumer<ViewChange<UUID>>> listeners = new ArrayList<>();
        private final List<UUID> members = new ArrayList<>();

        @Override
        public Stream<UUID> getMembers() {
            return members.stream();
        }

        @Override
        public Stream<UUID> activeMembers() {
            // Test double: active == all (intentional equivalence; no active/all distinction exercised).
            return members.stream();
        }

        @Override
        public void addListener(Consumer<ViewChange<UUID>> listener) {
            listeners.add(listener);
        }

        void fireViewChange(List<UUID> joined, List<UUID> left) {
            members.addAll(joined);
            members.removeAll(left);
            var change = new ViewChange<>(joined, left);
            listeners.forEach(l -> l.accept(change));
        }
    }
}
