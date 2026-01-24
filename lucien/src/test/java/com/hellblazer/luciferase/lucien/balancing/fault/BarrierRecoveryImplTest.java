package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BarrierRecoveryImpl.
 */
class BarrierRecoveryImplTest {

    private BarrierRecoveryImpl recovery;

    @AfterEach
    void tearDown() {
        if (recovery != null) {
            recovery.close();
        }
    }

    @Test
    void testSuccessfulBarrierSync() throws Exception {
        recovery = new BarrierRecoveryImpl();
        var handler = new MockFaultHandler();
        var partitionId = UUID.randomUUID();

        // Setup: Partition is SUSPECTED
        handler.markSuspected(partitionId);

        // Execute recovery
        var result = recovery.recover(partitionId, handler).get(1, TimeUnit.SECONDS);

        assertTrue(result.success(), "Recovery should succeed");
        assertEquals(partitionId, result.partitionId(), "Result must match partition");
        assertEquals("barrier-recovery", result.strategy(), "Strategy must be barrier-recovery");
        assertEquals(1, result.attemptsNeeded(), "Should succeed on first attempt");
        assertNull(result.failureReason(), "No failure reason on success");

        // Verify partition marked healthy
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partitionId),
            "Partition should be marked HEALTHY after recovery");
    }

    @Test
    void testBarrierTimeoutHandling() throws Exception {
        recovery = new BarrierRecoveryImpl();
        var handler = new TimeoutMockFaultHandler(2); // Fail first 2 attempts
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var result = recovery.recover(partitionId, handler).get(2, TimeUnit.SECONDS);

        assertTrue(result.success(), "Recovery should succeed after retries");
        assertEquals(3, result.attemptsNeeded(), "Should succeed on third attempt");
    }

    @Test
    void testRetryLogic() throws Exception {
        var config = FaultConfiguration.defaultConfig().withMaxRetries(5);
        recovery = new BarrierRecoveryImpl(config);
        var handler = new TimeoutMockFaultHandler(3); // Fail first 3 attempts
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var startTime = System.currentTimeMillis();
        var result = recovery.recover(partitionId, handler).get(5, TimeUnit.SECONDS);
        var duration = System.currentTimeMillis() - startTime;

        assertTrue(result.success(), "Recovery should succeed after retries");
        assertEquals(4, result.attemptsNeeded(), "Should succeed on fourth attempt");

        // Verify exponential backoff occurred (100ms, 200ms, 400ms)
        assertTrue(duration >= 700, "Should include exponential backoff delays, took " + duration + "ms");
    }

    @Test
    void testPartitionStateValidation() throws Exception {
        recovery = new BarrierRecoveryImpl();
        var handler = new MockFaultHandler();
        var partitionId = UUID.randomUUID();

        // Partition is HEALTHY - should fail validation
        handler.markHealthy(partitionId);

        var result = recovery.recover(partitionId, handler).get(1, TimeUnit.SECONDS);

        assertFalse(result.success(), "Recovery should fail for HEALTHY partition");
        assertTrue(result.statusMessage().contains("validation failed"),
            "Status message should indicate validation failure");
    }

    @Test
    void testMaxRetriesExhausted() throws Exception {
        var config = FaultConfiguration.defaultConfig().withMaxRetries(3);
        recovery = new BarrierRecoveryImpl(config);
        var handler = new TimeoutMockFaultHandler(10); // Always fail
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var result = recovery.recover(partitionId, handler).get(3, TimeUnit.SECONDS);

        assertFalse(result.success(), "Recovery should fail after max retries");
        assertEquals(3, result.attemptsNeeded(), "Should exhaust all retries");
        assertTrue(result.statusMessage().contains("failed after 3 attempts"),
            "Status message should mention retry count");
    }

    @Test
    void testProgressObserver() throws Exception {
        recovery = new BarrierRecoveryImpl();
        var handler = new MockFaultHandler();
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var progressCount = new AtomicInteger();
        var eventCount = new AtomicInteger();

        recovery.addObserver(new RecoveryProgressObserver() {
            @Override
            public void onProgress(RecoveryProgress progress) {
                progressCount.incrementAndGet();
                assertNotNull(progress.phase(), "Phase must not be null");
                assertEquals(partitionId, progress.partitionId(), "Progress must match partition");
            }

            @Override
            public void onEvent(RecoveryEvent event) {
                eventCount.incrementAndGet();
                assertNotNull(event.eventType(), "Event type must not be null");
                assertEquals(partitionId, event.partitionId(), "Event must match partition");
            }
        });

        var result = recovery.recover(partitionId, handler).get(1, TimeUnit.SECONDS);

        assertTrue(result.success(), "Recovery should succeed");
        assertTrue(progressCount.get() > 0, "Progress observer should be notified");
        assertTrue(eventCount.get() > 0, "Event observer should be notified");
    }

    /**
     * Mock FaultHandler for testing.
     */
    private static class MockFaultHandler implements FaultHandler {
        protected final java.util.Map<UUID, PartitionStatus> statuses = new java.util.HashMap<>();

        void markSuspected(UUID partitionId) {
            statuses.put(partitionId, PartitionStatus.SUSPECTED);
        }

        void markFailed(UUID partitionId) {
            statuses.put(partitionId, PartitionStatus.FAILED);
        }

        @Override
        public PartitionStatus checkHealth(UUID partitionId) {
            return statuses.get(partitionId);
        }

        @Override
        public void markHealthy(UUID partitionId) {
            statuses.put(partitionId, PartitionStatus.HEALTHY);
        }

        @Override
        public PartitionView getPartitionView(UUID partitionId) { return null; }
        @Override
        public Subscription subscribeToChanges(java.util.function.Consumer<PartitionChangeEvent> consumer) { return () -> {}; }
        @Override
        public void reportBarrierTimeout(UUID partitionId) {}
        @Override
        public void reportSyncFailure(UUID partitionId) {}
        @Override
        public void reportHeartbeatFailure(UUID partitionId, UUID nodeId) {}
        @Override
        public void registerRecovery(UUID partitionId, PartitionRecovery recovery) {}
        @Override
        public CompletableFuture<Boolean> initiateRecovery(UUID partitionId) { return CompletableFuture.completedFuture(true); }
        @Override
        public void notifyRecoveryComplete(UUID partitionId, boolean success) {}
        @Override
        public FaultConfiguration getConfiguration() { return FaultConfiguration.defaultConfig(); }
        @Override
        public FaultMetrics getMetrics(UUID partitionId) { return null; }
        @Override
        public FaultMetrics getAggregateMetrics() { return null; }
        @Override
        public void start() {}
        @Override
        public void stop() {}
        @Override
        public boolean isRunning() { return true; }
    }

    /**
     * Mock FaultHandler that fails barrier sync for first N attempts.
     */
    private static class TimeoutMockFaultHandler extends MockFaultHandler {
        private final int failCount;
        private final java.util.Map<UUID, AtomicInteger> attemptCounts = new java.util.HashMap<>();

        TimeoutMockFaultHandler(int failCount) {
            this.failCount = failCount;
        }

        @Override
        public void markHealthy(UUID partitionId) {
            var attempts = attemptCounts.computeIfAbsent(partitionId, k -> new AtomicInteger(0));
            var currentAttempt = attempts.incrementAndGet();

            if (currentAttempt <= failCount) {
                // Fail by not updating status (simulate barrier timeout)
                return;
            }

            // Succeed on subsequent attempts
            super.markHealthy(partitionId);
        }
    }
}
