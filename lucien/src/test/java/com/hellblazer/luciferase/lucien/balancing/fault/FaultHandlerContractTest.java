package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract tests for FaultHandler interface.
 * <p>
 * These tests define the behavior all FaultHandler implementations must exhibit.
 * Concrete implementations should extend this test class to inherit contract verification.
 */
public abstract class FaultHandlerContractTest {

    protected FaultHandler handler;
    protected FaultConfiguration config;
    protected UUID partition1;
    protected UUID partition2;
    protected UUID partition3;

    /**
     * Subclasses must provide a FaultHandler instance to test.
     */
    protected abstract FaultHandler createFaultHandler(FaultConfiguration config);

    @BeforeEach
    void setUp() {
        config = FaultConfiguration.defaultConfig();
        handler = createFaultHandler(config);
        partition1 = UUID.randomUUID();
        partition2 = UUID.randomUUID();
        partition3 = UUID.randomUUID();
    }

    @Test
    void testLifecycle_StartAndStop() {
        assertThat(handler.isRunning()).isFalse();

        handler.start();
        assertThat(handler.isRunning()).isTrue();

        handler.stop();
        assertThat(handler.isRunning()).isFalse();
    }

    @Test
    void testLifecycle_DoubleStartIsIdempotent() {
        handler.start();
        handler.start(); // Should not throw
        assertThat(handler.isRunning()).isTrue();
    }

    @Test
    void testLifecycle_DoubleStopIsIdempotent() {
        handler.start();
        handler.stop();
        handler.stop(); // Should not throw
        assertThat(handler.isRunning()).isFalse();
    }

    @Test
    void testCheckHealth_UnknownPartitionReturnsNull() {
        handler.start();
        var unknownId = UUID.randomUUID();
        var status = handler.checkHealth(unknownId);
        assertThat(status).isNull();
    }

    @Test
    void testMarkHealthy_TransitionsToHealthy() {
        handler.start();
        handler.markHealthy(partition1);

        var status = handler.checkHealth(partition1);
        assertThat(status).isEqualTo(PartitionStatus.HEALTHY);
    }

    @Test
    void testGetPartitionView_ReturnsNullForUnknownPartition() {
        handler.start();
        var unknownId = UUID.randomUUID();
        var view = handler.getPartitionView(unknownId);
        assertThat(view).isNull();
    }

    @Test
    void testGetPartitionView_ReturnsViewForKnownPartition() {
        handler.start();
        handler.markHealthy(partition1);

        var view = handler.getPartitionView(partition1);
        assertThat(view).isNotNull();
        assertThat(view.partitionId()).isEqualTo(partition1);
        assertThat(view.status()).isEqualTo(PartitionStatus.HEALTHY);
    }

    @Test
    void testSubscribeToChanges_ReceivesEvents() throws Exception {
        handler.start();
        var latch = new CountDownLatch(1);
        var events = new ArrayList<PartitionChangeEvent>();

        handler.subscribeToChanges(event -> {
            events.add(event);
            latch.countDown();
        });

        handler.markHealthy(partition1);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(events).hasSize(1);
        var event = events.get(0);
        assertThat(event.partitionId()).isEqualTo(partition1);
        assertThat(event.newStatus()).isEqualTo(PartitionStatus.HEALTHY);
    }

    @Test
    void testSubscribeToChanges_MultipleSubscribers() throws Exception {
        handler.start();
        var latch1 = new CountDownLatch(1);
        var latch2 = new CountDownLatch(1);
        var events1 = new ArrayList<PartitionChangeEvent>();
        var events2 = new ArrayList<PartitionChangeEvent>();

        handler.subscribeToChanges(event -> {
            events1.add(event);
            latch1.countDown();
        });

        handler.subscribeToChanges(event -> {
            events2.add(event);
            latch2.countDown();
        });

        handler.markHealthy(partition1);

        assertThat(latch1.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(latch2.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(events1).hasSize(1);
        assertThat(events2).hasSize(1);
    }

    @Test
    void testSubscription_Unsubscribe() throws Exception {
        handler.start();
        var counter = new AtomicInteger(0);

        var subscription = handler.subscribeToChanges(event -> counter.incrementAndGet());

        handler.markHealthy(partition1);
        Thread.sleep(100); // Allow event processing
        assertThat(counter.get()).isEqualTo(1);

        subscription.unsubscribe();

        handler.markHealthy(partition2);
        Thread.sleep(100); // Allow event processing
        assertThat(counter.get()).isEqualTo(1); // No new events after unsubscribe
    }

    @Test
    void testReportBarrierTimeout_TransitionsToSuspected() throws Exception {
        handler.start();
        handler.markHealthy(partition1);

        var latch = new CountDownLatch(1);
        var events = new ArrayList<PartitionChangeEvent>();
        handler.subscribeToChanges(event -> {
            if (event.newStatus() == PartitionStatus.SUSPECTED) {
                events.add(event);
                latch.countDown();
            }
        });

        handler.reportBarrierTimeout(partition1);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(events).hasSize(1);
        var status = handler.checkHealth(partition1);
        assertThat(status).isEqualTo(PartitionStatus.SUSPECTED);
    }

    @Test
    void testReportSyncFailure_TransitionsToSuspected() throws Exception {
        handler.start();
        handler.markHealthy(partition1);

        var latch = new CountDownLatch(1);
        handler.subscribeToChanges(event -> {
            if (event.newStatus() == PartitionStatus.SUSPECTED) {
                latch.countDown();
            }
        });

        handler.reportSyncFailure(partition1);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        var status = handler.checkHealth(partition1);
        assertThat(status).isEqualTo(PartitionStatus.SUSPECTED);
    }

    @Test
    void testReportHeartbeatFailure_TransitionsToSuspected() throws Exception {
        handler.start();
        handler.markHealthy(partition1);
        var nodeId = UUID.randomUUID();

        var latch = new CountDownLatch(1);
        handler.subscribeToChanges(event -> {
            if (event.newStatus() == PartitionStatus.SUSPECTED) {
                latch.countDown();
            }
        });

        handler.reportHeartbeatFailure(partition1, nodeId);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        var status = handler.checkHealth(partition1);
        assertThat(status).isEqualTo(PartitionStatus.SUSPECTED);
    }

    @Test
    void testGetConfiguration_ReturnsInjectedConfig() {
        var customConfig = FaultConfiguration.defaultConfig()
            .withBarrierTimeout(10000)
            .withMaxRetries(5);

        var customHandler = createFaultHandler(customConfig);
        assertThat(customHandler.getConfiguration()).isEqualTo(customConfig);
    }

    @Test
    void testGetMetrics_ReturnsZeroForUnknownPartition() {
        handler.start();
        var unknownId = UUID.randomUUID();
        var metrics = handler.getMetrics(unknownId);
        assertThat(metrics).isNull();
    }

    @Test
    void testGetMetrics_ReturnsMetricsForKnownPartition() {
        handler.start();
        handler.markHealthy(partition1);

        var metrics = handler.getMetrics(partition1);
        assertThat(metrics).isNotNull();
        assertThat(metrics.failureCount()).isZero();
    }

    @Test
    void testGetAggregateMetrics_CombinesAllPartitions() {
        handler.start();
        handler.markHealthy(partition1);
        handler.markHealthy(partition2);

        var aggregate = handler.getAggregateMetrics();
        assertThat(aggregate).isNotNull();
    }

    @Test
    void testRecovery_RegisterAndInitiate() throws Exception {
        handler.start();
        handler.markHealthy(partition1);

        // Transition to FAILED state first
        handler.reportBarrierTimeout(partition1);
        Thread.sleep(100);

        var recoveryCompleted = new CompletableFuture<Boolean>();
        PartitionRecovery recovery = new PartitionRecovery() {
            @Override
            public CompletableFuture<Boolean> initiateRecovery(UUID failedPartitionId) {
                return recoveryCompleted;
            }

            @Override
            public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
                return CompletableFuture.completedFuture(
                    RecoveryResult.success(partitionId, 0, "test", 1)
                );
            }

            @Override
            public boolean canRecover(UUID partitionId, FaultHandler handler) {
                return true;
            }

            @Override
            public String getStrategyName() {
                return "test";
            }

            @Override
            public FaultConfiguration getConfiguration() {
                return FaultConfiguration.defaultConfig();
            }
        };

        handler.registerRecovery(partition1, recovery);

        var result = handler.initiateRecovery(partition1);
        assertThat(result).isNotNull();
        assertThat(result).isNotCompleted(); // Should be running
    }

    @Test
    void testNotifyRecoveryComplete_Success() throws Exception {
        handler.start();
        handler.markHealthy(partition1);

        // Transition to FAILED state first
        handler.reportBarrierTimeout(partition1);
        Thread.sleep(100);

        var latch = new CountDownLatch(1);
        handler.subscribeToChanges(event -> {
            if (event.newStatus() == PartitionStatus.HEALTHY &&
                event.partitionId().equals(partition1)) {
                latch.countDown();
            }
        });

        handler.notifyRecoveryComplete(partition1, true);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        var status = handler.checkHealth(partition1);
        assertThat(status).isEqualTo(PartitionStatus.HEALTHY);
    }

    @Test
    void testNotifyRecoveryComplete_Failure() throws Exception {
        handler.start();
        handler.markHealthy(partition1);

        // Transition to FAILED state first
        handler.reportBarrierTimeout(partition1);
        Thread.sleep(100);

        var latch = new CountDownLatch(1);
        handler.subscribeToChanges(event -> {
            if (event.newStatus() == PartitionStatus.FAILED &&
                event.partitionId().equals(partition1)) {
                latch.countDown();
            }
        });

        handler.notifyRecoveryComplete(partition1, false);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        var status = handler.checkHealth(partition1);
        assertThat(status).isEqualTo(PartitionStatus.FAILED);
    }
}
