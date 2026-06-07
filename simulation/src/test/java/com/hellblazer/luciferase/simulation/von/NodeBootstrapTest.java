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
import com.hellblazer.luciferase.simulation.lifecycle.LifecycleState;
import com.hellblazer.luciferase.simulation.lifecycle.PersistenceManagerAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.SocketConnectionManagerAdapter;
import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-017 P0 (Luciferase-vhhu0) — node bootstrap nodeId/WAL-dir derivation and lifecycle assembly.
 */
class NodeBootstrapTest {

    @Test
    void nodeIdResolvedViaDigestToUuid() {
        var memberId = DigestAlgorithm.DEFAULT.random();
        // gate C1: nodeId is the canonical FirefliesMemberLookup.digestToUuid derivation,
        // NOT UUID.nameUUIDFromBytes (which yields a different value from the same Digest).
        assertEquals(FirefliesMemberLookup.digestToUuid(memberId),
                     NodeBootstrap.resolveNodeId(memberId),
                     "nodeId must be derived via FirefliesMemberLookup.digestToUuid(member.getId())");
    }

    @Test
    void walDirDerivedFromNodeId(@TempDir Path base) {
        var nodeId = UUID.randomUUID();
        var expected = base.resolve(".luciferase").resolve("wal").resolve(nodeId.toString());
        assertEquals(expected, NodeBootstrap.walDir(base, nodeId),
                     "WAL dir must be <base>/.luciferase/wal/<nodeId>/");
    }

    @Test
    void assembleRegistersInfrastructureAtL0_andBubbleStartsAtL1(@TempDir Path walDir) throws IOException {
        var registry = LocalServerTransport.Registry.create();
        var manager = new Manager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                                  SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
        var scm = new SocketConnectionManager(ProcessAddress.localhost("p0-bootstrap", 0), msg -> {});
        var pm = new PersistenceManager(UUID.randomUUID(), walDir);
        try {
            NodeBootstrap.assemble(manager, new SocketConnectionManagerAdapter(scm),
                                   new PersistenceManagerAdapter(pm));

            assertEquals(java.util.List.of("PersistenceManager"), manager.getBubbleDependencies(),
                         "assemble must configure bubbles to depend on PersistenceManager");
            assertEquals(LifecycleState.RUNNING, manager.coordinator().getState("SocketConnectionManager"));
            assertEquals(LifecycleState.RUNNING, manager.coordinator().getState("PersistenceManager"));

            // Bubble created after infra wiring must register at Layer 1 (dep PersistenceManager)
            // and start without LifecycleException.
            var bubble = manager.createBubble();
            assertEquals(LifecycleState.RUNNING, manager.coordinator().getState("EnhancedBubble-" + bubble.id()),
                         "bubble must register and start under the coordinator (Layer 1)");
        } finally {
            // manager.close() stops the coordinator, which stops the SCM/PM adapters and closes
            // their wrapped components — closing pm/scm again here would double-close the WAL.
            manager.close();
        }
    }

    @Test
    void createBubbleFailsLoudWhenDeclaredDependencyMissing() {
        // Composed-node hardening (RDR-017 P0 review): if bubbleDependencies is configured but the
        // named dependency was never registered (assemble() skipped / misordered), createBubble must
        // fail loud rather than silently emit a bubble outside the lifecycle graph.
        var registry = LocalServerTransport.Registry.create();
        var manager = new Manager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                                  SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
        try {
            manager.setBubbleDependencies(java.util.List.of("PersistenceManager")); // PM NOT registered
            assertThrows(com.hellblazer.luciferase.simulation.lifecycle.LifecycleException.class,
                         manager::createBubble,
                         "createBubble must fail loud when a declared dependency is unregistered");
            assertEquals(0, manager.size(), "the half-registered bubble must not leak into the manager");
        } finally {
            manager.close();
        }
    }

    @Test
    void createBubbleHasNoPersistenceDependencyByDefault() {
        // No regression: a Manager not wired for persistence creates bubbles with no
        // lifecycle dependencies (registers at Layer 0, exactly as before RDR-017).
        var registry = LocalServerTransport.Registry.create();
        var manager = new Manager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                                  SpatialLevelHeuristic.DEFAULT_AOI_RADIUS);
        try {
            assertTrue(manager.getBubbleDependencies().isEmpty(),
                       "default Manager must declare no bubble dependencies");
            var bubble = manager.createBubble();
            assertEquals(LifecycleState.RUNNING, manager.coordinator().getState("EnhancedBubble-" + bubble.id()),
                         "bubble must register and start even with no persistence wired");
        } finally {
            manager.close();
        }
    }
}
