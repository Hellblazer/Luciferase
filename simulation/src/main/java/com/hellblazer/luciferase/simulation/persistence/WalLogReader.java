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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries the events parsed from one or more log files together with a count of
 * mid-file lines that could not be parsed (skippedCorrupt).
 *
 * <p>A malformed line that is the <em>final</em> line in a file (torn-tail after a crash) is
 * <strong>not</strong> counted — partial last records after a crash are an expected artifact of
 * append-only logs and must not be treated as corruption. A malformed line anywhere else in the
 * file IS counted, because it indicates a mid-stream write failure or log tampering that callers
 * should surface to the operator rather than silently skipping.
 *
 * @param events       parsed events in replay order
 * @param skippedCorrupt number of mid-file lines that failed to parse
 */
record WalReadResult(List<Map<String, Object>> events, int skippedCorrupt) {}

/**
 * WalLogReader - Read-only reader for write-ahead log files.
 *
 * <p>Recovery must NOT open the live WAL a second time: instantiating a full {@link WriteAheadLog}
 * over a running node's log directory opens the same JSONL log file with a second writable
 * {@code FileOutputStream} in append mode (via {@code initializeLogFile()}), while the owning WAL's
 * batch-flush scheduler is still writing. Two concurrent appenders interleave partial JSON lines and
 * corrupt the log irreparably (Luciferase-sc6pl).
 *
 * <p>This reader opens log files for reading only — no writable channel is ever created — so it is
 * safe to use against an active WAL directory. The file ordering and JSONL parsing match
 * {@link WriteAheadLog} exactly, including the numeric rotation-suffix ordering (Luciferase-cqy82).
 *
 * @author hal.hildebrand
 */
public final class WalLogReader {

    private static final Logger log = LoggerFactory.getLogger(WalLogReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID nodeId;
    private final Path logDirectory;

    public WalLogReader(UUID nodeId, Path logDirectory) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory must not be null");
    }

    /**
     * Read all events across this node's log files in chronological (rotation) order.
     *
     * <p>Mid-file parse failures are silently discarded. Use {@link #readAllEventsResult()} when
     * the caller needs to surface the corrupt count.
     *
     * @return all events in replay order
     * @throws IOException if a read fails
     */
    public List<Map<String, Object>> readAllEvents() throws IOException {
        return readAllEventsResult().events();
    }

    /**
     * Read all events, also returning the count of mid-file lines that could not be parsed.
     *
     * <p>A torn tail (malformed final line in a file) is <em>not</em> counted — it is an expected
     * artifact of crash-flush timing. A malformed line anywhere else in the file IS counted and
     * callers should refuse to proceed or alert the operator when {@code skippedCorrupt > 0}.
     *
     * @return {@link WalReadResult} carrying the events and skippedCorrupt count
     * @throws IOException if a read fails
     */
    public WalReadResult readAllEventsResult() throws IOException {
        var events = new ArrayList<Map<String, Object>>();
        int totalCorrupt = 0;
        var logFiles = findLogFiles();
        for (int i = 0; i < logFiles.size(); i++) {
            // Torn-tail exemption applies ONLY to the last (active/currently-appended) file.
            // Sealed/rotated files were cleanly closed; a corrupt last line there is real
            // corruption, not an acceptable crash artifact (H1 — Luciferase-7wzml.211).
            boolean allowTornTail = (i == logFiles.size() - 1);
            var result = readLogFile(logFiles.get(i), allowTornTail);
            events.addAll(result.events());
            totalCorrupt += result.skippedCorrupt();
        }
        return new WalReadResult(events, totalCorrupt);
    }

    /**
     * Read events with {@code sequence > sequenceNumber} (exclusive).
     *
     * <p>Mid-file parse failures are silently discarded. Use {@link #readEventsSinceResult(long)}
     * when the caller needs to surface the corrupt count.
     *
     * @param sequenceNumber starting sequence number (exclusive)
     * @return events after the given sequence number
     * @throws IOException if a read fails
     */
    public List<Map<String, Object>> readEventsSince(long sequenceNumber) throws IOException {
        return readEventsSinceResult(sequenceNumber).events();
    }

    /**
     * Read events since {@code sequenceNumber}, also returning the count of mid-file corrupt lines.
     *
     * @param sequenceNumber starting sequence number (exclusive)
     * @return {@link WalReadResult} carrying the filtered events and skippedCorrupt count
     * @throws IOException if a read fails
     */
    public WalReadResult readEventsSinceResult(long sequenceNumber) throws IOException {
        var allResult = readAllEventsResult();
        var filtered = new ArrayList<Map<String, Object>>();
        for (var event : allResult.events()) {
            var eventSeq = event.get("sequence");
            if (eventSeq instanceof Number n && n.longValue() > sequenceNumber) {
                filtered.add(event);
            }
        }
        return new WalReadResult(filtered, allResult.skippedCorrupt());
    }

    private List<Path> findLogFiles() throws IOException {
        var prefix = "node-" + nodeId;
        var logFiles = new ArrayList<Path>();
        try (var stream = Files.list(logDirectory)) {
            stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                  .filter(p -> p.getFileName().toString().endsWith(".log"))
                  .sorted(Comparator.comparingInt(this::rotationSuffix))
                  .forEach(logFiles::add);
        } catch (IOException e) {
            return List.of();
        }
        return logFiles;
    }

    private int rotationSuffix(Path path) {
        var fileName = path.getFileName().toString();
        var base = "node-" + nodeId;
        if (!fileName.startsWith(base) || !fileName.endsWith(".log")) {
            return 0;
        }
        var middle = fileName.substring(base.length(), fileName.length() - ".log".length());
        if (middle.isEmpty()) {
            return 0;
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

    /**
     * Read one log file and return its events plus a corrupt-line count.
     *
     * @param logFile      path to the log file to read
     * @param allowTornTail when {@code true}, a malformed final line is treated as an acceptable
     *                      crash-flush artifact (torn tail) and is NOT counted as corruption.
     *                      Pass {@code true} only for the active (currently-appended) file.
     *                      Pass {@code false} for sealed/rotated files: they were cleanly closed,
     *                      so a corrupt last line there is real corruption (H1 — Luciferase-7wzml.211).
     */
    private WalReadResult readLogFile(Path logFile, boolean allowTornTail) throws IOException {
        var events = new ArrayList<Map<String, Object>>();
        int skippedCorrupt = 0;

        // Read all non-empty lines first so we can distinguish a torn tail (last line only)
        // from a mid-file corruption (any other line). A torn tail after a crash-flush is an
        // expected artifact of append-only logs; a mid-file bad line is not.
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
                Map<String, Object> event = MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                if (event != null) {
                    events.add(event);
                }
            } catch (Exception e) {
                if (isFinalLine && allowTornTail) {
                    // Torn tail on the active file — acceptable after a crash; do NOT count as corruption.
                    log.debug("Ignoring torn tail at {}:{} (likely crash-flush truncation) - {}",
                              logFile, i + 1, e.getMessage());
                } else {
                    // Mid-file corruption, OR corrupt last line in a sealed file — must surface to caller.
                    log.warn("{}:{} — {} WAL line (corrupt)",
                             logFile, i + 1,
                             (isFinalLine ? "Corrupt last line in sealed file" : "Mid-file parse failure"));
                    skippedCorrupt++;
                }
            }
        }

        return new WalReadResult(events, skippedCorrupt);
    }
}
