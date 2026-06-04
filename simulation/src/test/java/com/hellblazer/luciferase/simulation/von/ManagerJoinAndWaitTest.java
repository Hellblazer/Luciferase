/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.von;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.126: {@code Manager.joinAndWait()} counts down its latch on a
 * self {@code Event.Join} (where {@code join.nodeId() == joiner.id()}). Before the fix, the joiner's
 * {@code handleJoinResponse()} never emitted that event, so the latch only fired via a racy
 * neighbors-non-empty fallback. The fix emits {@code Event.Join(id(), position())} from
 * {@code handleJoinResponse()} once neighbors are populated. This test asserts the self-Join is emitted
 * and that joinAndWait completes successfully.
 *
 * @author hal.hildebrand
 */
class ManagerJoinAndWaitTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;
    private static final float AOI_RADIUS = 50.0f;

    private LocalServerTransport.Registry registry;
    private Manager manager;

    @BeforeEach
    void setup() {
        registry = LocalServerTransport.Registry.create();
        manager = new Manager(registry, SPATIAL_LEVEL, TARGET_FRAME_MS, AOI_RADIUS);
    }

    @AfterEach
    void cleanup() {
        if (manager != null) manager.close();
        if (registry != null) registry.close();
    }

    @Test
    void joinerEmitsSelfJoinAndJoinAndWaitSucceeds() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // First bubble joins solo.
            var bubble1 = manager.createBubble();
            addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
            assertTrue(manager.joinAt(bubble1, bubble1.position()));

            // Second bubble: listen for its OWN Event.Join (the latch condition in joinAndWait).
            var bubble2 = manager.createBubble();
            addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);

            var selfJoinSeen = new AtomicBoolean(false);
            var selfJoinLatch = new CountDownLatch(1);
            bubble2.addEventListener(event -> {
                if (event instanceof Event.Join join && join.nodeId().equals(bubble2.id())) {
                    selfJoinSeen.set(true);
                    selfJoinLatch.countDown();
                }
            });

            boolean joined = manager.joinAndWait(bubble2, bubble2.position(), 5000);

            assertTrue(joined, "joinAndWait must complete successfully");
            assertTrue(selfJoinLatch.await(2, TimeUnit.SECONDS),
                       "joiner must emit a self Event.Join from handleJoinResponse (Luciferase-0frcy.126)");
            assertTrue(selfJoinSeen.get(), "self Event.Join must carry the joiner's own node id");
            assertFalse(bubble2.neighbors().isEmpty(), "joiner should have established neighbors");
        });
    }

    private void addEntities(Bubble bubble, Point3f center, int count) {
        var content = new Object();
        for (int i = 0; i < count; i++) {
            float x = Math.max(1.0f, center.x + (i % 10) * 0.1f);
            float y = Math.max(1.0f, center.y + (i / 10) * 0.1f);
            float z = Math.max(1.0f, center.z);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), content);
        }
    }
}
