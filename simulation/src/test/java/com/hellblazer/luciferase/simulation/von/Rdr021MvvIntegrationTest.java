/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionStatus;
import com.hellblazer.luciferase.simulation.bubble.SpatialLevelHeuristic;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import com.hellblazer.luciferase.simulation.lifecycle.PersistenceManagerAdapter;
import com.hellblazer.luciferase.simulation.lifecycle.SocketConnectionManagerAdapter;
import com.hellblazer.luciferase.simulation.persistence.PersistenceManager;
import com.hellblazer.luciferase.simulation.von.transport.ProcessAddress;
import com.hellblazer.luciferase.simulation.von.transport.SocketConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.vecmath.Point3d;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDR-021 Minimum Viable Validation (Luciferase-0frcy.135.6) — the end-to-end proof that the wired
 * VON-event-driven recovery chain fires against a <b>test-assembled node</b>: real
 * {@code RecoveryIntegration}, real {@code SimpleFaultHandler}, real
 * {@code InMemoryPartitionTopology}, {@code TestClock} — NOT mocked at the escalation boundary
 * (RDR-020 MVV lesson: mocking the integration boundary hides the gap).
 * <p>
 * The chain (RDR-021 §Minimum Viable Validation):
 * <ol>
 *   <li>{@code registerBubble(bubbleId, partitionId)} for a bubble in a partition;</li>
 *   <li>two VON {@code Event.Leave} for the registered bubble →
 *       {@code reportSyncFailure} ×2 → partition reaches <b>FAILED</b> (two-sync-failure
 *       confirmation threshold) — proves <b>Gap 1</b> (escalation reaches the FaultHandler);</li>
 *   <li>a VON {@code Event.Join} for a bubble in that partition → {@code markHealthy} →
 *       FAILED→HEALTHY → {@code onPartitionRecovered} → {@code processPartitionRecovery} →
 *       {@code vonManager.joinAt(bubble, position)} — proves <b>Gap 2</b> (the
 *       FAILED→recovery→rejoin chain fires end-to-end).</li>
 * </ol>
 * The {@code joinAt} assertion uses a recording subclass that delegates to the real
 * implementation — the VON rejoin actually executes (solo join), nothing is stubbed.
 * Does not depend on the live {@code main()} (still throws; Phase 0 contract).
 */
class Rdr021MvvIntegrationTest {

    /** Records joinAt invocations while delegating to the real implementation. */
    private static final class RecordingManager extends Manager {
        record JoinAtCall(UUID bubbleId, Point3d position, boolean result) {}

        final List<JoinAtCall> joinAtCalls = new CopyOnWriteArrayList<>();

        RecordingManager(LocalServerTransport.Registry registry, byte spatialLevel, long targetFrameMs,
                         float aoiRadius, Clock clock) {
            super(registry, spatialLevel, targetFrameMs, aoiRadius, clock);
        }

        @Override
        public boolean joinAt(Bubble bubble, Point3d position) {
            var result = super.joinAt(bubble, position);
            joinAtCalls.add(new JoinAtCall(bubble.id(), position, result));
            return result;
        }
    }

    private static final Point3d ORIGIN = new Point3d(0, 0, 0);

    private LocalServerTransport.Registry registry;
    private RecordingManager manager;
    private TestClock clock;
    private NodeBootstrap.FaultSubsystem subsystem;
    private UUID nodeId;
    private List<Event.PartitionRecovered> recoveredEvents;

    @BeforeEach
    void setUp(@TempDir Path walDir) throws IOException {
        registry = LocalServerTransport.Registry.create();
        clock = new TestClock(1_000L);
        manager = new RecordingManager(registry, SpatialLevelHeuristic.DEFAULT_SPATIAL_LEVEL, 16L,
                                       SpatialLevelHeuristic.DEFAULT_AOI_RADIUS, clock);
        var scm = new SocketConnectionManager(ProcessAddress.localhost("rdr021-mvv", 0), msg -> {});
        var pm = new PersistenceManager(UUID.randomUUID(), walDir);
        NodeBootstrap.assemble(manager, new SocketConnectionManagerAdapter(scm),
                               new PersistenceManagerAdapter(pm));
        subsystem = NodeBootstrap.assembleFaultTolerance(manager, clock);
        nodeId = UUID.randomUUID(); // the node's partition identity (stands in for resolveNodeId)
        recoveredEvents = new CopyOnWriteArrayList<>();
        // Registered after assembleFaultTolerance: dispatchEvent uses a CopyOnWriteArrayList, so
        // every listener present at dispatch time receives the event regardless of order — the
        // PartitionRecovered emission happens synchronously well after both registrations.
        manager.addEventListener(event -> {
            if (event instanceof Event.PartitionRecovered recovered) {
                recoveredEvents.add(recovered);
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close(); // drives subsystem shutdown via RecoveryIntegrationAdapter (S3)
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void twoLeavesEscalateToFailedThenJoinRecoversAndRejoins() {
        // 1. Registered bubble in the node's partition, at a NON-ORIGIN position (entities drive
        //    Bubble.position() via the bounds centroid). Without entities, position() falls back
        //    to (0,0,0) and the joinAt position assertion would be vacuously true against ORIGIN.
        var bubble = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);
        addEntities(bubble, new javax.vecmath.Point3f(50.0f, 50.0f, 50.0f), 9);
        assertThat(bubble.position()).as("precondition: meaningful position").isNotEqualTo(ORIGIN);

        // 2. Gap 1 — two VON leaves escalate HEALTHY→SUSPECTED→FAILED through the real handler.
        manager.dispatchEvent(new Event.Leave(bubble.id(), ORIGIN));
        assertThat(subsystem.faultHandler().checkHealth(nodeId))
            .as("first leave reaches the FaultHandler: HEALTHY→SUSPECTED")
            .isEqualTo(PartitionStatus.SUSPECTED);

        manager.dispatchEvent(new Event.Leave(bubble.id(), ORIGIN));
        assertThat(subsystem.faultHandler().checkHealth(nodeId))
            .as("second leave confirms the failure: SUSPECTED→FAILED (two-sync-failure threshold)")
            .isEqualTo(PartitionStatus.FAILED);

        assertThat(manager.joinAtCalls).as("no rejoin before recovery").isEmpty();

        // 3. Gap 2 — a VON join for the partition's bubble drives the full recovery chain:
        //    markHealthy → FAILED→HEALTHY → onPartitionRecovered → processPartitionRecovery →
        //    vonManager.joinAt(bubble, position).
        manager.dispatchEvent(new Event.Join(bubble.id(), ORIGIN));

        assertThat(subsystem.faultHandler().checkHealth(nodeId))
            .as("join marks the partition healthy")
            .isEqualTo(PartitionStatus.HEALTHY);

        assertThat(manager.joinAtCalls)
            .as("recovery rejoined the partition's bubble via the real joinAt")
            .hasSize(1);
        var call = manager.joinAtCalls.get(0);
        assertThat(call.bubbleId()).isEqualTo(bubble.id());
        // Non-vacuous: bubble.position() is entity-derived (~50,50,50), not the ORIGIN fallback —
        // proves recovery read the bubble's actual position, not some other coordinate source.
        assertThat(call.position()).isEqualTo(bubble.position());
        assertThat(call.result()).as("solo rejoin of the only bubble succeeds").isTrue();

        assertThat(recoveredEvents)
            .as("PartitionRecovered emitted through the VON manager")
            .hasSize(1);
        var recovered = recoveredEvents.get(0);
        assertThat(recovered.partitionId()).isEqualTo(nodeId);
        assertThat(recovered.totalBubbles()).isEqualTo(1);
        assertThat(recovered.successfulRejoins()).isEqualTo(1);
        assertThat(recovered.failedRejoins()).isZero();
        assertThat(recovered.cascadeTriggered())
            .as("no cascade: single partition, no recovery dependencies")
            .isFalse();
        assertThat(recovered.recoveryTimeMs()).isGreaterThanOrEqualTo(0L);
    }

    /** Entities establish spatial bounds so Bubble.position() is the entity centroid. */
    private static void addEntities(Bubble bubble, javax.vecmath.Point3f center, int count) {
        for (int i = 0; i < count; i++) {
            var x = center.x + (i % 3) * 0.1f;
            var y = center.y + (i / 3) * 0.1f;
            bubble.addEntity("entity-" + i, new javax.vecmath.Point3f(x, y, center.z), "content-" + i);
        }
    }

    @Test
    void joinFromSuspectedDoesNotFireRecovery() {
        // RDR-021 locked decision #9: SUSPECTED→HEALTHY via a join does NOT fire
        // onPartitionRecovered (the guard is oldStatus == FAILED). Correct by design — recovery
        // (and its joinAt rejoin) is reserved for confirmed-failed partitions.
        var bubble = NodeBootstrap.createRegisteredBubble(manager, subsystem.recovery(), nodeId);

        manager.dispatchEvent(new Event.Leave(bubble.id(), ORIGIN));
        assertThat(subsystem.faultHandler().checkHealth(nodeId)).isEqualTo(PartitionStatus.SUSPECTED);

        manager.dispatchEvent(new Event.Join(bubble.id(), ORIGIN));

        assertThat(subsystem.faultHandler().checkHealth(nodeId))
            .as("join still heals the partition")
            .isEqualTo(PartitionStatus.HEALTHY);
        assertThat(manager.joinAtCalls).as("no rejoin from SUSPECTED").isEmpty();
        assertThat(recoveredEvents).as("no PartitionRecovered from SUSPECTED").isEmpty();
    }
}
