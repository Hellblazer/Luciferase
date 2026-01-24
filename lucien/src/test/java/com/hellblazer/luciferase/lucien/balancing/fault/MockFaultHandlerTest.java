package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.test.MockFaultHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test MockFaultHandler infrastructure.
 */
class MockFaultHandlerTest {

    private MockFaultHandler handler;
    private UUID partitionId;

    @BeforeEach
    void setUp() {
        handler = new MockFaultHandler();
        partitionId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (handler != null && handler.isRunning()) {
            handler.stop();
        }
    }

    @Test
    void testStateInjectionAndVerification() {
        // Start with HEALTHY (default)
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partitionId));

        // Inject SUSPECTED
        handler.injectStatusChange(partitionId, PartitionStatus.SUSPECTED);
        assertEquals(PartitionStatus.SUSPECTED, handler.checkHealth(partitionId));

        // Inject FAILED
        handler.injectStatusChange(partitionId, PartitionStatus.FAILED);
        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partitionId));

        // Inject RECOVERING
        handler.injectStatusChange(partitionId, PartitionStatus.RECOVERING);
        assertEquals(PartitionStatus.RECOVERING, handler.checkHealth(partitionId));

        // Inject HEALTHY
        handler.injectStatusChange(partitionId, PartitionStatus.HEALTHY);
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partitionId));
    }

    @Test
    void testEventRecording() {
        // Inject status changes
        handler.injectStatusChange(partitionId, PartitionStatus.SUSPECTED);
        handler.injectStatusChange(partitionId, PartitionStatus.FAILED);
        handler.injectStatusChange(partitionId, PartitionStatus.RECOVERING);
        handler.injectStatusChange(partitionId, PartitionStatus.HEALTHY);

        // Verify all events recorded
        var events = handler.getRecordedEvents();
        assertEquals(4, events.size(), "Expected 4 events");

        // Verify sequence
        assertEquals(PartitionStatus.SUSPECTED, events.get(0).newStatus());
        assertEquals(PartitionStatus.FAILED, events.get(1).newStatus());
        assertEquals(PartitionStatus.RECOVERING, events.get(2).newStatus());
        assertEquals(PartitionStatus.HEALTHY, events.get(3).newStatus());

        // Verify partition-specific events
        var partitionEvents = handler.getEventsFor(partitionId);
        assertEquals(4, partitionEvents.size());
    }

    @Test
    void testCallCounting() {
        // Execute various operations
        handler.checkHealth(partitionId);
        handler.checkHealth(partitionId);
        handler.checkHealth(partitionId);

        handler.markHealthy(partitionId);
        handler.markHealthy(partitionId);

        handler.reportBarrierTimeout(partitionId);

        handler.getPartitionView(partitionId);

        // Verify call counts
        assertEquals(3, handler.getCallCount("checkHealth"));
        assertEquals(2, handler.getCallCount("markHealthy"));
        assertEquals(1, handler.getCallCount("reportBarrierTimeout"));
        assertEquals(1, handler.getCallCount("getPartitionView"));
        assertEquals(0, handler.getCallCount("reportSyncFailure"));
    }

    @Test
    void testMockResetCleanup() {
        // Inject some state
        handler.injectStatusChange(partitionId, PartitionStatus.FAILED);
        handler.markHealthy(partitionId);
        handler.reportBarrierTimeout(partitionId);

        // Verify state exists
        assertFalse(handler.getRecordedEvents().isEmpty());
        assertTrue(handler.getCallCount("markHealthy") > 0);

        // Reset
        handler.reset();

        // Verify all state cleared
        assertTrue(handler.getRecordedEvents().isEmpty());
        assertEquals(0, handler.getCallCount("markHealthy"));
        assertEquals(0, handler.getCallCount("reportBarrierTimeout"));
        assertFalse(handler.isRunning());
    }

    @Test
    void testListenerNotification() throws InterruptedException {
        var latch = new CountDownLatch(3);
        var eventCount = new AtomicInteger(0);

        // Subscribe to events
        handler.subscribeToChanges(event -> {
            eventCount.incrementAndGet();
            latch.countDown();
        });

        // Inject status changes
        handler.injectStatusChange(partitionId, PartitionStatus.SUSPECTED);
        handler.injectStatusChange(partitionId, PartitionStatus.FAILED);
        handler.injectStatusChange(partitionId, PartitionStatus.RECOVERING);

        // Wait for notifications
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Expected 3 event notifications");
        assertEquals(3, eventCount.get());
    }

    @Test
    void testBarrierTimeoutReporting() {
        handler.start();

        // First barrier timeout: HEALTHY → SUSPECTED
        handler.reportBarrierTimeout(partitionId);
        assertEquals(PartitionStatus.SUSPECTED, handler.checkHealth(partitionId));

        // Second barrier timeout: SUSPECTED → FAILED
        handler.reportBarrierTimeout(partitionId);
        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partitionId));

        // Verify events
        var events = handler.getEventsFor(partitionId);
        assertEquals(2, events.size());
        assertEquals(PartitionStatus.SUSPECTED, events.get(0).newStatus());
        assertEquals(PartitionStatus.FAILED, events.get(1).newStatus());
    }

    @Test
    void testSyncFailureReporting() {
        handler.start();

        // First sync failure: HEALTHY → SUSPECTED
        handler.reportSyncFailure(partitionId);
        assertEquals(PartitionStatus.SUSPECTED, handler.checkHealth(partitionId));

        // Second sync failure: SUSPECTED → FAILED
        handler.reportSyncFailure(partitionId);
        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partitionId));
    }

    @Test
    void testHeartbeatFailureReporting() {
        handler.start();
        var nodeId = UUID.randomUUID();

        // First heartbeat failure: HEALTHY → SUSPECTED
        handler.reportHeartbeatFailure(partitionId, nodeId);
        assertEquals(PartitionStatus.SUSPECTED, handler.checkHealth(partitionId));

        // Second heartbeat failure: SUSPECTED → FAILED
        handler.reportHeartbeatFailure(partitionId, nodeId);
        assertEquals(PartitionStatus.FAILED, handler.checkHealth(partitionId));
    }

    @Test
    void testMarkHealthyRestoresStatus() {
        handler.start();

        // Transition to SUSPECTED
        handler.reportBarrierTimeout(partitionId);
        assertEquals(PartitionStatus.SUSPECTED, handler.checkHealth(partitionId));

        // Mark healthy
        handler.markHealthy(partitionId);
        assertEquals(PartitionStatus.HEALTHY, handler.checkHealth(partitionId));

        // Verify event sequence
        var events = handler.getEventsFor(partitionId);
        assertEquals(2, events.size());
        assertEquals(PartitionStatus.SUSPECTED, events.get(0).newStatus());
        assertEquals(PartitionStatus.HEALTHY, events.get(1).newStatus());
    }

    @Test
    void testGetMetrics() {
        var view = handler.getPartitionView(partitionId);
        assertNotNull(view);

        var metrics = handler.getMetrics(partitionId);
        assertNotNull(metrics);
        assertEquals(0, metrics.failureCount());
        assertEquals(0, metrics.recoveryAttempts());
    }
}
