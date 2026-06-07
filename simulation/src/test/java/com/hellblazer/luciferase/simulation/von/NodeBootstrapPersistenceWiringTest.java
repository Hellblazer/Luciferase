/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.lifecycle.LifecycleException;
import com.hellblazer.luciferase.simulation.lifecycle.LifecycleState;
import com.hellblazer.luciferase.simulation.lifecycle.SocketConnectionManagerAdapter;
import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import com.hellblazer.luciferase.simulation.persistence.RecoveryStateSink;
import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-017 P1 (Luciferase-pf1iu) — persistence recovery exercised through the REAL
 * {@link NodeBootstrap#assemble} construction path (not an isolated adapter), per the P0 stacked-review
 * carry-forward: registering the persistence adapter on the coordinator must drive
 * {@code doStart() → recover() → FSM replay → startSchedulers()}, and a corrupt WAL must abort assembly.
 */
class NodeBootstrapPersistenceWiringTest {

    private static EntityMigrationStateMachine freshFsm() {
        return new EntityMigrationStateMachine(new FirefliesViewMonitor(new MockFirefliesView<>(), 3));
    }

    @Test
    void assembleRecoversFsmViaPersistenceAdapter(@TempDir Path walDir) throws Exception {
        var nodeId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        try (var writer = new PersistenceManager(nodeId, walDir, RecoveryStateSink.NOOP)) {
            writer.logEntityDeparture(entityId, UUID.randomUUID(), UUID.randomUUID());
        }

        var fsm = freshFsm();
        var adapter = NodeBootstrap.persistenceAdapter(nodeId, walDir, fsm);
        var pm = adapter.getPersistenceManager();
        var scm = new SocketConnectionManager(ProcessAddress.localhost("p1-wiring", 0), msg -> {});
        var registry = LocalServerTransport.Registry.create();
        var manager = new Manager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                                  SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
        try {
            NodeBootstrap.assemble(manager, new SocketConnectionManagerAdapter(scm), adapter);

            // Registering the adapter (assemble → registerInfrastructure → registerAndStart) drove doStart().
            assertEquals(LifecycleState.RUNNING, manager.coordinator().getState("PersistenceManager"));
            assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId.toString()),
                         "assemble() must recover the WAL and replay the departure into the FSM");
            assertTrue(pm.isSchedulersStarted(), "schedulers must start after recovery during assemble()");
        } finally {
            manager.close();
        }
    }

    @Test
    void assembledCleanShutdownCompactsWal_reopenReplaysNothing(@TempDir Path walDir) throws Exception {
        // RDR-017 P3 (gate O1): a clean lifecycle stop (mgr.close() → coordinator stop → doStop →
        // closeClean) must checkpoint + truncate the WAL. Guards the doStop→closeClean wiring against a
        // regression to plain close() (which would leave the segments on disk).
        var nodeId = UUID.randomUUID();
        try (var writer = new PersistenceManager(nodeId, walDir, RecoveryStateSink.NOOP)) {
            writer.logMigrationCommit(UUID.randomUUID());   // fsynced → segment has flushed content
        }
        assertTrue(Files.readAllLines(walDir.resolve("node-" + nodeId + ".log")).size() > 0,
                   "precondition: WAL segment has content before clean shutdown");

        var fsm1 = freshFsm();
        var adapter1 = NodeBootstrap.persistenceAdapter(nodeId, walDir, fsm1);
        var scm1 = new SocketConnectionManager(ProcessAddress.localhost("p3-clean1", 0), msg -> {});
        var mgr1 = new Manager(LocalServerTransport.Registry.create(),
                               SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                               SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
        NodeBootstrap.assemble(mgr1, new SocketConnectionManagerAdapter(scm1), adapter1);
        mgr1.close();   // clean lifecycle stop → doStop → closeClean (checkpoint + truncate)

        assertEquals(0, Files.readAllLines(walDir.resolve("node-" + nodeId + ".log")).size(),
                     "clean lifecycle stop must truncate the WAL segment (doStop → closeClean)");
        assertTrue(Files.exists(walDir.resolve("node-" + nodeId + ".meta")),
                   "clean lifecycle stop must leave a checkpoint");

        // Reopen the assembled node: recovery replays nothing.
        var fsm2 = freshFsm();
        var adapter2 = NodeBootstrap.persistenceAdapter(nodeId, walDir, fsm2);
        var pm2 = adapter2.getPersistenceManager();
        var scm2 = new SocketConnectionManager(ProcessAddress.localhost("p3-clean2", 0), msg -> {});
        var mgr2 = new Manager(LocalServerTransport.Registry.create(),
                               SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                               SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
        try {
            NodeBootstrap.assemble(mgr2, new SocketConnectionManagerAdapter(scm2), adapter2);
            assertEquals(LifecycleState.RUNNING, mgr2.coordinator().getState("PersistenceManager"),
                         "reopen on a compacted WAL must start cleanly");
            assertTrue(pm2.isSchedulersStarted(), "reopen must complete recovery and start schedulers");
        } finally {
            mgr2.close();
        }
    }

    @Test
    void assembleAbortsOnCorruptWal(@TempDir Path walDir) throws Exception {
        var nodeId = UUID.randomUUID();
        try (var writer = new PersistenceManager(nodeId, walDir, RecoveryStateSink.NOOP)) {
            writer.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            writer.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            writer.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }
        var logFile = walDir.resolve("node-" + nodeId + ".log");
        var lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        lines.set(1, "{ corrupt mid-file line");
        Files.write(logFile, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));

        var fsm = freshFsm();
        var adapter = NodeBootstrap.persistenceAdapter(nodeId, walDir, fsm);
        var pm = adapter.getPersistenceManager();
        var scm = new SocketConnectionManager(ProcessAddress.localhost("p1-corrupt", 0), msg -> {});
        var registry = LocalServerTransport.Registry.create();
        var manager = new Manager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                                  SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
        try {
            // assemble() registers SCM first (ok), then the persistence adapter whose doStart() recovers
            // a corrupt WAL — registerInfrastructure must propagate the failure (node refuses to start).
            assertThrows(LifecycleException.class,
                         () -> NodeBootstrap.assemble(manager, new SocketConnectionManagerAdapter(scm), adapter),
                         "corrupt WAL must abort node assembly");
            assertFalse(pm.isSchedulersStarted(), "schedulers must not start when assembly aborts");
        } finally {
            // Best-effort: manager.close() stops the RUNNING SCM adapter; the FAILED persistence
            // adapter is not RUNNING so its PM is closed explicitly here.
            try {
                manager.close();
            } catch (Exception ignored) {
            }
            pm.close();
        }
    }
}
