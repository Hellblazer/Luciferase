/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-017 P0 (Luciferase-vhhu0) — lifecycle layering regression.
 * <p>
 * Proves the gate-C2 fix: with {@link PersistenceManagerAdapter#dependencies()} emptied,
 * the coordinator computes {@code {L0:[SocketConnectionManager, PersistenceManager], L1:[Bubble]}}
 * without throwing {@link LifecycleException}. The pre-fix graph crashed
 * {@code computeLayers()} because the spurious {@code "SocketConnectionManager"} dependency on
 * the persistence adapter referenced a component never registered alongside it.
 * <p>
 * Uses real adapters (dynamic-port socket, {@code @TempDir} WAL) so the assertion exercises the
 * production {@code dependencies()} values, not mock stand-ins.
 */
class NodeBootstrapLayeringTest {

    @Test
    void computeLayersPlacesScmAndPmAtL0_bubbleAtL1_withoutThrowing(@TempDir Path walDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var scm = new SocketConnectionManager(ProcessAddress.localhost("p0-layering", 0), msg -> {});
        var pm = new PersistenceManager(nodeId, walDir);
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);
        try {
            var scmAdapter = new SocketConnectionManagerAdapter(scm);
            var pmAdapter = new PersistenceManagerAdapter(pm);
            var bubbleAdapter = new EnhancedBubbleAdapter(bubble, bubble.getRealTimeController(),
                                                          List.of("PersistenceManager"));

            // gate C2: persistence is Layer 0 (no network dependency).
            assertTrue(pmAdapter.dependencies().isEmpty(),
                       "PersistenceManagerAdapter must declare no dependencies (Layer 0)");
            assertTrue(scmAdapter.dependencies().isEmpty(),
                       "SocketConnectionManagerAdapter must declare no dependencies (Layer 0)");
            // AC3: bubble adapter built via the 3-arg ctor depending on PersistenceManager (Layer 1).
            assertEquals(List.of("PersistenceManager"), bubbleAdapter.dependencies(),
                         "Bubble adapter must depend on PersistenceManager");

            var coordinator = new LifecycleCoordinator();
            coordinator.register(scmAdapter);
            coordinator.register(pmAdapter);
            coordinator.register(bubbleAdapter);

            var layers = assertDoesNotThrow(coordinator::computeLayers,
                                            "computeLayers must not throw with PM at Layer 0");

            assertEquals(2, layers.size(), "expected exactly two dependency layers");
            assertEquals(Set.of("SocketConnectionManager", "PersistenceManager"),
                         Set.copyOf(layers.get(0)),
                         "Layer 0 must be {SocketConnectionManager, PersistenceManager}");
            assertEquals(List.of(bubbleAdapter.name()), layers.get(1),
                         "Layer 1 must be the bubble");
        } finally {
            pm.close();
            bubble.close();
            scm.closeAll();
        }
    }
}
