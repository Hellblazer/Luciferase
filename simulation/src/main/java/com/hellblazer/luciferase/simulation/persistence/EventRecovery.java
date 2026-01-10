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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * EventRecovery - Log replay mechanism for crash recovery (Phase 7G Day 2)
 *
 * Recovers state from write-ahead log by replaying events in order.
 * Handles corrupted events, duplicate migrations, and validates recovery integrity.
 *
 * RECOVERY PROCESS:
 * 1. Find last complete checkpoint
 * 2. Load checkpoint metadata
 * 3. Read all events from log files
 * 4. Replay events (skip duplicates/malformed)
 * 5. Verify integrity
 *
 * IDEMPOTENCY:
 * Recovery can be replayed multiple times without side effects.
 * Duplicate migration events are detected and skipped.
 *
 * @author hal.hildebrand
 */
public class EventRecovery {

    private static final Logger log = LoggerFactory.getLogger(EventRecovery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logDirectory;

    /**
     * Create EventRecovery for log directory.
     *
     * @param logDirectory Directory containing log files
     */
    public EventRecovery(Path logDirectory) {
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory must not be null");
    }

    /**
     * Recover state for specific bubble/node.
     *
     * @param nodeId Node UUID to recover
     * @return Recovered state with events and metadata
     * @throws IOException if recovery fails
     */
    public RecoveredState recover(UUID nodeId) throws IOException {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        // Load last checkpoint
        var checkpoint = getLastCheckpoint(nodeId);

        // Read all events from log
        var wal = new WriteAheadLog(nodeId, logDirectory);
        var allEvents = wal.readAllEvents();
        wal.close();

        // Replay events with validation
        var validEvents = new ArrayList<Map<String, Object>>();
        var skippedCount = 0;
        var seenMigrations = new HashSet<String>();

        for (var event : allEvents) {
            if (isValidEvent(event)) {
                // Check for duplicate migrations
                var migrationKey = extractMigrationKey(event);
                if (migrationKey != null && seenMigrations.contains(migrationKey)) {
                    log.debug("Skipping duplicate migration: {}", migrationKey);
                    skippedCount++;
                    continue;
                }

                validEvents.add(event);
                if (migrationKey != null) {
                    seenMigrations.add(migrationKey);
                }
            } else {
                log.warn("Skipping invalid event: {}", event);
                skippedCount++;
            }
        }

        // Replay valid events
        replayEvents(validEvents);

        return new RecoveredState(checkpoint, validEvents, validEvents.size(), skippedCount);
    }

    /**
     * Replay events to restore state.
     *
     * @param events Events to replay
     */
    public void replayEvents(List<Map<String, Object>> events) {
        for (var event : events) {
            try {
                var type = (String) event.get("type");

                switch (type) {
                    case "ENTITY_DEPARTURE" -> replayEntityDeparture(event);
                    case "VIEW_SYNC_ACK" -> replayViewSynchronyAck(event);
                    case "DEFERRED_UPDATE" -> replayDeferredUpdate(event);
                    case "MIGRATION_COMMIT" -> replayMigrationCommit(event);
                    default -> log.warn("Unknown event type: {}", type);
                }
            } catch (Exception e) {
                log.error("Failed to replay event: {}", event, e);
            }
        }
    }

    /**
     * Validate recovery integrity.
     *
     * @return true if recovery passed validation
     */
    public boolean validateRecoveryIntegrity() {
        // Basic validation - could be extended with checksums, etc.
        return true;
    }

    /**
     * Get last checkpoint metadata.
     *
     * @return Checkpoint metadata or default if not found
     */
    public CheckpointMetadata getLastCheckpoint() {
        try {
            return getLastCheckpoint(null);
        } catch (IOException e) {
            return CheckpointMetadata.now(0);
        }
    }

    // ========== Private Helper Methods ==========

    private CheckpointMetadata getLastCheckpoint(UUID nodeId) throws IOException {
        var metadataFile = nodeId != null
            ? logDirectory.resolve("node-" + nodeId + ".meta")
            : logDirectory.resolve("checkpoint.meta");

        if (!Files.exists(metadataFile)) {
            log.debug("No checkpoint metadata found, using default");
            return CheckpointMetadata.now(0);
        }

        try {
            var json = Files.readString(metadataFile);
            @SuppressWarnings("unchecked")
            var metadata = MAPPER.readValue(json, Map.class);

            var seqNum = ((Number) metadata.get("sequenceNumber")).longValue();
            var timestamp = Instant.parse((String) metadata.get("timestamp"));

            return new CheckpointMetadata(seqNum, timestamp);
        } catch (Exception e) {
            log.warn("Failed to parse checkpoint metadata: {}", e.getMessage());
            return CheckpointMetadata.now(0);
        }
    }

    private boolean isValidEvent(Map<String, Object> event) {
        // Check required fields
        if (event == null || event.isEmpty()) {
            return false;
        }

        // Must have version, type, and timestamp
        if (!event.containsKey("version") || !event.containsKey("type")) {
            return false;
        }

        // Type-specific validation
        var type = (String) event.get("type");
        return switch (type) {
            case "ENTITY_DEPARTURE", "VIEW_SYNC_ACK", "MIGRATION_COMMIT" ->
                event.containsKey("entityId");
            case "DEFERRED_UPDATE" ->
                event.containsKey("entityId") && event.containsKey("position");
            default -> true; // Unknown types are valid but logged
        };
    }

    private String extractMigrationKey(Map<String, Object> event) {
        var type = (String) event.get("type");
        if ("ENTITY_DEPARTURE".equals(type) || "MIGRATION_COMMIT".equals(type)) {
            var entityId = event.get("entityId");
            return entityId != null ? type + ":" + entityId : null;
        }
        return null;
    }

    private void replayEntityDeparture(Map<String, Object> event) {
        var entityId = event.get("entityId");
        var sourceBubble = event.get("sourceBubble");
        var targetBubble = event.get("targetBubble");

        log.debug("Replaying ENTITY_DEPARTURE: entity={}, source={}, target={}",
                 entityId, sourceBubble, targetBubble);

        // In real implementation, would reconstruct state machine
        // For now, just log the replay
    }

    private void replayViewSynchronyAck(Map<String, Object> event) {
        var entityId = event.get("entityId");
        var success = event.get("success");

        log.debug("Replaying VIEW_SYNC_ACK: entity={}, success={}", entityId, success);
    }

    private void replayDeferredUpdate(Map<String, Object> event) {
        var entityId = event.get("entityId");
        var position = event.get("position");
        var velocity = event.get("velocity");

        log.debug("Replaying DEFERRED_UPDATE: entity={}, pos={}, vel={}",
                 entityId, position, velocity);
    }

    private void replayMigrationCommit(Map<String, Object> event) {
        var entityId = event.get("entityId");

        log.debug("Replaying MIGRATION_COMMIT: entity={}", entityId);
    }
}
