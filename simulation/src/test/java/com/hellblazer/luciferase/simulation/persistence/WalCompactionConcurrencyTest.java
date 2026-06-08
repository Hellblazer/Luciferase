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
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RDR-019 Phase 2 P2.2 — failing-first concurrency regression for compaction (gate S2).
 *
 * <p>An in-flight {@code ENTITY_DEPARTURE} written to the <b>active</b> segment must survive a concurrent
 * compaction. Compaction must seal the active segment and touch only sealed segments older than the
 * watermark — it must never rewrite the live segment the batch-flush scheduler is appending to. This test
 * writes an in-flight departure to the active segment, runs compaction, and asserts the departure is still
 * recovered afterwards.
 *
 * <p>EXPECTED: RED until Phase 2 P2.3 implements {@link PersistenceManager#compact()} (stub throws
 * {@link UnsupportedOperationException}).
 *
 * @author hal.hildebrand
 */
class WalCompactionConcurrencyTest {

    private static EntityMigrationStateMachine freshFsm() {
        return new EntityMigrationStateMachine(new FirefliesViewMonitor(new MockFirefliesView<>(), 3));
    }

    @Test
    void inFlightDepartureInActiveSegmentSurvivesCompaction(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var older = UUID.randomUUID();      // a completed migration eligible for pruning
        var inFlight = UUID.randomUUID();   // in-flight in the ACTIVE segment when compaction runs
        var src = UUID.randomUUID();
        var tgt = UUID.randomUUID();
        var clock = new TestClock(1_000L);

        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.setClock(clock);
            // An older completed migration (compaction-eligible).
            mgr.logEntityDeparture(older, src, tgt);
            mgr.logMigrationCommit(older);
            // An in-flight departure sitting in the active segment when compaction runs.
            mgr.logEntityDeparture(inFlight, src, tgt);

            // Compaction must seal the active segment first and compact only prior sealed segments —
            // the in-flight departure in the (now sealed-but-newer-than-watermark) active segment must
            // NOT be pruned.
            mgr.compact();
            // crash (retain everything compaction left behind)
        }

        var fsm = freshFsm();
        try (var mgr = new PersistenceManager(nodeId, logDir, new MigrationRecoveryStateSink(fsm))) {
            mgr.setClock(clock);
            mgr.recover();
        }

        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(inFlight.toString()),
                "an in-flight ENTITY_DEPARTURE in the active segment must survive compaction — compaction "
                + "must seal-then-compact only sealed segments older than the watermark, never the live tail");
    }
}
