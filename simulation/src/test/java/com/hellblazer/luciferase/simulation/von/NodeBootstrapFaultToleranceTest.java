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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RDR-021 S1 (Luciferase-0frcy.135.2) — {@code NodeBootstrap.assembleFaultTolerance} constructs the
 * partition fault subsystem: a <b>started</b> {@code SimpleFaultHandler} +
 * {@code InMemoryPartitionTopology} backing a {@code RecoveryIntegration} subscribed to the live
 * VON {@link Manager}.
 */
class NodeBootstrapFaultToleranceTest {

    private LocalServerTransport.Registry registry;
    private Manager manager;
    private TestClock clock;
    private NodeBootstrap.FaultSubsystem subsystem;

    @BeforeEach
    void setUp() {
        registry = LocalServerTransport.Registry.create();
        clock = new TestClock(1_000L);
        manager = new Manager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                              SpatialLevelHeuristic.DEFAULT_AOI_RADIUS, clock);
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
    void assembleConstructsStartedWiredSubsystem() {
        subsystem = NodeBootstrap.assembleFaultTolerance(manager, clock);

        assertThat(subsystem.recovery()).isNotNull();
        assertThat(subsystem.topology()).isNotNull();
        assertThat(subsystem.faultHandler()).isNotNull();
        // start() is load-bearing: SimpleFaultHandler.notifySubscribers drops events when not
        // running, which would silently kill the FAILED->HEALTHY recovery chain.
        assertThat(subsystem.faultHandler().isRunning()).isTrue();

        // The three objects are wired to each other: registering a bubble through the returned
        // RecoveryIntegration must register the partition in the returned topology.
        var partitionId = UUID.randomUUID();
        subsystem.recovery().registerBubble(UUID.randomUUID(), partitionId);
        assertThat(subsystem.topology().rankFor(partitionId)).isPresent();
    }

    @Test
    void vonLeaveEscalatesThroughAssembledSubsystem() {
        subsystem = NodeBootstrap.assembleFaultTolerance(manager, clock);

        var bubbleId = UUID.randomUUID();
        var partitionId = UUID.randomUUID();
        subsystem.recovery().registerBubble(bubbleId, partitionId);

        // RecoveryIntegration's constructor subscribed to the live manager: a VON Leave for a
        // registered bubble must reach the fault handler, which escalates one level per failure.
        manager.dispatchEvent(new Event.Leave(bubbleId, new Point3d(0, 0, 0)));
        assertThat(subsystem.faultHandler().checkHealth(partitionId))
            .isEqualTo(PartitionStatus.SUSPECTED);

        manager.dispatchEvent(new Event.Leave(bubbleId, new Point3d(0, 0, 0)));
        assertThat(subsystem.faultHandler().checkHealth(partitionId))
            .isEqualTo(PartitionStatus.FAILED);
    }

    @Test
    void nullArgumentsFailLoud() {
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleFaultTolerance(null, clock));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleFaultTolerance(manager, null));
    }

    @Test
    void closeStopsHandlerAndUnsubscribesFromVon() {
        subsystem = NodeBootstrap.assembleFaultTolerance(manager, clock);

        var bubbleId = UUID.randomUUID();
        var partitionId = UUID.randomUUID();
        subsystem.recovery().registerBubble(bubbleId, partitionId);
        manager.dispatchEvent(new Event.Leave(bubbleId, new Point3d(0, 0, 0)));
        assertThat(subsystem.faultHandler().checkHealth(partitionId))
            .isEqualTo(PartitionStatus.SUSPECTED);

        subsystem.close();
        assertThat(subsystem.faultHandler().isRunning()).isFalse();

        // Unsubscribed: a post-close Leave must not keep escalating. SimpleFaultHandler escalation
        // does NOT gate on running, so a still-attached VON listener would step SUSPECTED->FAILED.
        manager.dispatchEvent(new Event.Leave(bubbleId, new Point3d(0, 0, 0)));
        assertThat(subsystem.faultHandler().checkHealth(partitionId))
            .isEqualTo(PartitionStatus.SUSPECTED);
    }

    @Test
    void closeIsIdempotent() {
        subsystem = NodeBootstrap.assembleFaultTolerance(manager, clock);

        subsystem.close();
        subsystem.close(); // second close must be a safe no-op
        assertThat(subsystem.faultHandler().isRunning()).isFalse();
    }
}
