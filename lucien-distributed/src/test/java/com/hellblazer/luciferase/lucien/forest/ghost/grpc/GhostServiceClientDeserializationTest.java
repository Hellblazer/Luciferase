/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.google.protobuf.ByteString;
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
import io.grpc.stub.StreamObserver;
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
        @Override public boolean removeGhostElement(String entityId, long treeId) { return false; }
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

    // --- Luciferase-7wzml.90: null/empty/partial contract ---

    /** Builds a fixed-response gRPC service that serves the given GhostBatch verbatim. */
    private GhostExchangeGrpc.GhostExchangeImplBase fixedBatchService(GhostBatch batch) {
        return new GhostExchangeGrpc.GhostExchangeImplBase() {
            @Override
            public void requestGhosts(GhostRequest request, StreamObserver<GhostBatch> responseObserver) {
                responseObserver.onNext(batch);
                responseObserver.onCompleted();
            }
        };
    }

    /** Builds a corrupt (unparseable) GhostElement — missing spatial key, garbage entity-id bytes. */
    private com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement corruptElement() {
        return com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setEntityId("not-a-valid-id")
            .setContent(ByteString.copyFromUtf8("garbage"))
            .setPosition(ProtobufConverters.point3fToProtobuf(new Point3f(0, 0, 0)))
            .setOwnerRank(0)
            .setGlobalTreeId(0L)
            // intentionally omit spatialKey — deserialization will throw
            .build();
    }

    private com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement validElement(MortonKey key,
            long entityId, String content) throws ContentSerializer.SerializationException {
        return com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement.newBuilder()
            .setSpatialKey(ProtobufConverters.spatialKeyToProtobuf(key))
            .setEntityId(ProtobufConverters.entityIdToString(new LongEntityID(entityId)))
            .setContent(ByteString.copyFrom(SERIALIZER.serialize(content)))
            .setPosition(ProtobufConverters.point3fToProtobuf(new Point3f(1, 2, 3)))
            .setOwnerRank(1)
            .setGlobalTreeId(0L)
            .build();
    }

    @Test
    void requestGhostElementsReturnsNullWhenAllElementsUnparseable() throws Exception {
        // Batch with 2 corrupt elements — every parse fails → must return null (not empty list).
        var batch = GhostBatch.newBuilder().addElements(corruptElement()).addElements(corruptElement()).build();
        var name = "ghost-all-corrupt";
        server = InProcessServerBuilder.forName(name).directExecutor().addService(fixedBatchService(batch)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var client = new GhostServiceClient<MortonKey, LongEntityID, String>(0, SERIALIZER, LongEntityID.class, null, null);
        injectStub(client, 1, GhostExchangeGrpc.newBlockingStub(channel));

        var result = client.requestGhostElements(1, 0L, GhostType.FACES, List.of());

        assertNull(result, "Wholly-unparseable batch must return null (indistinguishable from connection failure, "
                           + "not silently empty)");
    }

    @Test
    void requestGhostElementsReturnsEmptyListForGenuinelyEmptyBatch() throws Exception {
        // Peer returns an empty batch — legitimate "no ghosts" response → must return empty list (not null).
        var batch = GhostBatch.newBuilder().build();
        var name = "ghost-empty";
        server = InProcessServerBuilder.forName(name).directExecutor().addService(fixedBatchService(batch)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var client = new GhostServiceClient<MortonKey, LongEntityID, String>(0, SERIALIZER, LongEntityID.class, null, null);
        injectStub(client, 1, GhostExchangeGrpc.newBlockingStub(channel));

        var result = client.requestGhostElements(1, 0L, GhostType.FACES, List.of());

        assertNotNull(result, "Genuinely-empty batch is a valid response, not a failure");
        assertTrue(result.isEmpty(), "Genuinely-empty batch must return an empty list");
    }

    @Test
    void requestGhostElementsReturnsValidSubsetOnPartialParse() throws Exception {
        // One valid element, one corrupt — partial parse → return the valid subset (not null).
        var validKey = new MortonKey(0x123L, (byte) 3);
        var batch = GhostBatch.newBuilder()
            .addElements(validElement(validKey, 999L, "ok"))
            .addElements(corruptElement())
            .build();
        var name = "ghost-partial";
        server = InProcessServerBuilder.forName(name).directExecutor().addService(fixedBatchService(batch)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var client = new GhostServiceClient<MortonKey, LongEntityID, String>(0, SERIALIZER, LongEntityID.class, null, null);
        injectStub(client, 1, GhostExchangeGrpc.newBlockingStub(channel));

        var result = client.requestGhostElements(1, 0L, GhostType.FACES, List.of());

        assertNotNull(result, "Partial-parse batch is not a wholesale failure");
        assertEquals(1, result.size(), "Only the parseable element must survive");
        assertEquals(validKey, result.get(0).getSpatialKey());
        assertEquals("ok", result.get(0).getContent());
    }
}
