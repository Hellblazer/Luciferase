/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for InstrumentedGhostChannel.
 * <p>
 * Validates latency instrumentation wrapper delegates correctly and tracks latency accurately.
 *
 * @author hal.hildebrand
 */
class InstrumentedGhostChannelTest {

    private InMemoryGhostChannel<TestEntityID, String> delegate;
    private InstrumentedGhostChannel<TestEntityID, String> instrumented;
    private UUID targetBubbleId;
    private List<SimulationGhostEntity<TestEntityID, String>> testGhosts;

    @BeforeEach
    void setUp() {
        delegate = new InMemoryGhostChannel<>(0); // No simulated latency
        targetBubbleId = UUID.randomUUID();
        testGhosts = createTestGhosts(10);
    }

    /**
     * Test 1: Verify instrumentation wraps delegate correctly.
     */
    @Test
    void testInstrumentationWrapsDelegateCorrectly() {
        instrumented = new InstrumentedGhostChannel<>(delegate);

        // Queue should be delegated
        instrumented.queueGhost(targetBubbleId, testGhosts.get(0));
        assertEquals(1, delegate.getPendingCount(targetBubbleId), "Queue should delegate to underlying channel");

        // isConnected should be delegated
        assertTrue(instrumented.isConnected(targetBubbleId), "isConnected should delegate");
    }

    /**
     * Test 2: Verify latency is recorded on sendBatch.
     */
    @Test
    void testLatencyRecordedOnSendBatch() {
        instrumented = new InstrumentedGhostChannel<>(delegate);

        // Send batch
        instrumented.sendBatch(targetBubbleId, testGhosts);

        var stats = instrumented.getLatencyStats();

        assertEquals(1, stats.sampleCount(), "Should record exactly 1 sample");
        assertTrue(stats.avgLatencyNs() >= 0, "Latency should be non-negative");
        assertTrue(stats.minLatencyNs() <= stats.maxLatencyNs(), "Min <= Max");
    }

    /**
     * Test 3: Verify latency callback is invoked.
     */
    @Test
    void testLatencyCallbackInvoked() {
        var callbackInvoked = new AtomicLong(0);
        var recordedLatency = new AtomicLong(0);

        instrumented = new InstrumentedGhostChannel<>(delegate, latencyNs -> {
            callbackInvoked.incrementAndGet();
            recordedLatency.set(latencyNs);
        });

        // Send batch
        instrumented.sendBatch(targetBubbleId, testGhosts);

        assertEquals(1, callbackInvoked.get(), "Callback should be invoked exactly once");
        assertTrue(recordedLatency.get() >= 0, "Recorded latency should be non-negative");
    }

    /**
     * Test 4: Verify getLatencyStats returns tracker stats.
     */
    @Test
    void testGetLatencyStatsReturnsTrackerStats() {
        instrumented = new InstrumentedGhostChannel<>(delegate);

        // Initially empty
        var emptyStats = instrumented.getLatencyStats();
        assertEquals(0, emptyStats.sampleCount(), "Should have no samples initially");

        // After sending batch
        instrumented.sendBatch(targetBubbleId, testGhosts);

        var stats = instrumented.getLatencyStats();
        assertEquals(1, stats.sampleCount(), "Should have 1 sample");
        assertTrue(stats.avgLatencyNs() >= 0, "Average should be non-negative");
        assertTrue(stats.p50LatencyNs() >= 0, "P50 should be non-negative");
        assertTrue(stats.p99LatencyNs() >= 0, "P99 should be non-negative");
    }

    /**
     * Test 5: Verify flush is delegated correctly.
     */
    @Test
    void testFlushDelegatedCorrectly() {
        instrumented = new InstrumentedGhostChannel<>(delegate);

        // Set up handler to track delivery
        var delivered = new ArrayList<SimulationGhostEntity<TestEntityID, String>>();
        delegate.onReceive((from, ghosts) -> delivered.addAll(ghosts));

        // Queue ghosts
        for (var ghost : testGhosts) {
            instrumented.queueGhost(targetBubbleId, ghost);
        }

        // Flush
        instrumented.flush(1);

        // Verify delegation
        assertEquals(testGhosts.size(), delivered.size(), "All ghosts should be delivered via delegate");
        assertEquals(0, delegate.getPendingCount(targetBubbleId), "Pending count should be 0 after flush");
    }

    /**
     * Test 6: Verify queueGhost is delegated correctly.
     */
    @Test
    void testQueueGhostDelegatedCorrectly() {
        instrumented = new InstrumentedGhostChannel<>(delegate);

        // Queue multiple ghosts
        for (int i = 0; i < 5; i++) {
            instrumented.queueGhost(targetBubbleId, testGhosts.get(i));
        }

        assertEquals(5, delegate.getPendingCount(targetBubbleId), "All queued ghosts should be in delegate");
    }

    /**
     * Test 7: Verify close cleans both delegate and tracker.
     */
    @Test
    void testCloseCleansBothDelegateAndTracker() {
        instrumented = new InstrumentedGhostChannel<>(delegate);

        // Queue and send
        instrumented.queueGhost(targetBubbleId, testGhosts.get(0));
        instrumented.sendBatch(targetBubbleId, testGhosts);

        // Close
        instrumented.close();

        // Verify delegate is closed
        assertEquals(0, delegate.getPendingCount(targetBubbleId), "Delegate should be cleaned after close");

        // Verify tracker is reset
        var stats = instrumented.getLatencyStats();
        assertEquals(0, stats.sampleCount(), "Tracker should be reset after close");
    }

    /**
     * Test 8: Verify zero latency when no samples recorded.
     */
    @Test
    void testZeroLatencyWhenNoSamples() {
        instrumented = new InstrumentedGhostChannel<>(delegate);

        var stats = instrumented.getLatencyStats();

        assertEquals(0, stats.sampleCount(), "Sample count should be 0");
        assertEquals(0, stats.p50LatencyNs(), "P50 should be 0");
        assertEquals(0, stats.p99LatencyNs(), "P99 should be 0");
        assertEquals(0.0, stats.avgLatencyNs(), 0.01, "Average should be 0");
    }

    /**
     * Helper: Create test ghosts.
     */
    private List<SimulationGhostEntity<TestEntityID, String>> createTestGhosts(int count) {
        var ghosts = new ArrayList<SimulationGhostEntity<TestEntityID, String>>();
        for (int i = 0; i < count; i++) {
            var position = new Point3f(i, i, i);
            var ghostEntity = new GhostZoneManager.GhostEntity<>(new TestEntityID(i), "content-" + i, position, null,
                                                                 "source-tree");
            ghosts.add(new SimulationGhostEntity<>(ghostEntity, UUID.randomUUID(), 1L, 0L, 0L));
        }
        return ghosts;
    }

    /**
     * Simple EntityID for testing.
     */
    static class TestEntityID implements EntityID {
        private final int id;

        TestEntityID(int id) {
            this.id = id;
        }

        @Override
        public String toDebugString() {
            return "TestEntity-" + id;
        }

        @Override
        public int compareTo(EntityID o) {
            return Integer.compare(id, ((TestEntityID) o).id);
        }
    }
}
