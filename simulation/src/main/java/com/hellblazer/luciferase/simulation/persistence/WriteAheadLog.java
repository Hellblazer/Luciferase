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
import java.nio.file.StandardCopyOption;
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
     * Total bytes across all retained log segments for this node (sealed + active). Used by the
     * size-based compaction trigger (RDR-019 Phase 2). Returns 0 if the directory cannot be listed.
     *
     * @return summed size in bytes of all {@code node-<id>*.log} segments
     */
    public long retainedLogBytes() {
        try {
            long total = 0;
            for (var seg : findLogFiles()) {
                total += Files.size(seg);
            }
            return total;
        } catch (IOException e) {
            log.warn("Failed to measure retained log bytes for node {}", nodeId, e);
            return 0;
        }
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
     * the checkpoint metadata. RDR-017 P3 (gate O1) clean-shutdown compaction — called by
     * {@code PersistenceManager.closeClean()} after a head-sequence checkpoint. Under RDR-019 full-replay
     * semantics, recovery replays the ENTIRE retained log (it does not skip pre-checkpoint events);
     * discarding the segments here is safe because it leaves the log <em>empty</em>, so the next start's
     * full replay reads zero events — not because recovery would skip them. The high-water sequence
     * survives in the checkpoint metadata (see {@link #readCheckpointSequence()}), so a later restart
     * continues the monotonic sequence rather than restarting at 1 and colliding below the checkpoint.
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

    /**
     * Result of a {@link #compactCompletedMigrations()} run.
     *
     * @param sealedSegments  number of sealed segments scanned
     * @param prunedEvents    number of events removed (whole completed DEPARTURE+COMMIT pairs)
     * @param retainedEvents  number of events kept (in-flight departures + non-migration events)
     * @param watermark       highest sequence number pruned (0 if nothing pruned)
     * @param aborted         true if compaction made no changes (e.g. corrupt sealed segment); the log
     *                        is left intact so recovery can fail loud on the corruption
     */
    public record CompactionStats(int sealedSegments, long prunedEvents, long retainedEvents,
                                  long watermark, boolean aborted) {}

    /**
     * Mid-run compaction (RDR-019 Phase 2, gate S2): bound the retained log by pruning WHOLE completed
     * migration cycles — an {@code ENTITY_DEPARTURE} together with its matching {@code MIGRATION_COMMIT} —
     * while never dropping an in-flight (uncommitted) departure or a non-migration event.
     *
     * <p><b>Gate S2 — concurrent-write safety.</b> The active segment is SEALED first (roll to a fresh
     * segment via {@link #rotate()}); compaction then scans and rewrites ONLY the now-sealed segments.
     * The live segment that subsequent {@link #append(Map)} calls write to is never rewritten. (This
     * method is also {@code synchronized} on the WAL monitor, so no append interleaves — belt and
     * suspenders; the seal-then-compact-sealed design is the load-bearing guarantee.)
     *
     * <p><b>Pruning rule (Q2 order constraint).</b> An entity's events are prunable only if BOTH an
     * {@code ENTITY_DEPARTURE} and a {@code MIGRATION_COMMIT} for it appear among the sealed events. Only
     * those two event types, for such completed entities, are dropped — never a partial pair, never an
     * in-flight departure, and never a non-migration event ({@code DEFERRED_UPDATE}, {@code VIEW_SYNC_ACK},
     * consensus types are retained conservatively — gate O3; no recovery consumer, so never assumed
     * prunable). Relative order of retained events is preserved.
     *
     * <p><b>Bounded replay without logical filtering.</b> Pruned events are physically removed from disk,
     * so the next recovery's FULL replay (RDR-019 Phase 1 contract) is naturally bounded — there is no
     * watermark-filtered replay. This is deliberate: re-introducing a logical replay bound is the exact
     * RDR-019 data-loss class. The watermark is recorded in metadata for diagnostics/versioning only.
     *
     * <p><b>Crash safety.</b> Write-new-then-rename: retained sealed events are streamed to a temp file,
     * fsync'd, then atomically renamed over the base segment; the other sealed segments are deleted only
     * afterwards. A crash at any point leaves a fully recoverable (possibly un- or partially-compacted)
     * log with no event loss — replay is idempotent (duplicate migrations are deduped on recovery).
     *
     * <p><b>Corruption.</b> If a sealed segment contains a mid-file corrupt line, compaction ABORTS
     * (returns {@code aborted=true}, no changes) so the corruption survives for the recovery fail-loud
     * gate rather than being silently rewritten away. A torn final line of the last sealed segment
     * (crash-flush) is tolerated and dropped, mirroring recovery.
     *
     * @return statistics describing the compaction
     * @throws IOException if compaction I/O fails
     */
    public synchronized CompactionStats compactCompletedMigrations() throws IOException {
        checkNotClosed();

        // Seal the active segment: everything that exists before this roll becomes sealed (read-only);
        // appends after this point land in the fresh active segment, which compaction never touches.
        rotate();
        var activeSegment = currentLogFile;

        var sealed = new ArrayList<Path>();
        for (var f : findLogFiles()) {
            if (!f.equals(activeSegment)) {
                sealed.add(f);
            }
        }
        if (sealed.isEmpty()) {
            return new CompactionStats(0, 0, 0, 0, false);
        }

        // Pass 1: per-entity CYCLE accounting. An entity may migrate more than once within the retained
        // log (DEPARTURE→COMMIT, then DEPARTURE again, ...). We must prune only events of COMPLETED
        // cycles and NEVER an in-flight (uncommitted) departure — otherwise a re-migration's trailing
        // DEPARTURE is dropped and recovery reconstructs null instead of MIGRATING_OUT (the RDR-004-class
        // data loss this RDR closes; substantive-critic RDR-019 P2.4).
        //
        // By the Q2 append-order guarantee, an entity's events are DEPARTURE,COMMIT,DEPARTURE,COMMIT,...
        // optionally ending in a trailing in-flight DEPARTURE. With d departures and c commits in sealed,
        // the number of completed cycles is min(d, c); the trailing max(0, d - c) departures are in-flight.
        // So: prune every COMMIT (each closes a cycle whose DEPARTURE is also pruned, or is an orphan whose
        // DEPARTURE was pruned by an earlier compaction), and prune the FIRST min(d, c) departures (by
        // order), retaining the trailing in-flight ones. (A COMMIT can never appear in sealed with its
        // DEPARTURE in the still-active segment: DEPARTURE precedes COMMIT in time, and the seal is a
        // single point in time.)
        var departureCounts = new HashMap<String, Integer>();
        var commitCounts = new HashMap<String, Integer>();
        for (int i = 0; i < sealed.size(); i++) {
            List<Map<String, Object>> events;
            try {
                events = parseSegmentForCompaction(sealed.get(i), i == sealed.size() - 1);
            } catch (CompactionAbortException e) {
                log.warn("Compaction aborted for node {}: {} — leaving log intact for fail-loud recovery",
                         nodeId, e.getMessage());
                return new CompactionStats(sealed.size(), 0, 0, 0, true);
            }
            for (var event : events) {
                var type = (String) event.get("type");
                var entityId = event.get("entityId");
                if (entityId == null) {
                    continue;
                }
                if ("ENTITY_DEPARTURE".equals(type)) {
                    departureCounts.merge(entityId.toString(), 1, Integer::sum);
                } else if ("MIGRATION_COMMIT".equals(type)) {
                    commitCounts.merge(entityId.toString(), 1, Integer::sum);
                }
            }
        }

        long totalCommits = commitCounts.values().stream().mapToLong(Integer::longValue).sum();
        if (totalCommits == 0) {
            // No completed cycle anywhere in sealed → nothing prunable (every departure is in-flight).
            // The seal already happened (harmless empty active segment).
            return new CompactionStats(sealed.size(), 0, countSealed(sealed), 0, false);
        }

        // Per entity, the number of departures (oldest-first) that belong to completed cycles and are
        // therefore prunable; the remaining trailing departures are in-flight and retained.
        var prunableDepartures = new HashMap<String, Integer>();
        for (var e : departureCounts.entrySet()) {
            prunableDepartures.put(e.getKey(), Math.min(e.getValue(), commitCounts.getOrDefault(e.getKey(), 0)));
        }

        // Pass 2: stream retained events to a temp file (no full in-memory rewrite), preserving order.
        // Prune every COMMIT, and the first min(d,c) DEPARTUREs per entity (the completed-cycle ones),
        // retaining trailing in-flight departures and all non-migration events.
        var tempFile = logDirectory.resolve("node-" + nodeId + ".compact.tmp");
        var departuresPrunedSoFar = new HashMap<String, Integer>();
        long pruned = 0;
        long retained = 0;
        long watermark = 0;
        try (var fos = new FileOutputStream(tempFile.toFile(), false);
             var ch = fos.getChannel();
             var out = new BufferedWriter(new OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8))) {
            for (int i = 0; i < sealed.size(); i++) {
                List<Map<String, Object>> events;
                try {
                    events = parseSegmentForCompaction(sealed.get(i), i == sealed.size() - 1);
                } catch (CompactionAbortException e) {
                    log.warn("Compaction aborted (pass 2) for node {}: {} — leaving log intact", nodeId, e.getMessage());
                    Files.deleteIfExists(tempFile);
                    return new CompactionStats(sealed.size(), 0, 0, 0, true);
                }
                for (var event : events) {
                    var type = (String) event.get("type");
                    var entityId = event.get("entityId");
                    boolean prune = false;
                    if (entityId != null) {
                        var id = entityId.toString();
                        if ("MIGRATION_COMMIT".equals(type)) {
                            prune = true;
                        } else if ("ENTITY_DEPARTURE".equals(type)) {
                            int budget = prunableDepartures.getOrDefault(id, 0);
                            int done = departuresPrunedSoFar.getOrDefault(id, 0);
                            if (done < budget) {
                                prune = true;
                                departuresPrunedSoFar.put(id, done + 1);
                            }
                        }
                    }
                    if (prune) {
                        pruned++;
                        var seqRaw = event.get("sequence");
                        if (seqRaw instanceof Number n) {
                            watermark = Math.max(watermark, n.longValue());
                        }
                        continue;
                    }
                    out.write(MAPPER.writeValueAsString(event));
                    out.write(System.lineSeparator());
                    retained++;
                }
            }
            out.flush();
            ch.force(true);
        }

        // Atomic publish: rename temp over the base segment, then delete the other (now superseded)
        // sealed segments. Order matters for crash-safety: the rename happens BEFORE deletion so a crash
        // between the two leaves duplicate (but never missing) events, which recovery dedupes.
        var baseSegment = logDirectory.resolve("node-" + nodeId + ".log");
        try {
            Files.move(tempFile, baseSegment, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tempFile, baseSegment, StandardCopyOption.REPLACE_EXISTING);
        }
        for (var seg : sealed) {
            if (!seg.equals(baseSegment)) {
                Files.deleteIfExists(seg);
            }
        }

        writeCompactionWatermark(watermark);
        log.info("WriteAheadLog compacted for node {}: {} sealed segment(s), {} pruned, {} retained, watermark={}",
                 nodeId, sealed.size(), pruned, retained, watermark);
        return new CompactionStats(sealed.size(), pruned, retained, watermark, false);
    }

    /** Count events across sealed segments (used only when nothing is pruned, for stats). */
    private long countSealed(List<Path> sealed) throws IOException {
        long n = 0;
        for (int i = 0; i < sealed.size(); i++) {
            try {
                n += parseSegmentForCompaction(sealed.get(i), i == sealed.size() - 1).size();
            } catch (CompactionAbortException e) {
                return n;
            }
        }
        return n;
    }

    /** Signals that compaction must abort and leave the log intact (mid-file corruption). */
    private static final class CompactionAbortException extends Exception {
        CompactionAbortException(String message) { super(message); }
    }

    /**
     * Parse a sealed segment for compaction. A torn final line of the LAST sealed segment (crash-flush)
     * is tolerated and skipped; any other parse failure is mid-file corruption and aborts compaction
     * (so it survives for the recovery fail-loud gate rather than being silently rewritten away).
     */
    private List<Map<String, Object>> parseSegmentForCompaction(Path segment, boolean isLastSealed)
            throws IOException, CompactionAbortException {
        var rawLines = new ArrayList<String>();
        try (var reader = Files.newBufferedReader(segment)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    rawLines.add(line);
                }
            }
        }
        var events = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rawLines.size(); i++) {
            boolean isFinalLine = (i == rawLines.size() - 1);
            try {
                Map<String, Object> event = MAPPER.readValue(rawLines.get(i), new TypeReference<Map<String, Object>>() {});
                if (event != null) {
                    events.add(event);
                }
            } catch (Exception e) {
                if (isLastSealed && isFinalLine) {
                    log.debug("Compaction tolerating torn tail at {}:{} - {}", segment, i + 1, e.getMessage());
                } else {
                    throw new CompactionAbortException(
                        "mid-file corrupt line at " + segment + ":" + (i + 1) + " - " + e.getMessage());
                }
            }
        }
        return events;
    }

    /** Stamp the compaction watermark + format version into metadata (diagnostics/versioning only). */
    private void writeCompactionWatermark(long watermark) {
        try {
            var meta = new HashMap<String, Object>();
            meta.put("watermark", watermark);
            meta.put("formatVersion", 1);
            meta.put("nodeId", nodeId.toString());
            meta.put("timestamp", Instant.ofEpochMilli(clock.currentTimeMillis()).toString());
            var file = logDirectory.resolve("node-" + nodeId + ".compaction.meta");
            Files.writeString(file, MAPPER.writeValueAsString(meta),
                              StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // Watermark metadata is diagnostic only; failure to write it must not fail compaction.
            log.warn("Failed to write compaction watermark metadata for node {}", nodeId, e);
        }
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
