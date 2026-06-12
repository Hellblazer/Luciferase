/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.lifecycle.BubbleMigratorAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.LifecycleState;
import com.hellblazer.luciferase.simulation.lifecycle.SocketConnectionManagerAdapter;
import com.hellblazer.luciferase.simulation.tumbler.BubbleMigrator;
import com.hellblazer.luciferase.simulation.tumbler.SpatialTumbler;
import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.vecmath.Point3f;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * RDR-017 (Luciferase-n6jrh.2) — BubbleMigrator lifecycle integration: the migrator must be
 * drained BEFORE the coordinator closes the WAL on shutdown, so an in-flight migration can
 * never call {@code logMigrationCommit()} against a closed WAL (the RDR-016 R2
 * ENTITY_DEPARTURE-without-COMMIT split-brain precondition).
 */
class NodeBootstrapMigratorLifecycleTest {

    private static EntityMigrationStateMachine freshFsm() {
        return new EntityMigrationStateMachine(new FirefliesViewMonitor(new MockFirefliesView<>(), 3));
    }

    private static Manager manager() {
        return new Manager(LocalServerTransport.Registry.create(),
                           SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                           SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
    }

    @Test
    void fourArgAssembleLifecycleRegistersMigrator(@TempDir Path base) throws Exception {
        var memberId = DigestAlgorithm.DEFAULT.random();
        var nodeId = NodeBootstrap.resolveNodeId(memberId);
        var pmAdapter = NodeBootstrap.persistenceAdapter(nodeId, NodeBootstrap.walDir(base, nodeId), freshFsm());
        var scm = new SocketConnectionManager(ProcessAddress.localhost("n6jrh2-reg", 0), msg -> {});
        var mgr = manager();
        var migrator = new BubbleMigrator(new SpatialTumbler((byte) 5, 16L),
                                          Duration.ofSeconds(1), Duration.ofMillis(100), 5);
        try {
            NodeBootstrap.assemble(mgr, new SocketConnectionManagerAdapter(scm), pmAdapter, migrator);

            assertEquals(LifecycleState.RUNNING, mgr.coordinator().getState(BubbleMigratorAdapter.NAME),
                         "4-arg assemble must lifecycle-register the migrator (RUNNING)");
        } finally {
            mgr.close();
        }
        assertEquals(LifecycleState.STOPPED, mgr.coordinator().getState(BubbleMigratorAdapter.NAME),
                     "Manager.close() must stop the migrator adapter");
        assertTrue(migrator.isShutdown(), "lifecycle stop must drain/shut down the migrator executor");
    }

    /**
     * THE hazard (P2 review Sig-2): an in-flight migration at shutdown must complete its WAL
     * bracket before the coordinator closes the WAL. The transfer factory blocks the migration
     * in-flight (before any WAL write); Manager.close() starts on another thread; the migration
     * is then released. Because the migrator adapter sits at Layer 1 (above PersistenceManager
     * at Layer 0), reverse-order shutdown drains the migration FIRST — the WAL bracket
     * (ENTITY_DEPARTURE + MIGRATION_COMMIT) lands on an open WAL and the migration succeeds.
     * Without the adapter, close() returns without draining and the WAL closes under the
     * in-flight migration, which then fails its bracket.
     */
    @Test
    void inFlightMigrationDrainsBeforeWalCloseOnShutdown(@TempDir Path base) throws Exception {
        var memberId = DigestAlgorithm.DEFAULT.random();
        var nodeId = NodeBootstrap.resolveNodeId(memberId);
        var pmAdapter = NodeBootstrap.persistenceAdapter(nodeId, NodeBootstrap.walDir(base, nodeId), freshFsm());
        var scm = new SocketConnectionManager(ProcessAddress.localhost("n6jrh2-drain", 0), msg -> {});
        var mgr = manager();
        // migrationTimeout (= default drain grace) must fit the coordinator's per-component stop
        // budget (5000ms / 3 components here) or the coordinator races ahead of the drain and the
        // test exercises the gate fallback instead of the drain.
        var migrator = new BubbleMigrator(new SpatialTumbler((byte) 5, 16L),
                                          Duration.ofMillis(1500), Duration.ofMillis(100), 5);

        var entityId = UUID.randomUUID();
        var source = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var target = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        var migrationEntered = new CountDownLatch(1);
        var releaseMigration = new CountDownLatch(1);
        try {
            NodeBootstrap.assemble(mgr, new SocketConnectionManagerAdapter(scm), pmAdapter, migrator);

            source.addEntity(entityId.toString(), new Point3f(1f, 0f, 0f), "payload");
            // The factory runs on the migration executor BEFORE any WAL write — blocking here
            // holds the migration genuinely in-flight across the start of shutdown.
            migrator.setBubbleTransferFactory((tgtServer, src) -> {
                migrationEntered.countDown();
                try {
                    if (!releaseMigration.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeException("release latch timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return target;
            });

            var resultFuture = migrator.migrate(source, UUID.randomUUID(), UUID.randomUUID());
            assertTrue(migrationEntered.await(5, TimeUnit.SECONDS), "migration must be in-flight");

            // Begin shutdown while the migration is in-flight. The release is withheld until the
            // drain has demonstrably BEGUN (migrator.isShutdown() flips when doStop calls
            // executor.shutdown()), so the test cannot pass trivially by the migration finishing
            // before the coordinator reaches Layer 1 — the drain genuinely overlaps the migration.
            var closer = new Thread(mgr::close, "test-closer");
            closer.start();
            var drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!migrator.isShutdown()) {
                assertTrue(System.nanoTime() < drainDeadline, "drain must begin within 10s");
                Thread.onSpinWait();
            }
            releaseMigration.countDown();
            closer.join(15_000);
            assertFalse(closer.isAlive(), "Manager.close() must complete");

            var result = resultFuture.get(5, TimeUnit.SECONDS);
            assertTrue(result.success(),
                       "in-flight migration must complete its WAL bracket before the WAL closes, got: "
                       + result.message());
            assertEquals(LifecycleState.STOPPED, mgr.coordinator().getState(BubbleMigratorAdapter.NAME));
            assertEquals(LifecycleState.STOPPED, mgr.coordinator().getState("PersistenceManager"));
        } finally {
            target.close();
            migrator.shutdown();
            mgr.close();
        }
    }

    @Test
    void migrateAfterShutdownReturnsCleanFailure(@TempDir Path base) throws Exception {
        var memberId = DigestAlgorithm.DEFAULT.random();
        var nodeId = NodeBootstrap.resolveNodeId(memberId);
        var pmAdapter = NodeBootstrap.persistenceAdapter(nodeId, NodeBootstrap.walDir(base, nodeId), freshFsm());
        var scm = new SocketConnectionManager(ProcessAddress.localhost("n6jrh2-post", 0), msg -> {});
        var mgr = manager();
        var migrator = new BubbleMigrator(new SpatialTumbler((byte) 5, 16L),
                                          Duration.ofSeconds(1), Duration.ofMillis(100), 5);
        var source = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        try {
            NodeBootstrap.assemble(mgr, new SocketConnectionManagerAdapter(scm), pmAdapter, migrator);
            source.addEntity(UUID.randomUUID().toString(), new Point3f(1f, 0f, 0f), "payload");
        } finally {
            mgr.close();
        }

        // After lifecycle shutdown the migrator executor is gone: migrate() must return a clean
        // failed result, not throw RejectedExecutionException at the caller.
        var result = migrator.migrate(source, UUID.randomUUID(), UUID.randomUUID())
                             .get(5, TimeUnit.SECONDS);
        assertFalse(result.success(), "migrate after shutdown must fail");
        assertTrue(result.message().toLowerCase().contains("shut"),
                   "failure must say the migrator is shut down, got: " + result.message());
    }

    /**
     * The over-budget defense (review Critical on n6jrh.2): the coordinator's per-component
     * stop timeout does not cancel a still-running drain, so the persistence layer can be
     * stopped while the migrator has not fully terminated. In that case the WAL must be closed
     * crash-safe and RETAINED — a checkpoint-truncate would silently erase the in-flight
     * migration's ENTITY_DEPARTURE half-bracket, losing the MIGRATING_OUT recovery evidence.
     */
    @Test
    void dirtyShutdownGateRetainsWalForRecovery(@TempDir Path base) throws Exception {
        var memberId = DigestAlgorithm.DEFAULT.random();
        var nodeId = NodeBootstrap.resolveNodeId(memberId);
        var walDir = NodeBootstrap.walDir(base, nodeId);
        var inFlight = UUID.randomUUID();

        var pmAdapter = NodeBootstrap.persistenceAdapter(nodeId, walDir, freshFsm());
        pmAdapter.start().join();
        pmAdapter.getPersistenceManager().logEntityDeparture(inFlight, UUID.randomUUID(), UUID.randomUUID());

        // Simulate the over-budget case: the migration executor has NOT fully terminated when
        // the persistence layer stops.
        pmAdapter.setCleanShutdownGate(() -> false);
        pmAdapter.stop().join();

        // The reopened node must reconstruct MIGRATING_OUT — truncation would have erased it.
        var fsm2 = freshFsm();
        var pmAdapter2 = NodeBootstrap.persistenceAdapter(nodeId, walDir, fsm2);
        pmAdapter2.start().join();
        try {
            assertEquals(EntityMigrationState.MIGRATING_OUT, fsm2.getState(inFlight.toString()),
                         "retained WAL must recover the in-flight half-bracket as MIGRATING_OUT");
        } finally {
            pmAdapter2.stop().join();
        }
    }

    /**
     * Inversion: with the gate holding (migrator fully terminated), clean shutdown keeps the
     * P3 gate-O1 semantics — checkpoint + truncate, nothing to recover.
     */
    @Test
    void cleanShutdownGateStillTruncates(@TempDir Path base) throws Exception {
        var memberId = DigestAlgorithm.DEFAULT.random();
        var nodeId = NodeBootstrap.resolveNodeId(memberId);
        var walDir = NodeBootstrap.walDir(base, nodeId);
        var inFlight = UUID.randomUUID();

        var pmAdapter = NodeBootstrap.persistenceAdapter(nodeId, walDir, freshFsm());
        pmAdapter.start().join();
        pmAdapter.getPersistenceManager().logEntityDeparture(inFlight, UUID.randomUUID(), UUID.randomUUID());

        pmAdapter.setCleanShutdownGate(() -> true);
        pmAdapter.stop().join();

        var fsm2 = freshFsm();
        var pmAdapter2 = NodeBootstrap.persistenceAdapter(nodeId, walDir, fsm2);
        pmAdapter2.start().join();
        try {
            assertNotEquals(EntityMigrationState.MIGRATING_OUT, fsm2.getState(inFlight.toString()),
                            "clean shutdown must checkpoint-truncate (gate O1 semantics preserved)");
        } finally {
            pmAdapter2.stop().join();
        }
    }

    @Test
    void migratorAdapterDoesNotRestartAfterDrain() {
        var migrator = new BubbleMigrator(new SpatialTumbler((byte) 5, 16L),
                                          Duration.ofSeconds(1), Duration.ofMillis(100), 5);
        var adapter = new BubbleMigratorAdapter(migrator);
        assertEquals(java.util.List.of("PersistenceManager"), adapter.dependencies(),
                     "migrator must sit above PersistenceManager so it is stopped first");

        adapter.start().join();
        adapter.stop().join();
        assertTrue(migrator.isShutdown());

        // The migration executor cannot be revived: a restart must fail loud, not produce a
        // zombie adapter whose migrations are silently rejected.
        var ex = assertThrows(Exception.class, () -> adapter.start().join(),
                              "restart after drain must fail loud");
        var found = false;
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("restart")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "failure chain must explain the no-restart contract, got: " + ex);
    }
}
