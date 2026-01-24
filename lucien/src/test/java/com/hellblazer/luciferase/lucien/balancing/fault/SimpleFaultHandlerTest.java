package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SimpleFaultHandler implementation.
 * <p>
 * Inherits contract tests from FaultHandlerContractTest and adds
 * implementation-specific tests for SimpleFaultHandler.
 */
class SimpleFaultHandlerTest extends FaultHandlerContractTest {

    @Override
    protected FaultHandler createFaultHandler(FaultConfiguration config) {
        return new SimpleFaultHandler(config);
    }

    @Test
    void testConcurrentStatusUpdates() throws Exception {
        handler.start();
        var partition = UUID.randomUUID();
        handler.markHealthy(partition);

        var latch = new CountDownLatch(10);
        var errors = new ArrayList<Exception>();

        // Launch 10 threads updating status concurrently
        for (int i = 0; i < 10; i++) {
            var threadNum = i;
            new Thread(() -> {
                try {
                    if (threadNum % 2 == 0) {
                        handler.reportBarrierTimeout(partition);
                    } else {
                        handler.reportSyncFailure(partition);
                    }
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();

        // Final status should be consistent (SUSPECTED or FAILED)
        var status = handler.checkHealth(partition);
        assertThat(status).isIn(PartitionStatus.SUSPECTED, PartitionStatus.FAILED);
    }

    @Test
    void testEventOrdering_Sequential() throws Exception {
        handler.start();
        var partition = UUID.randomUUID();
        var events = new ArrayList<PartitionChangeEvent>();
        var latch = new CountDownLatch(3);

        handler.subscribeToChanges(event -> {
            synchronized (events) {
                events.add(event);
            }
            latch.countDown();
        });

        // Trigger sequence: HEALTHY -> SUSPECTED -> FAILED
        handler.markHealthy(partition);
        Thread.sleep(50);
        handler.reportBarrierTimeout(partition);
        Thread.sleep(50);
        handler.reportSyncFailure(partition);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        synchronized (events) {
            assertThat(events).hasSizeGreaterThanOrEqualTo(2);
            // First event should transition to HEALTHY
            assertThat(events.get(0).newStatus()).isEqualTo(PartitionStatus.HEALTHY);
            // Second event should transition to SUSPECTED or FAILED
            assertThat(events.get(1).newStatus()).isIn(PartitionStatus.SUSPECTED, PartitionStatus.FAILED);
        }
    }

    @Test
    void testMetricsAccumulation_FailureCount() {
        handler.start();
        var partition = UUID.randomUUID();
        handler.markHealthy(partition);

        // Report multiple failures
        handler.reportBarrierTimeout(partition);
        handler.reportSyncFailure(partition);

        var metrics = handler.getMetrics(partition);
        assertThat(metrics).isNotNull();
        // At least one failure should be counted
        assertThat(metrics.failureCount()).isGreaterThan(0);
    }

    @Test
    void testMetricsAccumulation_RecoveryAttempts() {
        handler.start();
        var partition = UUID.randomUUID();
        handler.markHealthy(partition);

        // Transition to FAILED
        handler.reportBarrierTimeout(partition);

        // Register recovery
        var recovery = new TestRecovery();
        handler.registerRecovery(partition, recovery);

        // Initiate recovery
        handler.initiateRecovery(partition);

        var metrics = handler.getMetrics(partition);
        assertThat(metrics).isNotNull();
        assertThat(metrics.recoveryAttempts()).isGreaterThan(0);
    }

    @Test
    void testMultiplePartitions_IndependentState() throws Exception {
        handler.start();
        var p1 = UUID.randomUUID();
        var p2 = UUID.randomUUID();

        handler.markHealthy(p1);
        handler.markHealthy(p2);

        // Fail only p1
        handler.reportBarrierTimeout(p1);
        Thread.sleep(100);

        var status1 = handler.checkHealth(p1);
        var status2 = handler.checkHealth(p2);

        assertThat(status1).isEqualTo(PartitionStatus.SUSPECTED);
        assertThat(status2).isEqualTo(PartitionStatus.HEALTHY);
    }

    @Test
    void testRecoveryCoordination_Success() throws Exception {
        handler.start();
        var partition = UUID.randomUUID();
        handler.markHealthy(partition);

        // Transition to FAILED
        handler.reportBarrierTimeout(partition);
        Thread.sleep(100);

        var recovery = new TestRecovery(true);
        handler.registerRecovery(partition, recovery);

        var latch = new CountDownLatch(1);
        handler.subscribeToChanges(event -> {
            if (event.partitionId().equals(partition) &&
                event.newStatus() == PartitionStatus.HEALTHY) {
                latch.countDown();
            }
        });

        var result = handler.initiateRecovery(partition);
        handler.notifyRecoveryComplete(partition, true);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        var status = handler.checkHealth(partition);
        assertThat(status).isEqualTo(PartitionStatus.HEALTHY);
    }

    @Test
    void testRecoveryCoordination_Failure() throws Exception {
        handler.start();
        var partition = UUID.randomUUID();
        handler.markHealthy(partition);

        // Transition to FAILED
        handler.reportBarrierTimeout(partition);
        Thread.sleep(100);

        var recovery = new TestRecovery(false);
        handler.registerRecovery(partition, recovery);

        var latch = new CountDownLatch(1);
        handler.subscribeToChanges(event -> {
            if (event.partitionId().equals(partition) &&
                event.newStatus() == PartitionStatus.FAILED) {
                latch.countDown();
            }
        });

        handler.initiateRecovery(partition);
        handler.notifyRecoveryComplete(partition, false);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        var status = handler.checkHealth(partition);
        assertThat(status).isEqualTo(PartitionStatus.FAILED);
    }

    @Test
    void testAggregateMetrics_MultiplePartitions() {
        handler.start();
        var p1 = UUID.randomUUID();
        var p2 = UUID.randomUUID();
        var p3 = UUID.randomUUID();

        handler.markHealthy(p1);
        handler.markHealthy(p2);
        handler.markHealthy(p3);

        // Fail p1 and p2
        handler.reportBarrierTimeout(p1);
        handler.reportSyncFailure(p2);

        var aggregate = handler.getAggregateMetrics();
        assertThat(aggregate).isNotNull();
        assertThat(aggregate.failureCount()).isGreaterThan(0);
    }

    @Test
    void testSubscriptionIsolation_ExceptionInListener() throws Exception {
        handler.start();
        var partition = UUID.randomUUID();

        var goodLatch = new CountDownLatch(1);
        var goodEvents = new ArrayList<PartitionChangeEvent>();

        // Faulty listener that throws exception
        handler.subscribeToChanges(event -> {
            throw new RuntimeException("Intentional test exception");
        });

        // Good listener that should still receive events
        handler.subscribeToChanges(event -> {
            goodEvents.add(event);
            goodLatch.countDown();
        });

        handler.markHealthy(partition);

        assertThat(goodLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(goodEvents).hasSize(1);
    }

    @Test
    void testAutoCloseable_SubscriptionWithTryWithResources() throws Exception {
        handler.start();
        var partition = UUID.randomUUID();
        var events = new ArrayList<PartitionChangeEvent>();
        var latch1 = new CountDownLatch(1);
        var latch2 = new CountDownLatch(1);

        try (var subscription = handler.subscribeToChanges(event -> {
            events.add(event);
            latch1.countDown();
        })) {
            handler.markHealthy(partition);
            assertThat(latch1.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(events).hasSize(1);
        } // Auto-unsubscribe here

        handler.subscribeToChanges(event -> latch2.countDown());
        handler.reportBarrierTimeout(partition);
        assertThat(latch2.await(1, TimeUnit.SECONDS)).isTrue();

        // Original subscription should not have received the second event
        assertThat(events).hasSize(1);
    }

    @Test
    void testStopCancelsSubscriptions() throws Exception {
        handler.start();
        var partition = UUID.randomUUID();
        var counter = new java.util.concurrent.atomic.AtomicInteger(0);

        handler.subscribeToChanges(event -> counter.incrementAndGet());

        handler.markHealthy(partition);
        Thread.sleep(100);
        assertThat(counter.get()).isEqualTo(1);

        handler.stop();

        // After stop, no more events should be delivered
        handler.markHealthy(UUID.randomUUID());
        Thread.sleep(100);
        assertThat(counter.get()).isEqualTo(1);
    }

    /**
     * Test implementation of PartitionRecovery for testing purposes.
     */
    private static class TestRecovery implements PartitionRecovery {
        private final boolean willSucceed;

        TestRecovery() {
            this(true);
        }

        TestRecovery(boolean willSucceed) {
            this.willSucceed = willSucceed;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> initiateRecovery(UUID failedPartitionId) {
            return java.util.concurrent.CompletableFuture.completedFuture(willSucceed);
        }

        @Override
        public java.util.concurrent.CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
            var result = willSucceed
                ? RecoveryResult.success(partitionId, 0, "test-recovery", 1)
                : RecoveryResult.failure(partitionId, 0, "test-recovery", 1, "Test failure", null);
            return java.util.concurrent.CompletableFuture.completedFuture(result);
        }

        @Override
        public boolean canRecover(UUID partitionId, FaultHandler handler) {
            return true;
        }

        @Override
        public String getStrategyName() {
            return "test-recovery";
        }

        @Override
        public FaultConfiguration getConfiguration() {
            return FaultConfiguration.defaultConfig();
        }
    }
}
