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
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostExchangeGrpc;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostRequest;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostType;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.StatsResponse;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.SyncRequest;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Luciferase-onzvy: {@code syncGhosts} is a unary RPC — a small {@link SyncRequest} listing an arbitrarily large
 * number of {@code tree_ids} makes the server build a correspondingly large {@code SyncResponse} before sending.
 * The server INBOUND message bound caps the request, not the outbound response, so an unbounded tree_id list is a
 * response-amplification DoS vector. The fix caps the tree_id count and rejects an oversized request with
 * {@code INVALID_ARGUMENT} BEFORE doing any work.
 *
 * @author hal.hildebrand
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GhostSyncTreeIdCapTest {

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

    /** Provider returning a fixed (possibly null) ghost layer for any tree id. */
    private static final class FixedLayerProvider
            implements GhostExchangeServiceImpl.GhostLayerProvider<MortonKey, LongEntityID, String> {
        private final GhostLayer<MortonKey, LongEntityID, String> layer;
        FixedLayerProvider(GhostLayer<MortonKey, LongEntityID, String> layer) { this.layer = layer; }
        @Override public GhostLayer<MortonKey, LongEntityID, String> getGhostLayer(long treeId) { return layer; }
        @Override public int getCurrentRank() { return 0; }
        @Override public void addGhostElement(GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public void updateGhostElement(GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public boolean removeGhostElement(String entityId, long treeId) { return false; }
        @Override public StatsResponse getGlobalStats() { return StatsResponse.getDefaultInstance(); }
    }

    /** A ghost layer reporting an arbitrary element count without actually holding that many elements. */
    private static GhostLayer<MortonKey, LongEntityID, String> layerReporting(long count) {
        return new GhostLayer<>(com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES) {
            @Override public long getNumGhostElements() { return count; }
        };
    }

    private GhostExchangeGrpc.GhostExchangeBlockingStub startAndConnect(
            GhostExchangeServiceImpl.GhostLayerProvider<MortonKey, LongEntityID, String> provider) throws Exception {
        var serviceImpl = new GhostExchangeServiceImpl<>(provider, SERIALIZER, LongEntityID.class);
        var name = "ghost-sync-cap-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(serviceImpl).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return GhostExchangeGrpc.newBlockingStub(channel);
    }

    private GhostExchangeGrpc.GhostExchangeBlockingStub startAndConnect() throws Exception {
        return startAndConnect(new FixedLayerProvider(null));
    }

    private static int syncCap() throws Exception {
        Field f = GhostExchangeServiceImpl.class.getDeclaredField("MAX_SYNC_TREE_IDS");
        f.setAccessible(true);
        return (int) f.get(null);
    }

    private static long responseElementCap() throws Exception {
        Field f = GhostExchangeServiceImpl.class.getDeclaredField("MAX_RESPONSE_GHOST_ELEMENTS");
        f.setAccessible(true);
        return (long) f.get(null);
    }

    @Test
    void syncGhosts_rejectsOversizedTreeIdList_withInvalidArgument() throws Exception {
        var stub = startAndConnect();
        int cap = syncCap();

        var oversized = SyncRequest.newBuilder().setRequesterRank(1).setGhostType(GhostType.FACES);
        for (int i = 0; i <= cap; i++) {     // cap + 1 tree ids
            oversized.addTreeIds(i);
        }

        var ex = assertThrows(StatusRuntimeException.class, () -> stub.syncGhosts(oversized.build()),
                              "an over-cap tree_id list must be rejected, not serviced");
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode(),
                     "oversized sync request must be rejected with INVALID_ARGUMENT");
    }

    @Test
    void syncGhosts_acceptsRequestAtCap() throws Exception {
        var stub = startAndConnect();
        int cap = syncCap();

        var atCap = SyncRequest.newBuilder().setRequesterRank(1).setGhostType(GhostType.FACES);
        for (int i = 0; i < cap; i++) {      // exactly cap tree ids — must be serviced
            atCap.addTreeIds(i);
        }

        var response = stub.syncGhosts(atCap.build());
        assertNotNull(response, "a request at the cap must be serviced normally");
        assertEquals(0, response.getTotalElements(), "no ghost layers registered → empty but valid response");
    }

    @Test
    void syncGhosts_rejectsOversizedResponse_withResourceExhausted() throws Exception {
        long cap = responseElementCap();
        // A single tree whose ghost layer reports more than the cap — the count cap alone (one tree) would let this
        // through, but the OUTBOUND element bound must reject it before the server builds the response.
        var stub = startAndConnect(new FixedLayerProvider(layerReporting(cap + 1)));

        var request = SyncRequest.newBuilder().setRequesterRank(1).setGhostType(GhostType.FACES).addTreeIds(1L).build();

        var ex = assertThrows(StatusRuntimeException.class, () -> stub.syncGhosts(request),
                              "a single huge tree must be rejected by the outbound element bound");
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, ex.getStatus().getCode(),
                     "an over-cap response must be rejected with RESOURCE_EXHAUSTED");
    }

    @Test
    void requestGhosts_rejectsOversizedAllGhostsResponse_withResourceExhausted() throws Exception {
        long cap = responseElementCap();
        var stub = startAndConnect(new FixedLayerProvider(layerReporting(cap + 1)));

        // No boundary keys → the all-ghosts path, which dumps the entire layer; must be bounded like syncGhosts.
        var request = GhostRequest.newBuilder().setRequesterRank(1).setRequesterTreeId(1L)
                                  .setGhostType(GhostType.FACES).build();

        var ex = assertThrows(StatusRuntimeException.class, () -> stub.requestGhosts(request),
                              "the no-boundary-keys all-ghosts path must be bounded too");
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, ex.getStatus().getCode(),
                     "an over-cap all-ghosts batch must be rejected with RESOURCE_EXHAUSTED");
    }
}
