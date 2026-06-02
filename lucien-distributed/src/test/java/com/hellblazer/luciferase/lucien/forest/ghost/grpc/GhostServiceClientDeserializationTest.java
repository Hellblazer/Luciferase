/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostExchangeGrpc;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.StatsResponse;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-7pias (RDR-004 D3 class). {@code GhostServiceClient.requestGhostElements} hardcoded
 * {@code new UUIDEntityID(...)} (ignoring the injected {@code entityIdClass}) and cast raw {@code ByteString} bytes
 * to {@code Content} (ignoring {@code contentSerializer}). For any {@link LongEntityID} deployment or non-{@code
 * byte[]} content, both throws were swallowed by the per-element catch → every ghost silently dropped while the batch
 * reported success.
 *
 * <p>This drives a real in-process gRPC round trip: a {@link GhostExchangeServiceImpl} serving a {@link LongEntityID}
 * + {@link String}-content ghost, queried through the production {@code GhostServiceClient.requestGhostElements}.
 *
 * @author hal.hildebrand
 */
class GhostServiceClientDeserializationTest {

    private static final ContentSerializer<String> SERIALIZER = new ContentSerializer<>() {
        @Override public byte[] serialize(String content) { return content.getBytes(StandardCharsets.UTF_8); }
        @Override public String deserialize(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        @Override public String getContentType() { return "string"; }
    };

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    /** Minimal provider serving a single fixed layer. */
    private static final class FixedProvider
            implements GhostExchangeServiceImpl.GhostLayerProvider<MortonKey, LongEntityID, String> {
        final GhostLayer<MortonKey, LongEntityID, String> layer;
        FixedProvider(GhostLayer<MortonKey, LongEntityID, String> layer) { this.layer = layer; }
        @Override public GhostLayer<MortonKey, LongEntityID, String> getGhostLayer(long treeId) { return layer; }
        @Override public int getCurrentRank() { return 1; }
        @Override public void addGhostElement(GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public void updateGhostElement(GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public void removeGhostElement(String entityId, long treeId) { }
        @Override public StatsResponse getGlobalStats() { return StatsResponse.getDefaultInstance(); }
    }

    @SuppressWarnings("unchecked")
    private static void injectStub(GhostServiceClient<MortonKey, LongEntityID, String> client, int rank,
                                   GhostExchangeGrpc.GhostExchangeBlockingStub stub) throws Exception {
        Field f = GhostServiceClient.class.getDeclaredField("blockingStubs");
        f.setAccessible(true);
        ((Map<Integer, GhostExchangeGrpc.GhostExchangeBlockingStub>) f.get(client)).put(rank, stub);
    }

    @Test
    void requestGhostElementsSurvivesLongIdAndStringContent() throws Exception {
        var key = new MortonKey(0xABCL, (byte) 5);
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(key, new LongEntityID(777L), "payload", new Point3f(1, 2, 3), 1, 9L));

        var service = new GhostExchangeServiceImpl<>(new FixedProvider(layer), SERIALIZER, LongEntityID.class);
        var name = "ghost-7pias-" + getClass().getSimpleName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var client = new GhostServiceClient<MortonKey, LongEntityID, String>(0, SERIALIZER, LongEntityID.class,
                                                                             null, null);
        injectStub(client, 1, GhostExchangeGrpc.newBlockingStub(channel));

        var elements = client.requestGhostElements(1, 9L, GhostType.FACES, List.of(key));

        assertNotNull(elements);
        assertEquals(1, elements.size(), "LongEntityID + String ghost must survive (no silent drop)");
        var e = elements.get(0);
        assertEquals(new LongEntityID(777L), e.getEntityId(), "entityId deserialized via entityIdClass, not hardcoded UUID");
        assertEquals("payload", e.getContent(), "content deserialized via contentSerializer, not raw byte[] cast");
        assertEquals(key, e.getSpatialKey());
        assertEquals(9L, e.getGlobalTreeId());
    }
}
