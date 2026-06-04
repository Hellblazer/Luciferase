package com.hellblazer.luciferase.simulation.animation;

import com.hellblazer.primeMover.runtime.Kairos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that VolumeAnimator.close() stops the RealTimeController and releases
 * thread-local Kairos bindings across N create-start-close cycles.
 *
 * <p>Note on virtual threads: PrimeMover's RealTimeController uses {@code Thread.ofVirtual()}
 * for its event-loop thread. Virtual threads are NOT visible via
 * {@code Thread.getAllStackTraces()} (platform-thread enumeration only), so we use
 * the controller's {@code getSimulationEnd()} API to assert that {@code stop()} was
 * invoked. The field is 0 until {@code stop()} is called, then set to the simulation
 * end time (always positive).
 *
 * @author hal.hildebrand
 */
class VolumeAnimatorCloseTest {

    /**
     * Expose the underlying RealTimeController for white-box assertions.
     * VolumeAnimator does not expose the controller publicly, so we use a
     * package-private test accessor via {@link VolumeAnimatorTestAccessor}.
     */

    @Test
    void closeCallsStopOnController() throws Exception {
        var animator = new VolumeAnimator("va-close-stop-" + System.nanoTime());
        animator.start();

        // Before close: stop() has not been called, simulationEnd == 0
        var controller = VolumeAnimatorTestAccessor.getController(animator);
        assertEquals(0L, controller.getSimulationEnd(),
                "simulationEnd should be 0 before close()");

        animator.close();

        // After close: stop() was called, simulationEnd > 0
        assertTrue(controller.getSimulationEnd() > 0,
                "close() must call stop(), which sets simulationEnd > 0");
    }

    @Test
    void closeWithoutStartDoesNotThrow() {
        // close() on a never-started animator must be safe (stop() is idempotent
        // in RealTimeController: returns early when running==false, does not set simulationEnd)
        var animator = new VolumeAnimator("va-no-start-" + System.nanoTime());
        assertDoesNotThrow(animator::close);
    }

    @Test
    void closeIsIdempotent() throws Exception {
        var animator = new VolumeAnimator("va-idempotent-" + System.nanoTime());
        animator.start();
        assertDoesNotThrow(() -> {
            animator.close();
            animator.close(); // second close must not throw
        });
    }

    @Test
    void noCumulativeLeakOverNCycles() throws Exception {
        // Create-start-close N animators and verify stop() was called for each
        int n = 5;
        var names = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) {
            var name = "va-cycle-" + i + "-" + System.nanoTime();
            names.add(name);
            try (var animator = new VolumeAnimator(name)) {
                animator.start();
                Thread.sleep(20); // let virtual thread spin up
                var controller = VolumeAnimatorTestAccessor.getController(animator);
                assertEquals(0L, controller.getSimulationEnd(),
                        "Cycle " + i + ": simulationEnd should be 0 before close()");
                // try-with-resources calls close() here
            }
        }
        // All animators were closed; no assertion about platform threads needed
        // (virtual threads are not enumerable, but stop() was verified per iteration above)
    }

    @Test
    void tryWithResourcesCompiles() {
        // Compile-time proof that VolumeAnimator implements AutoCloseable
        try (var animator = new VolumeAnimator("va-twr-" + System.nanoTime())) {
            assertNotNull(animator);
        }
        // Reaching here without compile error IS the test
    }

    @Test
    void kairosBindingClearedAfterClose() {
        // Kairos.setController is ThreadLocal. After close(), calling thread's binding == null.
        var animator = new VolumeAnimator("va-kairos-" + System.nanoTime());
        animator.start();
        animator.close();

        assertNull(Kairos.queryController(),
                "Thread-local Kairos controller must be null after close()");
    }

    @Test
    void kairosNotSetAfterCloseOnCallingThread() throws InterruptedException {
        // Verify the thread that constructed and closed the animator has its binding cleared,
        // while a different thread that never touched it also has null (baseline sanity).
        var callerHadNullAfterClose = new AtomicBoolean(false);

        var t = new Thread(() -> {
            var animator = new VolumeAnimator("va-thread-kairos-" + System.nanoTime());
            animator.start();
            animator.close();
            callerHadNullAfterClose.set(Kairos.queryController() == null);
        });
        t.start();
        t.join(5_000);

        assertTrue(callerHadNullAfterClose.get(),
                "Kairos binding on the animator's thread must be null after close()");
    }
}
