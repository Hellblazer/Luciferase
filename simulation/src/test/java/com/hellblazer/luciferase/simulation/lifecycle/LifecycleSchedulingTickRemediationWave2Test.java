package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.config.SimulationMetrics;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleCoordinate;
import com.hellblazer.luciferase.simulation.distributed.grid.BubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.scheduling.BucketScheduler;
import com.hellblazer.luciferase.simulation.tick.SimulationTickOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-2 remediation beads on the lifecycle/scheduling/tick slice:
 * Luciferase-0frcy.27 (stop() of a STARTING component), .28 (legacy BucketScheduler.toString NPE),
 * .29 (null bubble passed to entity updater aborts the whole tick).
 */
class LifecycleSchedulingTickRemediationWave2Test {

    // ---- Luciferase-0frcy.27: stop() must cancel a STARTING component, not reject it ----

    /** Adapter whose doStart() blocks until released, holding the component in STARTING. */
    static final class BlockingAdapter extends AbstractLifecycleAdapter {
        final CountDownLatch enteredStart = new CountDownLatch(1);
        final CountDownLatch releaseStart = new CountDownLatch(1);
        final AtomicBoolean stopped = new AtomicBoolean(false);

        @Override protected String getComponentName() { return "blocking"; }

        @Override protected void doStart() throws Exception {
            enteredStart.countDown();
            releaseStart.await(5, TimeUnit.SECONDS);
        }

        @Override protected void doStop() { stopped.set(true); }

        @Override public String name() { return "blocking"; }

        @Override public java.util.List<String> dependencies() { return java.util.List.of(); }
    }

    @Test
    void stopOfStartingComponentCancelsRatherThanRejects() throws Exception {
        var adapter = new BlockingAdapter();

        var startFuture = adapter.start();
        assertTrue(adapter.enteredStart.await(5, TimeUnit.SECONDS), "start must reach doStart()");
        assertEquals(LifecycleState.STARTING, adapter.getState(), "component must be STARTING");

        // Stop while STARTING: pre-fix this threw "Cannot stop from state: STARTING"; post-fix it
        // cancels the start and settles in STOPPED.
        var stopFuture = adapter.stop();
        adapter.releaseStart.countDown(); // let the in-flight doStart() unwind
        stopFuture.get(5, TimeUnit.SECONDS); // must not complete exceptionally

        assertEquals(LifecycleState.STOPPED, adapter.getState(),
                     "STARTING component must end STOPPED after stop(), not leak in STARTING");
        assertTrue(adapter.stopped.get(), "doStop() must run best-effort cleanup during cancellation");
    }

    // ---- Luciferase-0frcy M2: doStop() must run at most once under a concurrent start()+stop()
    //      race on a STARTING component (no double-close in subclasses) ----

    /**
     * Adapter that counts doStop() invocations. doStart() parks on a barrier so the test can line
     * up the in-flight start() (about to attempt its STARTING->RUNNING CAS) against a concurrent
     * stop() (attempting STARTING->STOPPING), maximally exercising the race window.
     */
    static final class CountingAdapter extends AbstractLifecycleAdapter {
        final java.util.concurrent.CyclicBarrier raceBarrier = new java.util.concurrent.CyclicBarrier(2);
        final AtomicInteger startReached = new AtomicInteger(0);
        final AtomicInteger doStopCount = new AtomicInteger(0);

        @Override protected String getComponentName() { return "counting"; }

        @Override protected void doStart() throws Exception {
            startReached.incrementAndGet();
            // Rendezvous with the test thread, which then fires stop() and releases the barrier so
            // the start()-completion CAS and stop()'s CAS race.
            try {
                raceBarrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override protected void doStop() { doStopCount.incrementAndGet(); }

        @Override public String name() { return "counting"; }

        @Override public java.util.List<String> dependencies() { return java.util.List.of(); }
    }

    @Test
    void concurrentStartStopRaceInvokesDoStopExactlyOnce() throws Exception {
        var adapter = new CountingAdapter();

        var startFuture = adapter.start();
        assertTrue(waitFor(() -> adapter.startReached.get() == 1, 5000),
                   "start must reach doStart() and park in STARTING");
        assertEquals(LifecycleState.STARTING, adapter.getState());

        // Launch stop() concurrently; it will contend with the in-flight start() for the transition
        // out of STARTING.
        var stopFuture = adapter.stop();

        // Release the barrier so doStart() returns and start() races to RUNNING against stop().
        adapter.raceBarrier.await(5, TimeUnit.SECONDS);

        startFuture.get(5, TimeUnit.SECONDS);
        stopFuture.get(5, TimeUnit.SECONDS);

        // Regardless of who won the CAS, the component must settle STOPPED and doStop() must have
        // run exactly once — pre-fix, start()'s else-branch also called doStop() when stop() owned
        // cleanup, double-closing subclass resources.
        assertEquals(LifecycleState.STOPPED, adapter.getState(),
                     "component must settle STOPPED after a concurrent start+stop race");
        assertEquals(1, adapter.doStopCount.get(),
                     "doStop() must be invoked exactly once for one start/stop cycle");
    }

    private static boolean waitFor(java.util.function.BooleanSupplier cond, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(2);
        }
        return cond.getAsBoolean();
    }

    // ---- Luciferase-0frcy.28: legacy BucketScheduler.toString() must not NPE ----

    @Test
    void legacyBucketSchedulerToStringDoesNotNpe() {
        // Legacy 4-arg constructor sets entity=null and controller=null.
        var scheduler = new BucketScheduler<StringEntityID, String>(null, null, t -> { }, t -> { });

        assertDoesNotThrow(scheduler::toString, "legacy toString() must guard the null entity");
        assertDoesNotThrow(scheduler::getCurrentBucket);
        assertEquals(0L, scheduler.getCurrentBucket());
    }

    // ---- Luciferase-0frcy.29: a null grid cell must not abort the whole tick ----

    @Test
    void nullBubbleCellDoesNotAbortTickForOtherBubbles() {
        TestClock clock = new TestClock(1000L);
        var metrics = new SimulationMetrics();
        var behavior = new FlockingBehavior();
        var gridConfig = GridConfiguration.of(2, 2, 100f, 100f);
        BubbleGrid<EnhancedBubble> grid = BubbleGrid.createEmpty(gridConfig);

        // Populate 3 of 4 cells; (1,1) is left null (empty cell).
        int populated = 0;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                if (row == 1 && col == 1) {
                    continue;
                }
                grid.setBubble(new BubbleCoordinate(row, col),
                               new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L));
                populated++;
            }
        }

        var updated = new AtomicInteger(0);
        var sawNull = new AtomicBoolean(false);
        var orchestrator = SimulationTickOrchestrator.create(
            clock, gridConfig, grid, behavior, metrics,
            (bubble, dt) -> {
                if (bubble == null) {
                    sawNull.set(true);
                }
                updated.incrementAndGet();
            });

        orchestrator.executeTick();

        assertFalse(sawNull.get(), "updater must never receive a null bubble");
        assertEquals(populated, updated.get(),
                     "every populated cell must be visited even though one cell is empty");
        assertEquals(1L, orchestrator.getTickCount(),
                     "tick must complete (increment) rather than abort on the null cell");
    }
}
