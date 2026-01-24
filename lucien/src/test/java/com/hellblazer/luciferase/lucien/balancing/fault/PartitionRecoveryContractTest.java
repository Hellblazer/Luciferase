package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for PartitionRecovery interface.
 * <p>
 * Tests the core contract that all PartitionRecovery implementations must satisfy.
 */
class PartitionRecoveryContractTest {

    @Test
    void testRecoverySuccessPath() throws Exception {
        var recovery = new NoOpRecoveryImpl();
        var handler = new MockFaultHandler();
        var partitionId = UUID.randomUUID();

        // Setup: Mark partition as SUSPECTED
        handler.markSuspected(partitionId);

        // Execute recovery
        var future = recovery.recover(partitionId, handler);
        assertNotNull(future, "recover() must return non-null CompletableFuture");

        var result = future.get(1, TimeUnit.SECONDS);
        assertNotNull(result, "Recovery result must not be null");
        assertTrue(result.success(), "Recovery should succeed");
        assertEquals(partitionId, result.partitionId(), "Result must match requested partition");
        assertEquals(recovery.getStrategyName(), result.strategy(), "Strategy name must match");
        assertTrue(result.attemptsNeeded() >= 1, "Attempts must be >= 1");
        assertNotNull(result.statusMessage(), "Status message must not be null");
        assertNull(result.failureReason(), "Failure reason must be null on success");
    }

    @Test
    void testRecoveryFailurePath() throws Exception {
        var recovery = new FailingRecovery();
        var handler = new MockFaultHandler();
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var future = recovery.recover(partitionId, handler);
        var result = future.get(1, TimeUnit.SECONDS);

        assertFalse(result.success(), "Recovery should fail");
        assertEquals(partitionId, result.partitionId(), "Result must match requested partition");
        assertNotNull(result.statusMessage(), "Status message must not be null on failure");
        assertTrue(result.attemptsNeeded() >= 1, "Attempts must be >= 1 even on failure");
    }

    @Test
    void testCanRecoverValidation() {
        var recovery = new NoOpRecoveryImpl();
        var handler = new MockFaultHandler();
        var partitionId = UUID.randomUUID();

        // Unknown partition - cannot recover
        assertFalse(recovery.canRecover(partitionId, handler),
            "Cannot recover unknown partition");

        // HEALTHY partition - cannot recover
        handler.markHealthy(partitionId);
        assertFalse(recovery.canRecover(partitionId, handler),
            "Cannot recover HEALTHY partition");

        // SUSPECTED partition - can recover
        handler.markSuspected(partitionId);
        assertTrue(recovery.canRecover(partitionId, handler),
            "Can recover SUSPECTED partition");

        // FAILED partition - can recover
        handler.markFailed(partitionId);
        assertTrue(recovery.canRecover(partitionId, handler),
            "Can recover FAILED partition");
    }

    @Test
    void testConfigurationRetrieval() {
        var config = FaultConfiguration.defaultConfig();
        var recovery = new NoOpRecoveryImpl(config);

        var retrievedConfig = recovery.getConfiguration();
        assertNotNull(retrievedConfig, "Configuration must not be null");
        assertEquals(config, retrievedConfig, "Configuration must match provided config");
    }

    @Test
    void testStrategyNaming() {
        var noOpRecovery = new NoOpRecoveryImpl();
        var barrierRecovery = new BarrierRecoveryImpl();
        var cascadingRecovery = new CascadingRecoveryImpl();

        assertNotNull(noOpRecovery.getStrategyName(), "Strategy name must not be null");
        assertNotNull(barrierRecovery.getStrategyName(), "Strategy name must not be null");
        assertNotNull(cascadingRecovery.getStrategyName(), "Strategy name must not be null");

        assertFalse(noOpRecovery.getStrategyName().isBlank(), "Strategy name must not be blank");
        assertFalse(barrierRecovery.getStrategyName().isBlank(), "Strategy name must not be blank");
        assertFalse(cascadingRecovery.getStrategyName().isBlank(), "Strategy name must not be blank");

        // Strategy names should be distinct
        assertNotEquals(noOpRecovery.getStrategyName(), barrierRecovery.getStrategyName(),
            "Different strategies must have different names");
        assertNotEquals(barrierRecovery.getStrategyName(), cascadingRecovery.getStrategyName(),
            "Different strategies must have different names");
    }

    @Test
    void testAsyncExecutionGuarantees() throws Exception {
        var recovery = new NoOpRecoveryImpl(FaultConfiguration.defaultConfig(), 50);
        var handler = new MockFaultHandler();
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var startTime = System.currentTimeMillis();
        var future = recovery.recover(partitionId, handler);

        // recover() should return immediately (async execution)
        var callDuration = System.currentTimeMillis() - startTime;
        assertTrue(callDuration < 20, "recover() must return immediately, took " + callDuration + "ms");

        // Future should complete after simulated delay
        var result = future.get(200, TimeUnit.MILLISECONDS);
        assertTrue(result.success(), "Recovery should succeed");
        assertTrue(result.durationMs() >= 50, "Duration should reflect simulated delay");
    }

    @Test
    void testNullPartitionIdRejected() {
        var recovery = new NoOpRecoveryImpl();
        var handler = new MockFaultHandler();

        assertThrows(NullPointerException.class, () -> recovery.recover(null, handler),
            "recover() must reject null partitionId");
        assertThrows(NullPointerException.class, () -> recovery.canRecover(null, handler),
            "canRecover() must reject null partitionId");
    }

    @Test
    void testNullHandlerRejected() {
        var recovery = new NoOpRecoveryImpl();
        var partitionId = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> recovery.recover(partitionId, null),
            "recover() must reject null handler");
        assertThrows(NullPointerException.class, () -> recovery.canRecover(partitionId, null),
            "canRecover() must reject null handler");
    }

    /**
     * Mock recovery that always fails (for testing failure paths).
     */
    private static class FailingRecovery implements PartitionRecovery {

        @Override
        public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
            return CompletableFuture.completedFuture(
                RecoveryResult.failure(
                    partitionId,
                    0,
                    "failing-recovery",
                    1,
                    "Intentional failure for testing",
                    null
                )
            );
        }

        @Override
        public boolean canRecover(UUID partitionId, FaultHandler handler) {
            return true;
        }

        @Override
        public String getStrategyName() {
            return "failing-recovery";
        }

        @Override
        public FaultConfiguration getConfiguration() {
            return FaultConfiguration.defaultConfig();
        }
    }

    /**
     * Minimal mock FaultHandler for testing.
     */
    private static class MockFaultHandler implements FaultHandler {
        private final java.util.Map<UUID, PartitionStatus> statuses = new java.util.HashMap<>();

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
        public PartitionView getPartitionView(UUID partitionId) {
            return null;
        }

        @Override
        public Subscription subscribeToChanges(java.util.function.Consumer<PartitionChangeEvent> consumer) {
            return () -> {};
        }

        @Override
        public void markHealthy(UUID partitionId) {
            statuses.put(partitionId, PartitionStatus.HEALTHY);
        }

        @Override
        public void reportBarrierTimeout(UUID partitionId) {}

        @Override
        public void reportSyncFailure(UUID partitionId) {}

        @Override
        public void reportHeartbeatFailure(UUID partitionId, UUID nodeId) {}

        @Override
        public void registerRecovery(UUID partitionId, PartitionRecovery recovery) {}

        @Override
        public CompletableFuture<Boolean> initiateRecovery(UUID partitionId) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void notifyRecoveryComplete(UUID partitionId, boolean success) {}

        @Override
        public FaultConfiguration getConfiguration() {
            return FaultConfiguration.defaultConfig();
        }

        @Override
        public FaultMetrics getMetrics(UUID partitionId) {
            return null;
        }

        @Override
        public FaultMetrics getAggregateMetrics() {
            return null;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
