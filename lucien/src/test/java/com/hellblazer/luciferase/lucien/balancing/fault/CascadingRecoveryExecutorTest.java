/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.test.MockFaultHandler;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CascadingRecoveryImpl.
 * <p>
 * Luciferase-h08sd: virtual-thread executor test.
 * Luciferase-7wzml.11: verifyRecovery de-tautologized via per-level outcome injection.
 *
 * @author hal.hildebrand
 */
class CascadingRecoveryExecutorTest {

    @Test
    void recoveryTasksRunOnCheapVirtualThreads() throws Exception {
        var recovery = new CascadingRecoveryImpl();
        var executor = recovery.executor();

        int tasks = 1_000;
        var allVirtual = new AtomicInteger(0);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(tasks);
        var finished = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            executor.submit(() -> {
                if (Thread.currentThread().isVirtual()) {
                    allVirtual.incrementAndGet();
                }
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        assertTrue(started.await(10, TimeUnit.SECONDS),
                   "all " + tasks + " recovery tasks must start concurrently (Luciferase-h08sd)");
        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "all tasks must finish");
        assertEquals(tasks, allVirtual.get(),
                     "every recovery task must run on a virtual thread (Luciferase-h08sd)");
    }

    /**
     * Level1 forced fail -> escalation reaches STATE_TRANSFER and FULL_REBUILD (Luciferase-7wzml.11).
     */
    @Test
    void level1FailForceEscalationToStateTransferAndFullRebuild() throws Exception {
        var partitionId = UUID.randomUUID();
        var config = FaultConfiguration.defaultConfig().withMaxRetries(1);
        var handler = new MockFaultHandler(config);
        handler.injectStatusChange(partitionId, PartitionStatus.FAILED);

        Set<CascadingRecoveryImpl.RecoveryLevel> levelsAttempted = ConcurrentHashMap.newKeySet();

        var recovery = new CascadingRecoveryImpl(config)
            .enableSimulatedRecovery()
            .setLevelOutcome(level -> {
                levelsAttempted.add(level);
                return level == CascadingRecoveryImpl.RecoveryLevel.FULL_REBUILD;
            });

        var result = recovery.recover(partitionId, handler).get(10, TimeUnit.SECONDS);

        assertTrue(result.success(), "recovery must succeed via FULL_REBUILD");
        assertTrue(result.statusMessage().contains("full rebuild") || result.statusMessage().contains("Level 3"),
                   "success message must mention full rebuild, got: " + result.statusMessage());
        assertEquals(EnumSet.allOf(CascadingRecoveryImpl.RecoveryLevel.class), levelsAttempted,
                     "all three levels must have been attempted");
        assertEquals(1, handler.getCallCount("markHealthy"),
                     "markHealthy called exactly once — when FULL_REBUILD succeeds");
    }

    /**
     * Level1 fail -> Level2 succeeds -> Level3 not reached (Luciferase-7wzml.11).
     */
    @Test
    void level1FailEscalesToLevel2WhenLevel2Succeeds() throws Exception {
        var partitionId = UUID.randomUUID();
        var config = FaultConfiguration.defaultConfig().withMaxRetries(1);
        var handler = new MockFaultHandler(config);
        handler.injectStatusChange(partitionId, PartitionStatus.SUSPECTED);

        Set<CascadingRecoveryImpl.RecoveryLevel> levelsAttempted = ConcurrentHashMap.newKeySet();

        var recovery = new CascadingRecoveryImpl(config)
            .enableSimulatedRecovery()
            .setLevelOutcome(level -> {
                levelsAttempted.add(level);
                return level != CascadingRecoveryImpl.RecoveryLevel.BARRIER;
            });

        var result = recovery.recover(partitionId, handler).get(10, TimeUnit.SECONDS);

        assertTrue(result.success(), "recovery must succeed via STATE_TRANSFER");
        assertTrue(result.statusMessage().contains("state transfer") || result.statusMessage().contains("Level 2"),
                   "must succeed at Level 2: " + result.statusMessage());
        assertTrue(levelsAttempted.contains(CascadingRecoveryImpl.RecoveryLevel.BARRIER), "BARRIER must be attempted");
        assertTrue(levelsAttempted.contains(CascadingRecoveryImpl.RecoveryLevel.STATE_TRANSFER), "STATE_TRANSFER must be attempted");
        assertFalse(levelsAttempted.contains(CascadingRecoveryImpl.RecoveryLevel.FULL_REBUILD),
                    "FULL_REBUILD must NOT be reached when STATE_TRANSFER succeeds");
        assertEquals(1, handler.getCallCount("markHealthy"), "markHealthy called exactly once");
    }

    /**
     * All levels fail -> result is failure, markHealthy never called.
     * Proves verifyRecovery is not self-confirming (Luciferase-7wzml.11).
     */
    @Test
    void allLevelsFailedMeansNoMarkHealthyAndResultIsFailure() throws Exception {
        var partitionId = UUID.randomUUID();
        var config = FaultConfiguration.defaultConfig().withMaxRetries(1);
        var handler = new MockFaultHandler(config);
        handler.injectStatusChange(partitionId, PartitionStatus.FAILED);

        var recovery = new CascadingRecoveryImpl(config)
            .enableSimulatedRecovery()
            .setLevelOutcome(level -> false);

        var result = recovery.recover(partitionId, handler).get(10, TimeUnit.SECONDS);

        assertFalse(result.success(), "all levels failed — result must be failure");
        assertEquals(0, handler.getCallCount("markHealthy"),
                     "markHealthy must NEVER be called when all levels fail — "
                     + "old tautological verifyRecovery would have self-confirmed (Luciferase-7wzml.11)");
        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partitionId),
                     "partition must remain FAILED");
    }

    /**
     * Retry/backoff loop executes >1 attempt under injected failure (Luciferase-7wzml.11).
     * maxRecoveryRetries=2: attempt1 fails -> 100ms sleep -> attempt2 fails -> escalate.
     */
    @Test
    void retryBackoffLoopExecutesMultipleAttemptsUnderInjectedFailure() throws Exception {
        var partitionId = UUID.randomUUID();
        var config = FaultConfiguration.defaultConfig().withMaxRetries(2);
        var handler = new MockFaultHandler(config);
        handler.injectStatusChange(partitionId, PartitionStatus.FAILED);

        var barrierCallCount = new AtomicInteger(0);

        var recovery = new CascadingRecoveryImpl(config)
            .enableSimulatedRecovery()
            .setLevelOutcome(level -> {
                if (level == CascadingRecoveryImpl.RecoveryLevel.BARRIER) {
                    barrierCallCount.incrementAndGet();
                    return false;
                }
                return true;  // STATE_TRANSFER succeeds
            });

        var result = recovery.recover(partitionId, handler).get(10, TimeUnit.SECONDS);

        assertTrue(result.success(), "recovery must succeed at STATE_TRANSFER after BARRIER retries");
        assertEquals(2, barrierCallCount.get(),
                     "BARRIER verifyRecovery called exactly 2 times (maxRecoveryRetries=2), "
                     + "proving retry/backoff loop ran >1 attempt (Luciferase-7wzml.11)");
    }

    /**
     * Default (no injection): BARRIER succeeds on first attempt. No regression.
     */
    @Test
    void defaultOutcomeAllLevelsSucceedBarrierOnFirstAttempt() throws Exception {
        var partitionId = UUID.randomUUID();
        var config = FaultConfiguration.defaultConfig().withMaxRetries(1);
        var handler = new MockFaultHandler(config);
        handler.injectStatusChange(partitionId, PartitionStatus.SUSPECTED);

        var recovery = new CascadingRecoveryImpl(config).enableSimulatedRecovery();

        var result = recovery.recover(partitionId, handler).get(10, TimeUnit.SECONDS);

        assertTrue(result.success(), "default: BARRIER must succeed on first attempt");
        assertTrue(result.statusMessage().contains("barrier") || result.statusMessage().contains("Level 1"),
                   "must succeed at Level 1: " + result.statusMessage());
        assertEquals(1, handler.getCallCount("markHealthy"), "markHealthy called exactly once");
    }

    // ── Luciferase-7wzml.102: AutoCloseable + idempotent close ──────────────

    /**
     * CascadingRecoveryImpl is AutoCloseable and try-with-resources compiles (Luciferase-7wzml.102).
     * After close(), executor is shut down; a second close() is a no-op (idempotent).
     */
    @Test
    void autoCloseableAndIdempotentClose() {
        var config = FaultConfiguration.defaultConfig();
        CascadingRecoveryImpl recovery;
        try (var r = new CascadingRecoveryImpl(config)) {
            recovery = r;
            // executor is alive inside the block
            assertFalse(r.executor().isShutdown(), "executor must be alive before close");
        }
        // try-with-resources called close() — executor must be shut down
        assertTrue(recovery.executor().isShutdown(), "executor must be shut down after close");

        // second close() must be a no-op (no exception, no double-shutdown)
        assertDoesNotThrow(recovery::close, "second close() must not throw (idempotent)");
        assertTrue(recovery.executor().isShutdown(), "executor must still be shut down after second close");
    }

    /**
     * When shutdownExecutorOnClose=false the caller owns the executor; close() must not shut it down
     * (still idempotent, Luciferase-7wzml.102).
     */
    @Test
    void closeDoesNotShutDownExternalExecutor() {
        var config = FaultConfiguration.defaultConfig();
        var externalExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var recovery = new CascadingRecoveryImpl(config, externalExecutor, false);
            recovery.close();
            recovery.close(); // idempotent
            assertFalse(externalExecutor.isShutdown(),
                        "external executor must NOT be shut down when shutdownExecutorOnClose=false");
        } finally {
            externalExecutor.shutdown();
        }
    }

    // ── Luciferase-7wzml.103: clock injection into RecoveryEvent timestamps ──

    /**
     * RecoveryEvent timestamps produced by notifyEvent must come from the injected clock,
     * NOT System.currentTimeMillis() (Luciferase-7wzml.103).
     */
    @Test
    void recoveryEventTimestampUsesInjectedClock() throws Exception {
        var partitionId = UUID.randomUUID();
        var config = FaultConfiguration.defaultConfig().withMaxRetries(1);
        var handler = new MockFaultHandler(config);
        handler.injectStatusChange(partitionId, PartitionStatus.SUSPECTED);

        long fixedTime = 1_000_000L;
        var testClock = new TestClock(fixedTime);

        List<RecoveryEvent> capturedEvents = new ArrayList<>();
        RecoveryProgressObserver observer = new RecoveryProgressObserver() {
            @Override
            public void onProgress(RecoveryProgress progress) { /* not under test */ }

            @Override
            public void onEvent(RecoveryEvent event) {
                capturedEvents.add(event);
            }
        };

        var recovery = new CascadingRecoveryImpl(config)
            .enableSimulatedRecovery()
            .setClock(testClock);
        recovery.addObserver(observer);

        recovery.recover(partitionId, handler).get(10, TimeUnit.SECONDS);

        assertFalse(capturedEvents.isEmpty(), "at least one RecoveryEvent must be emitted");
        for (var event : capturedEvents) {
            assertEquals(fixedTime, event.timestamp(),
                         "RecoveryEvent.timestamp() must equal the injected clock time, not System.currentTimeMillis(). "
                         + "Event: " + event.eventType());
        }
    }

    /**
     * RecoveryEvent.at() factory creates an event with the supplied explicit timestamp (Luciferase-7wzml.103).
     */
    @Test
    void recoveryEventAtFactoryUsesExplicitTimestamp() {
        var partitionId = UUID.randomUUID();
        long explicitTs = 42_000L;
        var event = RecoveryEvent.at(partitionId, RecoveryEventType.RECOVERY_STARTED, "test", explicitTs);
        assertEquals(explicitTs, event.timestamp(), "RecoveryEvent.at() must preserve the explicit timestamp");
    }
}
