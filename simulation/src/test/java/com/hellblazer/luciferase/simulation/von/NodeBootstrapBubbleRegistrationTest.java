/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.lucien.balancing.fault.PartitionStatus;
import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3d;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RDR-021 S2 (Luciferase-0frcy.135.3) — the bubble create/remove path drives
 * {@code RecoveryIntegration.registerBubble/unregisterBubble}, and the error contract for
 * VON events on unregistered bubbles is a deliberate silent no-op.
 * <p>
 * Partition-id provenance (S0, Luciferase-0frcy.135.1): the single-process node uses its own
 * node identity ({@code NodeBootstrap.resolveNodeId}) as the partition id; the registration
 * seam lives at the composition layer because {@code Manager.createBubble} has no creation
 * event hook.
 */
class NodeBootstrapBubbleRegistrationTest {

    private LocalServerTransport.Registry registry;
    private Manager manager;
    private TestClock clock;
    private NodeBootstrap.FaultSubsystem subsystem;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        registry = LocalServerTransport.Registry.create();
        clock = new TestClock(1_000L);
        manager = new Manager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                              SpatialLevelHeuristic.DEFAULT_AOI_RADIUS, clock);
        subsystem = NodeBootstrap.assembleFaultTolerance(manager, clock);
        nodeId = UUID.randomUUID(); // stands in for NodeBootstrap.resolveNodeId(member)
    }

    @AfterEach
    void tearDown() {
        if (subsystem != null) {
            subsystem.close();
        }
        if (manager != null) {
            manager.close();
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void createRegisteredBubbleDrivesRegistration() {
        var bubble = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);

        assertThat(bubble).isNotNull();
        assertThat(manager.getBubble(bubble.id())).isSameAs(bubble);
        // The creation path drove registerBubble with the node's partition identity.
        assertThat(subsystem.recovery().getPartitionForBubble(bubble.id())).isEqualTo(nodeId);
        assertThat(subsystem.topology().rankFor(nodeId)).isPresent();
    }

    @Test
    void removeRegisteredBubbleDrivesUnregistration() {
        var bubble = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);

        NodeBootstrap.removeRegisteredBubble(manager, subsystem.recovery(), bubble);

        assertThat(subsystem.recovery().getPartitionForBubble(bubble.id())).isNull();
        assertThat(manager.getBubble(bubble.id())).isNull();
    }

    @Test
    void removalWithInProcessNeighborDoesNotSelfEscalate() {
        // The unregister-BEFORE-leave ordering regression test. A single-bubble removal cannot
        // catch a wrong ordering (no neighbors -> broadcastLeave() sends nothing), so this test
        // wires an in-process neighbor: bubble1's broadcastLeave() synchronously delivers LEAVE
        // to bubble2, whose handleLeave dispatches Event.Leave(bubble1) back through the manager.
        // Wrong ordering (leave before unregister) would find bubble1 still registered ->
        // reportSyncFailure(nodeId) -> SUSPECTED. Correct ordering makes it a silent no-op.
        var bubble1 = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);
        var bubble2 = manager.createBubble(); // unregistered in-process neighbor
        bubble1.addNeighbor(bubble2.id());
        bubble2.addNeighbor(bubble1.id());

        NodeBootstrap.removeRegisteredBubble(manager, subsystem.recovery(), bubble1);

        assertThat(subsystem.faultHandler().checkHealth(nodeId)).isNull();
        assertThat(subsystem.faultHandler().getAggregateMetrics().failureCount()).isZero();
    }

    @Test
    void removalOfLastBubbleUnregistersPartition() {
        var bubble = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);
        assertThat(subsystem.topology().rankFor(nodeId)).isPresent();

        NodeBootstrap.removeRegisteredBubble(manager, subsystem.recovery(), bubble);

        assertThat(subsystem.topology().rankFor(nodeId)).isEmpty();
    }

    @Test
    void vonEventsForUnregisteredBubbleAreSilentNoOps() {
        // Error contract (RDR-021 §Technical Design): a VON Leave/Join for a bubble not in
        // bubbleToPartition is deliberately silent — many VON neighbors are legitimately
        // unregistered. NOT fail-loud.
        var bubble = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);
        var unregistered = UUID.randomUUID();

        // Establish LIVE fault-handler state first: a Leave for the registered bubble drives
        // nodeId to SUSPECTED. Without this, the no-escalation assertions below would also pass
        // if RecoveryIntegration were never subscribed at all (review S2 critique).
        manager.dispatchEvent(new Event.Leave(bubble.id(), new Point3d(0, 0, 0)));
        assertThat(subsystem.faultHandler().checkHealth(nodeId)).isEqualTo(PartitionStatus.SUSPECTED);
        var failuresBefore = subsystem.faultHandler().getMetrics(nodeId).failureCount();

        assertThatCode(() -> {
            manager.dispatchEvent(new Event.Leave(unregistered, new Point3d(0, 0, 0)));
            manager.dispatchEvent(new Event.Join(unregistered, new Point3d(0, 0, 0)));
        }).doesNotThrowAnyException();

        // Still SUSPECTED (a second registered Leave would have escalated to FAILED; a registered
        // Join would have recovered to HEALTHY): the unregistered events were genuine no-ops
        // against a live handler.
        assertThat(subsystem.faultHandler().checkHealth(nodeId)).isEqualTo(PartitionStatus.SUSPECTED);
        assertThat(subsystem.faultHandler().getMetrics(nodeId).failureCount()).isEqualTo(failuresBefore);
    }

    @Test
    void registeredBubbleLeaveStillEscalates() {
        // Guard against over-correcting the silent no-op contract: events for REGISTERED
        // bubbles must still reach the fault handler.
        var bubble = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);

        manager.dispatchEvent(new Event.Leave(bubble.id(), new Point3d(0, 0, 0)));

        assertThat(subsystem.faultHandler().checkHealth(nodeId)).isNotNull();
    }

    @Test
    void nullArgumentsFailLoud() {
        var recovery = subsystem.recovery();
        var bubble = manager.createBubble();

        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.createRegisteredBubble(null, recovery, nodeId));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.createRegisteredBubble(manager, null, nodeId));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.createRegisteredBubble(manager, recovery, null));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.removeRegisteredBubble(null, recovery, bubble));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.removeRegisteredBubble(manager, null, bubble));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.removeRegisteredBubble(manager, recovery, null));
    }
}
