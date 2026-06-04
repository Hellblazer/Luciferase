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
     * @return all events in replay order
     * @throws IOException if a read fails
     */
    public List<Map<String, Object>> readAllEvents() throws IOException {
        var events = new ArrayList<Map<String, Object>>();
        for (var logFile : findLogFiles()) {
            events.addAll(readLogFile(logFile));
        }
        return events;
    }

    /**
     * Read events with {@code sequence > sequenceNumber} (exclusive).
     *
     * @param sequenceNumber starting sequence number (exclusive)
     * @return events after the given sequence number
     * @throws IOException if a read fails
     */
    public List<Map<String, Object>> readEventsSince(long sequenceNumber) throws IOException {
        var filtered = new ArrayList<Map<String, Object>>();
        for (var event : readAllEvents()) {
            var eventSeq = event.get("sequence");
            if (eventSeq instanceof Number n && n.longValue() > sequenceNumber) {
                filtered.add(event);
            }
        }
        return filtered;
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

    private List<Map<String, Object>> readLogFile(Path logFile) throws IOException {
        var events = new ArrayList<Map<String, Object>>();
        try (var reader = Files.newBufferedReader(logFile)) {
            String line;
            var lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Map<String, Object> event = MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                    if (event != null) {
                        events.add(event);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse event at {}:{} - {}", logFile, lineNumber, e.getMessage());
                }
            }
        }
        return events;
    }
}
