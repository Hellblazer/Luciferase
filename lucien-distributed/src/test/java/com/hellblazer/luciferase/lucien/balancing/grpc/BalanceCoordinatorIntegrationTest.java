/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.grpc;

import com.hellblazer.luciferase.lucien.balancing.proto.*;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.MortonKey;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.Point3f;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.SpatialKey;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.grpc.StatusRuntimeException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for BalanceCoordinator using InProcessChannelBuilder.
 *
 * @author Hal Hildebrand
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class BalanceCoordinatorIntegrationTest {

    private Server server;
    private ManagedChannel channel;
    private BalanceCoordinatorGrpc.BalanceCoordinatorBlockingStub blockingStub;
    private MockBalanceProvider balanceProvider;
    private String serverName;
    private BalanceCoordinatorServer serverImpl;

    @BeforeEach
    void setUp() throws Exception {
        serverName = "test-server-" + System.nanoTime();
        balanceProvider = new MockBalanceProvider();
        serverImpl = new BalanceCoordinatorServer(balanceProvider);

        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serverImpl)
            .build()
            .start();

        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();

        blockingStub = BalanceCoordinatorGrpc.newBlockingStub(channel);
    }

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

    @Test
    void testRequestRefinement() {
        var spatialKey = mortonKey(0x12345L);

        var request = RefinementRequest.newBuilder()
            .setRequesterRank(1)
            .setRequesterTreeId(1000L)
            .setRoundNumber(1)
            .setTreeLevel(5)
            .addBoundaryKeys(spatialKey)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var response = blockingStub.requestRefinement(request);

        assertNotNull(response);
        assertEquals(0, response.getResponderRank());
        assertEquals(1000L, response.getResponderTreeId());
        assertEquals(1, response.getRoundNumber());
    }

    @Test
    void testCoordinateBalance() {
        var request = BalanceCoordinationRequest.newBuilder()
            .setInitiatorRank(0)
            .setInitiatorTreeId(5000L)
            .setTotalPartitions(4)
            .setMaxRounds(10)
            .setRefinementThreshold(0.2f)
            .build();

        var response = blockingStub.coordinateBalance(request);

        assertNotNull(response);
        assertTrue(response.getCoordinationAccepted());
        assertEquals(1, response.getAssignedRound());
    }

    @Test
    void testGetBalanceStatistics() {
        balanceProvider.recordRefinementRequest(1);
        balanceProvider.recordRefinementApplied(1);

        var request = BalanceCoordinationRequest.newBuilder()
            .setInitiatorRank(0)
            .setInitiatorTreeId(0L)
            .setTotalPartitions(1)
            .setMaxRounds(1)
            .setRefinementThreshold(0.2f)
            .build();

        var stats = blockingStub.getBalanceStatistics(request);

        assertNotNull(stats);
        assertTrue(stats.getTotalRefinementsRequested() >= 1);
    }

    @Test
    void testExchangeViolations() {
        var violation = BalanceViolation.newBuilder()
            .setLocalKey(mortonKey(100L))
            .setGhostKey(mortonKey(200L))
            .setLocalLevel(5)
            .setGhostLevel(7)
            .setLevelDifference(2)
            .setSourceRank(1)
            .build();

        var batch = ViolationBatch.newBuilder()
            .setRequesterRank(1)
            .setResponderRank(0)
            .setRoundNumber(0)
            .addViolations(violation)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var responseBatch = blockingStub.exchangeViolations(batch);

        assertNotNull(responseBatch);
        assertEquals(0, responseBatch.getRequesterRank());
        assertEquals(1, responseBatch.getResponderRank());
        assertEquals(0, responseBatch.getRoundNumber());
        assertTrue(responseBatch.getTimestamp() > 0);
    }

    @Test
    void testRefinementResponseTimestampUsesInjectedClock() {
        var testClock = new TestClock(999_000L);
        serverImpl.setClock(testClock);

        var request = RefinementRequest.newBuilder()
            .setRequesterRank(2)
            .setRequesterTreeId(7777L)
            .setRoundNumber(3)
            .setTreeLevel(4)
            .setTimestamp(System.currentTimeMillis())
            .build();

        var response = blockingStub.requestRefinement(request);

        assertNotNull(response);
        assertEquals(999_000L, response.getTimestamp(),
            "RefinementResponse.timestamp must come from the injected clock, not wall time");
    }

    @Test
    void testStreamSessionIdIsCounterDerived() throws Exception {
        // Open two streaming sessions and verify both complete without collision.
        // sessionId = "stream-<counter>" — no wall-clock component means two sessions
        // opened within the same millisecond get distinct ids.
        var asyncStub = BalanceCoordinatorGrpc.newStub(channel);
        var latch = new java.util.concurrent.CountDownLatch(2);

        var obs1 = asyncStub.streamBalanceUpdates(new io.grpc.stub.StreamObserver<>() {
            @Override public void onNext(BalanceStatistics s) {}
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });
        obs1.onCompleted();

        var obs2 = asyncStub.streamBalanceUpdates(new io.grpc.stub.StreamObserver<>() {
            @Override public void onNext(BalanceStatistics s) {}
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });
        obs2.onCompleted();

        assertTrue(latch.await(5, TimeUnit.SECONDS),
            "Both streaming sessions must complete — counter-only ids guarantee no map key collision");
    }

    /**
     * Verifies the MAX_ACTIVE_STREAMS cap: once the activeStreams map is at capacity, the next
     * streamBalanceUpdates call is rejected with RESOURCE_EXHAUSTED and the map is NOT grown
     * (no leak-before-put). Under-cap calls succeed normally after the fake sessions are cleared.
     *
     * <p>We pre-fill both {@code activeStreams} AND {@code activeStreamCount} via reflection to
     * avoid opening 1024 real gRPC streams. Both must be at cap to trigger the atomic guard.
     * The actual rejection code path in streamBalanceUpdates is exercised — the test asserts
     * the real Status code, not a mock.
     */
    @Test
    void testStreamBalanceUpdates_capsAtMaxActiveStreams_rejectsWithResourceExhausted() throws Exception {
        // Read the cap constant so the test stays in sync with the production value.
        Field capField = BalanceCoordinatorServer.class.getDeclaredField("MAX_ACTIVE_STREAMS");
        capField.setAccessible(true);
        int cap = (int) capField.get(null);

        // Pre-fill the activeStreams map to exactly 'cap' fake entries via reflection.
        Field mapField = BalanceCoordinatorServer.class.getDeclaredField("activeStreams");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> activeStreams = (Map<String, Object>) mapField.get(serverImpl);

        // Also prime the AtomicInteger counter — the cap gate checks this, not map.size().
        Field counterField = BalanceCoordinatorServer.class.getDeclaredField("activeStreamCount");
        counterField.setAccessible(true);
        AtomicInteger activeStreamCount = (AtomicInteger) counterField.get(serverImpl);

        for (int i = 0; i < cap; i++) {
            activeStreams.put("fake-" + i, new Object());
        }
        activeStreamCount.set(cap);
        assertEquals(cap, activeStreams.size(), "Pre-fill should bring map exactly to cap");
        assertEquals(cap, activeStreamCount.get(), "Pre-fill should bring counter exactly to cap");

        // The (cap+1)-th call must be rejected with RESOURCE_EXHAUSTED.
        var asyncStub = BalanceCoordinatorGrpc.newStub(channel);
        var errorHolder = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var latch = new java.util.concurrent.CountDownLatch(1);

        asyncStub.streamBalanceUpdates(new io.grpc.stub.StreamObserver<>() {
            @Override public void onNext(BalanceStatistics s) { }
            @Override public void onError(Throwable t) { errorHolder.set(t); latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "onError must fire within 5s");

        Throwable error = errorHolder.get();
        assertNotNull(error, "Expected onError to be called, not onCompleted");
        assertInstanceOf(StatusRuntimeException.class, error,
            "Expected StatusRuntimeException, got: " + error.getClass());
        assertEquals(io.grpc.Status.Code.RESOURCE_EXHAUSTED,
                     ((StatusRuntimeException) error).getStatus().getCode(),
                     "Rejection must use RESOURCE_EXHAUSTED");

        // The rejected stream must NOT have been inserted — no leak before put.
        assertEquals(cap, activeStreams.size(), "activeStreams must not grow beyond cap on rejection");
        // Counter must have been decremented back to cap after the rejected increment.
        assertEquals(cap, activeStreamCount.get(), "counter must be restored to cap after rejection");

        // Under-cap: clear fake entries and reset counter, then verify a normal stream is accepted.
        activeStreams.clear();
        activeStreamCount.set(0);

        var successLatch = new java.util.concurrent.CountDownLatch(1);
        var obs = asyncStub.streamBalanceUpdates(new io.grpc.stub.StreamObserver<>() {
            @Override public void onNext(BalanceStatistics s) { }
            @Override public void onError(Throwable t) { successLatch.countDown(); }
            @Override public void onCompleted() { successLatch.countDown(); }
        });
        obs.onCompleted();  // close the stream immediately
        assertTrue(successLatch.await(5, TimeUnit.SECONDS), "Under-cap stream must complete normally");
    }

    /**
     * Concurrent admission test: 50 threads race to open streams against a cap of 5.
     * Asserts that the admitted count never exceeds the cap, and that all surplus callers
     * receive RESOURCE_EXHAUSTED (the AtomicInteger gate is TOCTOU-free).
     *
     * <p>Uses a small test cap injected via the AtomicInteger pre-seed trick to keep the test fast.
     * Streams are kept open until all THREADS have attempted admission, ensuring real concurrency
     * is tested rather than sequential open-then-close.
     *
     * <p>Uses a non-directExecutor server so the gRPC threads actually contend.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testStreamBalanceUpdates_concurrentRace_neverExceedsCap() throws Exception {
        final int TEST_CAP = 5;
        final int THREADS = 50;

        // Build a separate server with a real (non-direct) executor to allow actual concurrency.
        String concurrentServerName = "concurrent-test-" + System.nanoTime();
        var concurrentImpl = new BalanceCoordinatorServer(balanceProvider);
        var concurrentServer = InProcessServerBuilder.forName(concurrentServerName)
            .addService(concurrentImpl)
            .build()
            .start();
        var concurrentChannel = InProcessChannelBuilder.forName(concurrentServerName)
            .build();
        try {
            // Pre-seed the AtomicInteger so only TEST_CAP slots remain.
            Field capField = BalanceCoordinatorServer.class.getDeclaredField("MAX_ACTIVE_STREAMS");
            capField.setAccessible(true);
            int realCap = (int) capField.get(null);

            Field counterField = BalanceCoordinatorServer.class.getDeclaredField("activeStreamCount");
            counterField.setAccessible(true);
            AtomicInteger activeStreamCount = (AtomicInteger) counterField.get(concurrentImpl);
            activeStreamCount.set(realCap - TEST_CAP);

            var asyncStub = BalanceCoordinatorGrpc.newStub(concurrentChannel);
            var admittedCount = new AtomicInteger(0);
            var rejectedCount = new AtomicInteger(0);
            // Holds request observers for admitted streams so we can close them after the race.
            var openObservers = new java.util.concurrent.CopyOnWriteArrayList<io.grpc.stub.StreamObserver<BalanceStatistics>>();
            var doneLatch = new java.util.concurrent.CountDownLatch(THREADS);
            var startGate = new java.util.concurrent.CountDownLatch(1);

            for (int t = 0; t < THREADS; t++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    var responseLatch = new java.util.concurrent.CountDownLatch(1);
                    var wasRejected = new java.util.concurrent.atomic.AtomicBoolean(false);
                    // requestObs is the observer returned by streamBalanceUpdates (client→server direction).
                    var requestObsRef = new java.util.concurrent.atomic.AtomicReference<io.grpc.stub.StreamObserver<BalanceStatistics>>();

                    var requestObs = asyncStub.streamBalanceUpdates(new io.grpc.stub.StreamObserver<>() {
                        @Override public void onNext(BalanceStatistics s) { }
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

                    // For rejected streams, onError fires immediately (server-side rejection is synchronous
                    // even on the non-direct executor when the counter is pre-seeded). Wait briefly to
                    // distinguish rejected from admitted.
                    try {
                        boolean rejected = responseLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (rejected && wasRejected.get()) {
                            // Already counted above.
                        } else {
                            // Admitted — store the observer to close later, count it.
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
            assertTrue(doneLatch.await(20, java.util.concurrent.TimeUnit.SECONDS),
                "All threads must finish their admission attempt within 20s");

            int totalAdmitted = admittedCount.get();
            int totalRejected = rejectedCount.get();

            // The admitted count must never exceed the cap.
            assertTrue(totalAdmitted <= TEST_CAP,
                "Admitted streams (" + totalAdmitted + ") exceeded cap (" + TEST_CAP + ") — TOCTOU race!");

            // All callers accounted for.
            assertEquals(THREADS, totalAdmitted + totalRejected,
                "Every caller must be admitted or rejected: admitted=" + totalAdmitted
                + " rejected=" + totalRejected);

            // Close all open streams and verify the counter returns to pre-seed.
            for (var obs : openObservers) {
                obs.onCompleted();
            }
            // Brief settle for counter decrements.
            Thread.sleep(200);
            assertEquals(realCap - TEST_CAP, activeStreamCount.get(),
                "counter must return to pre-seed value after all admitted streams close");
        } finally {
            concurrentChannel.shutdownNow();
            concurrentChannel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            concurrentServer.shutdownNow();
            concurrentServer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /**
     * Mock balance provider for testing.
     */
    private static class MockBalanceProvider implements BalanceCoordinatorServer.BalanceProvider {
        private final List<GhostElement> ghostElements = new ArrayList<>();
        private final AtomicInteger refinementsRequested = new AtomicInteger(0);
        private final AtomicInteger refinementsApplied = new AtomicInteger(0);

        public void recordRefinementRequest(int rank) {
            refinementsRequested.incrementAndGet();
        }

        public void recordRefinementApplied(int rank) {
            refinementsApplied.incrementAndGet();
        }

        @Override
        public int getCurrentRank() {
            return 0;
        }

        @Override
        public List<GhostElement> getGhostElementsForRefinement(RefinementRequest request) {
            return new ArrayList<>(ghostElements);
        }

        @Override
        public boolean acceptCoordination(BalanceCoordinationRequest request) {
            return true;
        }

        @Override
        public int assignRound(BalanceCoordinationRequest request) {
            return 1;
        }

        @Override
        public BalanceStatistics getStatistics() {
            return BalanceStatistics.newBuilder()
                .setTotalRoundsCompleted(0)
                .setTotalRefinementsRequested(refinementsRequested.get())
                .setTotalRefinementsApplied(refinementsApplied.get())
                .setTotalTimeMicros(0)
                .build();
        }

        @Override
        public void recordRefinementRequest(RefinementRequest request) {
            refinementsRequested.incrementAndGet();
        }

        @Override
        public void recordRefinementApplied(int rank, int count) {
            refinementsApplied.addAndGet(count);
        }

        @Override
        public ViolationBatch processViolations(ViolationBatch batch) {
            // Mock implementation - return empty batch with correct metadata
            return ViolationBatch.newBuilder()
                .setRequesterRank(getCurrentRank())
                .setResponderRank(batch.getRequesterRank())
                .setRoundNumber(batch.getRoundNumber())
                .setTimestamp(System.currentTimeMillis())
                .build();
        }
    }

    /** Build a proto SpatialKey envelope from a long Morton code (Luciferase-546 serde dispatch). */
    private static SpatialKey mortonKey(long mortonCode) {
        return com.hellblazer.luciferase.lucien.forest.ghost.grpc.ProtobufConverters.spatialKeyToProtobuf(
            new com.hellblazer.luciferase.lucien.octree.MortonKey(mortonCode, (byte) 0));
    }
}
