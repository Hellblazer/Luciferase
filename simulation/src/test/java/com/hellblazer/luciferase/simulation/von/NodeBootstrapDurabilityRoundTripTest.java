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
    void migrateThenReopenSameWalDir_recoversDepartedFsmState(@TempDir Path base) throws Exception {
        var memberId = DigestAlgorithm.DEFAULT.random();   // stable member identity across "restarts"
        var entityId = UUID.randomUUID();

        // ───────── node #1: assemble, wire migration durability, migrate one entity ─────────
        var nodeId1 = NodeBootstrap.resolveNodeId(memberId);
        var walDir1 = NodeBootstrap.walDir(base, nodeId1);

        var fsm1 = freshFsm();
        var pmAdapter1 = NodeBootstrap.persistenceAdapter(nodeId1, walDir1, fsm1);
        var scm1 = new SocketConnectionManager(ProcessAddress.localhost("p2-node1", 0), msg -> {});
        var mgr1 = manager();
        var tumbler = new SpatialTumbler((byte) 5, 16L);
        var migrator = new BubbleMigrator(tumbler, Duration.ofSeconds(1), Duration.ofMillis(100), 5);
        try {
            NodeBootstrap.assemble(mgr1, new SocketConnectionManagerAdapter(scm1), pmAdapter1, migrator);

            // Drive a real, successful WAL-bracketed migration (no neighbors → MOVE ACK completes
            // immediately). The entity id MUST be a UUID so the bracket's UUID.fromString succeeds.
            var source = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
            source.addEntity(entityId.toString(), new Point3f(1f, 0f, 0f), "payload");
            var target = new Bubble(UUID.randomUUID(), (byte) 5, 16L, mock(Transport.class));
            migrator.setBubbleTransferFactory((tgtServer, src) -> target);

            var result = migrator.migrate(source, UUID.randomUUID(), UUID.randomUUID())
                                 .get(5, TimeUnit.SECONDS);
            assertTrue(result.success(),
                       "live WAL-bracketed migration must commit: " + result.message());
        } finally {
            mgr1.close();          // stops the PM adapter → flushes + closes the WAL durably
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

            // The committed migration (ENTITY_DEPARTURE + MIGRATION_COMMIT) must reconstruct as DEPARTED.
            assertEquals(EntityMigrationState.DEPARTED, fsm2.getState(entityId.toString()),
                         "a committed migration must recover to DEPARTED on the reopened node");
        } finally {
            mgr2.close();
        }
    }
}
