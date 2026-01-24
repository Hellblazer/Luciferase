package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 1 foundation classes.
 * <p>
 * Validates that all Phase 1 components (data structures, interfaces, implementations)
 * work correctly together and integrate properly.
 * <p>
 * These tests serve as acceptance criteria for Phase 1 completion.
 */
@DisplayName("Phase 1: Foundation Integration Tests")
class Phase1FoundationIntegrationTest {

    /**
     * Test-specific recovery implementation that supports the deprecated API
     * used by SimpleFaultHandler for backward compatibility.
     */
    private static class TestRecovery implements PartitionRecovery {
        private final FaultConfiguration config;

        TestRecovery(FaultConfiguration config) {
            this.config = config;
        }

        @Override
        public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
            return CompletableFuture.completedFuture(
                RecoveryResult.success(partitionId, 0, "test-recovery", 1,
                    "Test recovery completed")
            );
        }

        @Override
        public boolean canRecover(UUID partitionId, FaultHandler handler) {
            var status = handler.checkHealth(partitionId);
            return status == PartitionStatus.SUSPECTED || status == PartitionStatus.FAILED;
        }

        @Override
        public String getStrategyName() {
            return "test-recovery";
        }

        @Override
        public FaultConfiguration getConfiguration() {
            return config;
        }

        @Override
        public CompletableFuture<Boolean> initiateRecovery(UUID failedPartitionId) {
            // Implement deprecated method for backward compatibility with SimpleFaultHandler
            return CompletableFuture.completedFuture(true);
        }
    }

    private SimpleFaultHandler handler;
    private PartitionRecovery recovery;
    private UUID partition1;
    private UUID partition2;
    private UUID partition3;

    @BeforeEach
    void setUp() {
        handler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        recovery = new TestRecovery(FaultConfiguration.defaultConfig());
        partition1 = UUID.randomUUID();
        partition2 = UUID.randomUUID();
        partition3 = UUID.randomUUID();
        handler.start();
    }

    @AfterEach
    void tearDown() {
        if (handler.isRunning()) {
            handler.stop();
        }
    }

    // ===== 1. Data Structure Integration Tests (5 tests) =====

    @Test
    @DisplayName("Status transitions follow valid sequence: HEALTHY → SUSPECTED → FAILED → RECOVERING → HEALTHY")
    void testStatusTransitionSequence() {
        // Initial: HEALTHY (auto-registered on first markHealthy)
        handler.markHealthy(partition1);
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partition1),
            "Partition should start HEALTHY");

        // HEALTHY → SUSPECTED (barrier timeout)
        handler.reportBarrierTimeout(partition1);
        assertEquals(PartitionStatus.SUSPECTED, handler.checkHealth(partition1),
            "Partition should transition to SUSPECTED after barrier timeout");

        // SUSPECTED → FAILED (second timeout)
        handler.reportBarrierTimeout(partition1);
        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partition1),
            "Partition should transition to FAILED after repeated timeout");

        // FAILED → RECOVERING (recovery initiated)
        handler.registerRecovery(partition1, recovery);
        var future = handler.initiateRecovery(partition1);
        assertEquals(PartitionStatus.RECOVERING, handler.checkHealth(partition1),
            "Partition should transition to RECOVERING when recovery starts");

        // Wait for recovery to complete
        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS),
            "Recovery should complete within timeout");

        // RECOVERING → HEALTHY (after successful recovery)
        handler.notifyRecoveryComplete(partition1, true);
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partition1),
            "Partition should transition back to HEALTHY after successful recovery");
    }

    @Test
    @DisplayName("Fault metrics accumulate correctly across status changes")
    void testFaultMetricsAccumulation() {
        handler.markHealthy(partition1);

        // Initial metrics should be zero
        var metrics0 = handler.getMetrics(partition1);
        assertNotNull(metrics0, "Metrics should not be null");
        assertEquals(0, metrics0.failureCount(), "Initial failure count should be 0");
        assertEquals(0, metrics0.recoveryAttempts(), "Initial recovery attempts should be 0");

        // Trigger failure: HEALTHY → SUSPECTED
        handler.reportBarrierTimeout(partition1);
        var metrics1 = handler.getMetrics(partition1);
        assertEquals(1, metrics1.failureCount(), "Failure count should increment to 1");

        // Trigger second failure: SUSPECTED → FAILED
        handler.reportBarrierTimeout(partition1);
        var metrics2 = handler.getMetrics(partition1);
        assertEquals(2, metrics2.failureCount(), "Failure count should increment to 2");

        // Initiate recovery
        handler.registerRecovery(partition1, recovery);
        var future = handler.initiateRecovery(partition1);
        var metrics3 = handler.getMetrics(partition1);
        assertEquals(1, metrics3.recoveryAttempts(), "Recovery attempts should increment to 1");

        // Complete recovery successfully
        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS));
        handler.notifyRecoveryComplete(partition1, true);
        var metrics4 = handler.getMetrics(partition1);
        assertEquals(1, metrics4.successfulRecoveries(), "Successful recoveries should increment to 1");
        assertEquals(0, metrics4.failedRecoveries(), "Failed recoveries should remain 0");
    }

    @Test
    @DisplayName("PartitionView provides consistent read-only snapshot")
    void testPartitionViewSnapshot() {
        handler.markHealthy(partition1);

        // Get initial view
        var view = handler.getPartitionView(partition1);
        assertNotNull(view, "PartitionView should not be null");
        assertEquals(partition1, view.partitionId(), "Partition ID should match");
        assertEquals(PartitionStatus.HEALTHY, view.status(), "Status should be HEALTHY");
        assertTrue(view.lastSeenMs() > 0, "Last seen timestamp should be positive");
        assertEquals(1, view.nodeCount(), "Node count should be 1");
        assertEquals(1, view.healthyNodes(), "Healthy nodes should be 1");

        // Verify metrics are included
        var metrics = view.metrics();
        assertNotNull(metrics, "Metrics should be included in view");
        assertEquals(0, metrics.failureCount(), "Initial failure count should be 0");

        // Transition to SUSPECTED
        handler.reportBarrierTimeout(partition1);
        var view2 = handler.getPartitionView(partition1);
        assertEquals(PartitionStatus.SUSPECTED, view2.status(), "Status should update to SUSPECTED");
        assertEquals(1, view2.metrics().failureCount(), "Failure count should be 1");

        // Original view should be unchanged (snapshot semantics)
        assertEquals(PartitionStatus.HEALTHY, view.status(),
            "Original view should remain HEALTHY (immutable snapshot)");
    }

    @Test
    @DisplayName("PartitionChangeEvent ordering and timestamps are correct during transitions")
    void testPartitionChangeEventOrdering() throws InterruptedException {
        var events = new ArrayList<PartitionChangeEvent>();
        var latch = new CountDownLatch(3); // Expect 3 events

        handler.subscribeToChanges(event -> {
            events.add(event);
            latch.countDown();
        });

        // Trigger sequence: HEALTHY → SUSPECTED → FAILED
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1);
        handler.reportBarrierTimeout(partition1);

        // Wait for all events
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive all 3 events");

        // Verify event count
        assertEquals(3, events.size(), "Should have exactly 3 events");

        // Verify event 1: HEALTHY → HEALTHY (registration)
        var event1 = events.get(0);
        assertEquals(partition1, event1.partitionId());
        assertEquals(PartitionStatus.HEALTHY, event1.oldStatus());
        assertEquals(PartitionStatus.HEALTHY, event1.newStatus());
        assertTrue(event1.timestamp() > 0, "Timestamp should be positive");

        // Verify event 2: HEALTHY → SUSPECTED
        var event2 = events.get(1);
        assertEquals(partition1, event2.partitionId());
        assertEquals(PartitionStatus.HEALTHY, event2.oldStatus());
        assertEquals(PartitionStatus.SUSPECTED, event2.newStatus());
        assertTrue(event2.timestamp() >= event1.timestamp(), "Timestamp should be monotonic");

        // Verify event 3: SUSPECTED → FAILED
        var event3 = events.get(2);
        assertEquals(partition1, event3.partitionId());
        assertEquals(PartitionStatus.SUSPECTED, event3.oldStatus());
        assertEquals(PartitionStatus.FAILED, event3.newStatus());
        assertTrue(event3.timestamp() >= event2.timestamp(), "Timestamp should be monotonic");
    }

    @Test
    @DisplayName("FaultConfiguration supports default values and custom overrides")
    void testFaultConfigurationDefaultsAndCustom() {
        // Test default configuration
        var defaultConfig = FaultConfiguration.defaultConfig();
        assertEquals(500, defaultConfig.heartbeatIntervalMs());
        assertEquals(2000, defaultConfig.heartbeatTimeoutMs());
        assertEquals(5000, defaultConfig.barrierTimeoutMs());
        assertEquals(3, defaultConfig.maxRetries());
        assertEquals(2, defaultConfig.cascadingThreshold());

        // Test custom configuration using builder methods
        var customConfig = defaultConfig
            .withHeartbeatInterval(1000)
            .withHeartbeatTimeout(3000)
            .withBarrierTimeout(10000)
            .withMaxRetries(5)
            .withCascadingThreshold(3);

        assertEquals(1000, customConfig.heartbeatIntervalMs());
        assertEquals(3000, customConfig.heartbeatTimeoutMs());
        assertEquals(10000, customConfig.barrierTimeoutMs());
        assertEquals(5, customConfig.maxRetries());
        assertEquals(3, customConfig.cascadingThreshold());

        // Verify original is unchanged (immutability)
        assertEquals(500, defaultConfig.heartbeatIntervalMs(),
            "Original config should be unchanged");

        // Test validation
        assertThrows(IllegalArgumentException.class,
            () -> new FaultConfiguration(-1, 1000, 1000, 3, 2),
            "Should reject negative heartbeat interval");
        assertThrows(IllegalArgumentException.class,
            () -> new FaultConfiguration(1000, -1, 1000, 3, 2),
            "Should reject negative heartbeat timeout");
        assertThrows(IllegalArgumentException.class,
            () -> new FaultConfiguration(1000, 1000, 1000, -1, 2),
            "Should reject negative max retries");
    }

    // ===== 2. FaultHandler Contract Tests (8 tests) =====

    @Test
    @DisplayName("checkHealth returns HEALTHY for healthy partition")
    void testHealthCheckOnHealthyPartition() {
        handler.markHealthy(partition1);
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partition1),
            "Healthy partition should return HEALTHY status");
    }

    @Test
    @DisplayName("checkHealth returns SUSPECTED after timeout")
    void testHealthCheckOnSuspectedPartition() {
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1);
        assertEquals(PartitionStatus.SUSPECTED, handler.checkHealth(partition1),
            "Partition should be SUSPECTED after barrier timeout");
    }

    @Test
    @DisplayName("Status changes trigger subscribed callbacks")
    void testStatusTransitionNotification() throws InterruptedException {
        var eventCount = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        handler.subscribeToChanges(event -> {
            eventCount.incrementAndGet();
            latch.countDown();
        });

        // Trigger two transitions
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive both events");
        assertEquals(2, eventCount.get(), "Should have received exactly 2 events");
    }

    @Test
    @DisplayName("Can subscribe and unsubscribe from status change events")
    void testSubscriptionUnsubscription() throws InterruptedException {
        var eventCount = new AtomicInteger(0);

        var subscription = handler.subscribeToChanges(event -> eventCount.incrementAndGet());

        // Trigger event while subscribed
        handler.markHealthy(partition1);
        Thread.sleep(100); // Allow event delivery
        assertEquals(1, eventCount.get(), "Should receive event while subscribed");

        // Unsubscribe
        subscription.unsubscribe();

        // Trigger event after unsubscribe
        handler.reportBarrierTimeout(partition1);
        Thread.sleep(100); // Allow potential event delivery
        assertEquals(1, eventCount.get(), "Should NOT receive event after unsubscribe");
    }

    @Test
    @DisplayName("markHealthy transitions FAILED partition back to HEALTHY")
    void testMarkHealthyRestoresState() {
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1); // HEALTHY → SUSPECTED
        handler.reportBarrierTimeout(partition1); // SUSPECTED → FAILED

        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partition1),
            "Partition should be FAILED");

        handler.markHealthy(partition1);
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partition1),
            "markHealthy should restore FAILED partition to HEALTHY");
    }

    @Test
    @DisplayName("reportBarrierTimeout triggers SUSPECTED state")
    void testReportBarrierTimeout() {
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1);

        var status = handler.checkHealth(partition1);
        assertEquals(PartitionStatus.SUSPECTED, status,
            "Barrier timeout should trigger SUSPECTED state");
    }

    @Test
    @DisplayName("reportSyncFailure is tracked in metrics")
    void testReportSyncFailure() {
        handler.markHealthy(partition1);
        handler.reportSyncFailure(partition1);

        var status = handler.checkHealth(partition1);
        assertEquals(PartitionStatus.SUSPECTED, status,
            "Sync failure should trigger SUSPECTED state");

        var metrics = handler.getMetrics(partition1);
        assertEquals(1, metrics.failureCount(),
            "Sync failure should increment failure count");
    }

    @Test
    @DisplayName("getAggregateMetrics combines all partition metrics correctly")
    void testGetMetricsAggregation() {
        // Setup multiple partitions with different states
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1); // 1 failure

        handler.markHealthy(partition2);
        handler.reportBarrierTimeout(partition2); // 1 failure
        handler.reportBarrierTimeout(partition2); // 2 failures total

        handler.markHealthy(partition3);
        handler.reportSyncFailure(partition3); // 1 failure

        // Get aggregate metrics
        var aggregate = handler.getAggregateMetrics();

        // Total failures: 1 + 2 + 1 = 4
        assertEquals(4, aggregate.failureCount(),
            "Aggregate should sum all partition failure counts");

        // No recoveries initiated yet
        assertEquals(0, aggregate.recoveryAttempts(),
            "Aggregate should have 0 recovery attempts initially");
    }

    // ===== 3. PartitionRecovery Strategy Tests (5 tests) =====

    @Test
    @DisplayName("Recovery strategy can be registered for a partition")
    void testRecoveryStrategyRegistration() {
        var testRecovery = new TestRecovery(FaultConfiguration.defaultConfig());

        assertDoesNotThrow(() -> handler.registerRecovery(partition1, testRecovery),
            "Should be able to register recovery strategy");

        // Verify registration by initiating recovery
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1);
        handler.reportBarrierTimeout(partition1); // Transition to FAILED

        var future = handler.initiateRecovery(partition1);
        assertNotNull(future, "Should return future for registered recovery");
        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS),
            "Registered recovery should execute");
    }

    @Test
    @DisplayName("Initiating recovery returns a CompletableFuture")
    void testRecoveryInitiation() {
        handler.registerRecovery(partition1, recovery);
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1);
        handler.reportBarrierTimeout(partition1); // FAILED

        var future = handler.initiateRecovery(partition1);

        assertNotNull(future, "Recovery should return CompletableFuture");

        // TestRecovery returns completed future immediately (synchronous test recovery)
        // In production, this would be async and not done immediately

        // Wait for completion (should be immediate for test recovery)
        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS),
            "Recovery future should complete");
        assertTrue(future.isDone(), "Future should be done after get()");
    }

    @Test
    @DisplayName("Successful recovery completes with success status")
    void testRecoverySuccess() {
        var testRecovery = new TestRecovery(FaultConfiguration.defaultConfig());

        handler.registerRecovery(partition1, testRecovery);
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1);
        handler.reportBarrierTimeout(partition1); // FAILED

        var future = handler.initiateRecovery(partition1);

        // Test recovery should succeed immediately
        var success = assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS),
            "Recovery should complete successfully");

        assertTrue(success, "Test recovery should return true (success)");

        // Notify completion
        handler.notifyRecoveryComplete(partition1, true);

        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partition1),
            "Partition should be HEALTHY after successful recovery");

        var metrics = handler.getMetrics(partition1);
        assertEquals(1, metrics.successfulRecoveries(),
            "Should have 1 successful recovery");
    }

    @Test
    @DisplayName("Failed recovery captures failure reason")
    void testRecoveryFailure() {
        var testRecovery = new TestRecovery(FaultConfiguration.defaultConfig());

        handler.registerRecovery(partition1, testRecovery);
        handler.markHealthy(partition1);
        handler.reportBarrierTimeout(partition1);
        handler.reportBarrierTimeout(partition1); // FAILED

        handler.initiateRecovery(partition1);

        // Notify recovery failed
        handler.notifyRecoveryComplete(partition1, false);

        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partition1),
            "Partition should remain FAILED after failed recovery");

        var metrics = handler.getMetrics(partition1);
        assertEquals(1, metrics.failedRecoveries(),
            "Should have 1 failed recovery");
        assertEquals(0, metrics.successfulRecoveries(),
            "Should have 0 successful recoveries");
    }

    @Test
    @DisplayName("Recovery strategy provides configuration")
    void testRecoveryConfiguration() {
        var customConfig = FaultConfiguration.defaultConfig()
            .withMaxRetries(5);
        var testRecovery = new TestRecovery(customConfig);

        var config = testRecovery.getConfiguration();
        assertNotNull(config, "Recovery should provide configuration");
        assertEquals(5, config.maxRetries(), "Configuration should match custom value");
        assertEquals("test-recovery", testRecovery.getStrategyName(),
            "Strategy name should be test-recovery");
    }

    // ===== 4. Lifecycle Tests (2 tests) =====

    @Test
    @DisplayName("FaultHandler start() and stop() work correctly")
    void testFaultHandlerLifecycle() {
        var testHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());

        assertFalse(testHandler.isRunning(), "Handler should not be running initially");

        testHandler.start();
        assertTrue(testHandler.isRunning(), "Handler should be running after start()");

        testHandler.stop();
        assertFalse(testHandler.isRunning(), "Handler should not be running after stop()");
    }

    @Test
    @DisplayName("Resources are cleaned up when FaultHandler is stopped")
    void testResourceCleanupOnStop() throws InterruptedException {
        var eventCount = new AtomicInteger(0);

        handler.subscribeToChanges(event -> eventCount.incrementAndGet());

        // Verify subscription works before stop
        handler.markHealthy(partition1);
        Thread.sleep(100);
        assertEquals(1, eventCount.get(), "Should receive event before stop");

        // Stop handler
        handler.stop();

        // Trigger event after stop - should not be delivered
        handler.reportBarrierTimeout(partition1);
        Thread.sleep(100);
        assertEquals(1, eventCount.get(),
            "Should NOT receive events after stop (resources cleaned up)");
    }

    // ===== Bonus Integration Tests (beyond 20) =====

    @Test
    @DisplayName("Multiple partitions can be monitored independently")
    void testMultiplePartitionMonitoring() {
        // Setup three partitions in different states
        handler.markHealthy(partition1);
        handler.markHealthy(partition2);
        handler.markHealthy(partition3);

        handler.reportBarrierTimeout(partition2); // P2: SUSPECTED
        handler.reportBarrierTimeout(partition3); // P3: SUSPECTED
        handler.reportBarrierTimeout(partition3); // P3: FAILED

        // Verify independent states
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partition1));
        assertEquals(PartitionStatus.SUSPECTED, handler.checkHealth(partition2));
        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partition3));
    }

    @Test
    @DisplayName("FaultMetrics success rate calculation is accurate")
    void testFaultMetricsSuccessRate() {
        var metrics = FaultMetrics.zero();
        assertEquals(0.0, metrics.successRate(), 0.001,
            "Zero attempts should have 0.0 success rate");

        var metrics1 = metrics
            .withIncrementedRecoveryAttempts()
            .withIncrementedSuccessfulRecoveries();
        assertEquals(1.0, metrics1.successRate(), 0.001,
            "1 success out of 1 attempt should be 1.0");

        var metrics2 = metrics1
            .withIncrementedRecoveryAttempts()
            .withIncrementedFailedRecoveries();
        assertEquals(0.5, metrics2.successRate(), 0.001,
            "1 success out of 2 attempts should be 0.5");

        var metrics3 = metrics2
            .withIncrementedRecoveryAttempts()
            .withIncrementedSuccessfulRecoveries();
        assertEquals(0.666, metrics3.successRate(), 0.01,
            "2 successes out of 3 attempts should be ~0.667");
    }

    @Test
    @DisplayName("RecoveryResult validation enforces correct field values")
    void testRecoveryResultValidation() {
        var validPartitionId = UUID.randomUUID();

        // Valid success result
        assertDoesNotThrow(() ->
            RecoveryResult.success(validPartitionId, 100, "test-strategy", 1),
            "Valid success result should not throw");

        // Valid failure result
        assertDoesNotThrow(() ->
            RecoveryResult.failure(validPartitionId, 100, "test-strategy", 1,
                "Test failure", new RuntimeException("Test")),
            "Valid failure result should not throw");

        // Test validation: null partitionId
        assertThrows(IllegalArgumentException.class, () ->
            new RecoveryResult(null, true, 100, "strategy", 1, "message", null),
            "Should reject null partitionId");

        // Test validation: negative duration
        assertThrows(IllegalArgumentException.class, () ->
            new RecoveryResult(validPartitionId, true, -1, "strategy", 1, "message", null),
            "Should reject negative duration");

        // Test validation: zero attempts
        assertThrows(IllegalArgumentException.class, () ->
            new RecoveryResult(validPartitionId, true, 100, "strategy", 0, "message", null),
            "Should reject zero attempts");

        // Test validation: blank strategy
        assertThrows(IllegalArgumentException.class, () ->
            new RecoveryResult(validPartitionId, true, 100, "", 1, "message", null),
            "Should reject blank strategy");

        // Test validation: blank status message
        assertThrows(IllegalArgumentException.class, () ->
            new RecoveryResult(validPartitionId, true, 100, "strategy", 1, "", null),
            "Should reject blank status message");
    }
}
