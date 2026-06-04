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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for WriteAheadLog rotation ordering, double-open avoidance, and
 * sequence-counter restoration across restart.
 *
 * <ul>
 *   <li>Luciferase-cqy82: findLogFiles must order by numeric rotation suffix (base=0, -N=N), so
 *       replay is chronological even with -2 and -10 present (lexicographic order is wrong).</li>
 *   <li>Luciferase-sc6pl: recovery must use a read-only reader and not corrupt a live WAL.</li>
 *   <li>Luciferase-0frcy.115: sequence counter must continue monotonically after restart.</li>
 * </ul>
 */
class WalRotationOrderingTest {

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

    private Map<String, Object> event(String marker) {
        var m = new HashMap<String, Object>();
        m.put("version", 1);
        m.put("type", "DEFERRED_UPDATE");
        m.put("entityId", marker);
        m.put("position", List.of(0, 0, 0));
        m.put("marker", marker);
        return m;
    }

    /**
     * Luciferase-cqy82: write across the base file plus rotated files including -2 and -10, then
     * assert replay order is chronological (ascending sequence), NOT lexicographic.
     */
    @Test
    void replayOrderIsChronologicalAcrossRotationsIncluding2And10() throws IOException {
        // Tiny rotation size makes append() auto-rotate after every write, producing the base file
        // node-UUID.log (first event) then node-UUID-1.log, -2.log, ... -10.log, -11.log.
        wal = new WriteAheadLog(nodeId, tempDir, 1L);

        var markers = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            var marker = "evt-" + i;
            markers.add(marker);
            wal.append(event(marker)); // writes to current file, then auto-rotates
        }
        wal.close();
        wal = null;

        var reader = new WalLogReader(nodeId, tempDir);
        var replayed = reader.readAllEvents();

        var replayedMarkers = replayed.stream().map(e -> (String) e.get("marker")).toList();
        assertEquals(markers, replayedMarkers,
                     "Replay order must be chronological (base first, then -1, -2, ... -10, -11), "
                     + "not lexicographic. Got: " + replayedMarkers);

        // Sequences must be strictly ascending in replay order.
        long prev = -1;
        for (var e : replayed) {
            var seq = ((Number) e.get("sequence")).longValue();
            assertTrue(seq > prev, "sequence must ascend in replay order, prev=" + prev + " seq=" + seq);
            prev = seq;
        }
    }

    /**
     * Luciferase-sc6pl: recovery against an ACTIVE WAL must not open the live file in append mode
     * and must read all already-written records without corruption.
     */
    @Test
    void recoveryAgainstActiveWalPreservesRecords() throws IOException {
        wal = new WriteAheadLog(nodeId, tempDir);
        for (int i = 0; i < 5; i++) {
            wal.append(event("live-" + i));
        }
        wal.flush();

        // WAL still OPEN — recover concurrently via the read-only path.
        var recovery = new EventRecovery(tempDir);
        var state = recovery.recover(nodeId);

        assertEquals(5, state.events().size(), "recovery must see all 5 live records");

        // The live WAL must still be writable and uncorrupted afterwards.
        wal.append(event("post-recovery"));
        wal.flush();
        wal.close();
        wal = null;

        var reader = new WalLogReader(nodeId, tempDir);
        var all = reader.readAllEvents();
        assertEquals(6, all.size(), "log must remain intact and appendable after concurrent recovery");
        // No duplicated/torn lines: markers are all distinct and parse cleanly.
        var markers = all.stream().map(e -> (String) e.get("marker")).distinct().count();
        assertEquals(6, markers, "all records parse and remain distinct (no interleaved corruption)");
    }

    /**
     * Luciferase-0frcy.115: a fresh WAL over an existing log must continue the sequence numbers
     * rather than restarting at 1 and colliding with prior-run sequences.
     */
    @Test
    void sequenceCounterContinuesAcrossRestart() throws IOException {
        wal = new WriteAheadLog(nodeId, tempDir);
        for (int i = 0; i < 3; i++) {
            wal.append(event("run1-" + i));
        }
        wal.close();

        long maxRun1 = new WalLogReader(nodeId, tempDir).readAllEvents().stream()
                                                        .mapToLong(e -> ((Number) e.get("sequence")).longValue())
                                                        .max().orElse(0);
        assertEquals(3, maxRun1);

        // Restart: new WAL on same directory.
        wal = new WriteAheadLog(nodeId, tempDir);
        wal.append(event("run2-0"));
        wal.flush();

        var all = new WalLogReader(nodeId, tempDir).readAllEvents();
        long maxAll = all.stream().mapToLong(e -> ((Number) e.get("sequence")).longValue()).max().orElse(0);
        assertEquals(4, maxAll, "new run must continue from 4, not restart at 1");

        // Sequence numbers must be globally unique (no collision with prior run).
        var seqs = all.stream().map(e -> ((Number) e.get("sequence")).longValue()).toList();
        assertEquals(seqs.size(), seqs.stream().distinct().count(), "no duplicate sequence numbers across restart");
    }
}
