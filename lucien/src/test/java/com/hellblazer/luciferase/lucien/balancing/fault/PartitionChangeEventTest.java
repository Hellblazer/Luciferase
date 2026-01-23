package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PartitionChangeEvent} record.
 */
class PartitionChangeEventTest {

    @Test
    void testEventCreation() {
        var partitionId = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();

        var event = new PartitionChangeEvent(
            partitionId,
            PartitionStatus.HEALTHY,
            PartitionStatus.SUSPECTED,
            timestamp,
            "heartbeat_timeout"
        );

        assertEquals(partitionId, event.partitionId());
        assertEquals(PartitionStatus.HEALTHY, event.oldStatus());
        assertEquals(PartitionStatus.SUSPECTED, event.newStatus());
        assertEquals(timestamp, event.timestamp());
        assertEquals("heartbeat_timeout", event.reason());
    }

    @Test
    void testImmutability() {
        var partitionId = UUID.randomUUID();
        var event = new PartitionChangeEvent(
            partitionId,
            PartitionStatus.HEALTHY,
            PartitionStatus.FAILED,
            1000L,
            "test"
        );

        // Record fields are immutable
        assertEquals(partitionId, event.partitionId());
        assertEquals(PartitionStatus.HEALTHY, event.oldStatus());
    }

    @Test
    void testRecordEquality() {
        var id = UUID.randomUUID();
        var event1 = new PartitionChangeEvent(
            id,
            PartitionStatus.HEALTHY,
            PartitionStatus.SUSPECTED,
            1000L,
            "timeout"
        );
        var event2 = new PartitionChangeEvent(
            id,
            PartitionStatus.HEALTHY,
            PartitionStatus.SUSPECTED,
            1000L,
            "timeout"
        );
        var event3 = new PartitionChangeEvent(
            id,
            PartitionStatus.HEALTHY,
            PartitionStatus.FAILED,
            1000L,
            "timeout"
        );

        assertEquals(event1, event2);
        assertNotEquals(event1, event3);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    void testTimestampSemantics() {
        var id = UUID.randomUUID();
        var now = System.currentTimeMillis();

        var event1 = new PartitionChangeEvent(
            id, PartitionStatus.HEALTHY, PartitionStatus.SUSPECTED, now, "test"
        );
        var event2 = new PartitionChangeEvent(
            id, PartitionStatus.SUSPECTED, PartitionStatus.FAILED, now + 1000, "test"
        );

        assertTrue(event1.timestamp() < event2.timestamp());
    }

    @Test
    void testEventOrdering() {
        var id = UUID.randomUUID();
        var events = new ArrayList<PartitionChangeEvent>();

        events.add(new PartitionChangeEvent(id, PartitionStatus.HEALTHY, PartitionStatus.SUSPECTED, 1000, "timeout"));
        events.add(new PartitionChangeEvent(id, PartitionStatus.SUSPECTED, PartitionStatus.FAILED, 2000, "confirmed"));
        events.add(new PartitionChangeEvent(id, PartitionStatus.FAILED, PartitionStatus.RECOVERING, 3000, "recovery_started"));

        // Sort by timestamp
        events.sort((e1, e2) -> Long.compare(e1.timestamp(), e2.timestamp()));

        assertEquals(PartitionStatus.SUSPECTED, events.get(0).newStatus());
        assertEquals(PartitionStatus.FAILED, events.get(1).newStatus());
        assertEquals(PartitionStatus.RECOVERING, events.get(2).newStatus());
    }

    @Test
    void testCommonReasons() {
        var id = UUID.randomUUID();

        var heartbeatTimeout = new PartitionChangeEvent(
            id, PartitionStatus.HEALTHY, PartitionStatus.SUSPECTED, 1000, "heartbeat_timeout"
        );
        assertEquals("heartbeat_timeout", heartbeatTimeout.reason());

        var barrierTimeout = new PartitionChangeEvent(
            id, PartitionStatus.HEALTHY, PartitionStatus.SUSPECTED, 1000, "barrier_timeout"
        );
        assertEquals("barrier_timeout", barrierTimeout.reason());

        var recoveryCompleted = new PartitionChangeEvent(
            id, PartitionStatus.RECOVERING, PartitionStatus.HEALTHY, 1000, "recovery_completed"
        );
        assertEquals("recovery_completed", recoveryCompleted.reason());

        var recoveryFailed = new PartitionChangeEvent(
            id, PartitionStatus.RECOVERING, PartitionStatus.FAILED, 1000, "recovery_failed"
        );
        assertEquals("recovery_failed", recoveryFailed.reason());
    }

    @Test
    void testRecordToString() {
        var id = UUID.randomUUID();
        var event = new PartitionChangeEvent(
            id, PartitionStatus.HEALTHY, PartitionStatus.SUSPECTED, 1000, "test"
        );
        var str = event.toString();

        assertTrue(str.contains("PartitionChangeEvent"));
        assertTrue(str.contains("HEALTHY"));
        assertTrue(str.contains("SUSPECTED"));
    }

    @Test
    void testNullPartitionId() {
        assertThrows(NullPointerException.class, () ->
            new PartitionChangeEvent(null, PartitionStatus.HEALTHY, PartitionStatus.SUSPECTED, 1000, "test")
        );
    }

    @Test
    void testNullStatuses() {
        var id = UUID.randomUUID();

        assertThrows(NullPointerException.class, () ->
            new PartitionChangeEvent(id, null, PartitionStatus.SUSPECTED, 1000, "test")
        );

        assertThrows(NullPointerException.class, () ->
            new PartitionChangeEvent(id, PartitionStatus.HEALTHY, null, 1000, "test")
        );
    }

    @Test
    void testNullReason() {
        var id = UUID.randomUUID();
        assertThrows(NullPointerException.class, () ->
            new PartitionChangeEvent(id, PartitionStatus.HEALTHY, PartitionStatus.SUSPECTED, 1000, null)
        );
    }

    @Test
    void testEmptyReason() {
        var id = UUID.randomUUID();
        var event = new PartitionChangeEvent(
            id, PartitionStatus.HEALTHY, PartitionStatus.SUSPECTED, 1000, ""
        );
        assertEquals("", event.reason());
    }
}
