/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * RDR-017 P2 (Luciferase-1693b) — assembled-node durability round-trip with WAL-identity pinning
 * (gate S2). Distinct from and strengthens the in-process
 * {@code MigrationRecoveryStateSinkTest.endToEndViaManagerReconstructsFsmState()}: this drives a live
 * migration through the WAL bracket wired by {@link NodeBootstrap#assemble(Manager,
 * SocketConnectionManagerAdapter, com.hellblazer.luciferase.simulation.lifecycle.PersistenceManagerAdapter,
 * BubbleMigrator)}, closes the node, and recovers a FRESH node on the SAME digest-derived WAL directory.
 */
class NodeBootstrapDurabilityRoundTripTest {

    private static EntityMigrationStateMachine freshFsm() {
        return new EntityMigrationStateMachine(new FirefliesViewMonitor(new MockFirefliesView<>(), 3));
    }

    private static Manager manager() {
        return new Manager(LocalServerTransport.Registry.create(),
                           SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                           SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
    }

    @Test
    void migrateThenReopenSameWalDir_recoversFsmState(@TempDir Path base) throws Exception {
        var memberId = DigestAlgorithm.DEFAULT.random();   // stable member identity across "restarts"
        var committedEntity = UUID.randomUUID();   // full migration → DEPARTED on recovery
        var inFlightEntity = UUID.randomUUID();    // departure only, no commit → MIGRATING_OUT on recovery

        // ───────── node #1: assemble, wire migration durability, migrate one entity ─────────
        var nodeId1 = NodeBootstrap.resolveNodeId(memberId);
        var walDir1 = NodeBootstrap.walDir(base, nodeId1);

        var fsm1 = freshFsm();
        var pmAdapter1 = NodeBootstrap.persistenceAdapter(nodeId1, walDir1, fsm1);
        // 1-arg SocketConnectionManagerAdapter has no bind address → doStart() does not listen on a
        // socket. Intentional: this is a durability test, not a network test; the SCM only needs to
        // occupy Layer 0 so the lifecycle graph matches production.
        var scm1 = new SocketConnectionManager(ProcessAddress.localhost("p2-node1", 0), msg -> {});
        var mgr1 = manager();
        var tumbler = new SpatialTumbler((byte) 5, 16L);
        var migrator = new BubbleMigrator(tumbler, Duration.ofSeconds(1), Duration.ofMillis(100), 5);
        var target = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
        try {
            NodeBootstrap.assemble(mgr1, new SocketConnectionManagerAdapter(scm1), pmAdapter1, migrator);

            // (a) Drive a real, successful WAL-bracketed migration (no neighbors → MOVE ACK completes
            // immediately). The entity id MUST be a UUID so the bracket's UUID.fromString succeeds.
            var source = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
            source.addEntity(committedEntity.toString(), new Point3f(1f, 0f, 0f), "payload");
            migrator.setBubbleTransferFactory((tgtServer, src) -> target);

            var result = migrator.migrate(source, UUID.randomUUID(), UUID.randomUUID())
                                 .get(5, TimeUnit.SECONDS);
            assertTrue(result.success(),
                       "live WAL-bracketed migration must commit: " + result.message());

            // (b) Simulate a crash mid-migration: ENTITY_DEPARTURE durable, MIGRATION_COMMIT never
            // written (the half the RDR-016 R2 bracket protects). Recovery must reconstruct MIGRATING_OUT.
            pmAdapter1.getPersistenceManager()
                      .logEntityDeparture(inFlightEntity, UUID.randomUUID(), UUID.randomUUID());
        } finally {
            target.close();        // releases the target Bubble's retry-scheduler daemon thread
            migrator.shutdown();    // releases the migration executor pool
            mgr1.close();           // stops the PM adapter → flushes + closes the WAL durably
        }

        // ───────── WAL-identity pinning (gate C1): the reopened node derives the SAME dir ─────────
        var nodeId2 = NodeBootstrap.resolveNodeId(memberId);
        var walDir2 = NodeBootstrap.walDir(base, nodeId2);
        assertEquals(nodeId1, nodeId2, "nodeId must be deterministic across restarts (same member id)");
        assertEquals(walDir1, walDir2, "reopened node must resolve the SAME WAL directory");

        // ───────── node #2: reopen on the same WAL dir, recover the FSM ─────────
        var fsm2 = freshFsm();
        var pmAdapter2 = NodeBootstrap.persistenceAdapter(nodeId2, walDir2, fsm2);
        var scm2 = new SocketConnectionManager(ProcessAddress.localhost("p2-node2", 0), msg -> {});
        var mgr2 = manager();
        try {
            NodeBootstrap.assemble(mgr2, new SocketConnectionManagerAdapter(scm2), pmAdapter2);

            // The committed migration (ENTITY_DEPARTURE + MIGRATION_COMMIT) must reconstruct as DEPARTED;
            // the in-flight departure (ENTITY_DEPARTURE only) must reconstruct as MIGRATING_OUT (gate S2).
            assertEquals(EntityMigrationState.DEPARTED, fsm2.getState(committedEntity.toString()),
                         "a committed migration must recover to DEPARTED on the reopened node");
            assertEquals(EntityMigrationState.MIGRATING_OUT, fsm2.getState(inFlightEntity.toString()),
                         "an uncommitted departure must recover to MIGRATING_OUT on the reopened node");
        } finally {
            mgr2.close();
        }
    }
}
