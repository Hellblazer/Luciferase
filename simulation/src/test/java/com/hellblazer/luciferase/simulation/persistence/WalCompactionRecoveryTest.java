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

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-019 Phase 2 P2.2 — failing-first MVV for bounded-WAL compaction (RDR-019 §MVV Phase 2).
 *
 * <p><b>Multi-session bounded no-loss:</b> across crash → recover → compact → crash → recover, every
 * in-flight (uncommitted) migration must still be reconstructed (none silently dropped), while the
 * retained WAL is bounded (committed pairs pruned, so total log bytes shrink after compaction).
 *
 * <p>EXPECTED: RED until Phase 2 P2.3 (Luciferase-0ejd2) implements {@link PersistenceManager#compact()}
 * — the stub throws {@link UnsupportedOperationException}. GREEN once compaction seals the active segment
 * and prunes whole committed DEPARTURE+COMMIT pairs from sealed segments.
 *
 * @author hal.hildebrand
 */
class WalCompactionRecoveryTest {

    private static EntityMigrationStateMachine freshFsm() {
        return new EntityMigrationStateMachine(new FirefliesViewMonitor(new MockFirefliesView<>(), 3));
    }

    /** Sum of all WAL segment (.log) byte sizes in the directory — the "retained log" footprint. */
    private static long walBytes(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .sum();
        }
    }

    @Test
    void multiSessionCompactionBoundsWalWithoutDroppingInFlight(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var inFlight = UUID.randomUUID();   // departs, never commits — must survive ALL sessions
        var committed = UUID.randomUUID();  // departs + commits — eligible for compaction pruning
        var src = UUID.randomUUID();
        var tgt = UUID.randomUUID();
        var clock = new TestClock(1_000L);

        // session 1: one in-flight departure + one completed migration; crash (no clean shutdown).
        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.setClock(clock);
            mgr.logEntityDeparture(inFlight, src, tgt);
            mgr.logEntityDeparture(committed, src, tgt);
            mgr.logMigrationCommit(committed);
            // crash
        }
        long bytesBeforeCompaction = walBytes(logDir);

        // session 2: recover, then compact (must seal the active segment first, prune the committed
        // pair, retain the in-flight departure), then crash.
        var fsm2 = freshFsm();
        try (var mgr = new PersistenceManager(nodeId, logDir, new MigrationRecoveryStateSink(fsm2))) {
            mgr.setClock(clock);
            mgr.recover();
            assertEquals(EntityMigrationState.MIGRATING_OUT, fsm2.getState(inFlight.toString()),
                    "session 2: in-flight departure must recover as MIGRATING_OUT");
            mgr.compact();
            // crash
        }
        long bytesAfterCompaction = walBytes(logDir);

        // session 3: recover from the compacted log — in-flight migration still present, WAL bounded.
        var fsm3 = freshFsm();
        try (var mgr = new PersistenceManager(nodeId, logDir, new MigrationRecoveryStateSink(fsm3))) {
            mgr.setClock(clock);
            mgr.recover();
        }

        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm3.getState(inFlight.toString()),
                "session 3: the in-flight migration must survive compaction+crash — never silently dropped");
        assertTrue(bytesAfterCompaction < bytesBeforeCompaction,
                "compaction must bound the WAL: retained log bytes (" + bytesAfterCompaction
                + ") must shrink below pre-compaction (" + bytesBeforeCompaction
                + ") by pruning the committed DEPARTURE+COMMIT pair");
    }
}
