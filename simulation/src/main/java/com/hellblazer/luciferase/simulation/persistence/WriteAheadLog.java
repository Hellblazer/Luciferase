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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WriteAheadLog - Durable event logging to disk (Phase 7G Day 2)
 *
 * Provides append-only log file with thread-safe operations, fsync on critical events,
 * and log rotation by size. Uses JSONL format (JSON Lines) for human readability.
 *
 * LOG FORMAT (JSONL):
 * {"version":1,"timestamp":"2026-01-10T05:47:00Z","type":"ENTITY_DEPARTURE",...}
 * {"version":1,"timestamp":"2026-01-10T05:47:01Z","type":"VIEW_SYNC_ACK",...}
 *
 * DURABILITY:
 * - Critical events (migration commit) fsync immediately
 * - Non-critical events batch fsync every 100ms
 * - Log rotation at 10MB file size
 * - Metadata file tracks recovery checkpoints
 *
 * THREAD SAFETY:
 * All operations are thread-safe via synchronized methods on the writer.
 *
 * @author hal.hildebrand
 */
public class WriteAheadLog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WriteAheadLog.class);
    private static final long DEFAULT_ROTATION_SIZE = 10 * 1024 * 1024; // 10MB
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID nodeId;
    final Path logDirectory; // package-private for EventRecovery
    private final Path metadataFile;
    private final AtomicBoolean isClosed;
    private final AtomicLong currentSize;
    private final AtomicLong sequenceCounter;  // Event sequence number
    private final long rotationSize;
    private volatile Clock clock = Clock.system();

    private Path currentLogFile;
    private BufferedWriter writer;
    private FileChannel fileChannel;
    private int rotationCount;

    /**
     * Create WriteAheadLog with default rotation size.
     *
     * @param nodeId Node UUID for log file naming
     * @param logDirectory Directory to store log files
     * @throws IOException if log file cannot be created
     */
    public WriteAheadLog(UUID nodeId, Path logDirectory) throws IOException {
        this(nodeId, logDirectory, DEFAULT_ROTATION_SIZE);
    }

    /**
     * Create WriteAheadLog with custom rotation size.
     *
     * @param nodeId Node UUID for log file naming
     * @param logDirectory Directory to store log files
     * @param rotationSize Size in bytes to trigger rotation
     * @throws IOException if log file cannot be created
     */
    public WriteAheadLog(UUID nodeId, Path logDirectory, long rotationSize) throws IOException {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory must not be null");
        this.rotationSize = rotationSize;
        this.isClosed = new AtomicBoolean(false);
        this.currentSize = new AtomicLong(0);
        this.sequenceCounter = new AtomicLong(0);  // Start sequences at 0
        this.rotationCount = 0;

        // Ensure log directory exists
        Files.createDirectories(logDirectory);

        // Initialize metadata file
        this.metadataFile = logDirectory.resolve("node-" + nodeId + ".meta");

        // Initialize log file
        initializeLogFile();

        log.debug("WriteAheadLog initialized for node {} at {}", nodeId, logDirectory);
    }

    /**
     * Set Clock implementation for deterministic testing.
     *
     * @param clock Clock implementation
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Append event to log (thread-safe).
     * Automatically adds "sequence" field to event for ordering and filtering.
     *
     * @param event Event data as map
     * @throws IOException if write fails
     * @throws IllegalStateException if log is closed
     */
    public synchronized void append(Map<String, Object> event) throws IOException {
        Objects.requireNonNull(event, "event must not be null");
        checkNotClosed();

        // Add sequence number to event (1-indexed)
        var sequence = sequenceCounter.incrementAndGet();
        event.put("sequence", sequence);

        // Serialize event to JSON line
        var json = MAPPER.writeValueAsString(event);
        var line = json + System.lineSeparator();

        // Write to log
        writer.write(line);

        // Update size counter
        var written = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        currentSize.addAndGet(written);

        // Check if rotation needed
        if (currentSize.get() >= rotationSize) {
            rotate();
        }
    }

    /**
     * Force fsync to disk (thread-safe).
     *
     * @throws IOException if flush fails
     */
    public synchronized void flush() throws IOException {
        checkNotClosed();
        writer.flush();

        // Force fsync via FileChannel.force(true)
        // true = sync both data and metadata (required for WAL)
        if (fileChannel != null && fileChannel.isOpen()) {
            var startTime = clock.nanoTime();
            fileChannel.force(true);
            var elapsed = clock.nanoTime() - startTime;
            log.trace("fsync completed in {}ns", elapsed);
        }
    }

    /**
     * The current global event sequence (high-water mark).
     * <p>
     * This is the authoritative sequence counter, restored from the persisted log (and checkpoint
     * metadata) on construction via {@link #restoreSequenceCounter()} so it remains monotonic across
     * process restarts. RDR-019 Gap 4: {@link PersistenceManager#checkpoint()} must source the
     * checkpoint sequence from this value, NOT from a session-local counter that resets to 0 on each
     * restart (a reset counter checkpoints below the true high-water, silently bounding recovery
     * replay and dropping durably-logged events).
     *
     * @return the highest sequence number assigned so far (0 if no events have ever been appended)
     */
    public long getSequence() {
        return sequenceCounter.get();
    }

    /**
     * Mark recovery checkpoint in metadata file.
     *
     * @param sequenceNumber Sequence number for checkpoint
     * @param timestamp Checkpoint timestamp
     * @throws IOException if metadata write fails
     */
    public synchronized void checkpoint(long sequenceNumber, Instant timestamp) throws IOException {
        checkNotClosed();

        var metadata = new HashMap<String, Object>();
        metadata.put("sequenceNumber", sequenceNumber);
        metadata.put("timestamp", timestamp.toString());
        metadata.put("nodeId", nodeId.toString());

        var json = MAPPER.writeValueAsString(metadata);
        Files.writeString(metadataFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.debug("Checkpoint created: seq={}, timestamp={}", sequenceNumber, timestamp);
    }

    /**
     * Rotate to new log file (thread-safe).
     *
     * @throws IOException if rotation fails
     */
    public synchronized void rotate() throws IOException {
        checkNotClosed();

        // Close current writer and channel
        if (writer != null) {
            writer.flush();
            writer.close();
        }
        if (fileChannel != null) {
            fileChannel.close();
        }

        // Increment rotation counter
        rotationCount++;

        // Create new log file
        currentLogFile = logDirectory.resolve("node-" + nodeId + "-" + rotationCount + ".log");
        var fos = new FileOutputStream(currentLogFile.toFile(), true);
        this.fileChannel = fos.getChannel();
        this.writer = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8));
        currentSize.set(0);

        log.debug("Log rotated to {}", currentLogFile);
    }

    /**
     * Read all events from log files (for recovery).
     *
     * @return List of all events in log order
     * @throws IOException if read fails
     */
    public List<Map<String, Object>> readAllEvents() throws IOException {
        var events = new ArrayList<Map<String, Object>>();

        // Find all log files for this node
        var logFiles = findLogFiles();

        // Read each log file in order
        for (var logFile : logFiles) {
            events.addAll(readLogFile(logFile));
        }

        return events;
    }

    /**
     * Read events since specific sequence number.
     * Returns events with sequence > sequenceNumber (exclusive).
     *
     * @param sequenceNumber Starting sequence number (exclusive)
     * @return List of events after sequence number
     * @throws IOException if read fails
     */
    public List<Map<String, Object>> readEventsSince(long sequenceNumber) throws IOException {
        var allEvents = readAllEvents();
        var filtered = new ArrayList<Map<String, Object>>();

        for (var event : allEvents) {
            var eventSeq = event.get("sequence");
            if (eventSeq instanceof Number) {
                var seq = ((Number) eventSeq).longValue();
                if (seq > sequenceNumber) {
                    filtered.add(event);
                }
            }
        }

        return filtered;
    }

    /**
     * Close log and release resources (thread-safe).
     *
     * @throws IOException if close fails
     */
    @Override
    public synchronized void close() throws IOException {
        if (isClosed.compareAndSet(false, true)) {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
            if (fileChannel != null) {
                fileChannel.close();
                fileChannel = null;
            }
            log.debug("WriteAheadLog closed for node {}", nodeId);
        }
    }

    // ========== Private Helper Methods ==========

    private void initializeLogFile() throws IOException {
        // Find existing log files or create new one
        var logFiles = findLogFiles();

        if (logFiles.isEmpty()) {
            // No existing logs, create new one
            currentLogFile = logDirectory.resolve("node-" + nodeId + ".log");
            var fos = new FileOutputStream(currentLogFile.toFile(), true);
            this.fileChannel = fos.getChannel();
            this.writer = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8));
            currentSize.set(0);
        } else {
            // Use most recent log file
            currentLogFile = logFiles.get(logFiles.size() - 1);
            var existingSize = Files.size(currentLogFile);
            currentSize.set(existingSize);
            var fos = new FileOutputStream(currentLogFile.toFile(), true);
            this.fileChannel = fos.getChannel();
            this.writer = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8));

            // Set rotation count from file name (robust against UUID hyphens)
            rotationCount = rotationSuffix(currentLogFile);
        }

        // Restore sequence counter from existing log content so sequence numbers remain
        // globally monotonic across process restarts (otherwise they restart at 1,2,3...
        // and collide with prior runs, breaking readEventsSince filtering).
        restoreSequenceCounter();
    }

    /**
     * Scan all existing log files for this node and initialize the sequence counter to the
     * highest persisted sequence number, so subsequently-appended events continue the
     * monotonic sequence rather than colliding with prior-run sequence numbers.
     */
    private void restoreSequenceCounter() {
        long maxSequence = 0;
        try {
            for (var event : readAllEvents()) {
                var seq = event.get("sequence");
                if (seq instanceof Number n) {
                    maxSequence = Math.max(maxSequence, n.longValue());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan existing log for sequence restoration on node {}; starting at 0", nodeId, e);
            return;
        }
        // RDR-017 P3 (gate O1): a clean shutdown checkpoints then truncates the log files, so after a
        // clean restart the logs are empty but the checkpoint .meta still records the high-water
        // sequence. Seed from max(log max, checkpoint seq) so newly-appended events continue PAST the
        // checkpoint rather than restarting at 1 and colliding below it (which readEventsSince would
        // then silently filter out on the next recovery).
        maxSequence = Math.max(maxSequence, readCheckpointSequence());
        sequenceCounter.set(maxSequence);
        if (maxSequence > 0) {
            log.debug("Restored WAL sequence counter to {} for node {}", maxSequence, nodeId);
        }
    }

    /**
     * Read the checkpoint sequence number from the metadata file, or 0 if none exists / unreadable.
     */
    private long readCheckpointSequence() {
        if (!Files.exists(metadataFile)) {
            return 0L;
        }
        try {
            var json = Files.readString(metadataFile);
            var meta = MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            var seq = meta.get("sequenceNumber");
            return seq instanceof Number n ? n.longValue() : 0L;
        } catch (IOException e) {
            log.warn("Failed to read checkpoint metadata for node {}; sequence seed from log only", nodeId, e);
            return 0L;
        }
    }

    /**
     * Truncate the write-ahead log: delete all log segment files and start an empty base log, retaining
     * the checkpoint metadata. RDR-017 P3 (gate O1) clean-shutdown compaction — after a checkpoint at
     * the head sequence, the entire log is superseded (recovery replays only post-checkpoint events, of
     * which there are none), so the segments are safe to discard to reclaim space. The high-water
     * sequence survives in the checkpoint metadata (see {@link #readCheckpointSequence()}), so a later
     * restart continues the monotonic sequence rather than colliding below the checkpoint.
     *
     * @throws IOException if segment deletion or base-log recreation fails
     */
    public synchronized void truncate() throws IOException {
        checkNotClosed();

        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
        if (fileChannel != null) {
            fileChannel.close();
            fileChannel = null;
        }

        for (var logFile : findLogFiles()) {
            Files.deleteIfExists(logFile);
        }

        rotationCount = 0;
        currentLogFile = logDirectory.resolve("node-" + nodeId + ".log");
        var fos = new FileOutputStream(currentLogFile.toFile(), true);
        this.fileChannel = fos.getChannel();
        this.writer = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8));
        currentSize.set(0);

        log.info("WriteAheadLog truncated for node {} (clean-shutdown compaction)", nodeId);
    }

    private List<Path> findLogFiles() throws IOException {
        var prefix = "node-" + nodeId;
        var logFiles = new ArrayList<Path>();

        try (var stream = Files.list(logDirectory)) {
            // Sort by numeric rotation suffix (base=0, node-UUID-N.log=N) so replay is chronological.
            // Plain lexicographic .sorted() is WRONG: ASCII '-'(45) < '.'(46) puts node-UUID-1.log
            // before node-UUID.log, and orders -10 before -2, inverting causal order.
            stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                  .filter(p -> p.getFileName().toString().endsWith(".log"))
                  .sorted(Comparator.comparingInt(this::rotationSuffix))
                  .forEach(logFiles::add);
        } catch (IOException e) {
            // Directory might not exist yet
            return List.of();
        }

        return logFiles;
    }

    /**
     * Extract the numeric rotation suffix from a log file path.
     * The base file ({@code node-UUID.log}) returns 0; rotated files ({@code node-UUID-N.log})
     * return N. The node id is a UUID (which itself contains hyphens), so the suffix is parsed
     * relative to the fixed {@code node-UUID} prefix rather than by splitting on '-'.
     *
     * @param path log file path
     * @return rotation number (base=0), or 0 if the name does not match the rotated pattern
     */
    private int rotationSuffix(Path path) {
        var fileName = path.getFileName().toString();
        var base = "node-" + nodeId; // node-<UUID>
        if (!fileName.startsWith(base) || !fileName.endsWith(".log")) {
            return 0;
        }
        var middle = fileName.substring(base.length(), fileName.length() - ".log".length());
        if (middle.isEmpty()) {
            return 0; // base file: node-UUID.log
        }
        if (middle.startsWith("-")) {
            try {
                return Integer.parseInt(middle.substring(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private List<Map<String, Object>> readLogFile(Path logFile) throws IOException {
        var events = new ArrayList<Map<String, Object>>();

        // Collect non-empty lines first so we can detect torn-tail (final line only) vs
        // mid-file corruption. This method is used internally (restoreSequenceCounter) and
        // does not propagate a corrupt count; use WalLogReader for recovery paths.
        var rawLines = new ArrayList<String>();
        try (var reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    rawLines.add(line);
                }
            }
        }

        for (int i = 0; i < rawLines.size(); i++) {
            var line = rawLines.get(i);
            boolean isFinalLine = (i == rawLines.size() - 1);
            try {
                Map<String, Object> event = MAPPER.readValue(line, new TypeReference<Map<String, Object>>(){});
                if (event != null) {
                    events.add(event);
                }
            } catch (Exception e) {
                if (isFinalLine) {
                    log.debug("Ignoring torn tail at {}:{} (likely crash-flush truncation) - {}",
                              logFile, i + 1, e.getMessage());
                } else {
                    log.warn("Mid-file parse failure at {}:{} (corrupt WAL line) - {}",
                             logFile, i + 1, e.getMessage());
                }
            }
        }

        return events;
    }

    private void checkNotClosed() {
        if (isClosed.get()) {
            throw new IllegalStateException("WriteAheadLog is closed");
        }
    }
}
