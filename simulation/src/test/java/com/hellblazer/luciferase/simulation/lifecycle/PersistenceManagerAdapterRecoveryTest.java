/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.persistence.MigrationRecoveryStateSink;
import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import com.hellblazer.luciferase.simulation.persistence.RecoveryStateSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-017 P1 (Luciferase-pf1iu) — {@code PersistenceManagerAdapter.doStart()} fail-loud recovery and
 * recover-before-schedulers ordering. Closes the P0 gap where {@code doStart()} was a no-op that never
 * called {@code recover()}.
 */
class PersistenceManagerAdapterRecoveryTest {

    private static EntityMigrationStateMachine freshFsm() {
        return new EntityMigrationStateMachine(new FirefliesViewMonitor(new MockFirefliesView<>(), 3));
    }

    @Test
    void doStartRecoversCleanWalToFsmSink_thenStartsSchedulers(@TempDir Path walDir) throws Exception {
        var nodeId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var src = UUID.randomUUID();
        var tgt = UUID.randomUUID();

        // Clean WAL: one departure, no checkpoint (crash). Writer's schedulers stay idle (relocated).
        try (var writer = new PersistenceManager(nodeId, walDir, RecoveryStateSink.NOOP)) {
            writer.logEntityDeparture(entityId, src, tgt);
        }

        var fsm = freshFsm();
        var pm = new PersistenceManager(nodeId, walDir, new MigrationRecoveryStateSink(fsm));
        var adapter = new PersistenceManagerAdapter(pm);
        try {
            assertEquals(0, pm.scheduledTaskCount(), "schedulers must not run before doStart()");

            adapter.start().get(5, TimeUnit.SECONDS);

            assertEquals(LifecycleState.RUNNING, adapter.getState(), "clean WAL must start cleanly");
            assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId.toString()),
                         "doStart() must recover() and replay ENTITY_DEPARTURE into the FSM sink");
            assertTrue(pm.isSchedulersStarted(), "schedulers must start after a clean recover()");
            assertEquals(2, pm.scheduledTaskCount(), "both schedulers must be running after doStart()");
        } finally {
            pm.close();
        }
    }

    @Test
    void doStartCorruptWalAborts_schedulersNotStarted(@TempDir Path walDir) throws Exception {
        var nodeId = UUID.randomUUID();
        // Three events so a corrupt MIDDLE line is a mid-file corruption (fatal), not a trailing
        // partial line (tolerated). EventRecovery throws IOException when skippedCorrupt > 0.
        try (var writer = new PersistenceManager(nodeId, walDir, RecoveryStateSink.NOOP)) {
            writer.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            writer.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            writer.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }
        var logFile = walDir.resolve("node-" + nodeId + ".log");
        var lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertTrue(lines.size() >= 3, "expected at least 3 WAL lines");
        lines.set(1, "{ this is not valid json — mid-file corruption"); // corrupt a non-trailing line
        Files.write(logFile, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));

        var pm = new PersistenceManager(nodeId, walDir, RecoveryStateSink.NOOP);
        var adapter = new PersistenceManagerAdapter(pm);
        try {
            var ex = assertThrows(ExecutionException.class,
                                  () -> adapter.start().get(5, TimeUnit.SECONDS),
                                  "corrupt WAL must abort startup, not degrade");
            assertTrue(hasIoCause(ex), "abort cause chain must include IOException (fail-loud)");
            assertEquals(LifecycleState.FAILED, adapter.getState(), "adapter must be FAILED, not RUNNING");
            // recover-before-schedulers: a failed recover() must NOT have started the schedulers.
            assertFalse(pm.isSchedulersStarted(), "schedulers must not start when recover() fails");
            assertEquals(0, pm.scheduledTaskCount(), "no scheduler may run when recover() aborts");
        } finally {
            pm.close();
        }
    }

    private static boolean hasIoCause(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
