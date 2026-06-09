/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import com.hellblazer.luciferase.simulation.lifecycle.LifecycleState;
import com.hellblazer.luciferase.simulation.lifecycle.PersistenceManagerAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.RecoveryIntegrationAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.SocketConnectionManagerAdapter;
import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDR-021 S3 (Luciferase-0frcy.135.4) — the dedicated shutdown-ordering test. The fault subsystem
 * is a lifecycle participant ordered to stop AHEAD of the bubble adapters, so {@code Manager.close()}
 * drives {@code RecoveryIntegration.close()} (unsubscribe VON + fault listeners) then
 * {@code SimpleFaultHandler.stop()} BEFORE any bubble's {@code broadcastLeave()} can fire a VON
 * event into a half-torn handler (mirrors the RDR-017 migrator-before-WAL contract).
 * <p>
 * Uses the fully assembled node (SCM + PM at Layer 0, bubbles at Layer 1) so the coordinator path —
 * not the standalone fallback — is what is exercised.
 */
class NodeBootstrapShutdownOrderingTest {

    private LocalServerTransport.Registry registry;
    private Manager manager;
    private TestClock clock;
    private SocketConnectionManager scm;
    private NodeBootstrap.FaultSubsystem subsystem;
    private UUID nodeId;
    private boolean managerClosed;

    @BeforeEach
    void setUp(@TempDir Path walDir) throws IOException {
        registry = LocalServerTransport.Registry.create();
        clock = new TestClock(1_000L);
        manager = new Manager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                              SpatialLevelHeuristic.DEFAULT_AOI_RADIUS, clock);
        scm = new SocketConnectionManager(ProcessAddress.localhost("s3-shutdown", 0), msg -> {});
        var pm = new PersistenceManager(UUID.randomUUID(), walDir);
        NodeBootstrap.assemble(manager, new SocketConnectionManagerAdapter(scm),
                               new PersistenceManagerAdapter(pm));
        subsystem = NodeBootstrap.assembleFaultTolerance(manager, clock);
        nodeId = UUID.randomUUID();
        managerClosed = false;
    }

    @AfterEach
    void tearDown() {
        if (!managerClosed && manager != null) {
            manager.close();
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void assembleRegistersRecoveryAsLifecycleParticipant() {
        assertThat(manager.coordinator().getState(RecoveryIntegrationAdapter.NAME))
            .isEqualTo(LifecycleState.RUNNING);
    }

    @Test
    void managerCloseStopsRecoveryBeforeBubbleAdapters() {
        // The ordering test proper. bubble1 (registered) and bubble2 are in-process neighbors:
        // when a bubble adapter stops, its broadcastLeave() synchronously delivers LEAVE to the
        // sibling, whose handleLeave dispatches Event.Leave back through the manager. If the
        // recovery integration were still subscribed at that point (stopped after — or in the
        // same layer as — the bubbles), the node would report a sync failure against its own
        // partition during normal shutdown. The dynamic bubble dependencies of
        // RecoveryIntegrationAdapter put it in the layer ABOVE all bubbles, so it stops first.
        var bubble1 = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);
        var bubble2 = manager.createBubble();
        bubble1.addNeighbor(bubble2.id());
        bubble2.addNeighbor(bubble1.id());

        manager.close();
        managerClosed = true;

        // Recovery stopped through the coordinator (Manager.close() drove it)...
        assertThat(manager.coordinator().getState(RecoveryIntegrationAdapter.NAME))
            .isEqualTo(LifecycleState.STOPPED);
        assertThat(subsystem.faultHandler().isRunning()).isFalse();
        // ...and BEFORE the bubble adapters: no self-escalation happened during shutdown.
        assertThat(subsystem.faultHandler().checkHealth(nodeId)).isNull();
        assertThat(subsystem.faultHandler().getAggregateMetrics().failureCount()).isZero();
    }

    @Test
    void liveBubbleRemovalNotBlockedByRecoveryDependency() {
        // The recovery adapter's dynamic dependency on bubble adapters must be ORDERING-ONLY:
        // stopAndUnregister's dependents guard would otherwise reject every live bubble removal
        // ("Cannot remove EnhancedBubble-X - RecoveryIntegration depends on it"), which
        // Manager.leave silently absorbs by falling back to a direct bubble.close() — losing
        // coordinated shutdown AND leaking the bubble adapter in the coordinator forever
        // (S3 review Critical).
        var bubble = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);
        var adapterName = "EnhancedBubble-" + bubble.id();
        assertThat(manager.coordinator().getState(adapterName)).isEqualTo(LifecycleState.RUNNING);

        NodeBootstrap.removeRegisteredBubble(manager, subsystem.recovery(), bubble);

        // The COORDINATOR path must have run: the adapter is genuinely unregistered, not leaked
        // as a RUNNING component whose bubble was closed behind its back by the fallback path.
        assertThat(manager.coordinator().getState(adapterName)).isNull();
        // And the recovery adapter no longer lists it as a dependency.
        assertThat(manager.getBubble(bubble.id())).isNull();
    }

    @Test
    void managerCloseWithNoBubblesStopsRecoveryCleanly() {
        manager.close();
        managerClosed = true;

        assertThat(manager.coordinator().getState(RecoveryIntegrationAdapter.NAME))
            .isEqualTo(LifecycleState.STOPPED);
        assertThat(subsystem.faultHandler().isRunning()).isFalse();
    }

    @Test
    void manualSubsystemCloseBeforeManagerCloseRemainsSafe() {
        // The S1 caller contract (subsystem.close() before Manager.close()) must remain valid
        // alongside lifecycle integration: the adapter's doStop finds an already-closed
        // subsystem and the compositional idempotency absorbs the double close.
        NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);

        subsystem.close();
        manager.close();
        managerClosed = true;

        assertThat(manager.coordinator().getState(RecoveryIntegrationAdapter.NAME))
            .isEqualTo(LifecycleState.STOPPED);
        assertThat(subsystem.faultHandler().isRunning()).isFalse();
    }
}
