/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.common.grpc.GrpcServerHardening;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostExchangeGrpc;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostRequest;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.StatsResponse;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RDR-013 / Luciferase-06ujn: the Ghost gRPC server applies an explicit, configurable inbound message-size bound.
 * The inbound size bound is only observable on a marshalling transport (the in-process transport hands the
 * message object across by reference without serializing, so it cannot enforce a wire-size limit). This therefore
 * drives a real server on a dynamic port (0) for the enforcement assertions: a request exceeding the configured
 * bound is rejected with RESOURCE_EXHAUSTED, while the same request succeeds under a generous bound. The helper's
 * argument validation needs no transport and uses a throwaway in-process builder.
 *
 * @author hal.hildebrand
 */
class GhostMessageSizeLimitTest {

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

    private GhostExchangeGrpc.GhostExchangeBlockingStub startServer(int maxInboundBytes) throws Exception {
        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        var service = new GhostExchangeServiceImpl<>(new FixedProvider(layer), SERIALIZER, LongEntityID.class);
        var sb = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create());   // dynamic port
        sb.addService(service);
        GrpcServerHardening.applyInboundLimit(sb, maxInboundBytes);   // the production wiring under test
        server = sb.build().start();
        channel = Grpc.newChannelBuilderForAddress("localhost", server.getPort(), InsecureChannelCredentials.create())
                      .build();
        return GhostExchangeGrpc.newBlockingStub(channel);
    }

    private static GhostRequest request() {
        return GhostRequest.newBuilder()
                           .setRequesterRank(5)
                           .setRequesterTreeId(9L)
                           .setGhostType(com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType.FACES)
                           .build();
    }

    @Test
    void oversizedRequestIsRejectedWithResourceExhausted() throws Exception {
        var stub = startServer(1);   // 1-byte inbound bound — any real request exceeds it
        var ex = assertThrows(StatusRuntimeException.class, () -> stub.requestGhosts(request()),
                              "a request exceeding the server's inbound size bound must be rejected (RDR-013)");
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, ex.getStatus().getCode(),
                     "oversized inbound message must fail with RESOURCE_EXHAUSTED, got " + ex.getStatus());
    }

    @Test
    void underLimitRequestSucceeds() throws Exception {
        var stub = startServer(GrpcServerHardening.DEFAULT_MAX_INBOUND_MESSAGE_BYTES);
        var batch = stub.requestGhosts(request());
        assertNotNull(batch, "a request within the inbound bound must be served normally");
    }

    @Test
    void helperRejectsNonPositiveLimit() {
        var sb = InProcessServerBuilder.forName("helper-validate").directExecutor();
        assertThrows(IllegalArgumentException.class, () -> GrpcServerHardening.applyInboundLimit(sb, 0));
        assertThrows(IllegalArgumentException.class, () -> GrpcServerHardening.applyInboundLimit(sb, -1));
        assertThrows(NullPointerException.class, () -> GrpcServerHardening.applyInboundLimit(null, 1024));
    }
}
