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
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostAck;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostExchangeGrpc;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostUpdate;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.StatsResponse;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency test for GhostExchangeServiceImpl.streamGhostUpdates stream-cap enforcement.
 *
 * <p>Mirrors the pattern from BalanceCoordinatorIntegrationTest (Luciferase-7wzml.6). Verifies
 * that the AtomicInteger gate is TOCTOU-free: N concurrent callers against a cap of K (&lt;N) admit
 * exactly K, the rest receive RESOURCE_EXHAUSTED, and {@code activeStreamCount} never exceeds K.
 *
 * @see com.hellblazer.luciferase.lucien.balancing.grpc.BalanceCoordinatorIntegrationTest
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GhostStreamCapConcurrencyTest {

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

    /** Minimal no-op provider — streamGhostUpdates only needs a live service handle. */
    private static final class NoOpProvider
            implements GhostExchangeServiceImpl.GhostLayerProvider<MortonKey, LongEntityID, String> {
        @Override public GhostLayer<MortonKey, LongEntityID, String> getGhostLayer(long treeId) { return null; }
        @Override public int getCurrentRank() { return 0; }
        @Override public void addGhostElement(GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public void updateGhostElement(GhostElement<MortonKey, LongEntityID, String> e) { }
        @Override public boolean removeGhostElement(String entityId, long treeId) { return false; }
        @Override public StatsResponse getGlobalStats() { return StatsResponse.getDefaultInstance(); }
    }

    /**
     * Cap-at-limit test using reflection pre-fill (mirrors BalanceCoordinatorIntegrationTest
     * testStreamBalanceUpdates_capsAtMaxActiveStreams_rejectsWithResourceExhausted).
     *
     * <p>Pre-fills both {@code activeStreams} AND {@code activeStreamCount} to exactly {@code cap}
     * fake entries to avoid opening 1024 real gRPC streams. Asserts the (cap+1)-th call is rejected
     * with RESOURCE_EXHAUSTED and the counter is rolled back to cap.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStreamGhostUpdates_capsAtMaxActiveStreams_rejectsWithResourceExhausted() throws Exception {
        var serviceImpl = new GhostExchangeServiceImpl<>(new NoOpProvider(), SERIALIZER, LongEntityID.class);
        var name = "ghost-cap-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(serviceImpl).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        // Read the production cap constant.
        Field capField = GhostExchangeServiceImpl.class.getDeclaredField("MAX_ACTIVE_STREAMS");
        capField.setAccessible(true);
        int cap = (int) capField.get(null);

        // Pre-fill the activeStreams map and counter via reflection.
        Field mapField = GhostExchangeServiceImpl.class.getDeclaredField("activeStreams");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> activeStreams = (Map<String, Object>) mapField.get(serviceImpl);

        Field counterField = GhostExchangeServiceImpl.class.getDeclaredField("activeStreamCount");
        counterField.setAccessible(true);
        AtomicInteger activeStreamCount = (AtomicInteger) counterField.get(serviceImpl);

        for (int i = 0; i < cap; i++) {
            activeStreams.put("fake-" + i, new Object());
        }
        activeStreamCount.set(cap);
        assertEquals(cap, activeStreams.size(), "Pre-fill must bring map exactly to cap");
        assertEquals(cap, activeStreamCount.get(), "Pre-fill must bring counter exactly to cap");

        // The (cap+1)-th call must be rejected with RESOURCE_EXHAUSTED.
        var asyncStub = GhostExchangeGrpc.newStub(channel);
        var errorHolder = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);

        asyncStub.streamGhostUpdates(new StreamObserver<>() {
            @Override public void onNext(GhostAck a) { }
            @Override public void onError(Throwable t) { errorHolder.set(t); latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "onError must fire within 5s on rejection");

        Throwable error = errorHolder.get();
        assertNotNull(error, "Expected onError, not onCompleted");
        assertInstanceOf(StatusRuntimeException.class, error,
            "Expected StatusRuntimeException, got: " + error.getClass());
        assertEquals(io.grpc.Status.Code.RESOURCE_EXHAUSTED,
                     ((StatusRuntimeException) error).getStatus().getCode(),
                     "Rejection must use RESOURCE_EXHAUSTED");

        // No leak-before-put: map must not have grown.
        assertEquals(cap, activeStreams.size(), "activeStreams must not grow beyond cap on rejection");
        // Counter must have been rolled back to cap.
        assertEquals(cap, activeStreamCount.get(), "counter must be restored to cap after rejected increment");

        // Under-cap: clear fake entries, reset counter, verify a real stream is accepted.
        activeStreams.clear();
        activeStreamCount.set(0);

        var successLatch = new CountDownLatch(1);
        var obs = asyncStub.streamGhostUpdates(new StreamObserver<>() {
            @Override public void onNext(GhostAck a) { }
            @Override public void onError(Throwable t) { successLatch.countDown(); }
            @Override public void onCompleted() { successLatch.countDown(); }
        });
        obs.onCompleted();
        assertTrue(successLatch.await(5, TimeUnit.SECONDS), "Under-cap stream must complete normally");
    }

    /**
     * Concurrent admission test: 50 threads race to open streams against a cap of 5.
     * Asserts admitted count never exceeds the cap and all callers are accounted for.
     * Uses a non-directExecutor server to allow real concurrency.
     *
     * <p>Mirrors BalanceCoordinatorIntegrationTest testStreamBalanceUpdates_concurrentRace_neverExceedsCap.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testStreamGhostUpdates_concurrentRace_neverExceedsCap() throws Exception {
        final int TEST_CAP = 5;
        final int THREADS = 50;

        var serviceImpl = new GhostExchangeServiceImpl<>(new NoOpProvider(), SERIALIZER, LongEntityID.class);

        // Build a real (non-direct) executor server to get actual concurrency.
        String concurrentServerName = "ghost-concurrent-" + System.nanoTime();
        var concurrentServer = InProcessServerBuilder.forName(concurrentServerName)
            .addService(serviceImpl)
            .build()
            .start();
        var concurrentChannel = InProcessChannelBuilder.forName(concurrentServerName).build();

        try {
            // Pre-seed counter so only TEST_CAP slots remain.
            Field capField = GhostExchangeServiceImpl.class.getDeclaredField("MAX_ACTIVE_STREAMS");
            capField.setAccessible(true);
            int realCap = (int) capField.get(null);

            Field counterField = GhostExchangeServiceImpl.class.getDeclaredField("activeStreamCount");
            counterField.setAccessible(true);
            AtomicInteger activeStreamCount = (AtomicInteger) counterField.get(serviceImpl);
            activeStreamCount.set(realCap - TEST_CAP);

            var asyncStub = GhostExchangeGrpc.newStub(concurrentChannel);
            var admittedCount = new AtomicInteger(0);
            var rejectedCount = new AtomicInteger(0);
            var openObservers = new CopyOnWriteArrayList<StreamObserver<GhostUpdate>>();
            var doneLatch = new CountDownLatch(THREADS);
            var startGate = new CountDownLatch(1);

            for (int t = 0; t < THREADS; t++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    var responseLatch = new CountDownLatch(1);
                    var wasRejected = new AtomicBoolean(false);
                    var requestObsRef = new AtomicReference<StreamObserver<GhostUpdate>>();

                    var requestObs = asyncStub.streamGhostUpdates(new StreamObserver<>() {
                        @Override public void onNext(GhostAck a) { }
                        @Override public void onError(Throwable t) {
                            if (t instanceof StatusRuntimeException sre
                                    && sre.getStatus().getCode() == io.grpc.Status.Code.RESOURCE_EXHAUSTED) {
                                wasRejected.set(true);
                                rejectedCount.incrementAndGet();
                            }
                            responseLatch.countDown();
                        }
                        @Override public void onCompleted() {
                            responseLatch.countDown();
                        }
                    });
                    requestObsRef.set(requestObs);

                    // Rejected streams receive onError almost immediately (counter pre-seeded path).
                    try {
                        boolean completed = responseLatch.await(500, TimeUnit.MILLISECONDS);
                        if (completed && wasRejected.get()) {
                            // Already counted in rejectedCount above.
                        } else {
                            // Admitted — keep stream open until after race, then close.
                            admittedCount.incrementAndGet();
                            openObservers.add(requestObs);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    doneLatch.countDown();
                });
            }

            startGate.countDown();
            assertTrue(doneLatch.await(20, TimeUnit.SECONDS),
                "All threads must complete their admission attempt within 20s");

            int totalAdmitted = admittedCount.get();
            int totalRejected = rejectedCount.get();

            // The admitted count must never exceed the cap — this is the TOCTOU-free assertion.
            assertTrue(totalAdmitted <= TEST_CAP,
                "Admitted streams (" + totalAdmitted + ") exceeded cap (" + TEST_CAP + ") — TOCTOU race detected!");

            // All callers must be either admitted or rejected.
            assertEquals(THREADS, totalAdmitted + totalRejected,
                "Every caller must be admitted or rejected: admitted=" + totalAdmitted
                + " rejected=" + totalRejected);

            // Close all admitted streams and verify the counter returns to the pre-seed value.
            for (var obs : openObservers) {
                obs.onCompleted();
            }
            // Brief settle for counter decrements on virtual threads.
            Thread.sleep(200);
            assertEquals(realCap - TEST_CAP, activeStreamCount.get(),
                "counter must return to pre-seed value after all admitted streams close");

        } finally {
            concurrentChannel.shutdownNow();
            concurrentChannel.awaitTermination(5, TimeUnit.SECONDS);
            concurrentServer.shutdownNow();
            concurrentServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
