package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for SimpleFaultHandler.escalate() — verifies that N concurrent
 * reportBarrierTimeout calls on a HEALTHY partition produce exactly one
 * HEALTHY→SUSPECTED transition and then exactly one SUSPECTED→FAILED transition
 * (no duplicate/lost escalation steps).
 *
 * <p>Without the atomic escalate() fix the TOCTOU window allows:
 * <ul>
 *   <li>Multiple threads each seeing HEALTHY and each transitioning to SUSPECTED
 *       (emitting multiple HEALTHY→SUSPECTED events).</li>
 *   <li>Multiple threads each seeing SUSPECTED and each transitioning to FAILED
 *       (skipping the single SUSPECTED→FAILED step or emitting it multiple times).</li>
 * </ul>
 */
class SimpleFaultHandlerConcurrencyTest {

    private static final int THREAD_COUNT = 20;
    private static final int REPETITIONS = 5;

    private SimpleFaultHandler handler;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        handler.start();
        executor = Executors.newFixedThreadPool(THREAD_COUNT);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        handler.stop();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * N concurrent reportBarrierTimeout calls on a single HEALTHY partition must
     * produce exactly ONE HEALTHY→SUSPECTED event and (once all callers have fired)
     * ONE SUSPECTED→FAILED event — no duplicates, no skips.
     *
     * Repeated REPETITIONS times to surface sporadic races.
     */
    @RepeatedTest(REPETITIONS)
    void concurrentReportBarrierTimeout_exactlyOneHealthySuspectedAndOneSuspectedFailed()
            throws InterruptedException {
        var partitionId = UUID.randomUUID();

        var healthyToSuspected = new AtomicInteger(0);
        var suspectedToFailed = new AtomicInteger(0);
        var otherTransitions = new AtomicInteger(0);

        handler.subscribeToChanges(event -> {
            if (event.partitionId().equals(partitionId)) {
                if (event.oldStatus() == PartitionStatus.HEALTHY
                        && event.newStatus() == PartitionStatus.SUSPECTED) {
                    healthyToSuspected.incrementAndGet();
                } else if (event.oldStatus() == PartitionStatus.SUSPECTED
                        && event.newStatus() == PartitionStatus.FAILED) {
                    suspectedToFailed.incrementAndGet();
                } else {
                    otherTransitions.incrementAndGet();
                }
            }
        });

        // Fire THREAD_COUNT concurrent reports — enough to drive HEALTHY→SUSPECTED→FAILED.
        var ready = new CountDownLatch(THREAD_COUNT);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await(); // synchronized start maximizes contention
                    handler.reportBarrierTimeout(partitionId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("All threads should complete within 10 s")
                .isTrue();

        // Final status must be FAILED.
        assertThat(handler.checkHealth(partitionId))
                .as("Partition must end in FAILED state")
                .isEqualTo(PartitionStatus.FAILED);

        // Exactly one transition of each kind — no duplicates, no skips.
        assertThat(healthyToSuspected.get())
                .as("Exactly one HEALTHY→SUSPECTED transition")
                .isEqualTo(1);
        assertThat(suspectedToFailed.get())
                .as("Exactly one SUSPECTED→FAILED transition")
                .isEqualTo(1);
        assertThat(otherTransitions.get())
                .as("No unexpected transitions")
                .isZero();
    }

    /**
     * N concurrent reportSyncFailure calls exhibit the same atomic-escalation guarantee.
     */
    @RepeatedTest(REPETITIONS)
    void concurrentReportSyncFailure_exactlyOneHealthySuspectedAndOneSuspectedFailed()
            throws InterruptedException {
        var partitionId = UUID.randomUUID();
        var healthyToSuspected = new AtomicInteger(0);
        var suspectedToFailed = new AtomicInteger(0);

        handler.subscribeToChanges(event -> {
            if (event.partitionId().equals(partitionId)) {
                if (event.oldStatus() == PartitionStatus.HEALTHY
                        && event.newStatus() == PartitionStatus.SUSPECTED) {
                    healthyToSuspected.incrementAndGet();
                } else if (event.oldStatus() == PartitionStatus.SUSPECTED
                        && event.newStatus() == PartitionStatus.FAILED) {
                    suspectedToFailed.incrementAndGet();
                }
            }
        });

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(THREAD_COUNT);
        var ready = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    handler.reportSyncFailure(partitionId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(handler.checkHealth(partitionId)).isEqualTo(PartitionStatus.FAILED);
        assertThat(healthyToSuspected.get()).as("Exactly one HEALTHY→SUSPECTED").isEqualTo(1);
        assertThat(suspectedToFailed.get()).as("Exactly one SUSPECTED→FAILED").isEqualTo(1);
    }

    /**
     * N concurrent reportHeartbeatFailure calls exhibit the same guarantee.
     */
    @RepeatedTest(REPETITIONS)
    void concurrentReportHeartbeatFailure_exactlyOneHealthySuspectedAndOneSuspectedFailed()
            throws InterruptedException {
        var partitionId = UUID.randomUUID();
        var nodeId = UUID.randomUUID();
        var healthyToSuspected = new AtomicInteger(0);
        var suspectedToFailed = new AtomicInteger(0);

        handler.subscribeToChanges(event -> {
            if (event.partitionId().equals(partitionId)) {
                if (event.oldStatus() == PartitionStatus.HEALTHY
                        && event.newStatus() == PartitionStatus.SUSPECTED) {
                    healthyToSuspected.incrementAndGet();
                } else if (event.oldStatus() == PartitionStatus.SUSPECTED
                        && event.newStatus() == PartitionStatus.FAILED) {
                    suspectedToFailed.incrementAndGet();
                }
            }
        });

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(THREAD_COUNT);
        var ready = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    handler.reportHeartbeatFailure(partitionId, nodeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(handler.checkHealth(partitionId)).isEqualTo(PartitionStatus.FAILED);
        assertThat(healthyToSuspected.get()).as("Exactly one HEALTHY→SUSPECTED").isEqualTo(1);
        assertThat(suspectedToFailed.get()).as("Exactly one SUSPECTED→FAILED").isEqualTo(1);
    }

    /**
     * markHealthy resets a FAILED partition back to HEALTHY; subsequent concurrent
     * escalation must again produce exactly one HEALTHY→SUSPECTED and one SUSPECTED→FAILED.
     */
    @Test
    void afterMarkHealthy_concurrentEscalationStillProducesExactlyOneProgressionPerCycle()
            throws InterruptedException {
        var partitionId = UUID.randomUUID();

        // Drive to FAILED first (sequential, deterministic)
        handler.reportBarrierTimeout(partitionId);  // HEALTHY→SUSPECTED
        handler.reportBarrierTimeout(partitionId);  // SUSPECTED→FAILED

        assertThat(handler.checkHealth(partitionId)).isEqualTo(PartitionStatus.FAILED);

        // Reset
        handler.markHealthy(partitionId);
        assertThat(handler.checkHealth(partitionId)).isEqualTo(PartitionStatus.HEALTHY);

        // Now race again
        var healthyToSuspected = new AtomicInteger(0);
        var suspectedToFailed = new AtomicInteger(0);
        handler.subscribeToChanges(event -> {
            if (event.partitionId().equals(partitionId)) {
                if (event.oldStatus() == PartitionStatus.HEALTHY
                        && event.newStatus() == PartitionStatus.SUSPECTED) {
                    healthyToSuspected.incrementAndGet();
                } else if (event.oldStatus() == PartitionStatus.SUSPECTED
                        && event.newStatus() == PartitionStatus.FAILED) {
                    suspectedToFailed.incrementAndGet();
                }
            }
        });

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(THREAD_COUNT);
        var ready = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    handler.reportBarrierTimeout(partitionId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(handler.checkHealth(partitionId)).isEqualTo(PartitionStatus.FAILED);
        assertThat(healthyToSuspected.get()).as("Exactly one HEALTHY→SUSPECTED after reset").isEqualTo(1);
        assertThat(suspectedToFailed.get()).as("Exactly one SUSPECTED→FAILED after reset").isEqualTo(1);
    }
}
