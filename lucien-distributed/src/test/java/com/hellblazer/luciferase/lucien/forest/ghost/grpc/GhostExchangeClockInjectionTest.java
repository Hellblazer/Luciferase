/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostBatch;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostExchangeGrpc;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostRequest;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.StatsResponse;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end clock-wiring test for GhostExchangeServiceImpl (Luciferase-7wzml.86).
 *
 * <p>Verifies that {@code GhostExchangeServiceImpl.setClock()} is threaded all the way through
 * {@code createGhostBatch} → {@code GhostBatch.timestamp}.  A regression that reverts the
 * {@code now} threading would cause the returned timestamp to reflect wall time rather than the
 * injected time, making this assertion fail.
 *
 * <p>Mirrors the pattern in {@link com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorIntegrationTest
 * #testRefinementResponseTimestampUsesInjectedClock}.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class GhostExchangeClockInjectionTest {

    private static final ContentSerializer<String> SERIALIZER = new ContentSerializer<>() {
        @Override public byte[] serialize(String content) { return content.getBytes(StandardCharsets.UTF_8); }
        @Override public String deserialize(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        @Override public String getContentType() { return "string"; }
    };

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** Minimal provider serving a single fixed layer. */
    private static final class FixedProvider
            implements GhostExchangeServiceImpl.GhostLayerProvider<MortonKey, LongEntityID, String> {
        final GhostLayer<MortonKey, LongEntityID, String> layer;

        FixedProvider(GhostLayer<MortonKey, LongEntityID, String> layer) { this.layer = layer; }

        @Override public GhostLayer<MortonKey, LongEntityID, String> getGhostLayer(long treeId) { return layer; }
        @Override public int getCurrentRank() { return 0; }
        @Override public void addGhostElement(GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public void updateGhostElement(GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public boolean removeGhostElement(String entityId, long treeId) { return false; }
        @Override public StatsResponse getGlobalStats() { return StatsResponse.getDefaultInstance(); }
    }

    /** Controllable clock for deterministic assertions. */
    private static final class TestClock implements Clock {
        private final AtomicLong millis;

        TestClock(long initialMillis) { this.millis = new AtomicLong(initialMillis); }

        @Override public long currentTimeMillis() { return millis.get(); }
        @Override public long nanoTime() { return millis.get() * 1_000_000L; }
    }

    /**
     * Injects a TestClock, drives requestGhosts end-to-end via an in-process gRPC channel, and
     * asserts that GhostBatch.timestamp reflects the injected time — not wall time.
     *
     * <p>The injected time is 5_000_000 ms = 5000 seconds.  If clock threading regresses to
     * System.currentTimeMillis() the seconds field would be ~1.7 billion (Unix epoch), making the
     * equality check fail immediately.
     */
    @Test
    void requestGhostsBatchTimestampReflectsInjectedClock() throws Exception {
        // Injected time: 5_000_000 ms = 5000 s, 0 ns remainder
        long injectedMillis = 5_000_000L;
        long expectedSeconds = injectedMillis / 1000;       // 5000
        int expectedNanos   = (int) ((injectedMillis % 1000) * 1_000_000); // 0

        var key = new MortonKey(0x1234L, (byte) 3);
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(key, new LongEntityID(42L), "content", new Point3f(0, 1, 2), 0, 1L));

        var service = new GhostExchangeServiceImpl<>(new FixedProvider(layer), SERIALIZER, LongEntityID.class);
        service.setClock(new TestClock(injectedMillis));

        var name = "ghost-clock-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var stub = GhostExchangeGrpc.newBlockingStub(channel);

        // Build a request for all ghosts (no boundary keys) — routes through ghostLayerToProtobufBatch path
        var request = GhostRequest.newBuilder()
                                  .setRequesterRank(1)
                                  .setRequesterTreeId(1L)
                                  .setGhostType(com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType.FACES)
                                  .build();

        GhostBatch batch = stub.requestGhosts(request);

        assertNotNull(batch, "GhostBatch must not be null");
        assertTrue(batch.hasTimestamp(), "GhostBatch must have a timestamp set");
        assertEquals(expectedSeconds, batch.getTimestamp().getSeconds(),
            "GhostBatch.timestamp.seconds must come from the injected clock (5000), not wall time (~1.7e9)");
        assertEquals(expectedNanos, batch.getTimestamp().getNanos(),
            "GhostBatch.timestamp.nanos must be derived from injected millis (0 remainder)");
        assertEquals(1, batch.getElementsCount(), "Batch must contain the one ghost element");
    }

    /**
     * Also drives the boundary-keys path (the {@code request.getBoundaryKeysCount() > 0} branch
     * inside createGhostBatch), ensuring the SAME injected clock is used for both code paths.
     */
    @Test
    void requestGhostsBoundaryKeyPathTimestampReflectsInjectedClock() throws Exception {
        long injectedMillis = 7_500L;   // 7 s, 500 ms → 7 seconds, 500_000_000 nanos
        long expectedSeconds = 7L;
        int  expectedNanos   = 500_000_000;

        var key = new MortonKey(0xABCL, (byte) 5);
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(key, new LongEntityID(99L), "hello", new Point3f(3, 4, 5), 0, 2L));

        var service = new GhostExchangeServiceImpl<>(new FixedProvider(layer), SERIALIZER, LongEntityID.class);
        service.setClock(new TestClock(injectedMillis));

        var name = "ghost-clock-bk-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var stub = GhostExchangeGrpc.newBlockingStub(channel);

        var protoKey = ProtobufConverters.spatialKeyToProtobuf(key);
        var request = GhostRequest.newBuilder()
                                  .setRequesterRank(2)
                                  .setRequesterTreeId(2L)
                                  .setGhostType(com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType.FACES)
                                  .addBoundaryKeys(protoKey)
                                  .build();

        GhostBatch batch = stub.requestGhosts(request);

        assertNotNull(batch, "GhostBatch must not be null (boundary-keys path)");
        assertTrue(batch.hasTimestamp(), "GhostBatch must have a timestamp set (boundary-keys path)");
        assertEquals(expectedSeconds, batch.getTimestamp().getSeconds(),
            "boundary-keys path: timestamp.seconds must come from injected clock (7), not wall time");
        assertEquals(expectedNanos, batch.getTimestamp().getNanos(),
            "boundary-keys path: timestamp.nanos must match 500ms remainder (500_000_000 ns)");
    }
}
