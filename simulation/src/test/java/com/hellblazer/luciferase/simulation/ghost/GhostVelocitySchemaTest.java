/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostEntityHalo;
import com.hellblazer.luciferase.simulation.bubble.ExternalBubbleTracker;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.Bubble;
import com.hellblazer.luciferase.simulation.von.Message;
import com.hellblazer.luciferase.simulation.von.TransportGhostData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Acceptance tests for Luciferase-chmxx: velocity plumbed through ghost message schema.
 * <p>
 * Covers all four acceptance criteria:
 * (a) TransportGhost carries velocity; toTransportGhost sets it; fromTransportGhost reads it into 6-arg ctor.
 * (b) addGhost/GhostEntry carry velocity; adapters supply real entity velocity (via addGhost).
 * (c) Ghost received via P2PGhostChannel with non-zero velocity carries that velocity.
 * (d) Ghost queued via addGhost with non-zero velocity is transmitted with that velocity.
 *
 * @author hal.hildebrand
 */
class GhostVelocitySchemaTest {

    private static final byte  SPATIAL_LEVEL   = 10;
    private static final long  TARGET_FRAME_MS = 10;
    private static final float DELTA           = 0.0001f;

    // ── P2P channel infrastructure ──────────────────────────────────────────
    private LocalServerTransport.Registry registry;
    private Bubble                         bubble1;
    private Bubble                         bubble2;
    private P2PGhostChannel<StringEntityID, Object> channel1;
    private P2PGhostChannel<StringEntityID, Object> channel2;

    @BeforeEach
    void setup() {
        registry = LocalServerTransport.Registry.create();

        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var transport1 = registry.register(id1);
        var transport2 = registry.register(id2);

        bubble1 = new Bubble(id1, SPATIAL_LEVEL, TARGET_FRAME_MS, transport1);
        bubble2 = new Bubble(id2, SPATIAL_LEVEL, TARGET_FRAME_MS, transport2);

        bubble1.addEntity("entity-1", new Point3f(50.0f, 50.0f, 50.0f), new Object());
        bubble2.addEntity("entity-2", new Point3f(55.0f, 55.0f, 50.0f), new Object());

        bubble1.addNeighbor(bubble2.id());
        bubble2.addNeighbor(bubble1.id());

        channel1 = new P2PGhostChannel<>(bubble1);
        channel2 = new P2PGhostChannel<>(bubble2);
    }

    @AfterEach
    void teardown() {
        if (channel1 != null) channel1.close();
        if (channel2 != null) channel2.close();
        if (bubble1 != null) bubble1.close();
        if (bubble2 != null) bubble2.close();
        if (registry != null) registry.close();
    }

    // ── (a) TransportGhost record carries velocity ──────────────────────────

    @Test
    void testTransportGhostRecordHasVelocityField() {
        var vel = new Vector3f(1.5f, -2.3f, 0.7f);
        var tg = new Message.TransportGhost(
            "e-1",
            new Point3f(1f, 2f, 3f),
            "String",
            "value",
            "tree-1",
            1L, 1L, 100L,
            vel
        );

        assertThat(tg.velocity().x).isCloseTo(1.5f, within(DELTA));
        assertThat(tg.velocity().y).isCloseTo(-2.3f, within(DELTA));
        assertThat(tg.velocity().z).isCloseTo(0.7f, within(DELTA));
    }

    @Test
    void testTransportGhostDataRoundTripsVelocity() {
        var vel = new Vector3f(3.0f, -1.0f, 2.5f);
        var tg = new Message.TransportGhost(
            "e-2",
            new Point3f(0f, 0f, 0f),
            "String",
            "v",
            "tree-2",
            2L, 2L, 200L,
            vel
        );

        var data = TransportGhostData.from(tg);
        assertThat(data.velX()).isCloseTo(3.0f, within(DELTA));
        assertThat(data.velY()).isCloseTo(-1.0f, within(DELTA));
        assertThat(data.velZ()).isCloseTo(2.5f, within(DELTA));

        var recovered = data.toTransportGhost();
        assertThat(recovered.velocity().x).isCloseTo(3.0f, within(DELTA));
        assertThat(recovered.velocity().y).isCloseTo(-1.0f, within(DELTA));
        assertThat(recovered.velocity().z).isCloseTo(2.5f, within(DELTA));
    }

    // ── (b) addGhost/GhostEntry carry velocity ──────────────────────────────

    @Test
    void testAddGhostCarriesVelocity() {
        var tracker = new ExternalBubbleTracker();
        var health = new GhostLayerHealth();
        List<SimulationGhostEntity<StringEntityID, Object>> sent = new ArrayList<>();

        var sync = new GhostBoundarySync<StringEntityID, Object>(
            tracker, health, (neighborId, ghosts) -> sent.addAll(ghosts));

        var entityId = new StringEntityID("vel-entity");
        var position = new Point3f(1f, 2f, 3f);
        var vel = new Vector3f(5f, -3f, 1f);
        var ghostEntity = new GhostEntityHalo<>(entityId, (Object) com.hellblazer.luciferase.simulation.entity.EntityType.PREY, position,
                                                new EntityBounds(position, 0.5f), "tree-X");

        var sourceBubbleId = UUID.randomUUID();
        var neighborId = UUID.randomUUID();

        sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 10L, vel);
        sync.onBucketComplete(10L);

        assertThat(sent).hasSize(1);
        var g = sent.get(0);
        assertThat(g.velocity().x).isCloseTo(5f, within(DELTA));
        assertThat(g.velocity().y).isCloseTo(-3f, within(DELTA));
        assertThat(g.velocity().z).isCloseTo(1f, within(DELTA));
    }

    @Test
    void testAddGhostZeroVelocityOverload() {
        // The old 4-arg addGhost (no velocity) defaults to zero — existing callers remain correct.
        var tracker = new ExternalBubbleTracker();
        var health = new GhostLayerHealth();
        List<SimulationGhostEntity<StringEntityID, Object>> sent = new ArrayList<>();

        var sync = new GhostBoundarySync<StringEntityID, Object>(
            tracker, health, (neighborId, ghosts) -> sent.addAll(ghosts));

        var entityId = new StringEntityID("zero-vel-entity");
        var position = new Point3f(1f, 2f, 3f);
        var ghostEntity = new GhostEntityHalo<>(entityId, (Object) com.hellblazer.luciferase.simulation.entity.EntityType.PREY, position,
                                                new EntityBounds(position, 0.5f), "tree-Z");

        var sourceBubbleId = UUID.randomUUID();
        var neighborId = UUID.randomUUID();

        // Old 4-arg call — should still compile and produce zero velocity
        sync.addGhost(ghostEntity, sourceBubbleId, neighborId, 10L);
        sync.onBucketComplete(10L);

        assertThat(sent).hasSize(1);
        var g = sent.get(0);
        assertThat(g.velocity().x).isCloseTo(0f, within(DELTA));
        assertThat(g.velocity().y).isCloseTo(0f, within(DELTA));
        assertThat(g.velocity().z).isCloseTo(0f, within(DELTA));
    }

    // ── (c) Ghost received via P2PGhostChannel with non-zero velocity ────────

    @Test
    void testP2PGhostChannelPreservesVelocityRoundTrip() throws Exception {
        var vel = new Vector3f(2.0f, -4.0f, 1.5f);
        var ghost = createGhostWithVelocity("ghost-vel", new Point3f(51f, 51f, 50f), vel);

        var received = new AtomicReference<List<SimulationGhostEntity<StringEntityID, Object>>>();
        var latch = new CountDownLatch(1);
        channel2.onReceive((fromId, ghosts) -> {
            received.set(new ArrayList<>(ghosts));
            latch.countDown();
        });

        channel1.sendBatch(bubble2.id(), List.of(ghost));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).hasSize(1);
        var r = received.get().get(0);
        assertThat(r.velocity().x).isCloseTo(2.0f, within(DELTA));
        assertThat(r.velocity().y).isCloseTo(-4.0f, within(DELTA));
        assertThat(r.velocity().z).isCloseTo(1.5f, within(DELTA));
    }

    @Test
    void testP2PGhostChannelVelocityZeroWhenSenderHasZero() throws Exception {
        var ghost = createGhostWithVelocity("ghost-zero", new Point3f(51f, 51f, 50f),
                                            new Vector3f(0f, 0f, 0f));

        var received = new AtomicReference<List<SimulationGhostEntity<StringEntityID, Object>>>();
        var latch = new CountDownLatch(1);
        channel2.onReceive((fromId, ghosts) -> {
            received.set(new ArrayList<>(ghosts));
            latch.countDown();
        });

        channel1.sendBatch(bubble2.id(), List.of(ghost));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        var r = received.get().get(0);
        assertThat(r.velocity().x).isCloseTo(0f, within(DELTA));
        assertThat(r.velocity().y).isCloseTo(0f, within(DELTA));
        assertThat(r.velocity().z).isCloseTo(0f, within(DELTA));
    }

    // ── (d) Ghost queued via addGhost with non-zero velocity is transmitted ──

    @Test
    void testQueueAndFlushPreservesVelocity() throws Exception {
        var vel = new Vector3f(7f, 0f, -3.5f);
        var ghost = createGhostWithVelocity("ghost-flush", new Point3f(51f, 51f, 50f), vel);

        var received = new AtomicReference<List<SimulationGhostEntity<StringEntityID, Object>>>();
        var latch = new CountDownLatch(1);
        channel2.onReceive((fromId, ghosts) -> {
            received.set(new ArrayList<>(ghosts));
            latch.countDown();
        });

        channel1.queueGhost(bubble2.id(), ghost);
        channel1.flush(100);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).hasSize(1);
        var r = received.get().get(0);
        assertThat(r.velocity().x).isCloseTo(7f, within(DELTA));
        assertThat(r.velocity().y).isCloseTo(0f, within(DELTA));
        assertThat(r.velocity().z).isCloseTo(-3.5f, within(DELTA));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SimulationGhostEntity<StringEntityID, Object> createGhostWithVelocity(
        String id, Point3f position, Vector3f velocity) {
        var entityId = new StringEntityID(id);
        var halo = new GhostEntityHalo<>(entityId, (Object) com.hellblazer.luciferase.simulation.entity.EntityType.PREY, position,
                                         new EntityBounds(position, 0.5f), "tree-test");
        return new SimulationGhostEntity<>(halo, bubble1.id(), 100L, 1L, 1L, velocity);
    }
}
