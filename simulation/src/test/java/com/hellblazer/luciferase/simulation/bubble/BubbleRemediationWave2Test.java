package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.common.time.Clock;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the wave-2 simulation deep-review remediation beads on the bubble package:
 * Luciferase-0frcy.6 (BubbleLifecycle merged-bubble config), .7 (CubeForest.classifyPoint),
 * .8 (RealTimeController self-stop deadlock).
 */
class BubbleRemediationWave2Test {

    // ---- Luciferase-0frcy.7: CubeForest.classifyPoint canonical t8code ordering ----

    @Test
    void classifyPointMatchesCanonicalT8codeOrdering() {
        var forest = new CubeForest(0f, 1f, (byte) 0, 10L);

        // (0.8, 0.2, 0.3): x>=z>=y -> type 0 (old ad-hoc algorithm wrongly returned 4)
        assertEquals(0, forest.classifyPoint(new Point3f(0.8f, 0.2f, 0.3f)));

        // One representative interior point per canonical type ordering:
        assertEquals(0, forest.classifyPoint(new Point3f(0.9f, 0.1f, 0.5f))); // x>=z>=y
        assertEquals(1, forest.classifyPoint(new Point3f(0.9f, 0.5f, 0.1f))); // x>=y>=z
        assertEquals(2, forest.classifyPoint(new Point3f(0.5f, 0.9f, 0.1f))); // y>=x>=z
        assertEquals(3, forest.classifyPoint(new Point3f(0.1f, 0.9f, 0.5f))); // y>=z>=x
        assertEquals(4, forest.classifyPoint(new Point3f(0.1f, 0.5f, 0.9f))); // z>=y>=x
        assertEquals(5, forest.classifyPoint(new Point3f(0.5f, 0.1f, 0.9f))); // z>=x>=y
    }

    // ---- Luciferase-0frcy.6: merged bubble inherits source config + injectable UUID ----

    @Test
    void performJoinUsesSourceConfigAndInjectedUuid() {
        var lifecycle = new BubbleLifecycle(e -> { });
        lifecycle.setClock(Clock.fixed(1234L));
        var mergedId = UUID.randomUUID();
        lifecycle.setUuidSupplier(() -> mergedId);

        // level-8, 5ms  vs  level-12, 20ms
        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 8, 5L);
        var b2 = new EnhancedBubble(UUID.randomUUID(), (byte) 12, 20L);

        var merged = lifecycle.performJoin(b1, b2);

        assertEquals(mergedId, merged.id(), "merged id must come from injected supplier");
        assertEquals((byte) 8, merged.getSpatialLevel(), "merged level = finer (min) of sources, not hardcoded 10");
        assertEquals(20L, merged.getTargetFrameMs(), "merged frame budget = larger of sources, not hardcoded 10");
    }

    // ---- Luciferase-0frcy.8: stop() invoked from a TickListener must not deadlock ----

    @Test
    void stopFromTickListenerDoesNotDeadlock() throws Exception {
        var controller = new RealTimeController(UUID.randomUUID(), "selfstop", 100);
        var stopped = new AtomicBoolean(false);
        var done = new CountDownLatch(1);

        controller.addTickListener((simTime, lamport) -> {
            if (stopped.compareAndSet(false, true)) {
                controller.stop();   // self-stop from the tick thread: must not join itself
                done.countDown();
            }
        });

        controller.start();
        // If stop() tried to join its own thread, this would hang until the latch timeout.
        assertTrue(done.await(5, TimeUnit.SECONDS), "tick listener self-stop must complete promptly");

        // The loop must actually terminate.
        Thread.sleep(100);
        assertFalse(controller.isRunning(), "controller must be stopped after self-stop");
    }
}
