package com.hellblazer.luciferase.simulation.animation;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for EnhancedVolumeAnimator, focusing on thread-safety of frameCount (AtomicLong).
 * <p>
 * Bead: Luciferase-7wzml.191 — frameCount was a plain long with non-atomic ++ in tick();
 * fix: AtomicLong.incrementAndGet() ensures no lost updates under concurrent access.
 */
class EnhancedVolumeAnimatorTest {

    private EnhancedBubble bubble;
    private RealTimeController controller;
    private EnhancedVolumeAnimator animator;

    @BeforeEach
    void setUp() {
        var bubbleId = UUID.randomUUID();
        controller = new RealTimeController(bubbleId, "test", 100);
        bubble = new EnhancedBubble(bubbleId, (byte) 10, 16L, controller);
        animator = new EnhancedVolumeAnimator(bubble, controller);
    }

    @AfterEach
    void tearDown() {
        if (controller.isRunning()) {
            controller.stop();
        }
    }

    /**
     * Single-threaded sanity: N ticks produce frameCount == N.
     */
    @Test
    void frameCountIncrementsSingleThreaded() {
        int ticks = 250;
        for (int i = 0; i < ticks; i++) {
            animator.tick();
        }
        assertEquals(ticks, animator.getFrameCount(),
            "Single-threaded tick count must equal number of tick() calls");
    }

    /**
     * Concurrency test: N threads each call tick() K times; total must equal N*K.
     * A plain long with ++ would produce lost updates and fail this assertion.
     */
    @Test
    void frameCountNoLostUpdatesUnderConcurrentTick() throws InterruptedException {
        int threads = 8;
        int ticksPerThread = 1000;
        int expected = threads * ticksPerThread;

        var latch = new CountDownLatch(1);
        var futures = new ArrayList<Thread>(threads);

        for (int t = 0; t < threads; t++) {
            var thread = new Thread(() -> {
                try {
                    latch.await(); // All threads start simultaneously
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < ticksPerThread; i++) {
                    animator.tick();
                }
            });
            thread.start();
            futures.add(thread);
        }

        latch.countDown(); // Release all threads at once

        for (var t : futures) {
            t.join(5000);
        }

        assertEquals(expected, animator.getFrameCount(),
            "AtomicLong must record every tick with no lost updates across " + threads + " concurrent threads");
    }

    /**
     * Reader thread sees a monotonically non-decreasing frameCount while writer threads tick.
     * Validates visibility guarantee of AtomicLong.get().
     */
    @Test
    void frameCountVisibleToReaderThread() throws InterruptedException {
        int ticksTotal = 500;
        var writerDone = new CountDownLatch(1);
        var readerErrors = new ArrayList<String>();

        // Reader thread: continuously reads frameCount; value must never decrease
        var reader = new Thread(() -> {
            long prev = 0;
            while (writerDone.getCount() > 0 || animator.getFrameCount() < ticksTotal) {
                long current = animator.getFrameCount();
                if (current < prev) {
                    readerErrors.add("frameCount went backward: " + prev + " -> " + current);
                }
                prev = current;
                // Brief yield to interleave with writer
                Thread.yield();
            }
        });
        reader.setDaemon(true);
        reader.start();

        // Writer: advance all ticks
        for (int i = 0; i < ticksTotal; i++) {
            animator.tick();
        }
        writerDone.countDown();
        reader.join(2000);

        assertEquals(ticksTotal, animator.getFrameCount(),
            "Final frameCount must equal total ticks");
        assertEquals(0, readerErrors.size(),
            "Reader saw non-monotonic frameCount: " + readerErrors);
    }
}
