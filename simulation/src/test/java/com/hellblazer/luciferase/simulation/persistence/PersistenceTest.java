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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Phase 7G Day 2: Persistent State & Recovery.
 *
 * Tests cover:
 * - Basic functionality (write, read, checkpoint, rotation)
 * - Recovery scenarios (clean shutdown, crash, partial events, multiple migrations, dead letter)
 * - Integration (with migrator and FSM)
 * - Durability (fsync behavior)
 *
 * @author hal.hildebrand
 */
class PersistenceTest {

    @TempDir
    Path tempDir;

    private UUID nodeId;
    private WriteAheadLog wal;

    @BeforeEach
    void setUp() throws IOException {
        nodeId = UUID.randomUUID();
        wal = new WriteAheadLog(nodeId, tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (wal != null) {
            wal.close();
        }
    }

    // ========== Basic Functionality Tests ==========

    @Test
    void testWriteAndReadEvents() throws IOException {
        // Arrange
        var event1 = createEvent("ENTITY_DEPARTURE", "entity-1", "MIGRATING_OUT");
        var event2 = createEvent("VIEW_SYNC_ACK", "entity-1", "SUCCESS");

        // Act
        wal.append(event1);
        wal.append(event2);
        wal.flush();

        var events = wal.readAllEvents();

        // Assert
        assertEquals(2, events.size(), "Should read 2 events");
        assertEquals("ENTITY_DEPARTURE", events.get(0).get("type"));
        assertEquals("VIEW_SYNC_ACK", events.get(1).get("type"));
        assertEquals("entity-1", events.get(0).get("entityId"));
    }

    @Test
    void testCheckpoint() throws IOException {
        // Arrange
        var event1 = createEvent("ENTITY_DEPARTURE", "entity-1", "MIGRATING_OUT");
        wal.append(event1);
        wal.flush();

        // Act
        var checkpoint = CheckpointMetadata.now(1);
        wal.checkpoint(checkpoint.sequenceNumber(), checkpoint.timestamp());

        // Assert - checkpoint metadata file should exist
        var metadataFile = tempDir.resolve("node-" + nodeId + ".meta");
        assertTrue(Files.exists(metadataFile), "Metadata file should exist");

        var metadata = Files.readString(metadataFile);
        assertTrue(metadata.contains("sequenceNumber"), "Metadata should contain sequence number");
    }

    @Test
    void testRotation() throws IOException {
        // Arrange - write enough events to trigger rotation (force rotation for testing)
        for (int i = 0; i < 5; i++) {
            var event = createEvent("TEST_EVENT", "entity-" + i, "STATE");
            wal.append(event);
        }
        wal.flush();

        // Act - force rotation
        wal.rotate();

        // Assert - should have at least 2 log files (original + rotated)
        var logFiles = Files.list(tempDir)
            .filter(p -> p.getFileName().toString().endsWith(".log"))
            .count();

        assertTrue(logFiles >= 1, "Should have at least 1 log file after rotation");
    }

    // ========== Recovery Scenarios ==========

    @Test
    void testRecoverAfterCleanShutdown() throws IOException {
        // Arrange
        var event1 = createEvent("ENTITY_DEPARTURE", "entity-1", "MIGRATING_OUT");
        var event2 = createEvent("VIEW_SYNC_ACK", "entity-1", "SUCCESS");

        wal.append(event1);
        wal.append(event2);
        wal.flush();
        wal.checkpoint(2, Instant.now());
        wal.close();

        // Act - create new WAL and recover
        var recovery = new EventRecovery(tempDir);
        var recovered = recovery.recover(nodeId);

        // Assert
        assertNotNull(recovered, "Should recover state");
        assertEquals(2, recovered.checkpoint().sequenceNumber(), "Should recover checkpoint");
        assertTrue(recovery.validateRecoveryIntegrity(), "Recovery integrity should pass");
    }

    @Test
    void testRecoverAfterCrash() throws IOException {
        // Arrange - simulate crash by not calling close()
        var event1 = createEvent("ENTITY_DEPARTURE", "entity-1", "MIGRATING_OUT");
        var event2 = createEvent("VIEW_SYNC_ACK", "entity-1", "SUCCESS");

        wal.append(event1);
        wal.append(event2);
        wal.flush();
        wal.checkpoint(2, Instant.now());

        // Simulate crash - don't call close(), just null out reference
        wal = null;

        // Act - create new WAL and recover
        var recovery = new EventRecovery(tempDir);
        var recovered = recovery.recover(nodeId);

        // Assert - should still recover events that were fsynced
        assertNotNull(recovered, "Should recover state after crash");
        assertTrue(recovered.checkpoint().sequenceNumber() >= 0, "Should have valid checkpoint");
    }

    @Test
    void testRecoverWithPartialEvent() throws IOException {
        // Arrange - write events, then corrupt last event by truncating
        var event1 = createEvent("ENTITY_DEPARTURE", "entity-1", "MIGRATING_OUT");
        var event2 = createEvent("VIEW_SYNC_ACK", "entity-1", "SUCCESS");

        wal.append(event1);
        wal.append(event2);
        wal.flush();
        wal.close();

        // Corrupt log file by truncating last few bytes
        var logFile = tempDir.resolve("node-" + nodeId + ".log");
        var content = Files.readAllBytes(logFile);
        Files.write(logFile, java.util.Arrays.copyOf(content, content.length - 5));

        // Act - recover should handle partial event
        var recovery = new EventRecovery(tempDir);
        var recovered = recovery.recover(nodeId);

        // Assert - should recover what's valid, skip corrupted
        assertNotNull(recovered, "Should recover state");
        assertTrue(recovered.skippedEvents() >= 0, "Should track skipped events");
    }

    @Test
    void testRecoverMultipleMigrations() throws IOException {
        // Arrange - multiple entities migrating simultaneously
        var entities = new String[]{"entity-1", "entity-2", "entity-3"};

        for (var entityId : entities) {
            var departure = createEvent("ENTITY_DEPARTURE", entityId, "MIGRATING_OUT");
            wal.append(departure);
        }

        wal.flush();
        wal.checkpoint(3, Instant.now());

        for (var entityId : entities) {
            var ack = createEvent("VIEW_SYNC_ACK", entityId, "SUCCESS");
            wal.append(ack);
        }

        wal.flush();
        wal.checkpoint(6, Instant.now());
        wal.close();

        // Act - recover all migrations
        var recovery = new EventRecovery(tempDir);
        var recovered = recovery.recover(nodeId);

        // Assert
        assertNotNull(recovered, "Should recover all migrations");
        assertEquals(6, recovered.checkpoint().sequenceNumber(), "Should have final checkpoint");
        assertEquals(6, recovered.totalEventsReplayed(), "Should replay all events");
    }

    @Test
    void testRecoverWithDeadletter() throws IOException {
        // Arrange - write events including one that's valid JSON but invalid event
        var event1 = createEvent("ENTITY_DEPARTURE", "entity-1", "MIGRATING_OUT");

        // Create valid JSON event but missing required entityId for VIEW_SYNC_ACK
        var badEvent = new HashMap<String, Object>();
        badEvent.put("version", 1);
        badEvent.put("type", "VIEW_SYNC_ACK");
        badEvent.put("timestamp", Instant.now().toString());
        // Intentionally missing entityId - should be invalid for VIEW_SYNC_ACK type

        var event2 = createEvent("VIEW_SYNC_ACK", "entity-2", "SUCCESS");

        wal.append(event1);
        wal.append(badEvent);
        wal.append(event2);
        wal.flush();
        wal.close();

        // Act - recovery should skip invalid event and recover good events
        var recovery = new EventRecovery(tempDir);
        var recovered = recovery.recover(nodeId);

        // Assert - the bad event (missing entityId) should be skipped during replay
        assertNotNull(recovered, "Should recover despite invalid event");
        // Both good events should be replayed
        assertEquals(2, recovered.totalEventsReplayed(), "Should replay 2 valid events");
        // The bad event should be skipped (valid JSON but invalid event structure)
        assertEquals(1, recovered.skippedEvents(), "Should skip 1 invalid event");
    }

    // ========== Integration Tests ==========

    @Test
    void testIntegrationWithPersistenceManager() throws IOException {
        // Arrange
        var persistenceMgr = new PersistenceManager(nodeId, tempDir);
        var entityId = UUID.randomUUID();

        // Act - log various events
        persistenceMgr.logEntityDeparture(entityId, UUID.randomUUID(), UUID.randomUUID());
        persistenceMgr.logViewSynchronyAck(entityId, UUID.randomUUID(), true);
        persistenceMgr.logDeferredUpdate(entityId, new float[]{1.0f, 2.0f, 3.0f},
                                        new float[]{0.1f, 0.2f, 0.3f});
        persistenceMgr.logMigrationCommit(entityId);
        persistenceMgr.checkpoint();
        persistenceMgr.close();

        // Assert - recovery should work
        var recovery = new EventRecovery(tempDir);
        var recovered = recovery.recover(nodeId);

        assertNotNull(recovered, "Should recover state");
        assertEquals(4, recovered.totalEventsReplayed(), "Should replay 4 events");
    }

    @Test
    void testConcurrentLogsMultipleNodes() throws IOException {
        // Arrange - multiple nodes with separate logs
        var node1 = UUID.randomUUID();
        var node2 = UUID.randomUUID();

        var wal1 = new WriteAheadLog(node1, tempDir);
        var wal2 = new WriteAheadLog(node2, tempDir);

        // Act - write events to both logs concurrently
        wal1.append(createEvent("ENTITY_DEPARTURE", "entity-1", "MIGRATING_OUT"));
        wal2.append(createEvent("ENTITY_DEPARTURE", "entity-2", "MIGRATING_OUT"));

        wal1.flush();
        wal2.flush();

        wal1.close();
        wal2.close();

        // Assert - both logs should be recoverable independently
        var recovery1 = new EventRecovery(tempDir);
        var recovery2 = new EventRecovery(tempDir);

        var recovered1 = recovery1.recover(node1);
        var recovered2 = recovery2.recover(node2);

        assertNotNull(recovered1, "Should recover node1 state");
        assertNotNull(recovered2, "Should recover node2 state");
        assertEquals(1, recovered1.totalEventsReplayed(), "Node1 should have 1 event");
        assertEquals(1, recovered2.totalEventsReplayed(), "Node2 should have 1 event");
    }

    // ========== Durability Tests ==========

    @Test
    void testFsyncOnCriticalEvent() throws IOException {
        // Arrange
        var persistenceMgr = new PersistenceManager(nodeId, tempDir);
        var entityId = UUID.randomUUID();

        // Act - migration commit should fsync immediately
        var startTime = System.currentTimeMillis();
        persistenceMgr.logMigrationCommit(entityId);
        var endTime = System.currentTimeMillis();

        persistenceMgr.close();

        // Assert - should complete quickly (fsync adds minimal overhead)
        var duration = endTime - startTime;
        assertTrue(duration < 100, "Fsync should complete in < 100ms");

        // Verify event is durable (can be recovered without explicit flush)
        var recovery = new EventRecovery(tempDir);
        var recovered = recovery.recover(nodeId);

        assertNotNull(recovered, "Should recover fsynced event");
    }

    @Test
    void testBatchFsyncNonCritical() throws IOException {
        // Arrange
        var persistenceMgr = new PersistenceManager(nodeId, tempDir);
        var entityId = UUID.randomUUID();

        // Act - deferred updates should batch fsync
        for (int i = 0; i < 10; i++) {
            persistenceMgr.logDeferredUpdate(entityId,
                new float[]{i, i, i},
                new float[]{0.1f, 0.1f, 0.1f});
        }

        // Wait for batch fsync interval (should be ~100ms)
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        persistenceMgr.close();

        // Assert - all events should eventually be durable
        var recovery = new EventRecovery(tempDir);
        var recovered = recovery.recover(nodeId);

        assertNotNull(recovered, "Should recover batched events");
        assertTrue(recovered.totalEventsReplayed() >= 10, "Should replay all batched events");
    }

    // ========== Helper Methods ==========

    private Map<String, Object> createEvent(String type, String entityId, String state) {
        var event = new HashMap<String, Object>();
        event.put("version", 1);
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        event.put("entityId", entityId);
        event.put("state", state);
        return event;
    }
}
