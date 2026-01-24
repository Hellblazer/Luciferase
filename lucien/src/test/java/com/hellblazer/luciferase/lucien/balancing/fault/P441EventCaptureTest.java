package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.Event;
import com.hellblazer.luciferase.lucien.balancing.fault.testinfra.EventCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EventCapture test infrastructure (P4.4.1).
 * <p>
 * Validates event recording, filtering, sequencing, and thread-safety.
 */
class P441EventCaptureTest {

    private EventCapture eventCapture;

    @BeforeEach
    void setUp() {
        eventCapture = new EventCapture();
    }

    @Test
    void testRecordEvent() {
        // When: Record event
        var event = new Event(1000L, "recovery", "PHASE_CHANGE", RecoveryPhase.DETECTING);
        eventCapture.recordEvent("recovery", event);

        // Then: Event should be retrievable
        var events = eventCapture.getEventsByCategory("recovery");
        assertEquals(1, events.size());
        assertEquals(event, events.get(0));
    }

    @Test
    void testGetEventsByCategory() {
        // Given: Multiple events in different categories
        eventCapture.recordEvent("recovery", new Event(1000L, "recovery", "PHASE_CHANGE", null));
        eventCapture.recordEvent("recovery", new Event(1001L, "recovery", "RETRY", null));
        eventCapture.recordEvent("fault", new Event(1002L, "fault", "DETECTED", null));
        eventCapture.recordEvent("von", new Event(1003L, "von", "TOPOLOGY_UPDATE", null));

        // When: Filter by category
        var recoveryEvents = eventCapture.getEventsByCategory("recovery");
        var faultEvents = eventCapture.getEventsByCategory("fault");
        var vonEvents = eventCapture.getEventsByCategory("von");

        // Then: Should get correct events per category
        assertEquals(2, recoveryEvents.size());
        assertEquals(1, faultEvents.size());
        assertEquals(1, vonEvents.size());

        assertEquals("recovery", recoveryEvents.get(0).category());
        assertEquals("fault", faultEvents.get(0).category());
        assertEquals("von", vonEvents.get(0).category());
    }

    @Test
    void testGetEventSequence() {
        // Given: Events across multiple categories
        eventCapture.recordEvent("recovery", new Event(1000L, "recovery", "START", null));
        eventCapture.recordEvent("fault", new Event(1001L, "fault", "DETECTED", null));
        eventCapture.recordEvent("recovery", new Event(1002L, "recovery", "PHASE_CHANGE", null));
        eventCapture.recordEvent("von", new Event(1003L, "von", "TOPOLOGY_UPDATE", null));
        eventCapture.recordEvent("recovery", new Event(1004L, "recovery", "COMPLETE", null));

        // When: Get event sequence for multiple categories
        var sequence = eventCapture.getEventSequence("recovery", "fault", "von");

        // Then: Should maintain temporal ordering across categories
        assertEquals(5, sequence.size());
        assertEquals(1000L, sequence.get(0).timestamp());
        assertEquals(1001L, sequence.get(1).timestamp());
        assertEquals(1002L, sequence.get(2).timestamp());
        assertEquals(1003L, sequence.get(3).timestamp());
        assertEquals(1004L, sequence.get(4).timestamp());
    }

    @Test
    void testEventStatistics() {
        // Given: Multiple events recorded
        eventCapture.recordEvent("recovery", new Event(1000L, "recovery", "START", null));
        eventCapture.recordEvent("recovery", new Event(1001L, "recovery", "PHASE_CHANGE", null));
        eventCapture.recordEvent("fault", new Event(1002L, "fault", "DETECTED", null));
        eventCapture.recordEvent("recovery", new Event(1003L, "recovery", "COMPLETE", null));

        // When: Get statistics
        var stats = eventCapture.getStatistics();

        // Then: Should reflect correct counts
        assertNotNull(stats);
        assertEquals(4, stats.totalEvents());
        assertEquals(2, stats.categoryCounts().size());
        assertEquals(3, stats.categoryCounts().get("recovery"));
        assertEquals(1, stats.categoryCounts().get("fault"));
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        // Given: Multiple threads recording events concurrently
        var threadCount = 10;
        var eventsPerThread = 100;
        var latch = new CountDownLatch(threadCount);
        var threads = new ArrayList<Thread>();

        for (var i = 0; i < threadCount; i++) {
            var threadId = i;
            var thread = new Thread(() -> {
                for (var j = 0; j < eventsPerThread; j++) {
                    var timestamp = System.currentTimeMillis();
                    eventCapture.recordEvent(
                        "thread-" + threadId,
                        new Event(timestamp, "thread-" + threadId, "EVENT_" + j, null)
                    );
                }
                latch.countDown();
            });
            threads.add(thread);
            thread.start();
        }

        // When: Wait for all threads to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");

        // Then: All events should be recorded
        var totalEvents = 0;
        for (var i = 0; i < threadCount; i++) {
            var events = eventCapture.getEventsByCategory("thread-" + i);
            assertEquals(eventsPerThread, events.size(), "Thread " + i + " should have all events");
            totalEvents += events.size();
        }
        assertEquals(threadCount * eventsPerThread, totalEvents);

        // And: Statistics should be accurate
        var stats = eventCapture.getStatistics();
        assertEquals(threadCount * eventsPerThread, stats.totalEvents());
    }

    @Test
    void testReset() {
        // Given: Events recorded
        eventCapture.recordEvent("recovery", new Event(1000L, "recovery", "START", null));
        eventCapture.recordEvent("fault", new Event(1001L, "fault", "DETECTED", null));
        assertEquals(2, eventCapture.getStatistics().totalEvents());

        // When: Reset
        eventCapture.reset();

        // Then: All events should be cleared
        assertEquals(0, eventCapture.getStatistics().totalEvents());
        assertTrue(eventCapture.getEventsByCategory("recovery").isEmpty());
        assertTrue(eventCapture.getEventsByCategory("fault").isEmpty());
    }

    @Test
    void testGetEventsByCategory_EmptyCategory() {
        // When: Get events from non-existent category
        var events = eventCapture.getEventsByCategory("nonexistent");

        // Then: Should return empty list
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void testGetEventSequence_PreservesTimestampOrdering() {
        // Given: Events with non-sequential recording but ordered timestamps
        eventCapture.recordEvent("a", new Event(1000L, "a", "FIRST", null));
        eventCapture.recordEvent("b", new Event(1002L, "b", "THIRD", null));
        eventCapture.recordEvent("a", new Event(1001L, "a", "SECOND", null));
        eventCapture.recordEvent("b", new Event(1003L, "b", "FOURTH", null));

        // When: Get sequence
        var sequence = eventCapture.getEventSequence("a", "b");

        // Then: Should be sorted by timestamp
        assertEquals(4, sequence.size());
        assertEquals(1000L, sequence.get(0).timestamp());
        assertEquals(1001L, sequence.get(1).timestamp());
        assertEquals(1002L, sequence.get(2).timestamp());
        assertEquals(1003L, sequence.get(3).timestamp());
    }
}
