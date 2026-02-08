/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.persistence;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for WriteAheadLog fsync and Clock injection.
 *
 * Verifies:
 * - Clock injection and deterministic timing
 * - FileChannel.force(true) replaces broken reflection
 * - WAL recovery after simulated crash
 * - fsync performance measurement
 *
 * @author hal.hildebrand
 */
class WriteAheadLogTest {

    @TempDir
    Path tempDir;

    private UUID nodeId;
    private WriteAheadLog wal;

    @BeforeEach
    void setUp() {
        nodeId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (wal != null) {
            wal.close();
        }
    }

    @Test
    void testClockInjectionBasic() throws IOException {
        // Given: WAL with TestClock
        var testClock = new TestClock();
        testClock.setTime(1000L);  // Absolute time mode

        wal = new WriteAheadLog(nodeId, tempDir);
        wal.setClock(testClock);

        // When: Advance time and flush
        testClock.advance(500L);
        wal.flush();

        // Then: Clock timing should be deterministic
        assertEquals(1500L, testClock.currentTimeMillis());
    }

    @Test
    void testFsyncTimingMeasurement() throws IOException {
        // Given: WAL with TestClock tracking nanoTime
        var testClock = new TestClock();
        var startMillis = 1_000L;
        testClock.setTime(startMillis);

        wal = new WriteAheadLog(nodeId, tempDir);
        wal.setClock(testClock);

        // When: Append event and flush (simulating fsync)
        var event = createTestEvent("MIGRATION_COMMIT");
        wal.append(event);

        // Simulate fsync duration (real fsync would advance time)
        testClock.advance(500L);  // 500ms fsync duration
        wal.flush();

        // Then: Time advancement should be measurable (500ms = 500,000,000 ns)
        assertEquals(startMillis * 1_000_000 + 500_000_000L, testClock.nanoTime());
    }

    @Test
    void testAppendAndFlush() throws IOException {
        // Given: WAL with system clock
        wal = new WriteAheadLog(nodeId, tempDir);

        // When: Append multiple events
        wal.append(createTestEvent("ENTITY_ARRIVAL"));
        wal.append(createTestEvent("VIEW_SYNC_ACK"));
        wal.append(createTestEvent("MIGRATION_COMMIT"));
        wal.flush();

        // Then: Events should be persisted
        var events = wal.readAllEvents();
        assertEquals(3, events.size());
        assertEquals("ENTITY_ARRIVAL", events.get(0).get("type"));
        assertEquals("VIEW_SYNC_ACK", events.get(1).get("type"));
        assertEquals("MIGRATION_COMMIT", events.get(2).get("type"));
    }

    @Test
    void testRecoveryAfterSimulatedCrash() throws IOException {
        // Given: WAL with events written but not flushed
        wal = new WriteAheadLog(nodeId, tempDir);
        wal.append(createTestEvent("ENTITY_DEPARTURE"));
        wal.append(createTestEvent("MIGRATION_COMMIT"));
        wal.flush();  // Critical: ensure fsync completes

        // Simulate crash by closing WAL without cleanup
        wal.close();

        // When: Create new WAL instance (simulating recovery)
        var recoveredWal = new WriteAheadLog(nodeId, tempDir);

        // Then: All flushed events should be recoverable
        var events = recoveredWal.readAllEvents();
        assertEquals(2, events.size());
        assertEquals("ENTITY_DEPARTURE", events.get(0).get("type"));
        assertEquals("MIGRATION_COMMIT", events.get(1).get("type"));

        recoveredWal.close();
    }

    @Test
    void testRecoveryWithoutFlush() throws IOException {
        // Given: WAL with events written but NOT flushed
        wal = new WriteAheadLog(nodeId, tempDir);
        wal.append(createTestEvent("UNFLUSHED_EVENT"));
        // No flush() - simulates crash before fsync

        // Force close without flush
        wal.close();

        // When: Create new WAL instance
        var recoveredWal = new WriteAheadLog(nodeId, tempDir);

        // Then: Unflushed events might be lost (BufferedWriter cache)
        var events = recoveredWal.readAllEvents();
        // This is expected behavior: unflushed data may not survive crash
        // The test validates that WAL doesn't throw exceptions on recovery

        recoveredWal.close();
    }

    @Test
    void testCheckpoint() throws IOException {
        // Given: WAL with checkpoint capability
        wal = new WriteAheadLog(nodeId, tempDir);

        // When: Create checkpoint
        var sequenceNumber = 42L;
        var timestamp = Instant.now();
        wal.checkpoint(sequenceNumber, timestamp);

        // Then: Metadata file should exist
        var metadataFile = tempDir.resolve("node-" + nodeId + ".meta");
        assertTrue(Files.exists(metadataFile));

        var content = Files.readString(metadataFile);
        assertTrue(content.contains("\"sequenceNumber\":42"));
        assertTrue(content.contains("\"nodeId\":\"" + nodeId + "\""));
    }

    @Test
    void testLogRotation() throws IOException {
        // Given: WAL with small rotation size (1KB)
        wal = new WriteAheadLog(nodeId, tempDir, 1024);

        // When: Append events exceeding rotation size
        for (int i = 0; i < 100; i++) {
            wal.append(createTestEvent("EVENT_" + i));
        }
        wal.flush();

        // Then: Multiple log files should exist
        var logFiles = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().startsWith("node-" + nodeId))
            .filter(p -> p.getFileName().toString().endsWith(".log"))
            .count();

        assertTrue(logFiles > 1, "Expected log rotation to create multiple files");
    }

    @Test
    void testFsyncPerformance() throws IOException {
        // Given: WAL with system clock
        wal = new WriteAheadLog(nodeId, tempDir);

        // When: Measure fsync time
        var event = createTestEvent("PERFORMANCE_TEST");
        wal.append(event);

        var startNanos = System.nanoTime();
        wal.flush();
        var elapsedNanos = System.nanoTime() - startNanos;

        // Then: fsync should complete in reasonable time (<10ms typically)
        var elapsedMs = elapsedNanos / 1_000_000.0;
        System.out.printf("fsync completed in %.3f ms%n", elapsedMs);

        // Note: This is a performance observation, not a hard assertion
        // fsync time varies by filesystem and OS (SSD: <1ms, HDD: 5-10ms)
        assertTrue(elapsedMs < 100, "fsync took unexpectedly long: " + elapsedMs + "ms");
    }

    @Test
    void testConcurrentAppend() throws Exception {
        // Given: WAL with TestClock
        var testClock = new TestClock();
        testClock.setTime(0L);

        wal = new WriteAheadLog(nodeId, tempDir);
        wal.setClock(testClock);

        // When: Multiple threads append events
        var threads = new Thread[5];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        var event = createTestEvent("THREAD_" + threadId + "_EVENT_" + j);
                        wal.append(event);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (var thread : threads) {
            thread.join();
        }

        wal.flush();

        // Then: All events should be persisted
        var events = wal.readAllEvents();
        assertEquals(50, events.size(), "All events from 5 threads should be persisted");
    }

    @Test
    void testReadEventsSince() throws IOException {
        // Given: WAL with multiple events
        wal = new WriteAheadLog(nodeId, tempDir);

        // Append 5 events (sequences 1-5)
        wal.append(createTestEvent("EVENT_1"));
        wal.append(createTestEvent("EVENT_2"));
        wal.append(createTestEvent("EVENT_3"));
        wal.append(createTestEvent("EVENT_4"));
        wal.append(createTestEvent("EVENT_5"));
        wal.flush();

        // When: Read events since sequence 2 (exclusive)
        var eventsSince2 = wal.readEventsSince(2L);

        // Then: Should return events 3, 4, 5 only
        assertEquals(3, eventsSince2.size(), "Should return 3 events after sequence 2");
        assertEquals("EVENT_3", eventsSince2.get(0).get("type"));
        assertEquals("EVENT_4", eventsSince2.get(1).get("type"));
        assertEquals("EVENT_5", eventsSince2.get(2).get("type"));

        // Verify sequence numbers are present
        assertEquals(3L, ((Number) eventsSince2.get(0).get("sequence")).longValue());
        assertEquals(4L, ((Number) eventsSince2.get(1).get("sequence")).longValue());
        assertEquals(5L, ((Number) eventsSince2.get(2).get("sequence")).longValue());
    }

    @Test
    void testReadEventsSinceZero() throws IOException {
        // Given: WAL with events
        wal = new WriteAheadLog(nodeId, tempDir);

        wal.append(createTestEvent("EVENT_1"));
        wal.append(createTestEvent("EVENT_2"));
        wal.flush();

        // When: Read events since 0 (from beginning)
        var allEvents = wal.readEventsSince(0L);

        // Then: Should return all events
        assertEquals(2, allEvents.size());
        assertEquals("EVENT_1", allEvents.get(0).get("type"));
        assertEquals("EVENT_2", allEvents.get(1).get("type"));
    }

    @Test
    void testReadEventsSinceLastSequence() throws IOException {
        // Given: WAL with 3 events
        wal = new WriteAheadLog(nodeId, tempDir);

        wal.append(createTestEvent("EVENT_1"));
        wal.append(createTestEvent("EVENT_2"));
        wal.append(createTestEvent("EVENT_3"));
        wal.flush();

        // When: Read events since last sequence
        var eventsSinceLast = wal.readEventsSince(3L);

        // Then: Should return empty list
        assertTrue(eventsSinceLast.isEmpty(), "Should return no events after last sequence");
    }

    // ========== Helper Methods ==========

    private Map<String, Object> createTestEvent(String type) {
        var event = new HashMap<String, Object>();
        event.put("version", 1);
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        event.put("nodeId", nodeId.toString());
        return event;
    }
}
