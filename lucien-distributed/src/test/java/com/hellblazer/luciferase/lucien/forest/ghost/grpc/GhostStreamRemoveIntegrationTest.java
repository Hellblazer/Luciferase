/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest.ghost.grpc;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.ContentSerializer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostAck;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostExchangeGrpc;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostRemoval;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostUpdate;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-7wzml.1: GhostLayerProviderImpl.removeGhostElement was a hollow no-op
 * that always ACKed success=true without removing anything (RDR-004 D3 class silent divergence).
 *
 * <p>Drives a real in-process gRPC streaming round trip: stream a REMOVE update for a ghost that
 * exists in the layer and assert (a) the ACK carries success=true AND (b) the element is actually
 * gone from the layer.  Also asserts that removing a non-existent element yields success=false
 * rather than a silent success lie.
 *
 * @author hal.hildebrand
 */
class GhostStreamRemoveIntegrationTest {

    private static final ContentSerializer<String> SERIALIZER = new ContentSerializer<>() {
        @Override
        public byte[] serialize(String content) {
            return content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String deserialize(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String getContentType() {
            return "string";
        }
    };

    private Server         server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * A provider backed by a real GhostLayer that delegates removeGhostElement to the layer.
     * This mirrors the behaviour of GhostCommunicationManager.GhostLayerProviderImpl after the fix.
     */
    private static final class RealLayerProvider
            implements GhostExchangeServiceImpl.GhostLayerProvider<MortonKey, LongEntityID, String> {

        final long                                       treeId;
        final GhostLayer<MortonKey, LongEntityID, String> layer;

        RealLayerProvider(long treeId, GhostLayer<MortonKey, LongEntityID, String> layer) {
            this.treeId = treeId;
            this.layer  = layer;
        }

        @Override
        public GhostLayer<MortonKey, LongEntityID, String> getGhostLayer(long id) {
            return id == treeId ? layer : null;
        }

        @Override
        public int getCurrentRank() {
            return 0;
        }

        @Override
        public void addGhostElement(GhostElement<MortonKey, LongEntityID, String> e) {
            layer.addGhostElement(e);
        }

        @Override
        public void updateGhostElement(GhostElement<MortonKey, LongEntityID, String> e) {
            layer.addGhostElement(e);
        }

        @Override
        public boolean removeGhostElement(String entityId, long treeIdArg) {
            if (treeIdArg != treeId) return false;
            // Scan the layer for a matching entityId and remove each match.
            var toRemove = layer.getAllGhostElements()
                                .stream()
                                .filter(ge -> entityId.equals(ge.getEntityId().toString()))
                                .toList();
            if (toRemove.isEmpty()) return false;
            boolean removed = false;
            for (var ge : toRemove) {
                removed |= layer.removeGhostElement(ge.getSpatialKey(), ge);
            }
            return removed;
        }

        @Override
        public StatsResponse getGlobalStats() {
            return StatsResponse.getDefaultInstance();
        }
    }

    /**
     * Sends a single REMOVE update over a streaming call and collects the resulting ACK.
     *
     * <p>processStreamUpdate runs in a virtual thread. With {@code directExecutor()} the in-process
     * server calls the request-side {@code onCompleted} synchronously on the calling thread, which
     * immediately closes the response stream — so the virtual thread's ACK would be sent to an already-
     * completed observer. The correct protocol is: send the request, WAIT for the ACK, THEN close the
     * request stream so the server can still flush the response.
     */
    private GhostAck sendRemove(GhostExchangeGrpc.GhostExchangeStub asyncStub,
                                String entityId, long sourceTreeId) throws InterruptedException {
        var received             = new ArrayList<GhostAck>(1);
        var ackLatch             = new CountDownLatch(1);  // fires when the first ACK arrives
        var doneLatch            = new CountDownLatch(1);  // fires when the response stream completes
        // AtomicReference so the lambda below can call onCompleted after the ackLatch fires
        var requestObserverRef   = new AtomicReference<StreamObserver<GhostUpdate>>();

        var responseObserver = new StreamObserver<GhostAck>() {
            @Override
            public void onNext(GhostAck ack) {
                received.add(ack);
                ackLatch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                ackLatch.countDown();
                doneLatch.countDown();
            }

            @Override
            public void onCompleted() {
                doneLatch.countDown();
            }
        };

        var requestObserver = asyncStub.streamGhostUpdates(responseObserver);
        requestObserverRef.set(requestObserver);

        var removal = GhostRemoval.newBuilder()
                                  .setEntityId(entityId)
                                  .setSourceTreeId(sourceTreeId)
                                  .build();
        // Send the request; processStreamUpdate runs asynchronously in a virtual thread
        requestObserver.onNext(GhostUpdate.newBuilder().setRemove(removal).build());

        // Wait for the ACK BEFORE closing the request stream, so the virtual thread can still
        // send the ACK to an open response observer.
        assertTrue(ackLatch.await(5, TimeUnit.SECONDS), "No ACK received within 5 s");

        // Now close the request half; server will call responseObserver.onCompleted()
        requestObserver.onCompleted();
        doneLatch.await(2, TimeUnit.SECONDS);

        assertEquals(1, received.size(), "Expected exactly one ACK");
        return received.get(0);
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void removeExistingElement_acksSuccessAndElementGoneFromLayer() throws Exception {
        var treeId  = 42L;
        var key     = new MortonKey(0xABCL, (byte) 3);
        var entityId = new LongEntityID(999L);

        var layer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(key, entityId, "ghost-payload",
                                                 new Point3f(1, 2, 3), 1, treeId));
        assertEquals(1, layer.getNumGhostElements(), "Precondition: element must be present");

        var provider = new RealLayerProvider(treeId, layer);
        var service  = new GhostExchangeServiceImpl<>(provider, SERIALIZER, LongEntityID.class);
        var name     = "ghost-remove-exist-" + System.nanoTime();
        server  = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var ack = sendRemove(GhostExchangeGrpc.newStub(channel), entityId.toString(), treeId);

        // (1) ACK must report success=true
        assertTrue(ack.getSuccess(), "REMOVE of existing element must be ACKed with success=true");
        assertEquals("", ack.getErrorMessage(), "No error message expected on success");
        assertEquals(entityId.toString(), ack.getEntityId());

        // (2) Element must actually be gone
        assertEquals(0, layer.getNumGhostElements(),
                     "Ghost element must have been physically removed from the layer (not a no-op)");
    }

    @Test
    void removeAbsentElement_acksFailureNotSilentSuccess() throws Exception {
        var treeId = 42L;
        var layer  = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        // Layer is empty — no element with this id
        var provider = new RealLayerProvider(treeId, layer);
        var service  = new GhostExchangeServiceImpl<>(provider, SERIALIZER, LongEntityID.class);
        var name     = "ghost-remove-absent-" + System.nanoTime();
        server  = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var ack = sendRemove(GhostExchangeGrpc.newStub(channel), "777", treeId);

        // Must NOT silently report success when nothing was removed
        assertFalse(ack.getSuccess(),
                    "REMOVE of absent element must NOT be silently ACKed with success=true (RDR-004 D3 class)");
        assertFalse(ack.getErrorMessage().isEmpty(),
                    "An error message must accompany failure ACK");
    }

    /**
     * A provider that uses ProtobufConverters.createEntityId for typed matching — mirrors the
     * fixed GhostCommunicationManager.GhostLayerProviderImpl behaviour.  Used for UUID tests
     * where the wire string format can differ from toString() (e.g. case normalisation).
     */
    private static final class TypedLayerProvider<I extends EntityID>
            implements GhostExchangeServiceImpl.GhostLayerProvider<MortonKey, I, String> {

        final long              treeId;
        final GhostLayer<MortonKey, I, String> layer;
        final Class<I>          entityIdClass;

        TypedLayerProvider(long treeId, GhostLayer<MortonKey, I, String> layer, Class<I> entityIdClass) {
            this.treeId        = treeId;
            this.layer         = layer;
            this.entityIdClass = entityIdClass;
        }

        @Override
        public GhostLayer<MortonKey, I, String> getGhostLayer(long id) {
            return id == treeId ? layer : null;
        }

        @Override
        public int getCurrentRank() { return 0; }

        @Override
        public void addGhostElement(GhostElement<MortonKey, I, String> e) { layer.addGhostElement(e); }

        @Override
        public void updateGhostElement(GhostElement<MortonKey, I, String> e) { layer.addGhostElement(e); }

        @Override
        public boolean removeGhostElement(String wireEntityId, long treeIdArg) {
            if (treeIdArg != treeId) return false;
            // Typed deserialization — same contract as GhostCommunicationManager after fix
            final I typedId;
            try {
                typedId = ProtobufConverters.createEntityId(wireEntityId, entityIdClass);
            } catch (IllegalArgumentException e) {
                return false;
            }
            var toRemove = layer.getAllGhostElements()
                                .stream()
                                .filter(ge -> typedId.equals(ge.getEntityId()))
                                .toList();
            if (toRemove.isEmpty()) return false;
            boolean removed = false;
            for (var ge : toRemove) {
                removed |= layer.removeGhostElement(ge.getSpatialKey(), ge);
            }
            return removed;
        }

        @Override
        public StatsResponse getGlobalStats() { return StatsResponse.getDefaultInstance(); }
    }

    /**
     * Regression: REMOVE must succeed when the wire entityId string uses uppercase hex digits
     * in a UUID (as may happen if a sender does not normalise case, or if toString() format
     * changes).  Naive {@code entityId.equals(ge.getEntityId().toString())} fails here because
     * {@code UUID.toString()} is always lowercase, so "AABBCCDD-..." != "aabbccdd-...".
     * Typed matching via {@code ProtobufConverters.createEntityId} uses {@code UUID.fromString}
     * which normalises case, so it correctly finds and removes the element.
     *
     * <p>This test would FAIL if {@code removeGhostElement} still used the naive
     * {@code entityId.equals(e.getEntityId().toString())} string comparison.
     */
    @Test
    void removeUUID_upperCaseWireString_typedMatchSucceeds() throws Exception {
        var treeId = 77L;
        // A UUID with hex digits (a-f) that differs in case between toString() and the wire string
        var uuid     = UUID.fromString("aabbccdd-eeff-1122-3344-556677889900");
        var entityId = new UUIDEntityID(uuid);
        var key      = new MortonKey(0x100L, (byte) 2);

        var layer = new GhostLayer<MortonKey, UUIDEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(key, entityId, "uuid-payload",
                                                  new Point3f(0, 0, 0), 0, treeId));
        assertEquals(1, layer.getNumGhostElements(), "Precondition: element must be present");

        // Wire string uses UPPERCASE hex — naive toString() comparison would produce a mismatch:
        //   entityId.toString() → "aabbccdd-eeff-1122-3344-556677889900"  (lowercase)
        //   wireEntityId        → "AABBCCDD-EEFF-1122-3344-556677889900"  (uppercase)
        // Typed match via UUID.fromString normalises both to the same UUID value.
        var wireEntityId = uuid.toString().toUpperCase();
        assertNotEquals(wireEntityId, entityId.toString(),
                        "Sanity: wire string and toString() must differ to exercise the case-normalisation path");

        var provider = new TypedLayerProvider<>(treeId, layer, UUIDEntityID.class);
        var service  = new GhostExchangeServiceImpl<>(provider, SERIALIZER, UUIDEntityID.class);
        var name     = "ghost-remove-uuid-upper-" + System.nanoTime();
        server  = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var ack = sendRemove(GhostExchangeGrpc.newStub(channel), wireEntityId, treeId);

        assertTrue(ack.getSuccess(),
                   "REMOVE with uppercase UUID wire string must succeed via typed match (would fail with naive toString())");
        assertEquals(0, layer.getNumGhostElements(),
                     "Element must be physically removed from the layer");
    }

    @Test
    void removeFromAbsentLayer_acksFailure() throws Exception {
        var treeId  = 42L;
        var unknownTreeId = 99L;
        var layer   = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(new MortonKey(0x1L), new LongEntityID(1L),
                                                  "x", new Point3f(0, 0, 0), 0, treeId));

        var provider = new RealLayerProvider(treeId, layer);
        var service  = new GhostExchangeServiceImpl<>(provider, SERIALIZER, LongEntityID.class);
        var name     = "ghost-remove-no-layer-" + System.nanoTime();
        server  = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        // REMOVE targets a treeId that doesn't exist in the provider
        var ack = sendRemove(GhostExchangeGrpc.newStub(channel), "1", unknownTreeId);

        assertFalse(ack.getSuccess(),
                    "REMOVE targeting unknown treeId must NOT silently succeed");
        assertEquals(1, layer.getNumGhostElements(), "Element in known layer must be untouched");
    }
}
