package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CascadingRecoveryImpl.
 */
class CascadingRecoveryImplTest {

    private CascadingRecoveryImpl recovery;

    @AfterEach
    void tearDown() {
        if (recovery != null) {
            recovery.close();
        }
    }

    @Test
    void testSuccessAtBarrierLevel() throws Exception {
        recovery = new CascadingRecoveryImpl();
        var handler = new MockFaultHandler();
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var result = recovery.recover(partitionId, handler).get(1, TimeUnit.SECONDS);

        assertTrue(result.success(), "Recovery should succeed at barrier level");
        assertEquals("cascading-recovery", result.strategy(), "Strategy must be cascading-recovery");
        assertEquals(1, result.attemptsNeeded(), "Should succeed on first attempt");
        assertTrue(result.statusMessage().contains("Level 1"),
            "Status message should indicate barrier level (Level 1)");
    }

    @Test
    void testFallbackToStateTransfer() throws Exception {
        var config = FaultConfiguration.defaultConfig().withMaxRetries(2);
        recovery = new CascadingRecoveryImpl(config);
        var handler = new LevelFailureMockFaultHandler(1, 2); // Fail Level 1 (barrier)
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var result = recovery.recover(partitionId, handler).get(3, TimeUnit.SECONDS);

        assertTrue(result.success(), "Recovery should succeed at state transfer level");
        assertTrue(result.attemptsNeeded() > 2, "Should attempt barrier level first (2 retries) then succeed");
        assertTrue(result.statusMessage().contains("Level 2"),
            "Status message should indicate state transfer level (Level 2)");
    }

    @Test
    void testFallbackToFullRebuild() throws Exception {
        var config = FaultConfiguration.defaultConfig().withMaxRetries(2);
        recovery = new CascadingRecoveryImpl(config);
        var handler = new LevelFailureMockFaultHandler(2, 2); // Fail Levels 1 & 2
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var result = recovery.recover(partitionId, handler).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), "Recovery should succeed at full rebuild level");
        assertTrue(result.attemptsNeeded() > 4, "Should try both previous levels (2 retries each) before succeeding");
        assertTrue(result.statusMessage().contains("Level 3"),
            "Status message should indicate full rebuild level (Level 3)");
    }

    @Test
    void testRecoveryLevelEscalation() throws Exception {
        var config = FaultConfiguration.defaultConfig().withMaxRetries(2);
        recovery = new CascadingRecoveryImpl(config);
        var handler = new LevelFailureMockFaultHandler(2, 2); // Fail Levels 1 & 2
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var eventLog = new java.util.concurrent.CopyOnWriteArrayList<String>();

        recovery.addObserver(new RecoveryProgressObserver() {
            @Override
            public void onProgress(RecoveryProgress progress) {
                eventLog.add("Progress: " + progress.phase());
            }

            @Override
            public void onEvent(RecoveryEvent event) {
                eventLog.add("Event: " + event.eventType() + " - " + event.details());
            }
        });

        var result = recovery.recover(partitionId, handler).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), "Recovery should succeed");

        // Verify escalation through levels
        var log = String.join("\n", eventLog);
        assertTrue(log.contains("barrier-sync"), "Should attempt barrier sync");
        assertTrue(log.contains("state-transfer"), "Should escalate to state transfer");
        assertTrue(log.contains("full-rebuild"), "Should escalate to full rebuild");
    }

    @Test
    void testAllLevelsExhausted() throws Exception {
        var config = FaultConfiguration.defaultConfig().withMaxRetries(2);
        recovery = new CascadingRecoveryImpl(config);
        var handler = new LevelFailureMockFaultHandler(3, 2); // Fail all levels
        var partitionId = UUID.randomUUID();

        handler.markSuspected(partitionId);

        var result = recovery.recover(partitionId, handler).get(5, TimeUnit.SECONDS);

        assertFalse(result.success(), "Recovery should fail after exhausting all levels");
        assertTrue(result.attemptsNeeded() >= 6, "Should try all 3 levels with 2 retries each (6 attempts)");
        assertTrue(result.statusMessage().contains("All recovery levels exhausted"),
            "Status message should indicate all levels exhausted");
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
     * Mock FaultHandler that fails recovery levels selectively.
     * <p>
     * Tracks attempts per partition and fails according to:
     * - Levels 1-{failLevels}: Fail for all {maxAttemptsPerLevel} attempts
     * - Level {failLevels+1}: Succeed immediately
     */
    private static class LevelFailureMockFaultHandler extends MockFaultHandler {
        private final int failLevels; // How many levels to fail (1=barrier, 2=barrier+state, 3=all)
        private final int maxAttemptsPerLevel;
        private final java.util.Map<UUID, AtomicInteger> attemptCounts = new java.util.HashMap<>();

        LevelFailureMockFaultHandler(int failLevels, int maxAttemptsPerLevel) {
            this.failLevels = failLevels;
            this.maxAttemptsPerLevel = maxAttemptsPerLevel;
        }

        @Override
        public void markHealthy(UUID partitionId) {
            var attempts = attemptCounts.computeIfAbsent(partitionId, k -> new AtomicInteger(0));
            var currentAttempt = attempts.incrementAndGet();

            // Calculate which level we're on (each level gets maxAttemptsPerLevel attempts)
            var currentLevel = ((currentAttempt - 1) / maxAttemptsPerLevel) + 1;

            if (currentLevel <= failLevels) {
                // Fail this level
                return;
            }

            // Succeed on subsequent levels
            super.markHealthy(partitionId);
        }
    }
}
