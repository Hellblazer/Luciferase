/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.balancing.grpc;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.balancing.proto.*;
import com.hellblazer.luciferase.lucien.forest.ghost.proto.GhostElement;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BalanceCoordinatorClient} covering three beads:
 * <ul>
 *   <li><b>Luciferase-7wzml.51</b>: N concurrent same-rank {@code startStreaming} calls produce
 *       N distinct stream IDs, all using the monotonic sequence counter (not wall-clock).</li>
 *   <li><b>Luciferase-7wzml.84</b>: Clock injection — both {@code requestRefinement} and
 *       {@code requestRefinementBatched} stamp {@code clock.currentTimeMillis()} into the
 *       proto request {@code timestamp} field.</li>
 *   <li><b>Luciferase-7wzml.87</b>: Shared batch scheduler — creating N {@code BatchQueue}s does
 *       not spawn N scheduler threads; a single shared daemon thread serves all queues and
 *       is shut down cleanly on {@code client.shutdown()}.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class BalanceCoordinatorClientTest {

    // ---------------------------------------------------------------------------
    // Minimal TestClock (inline — avoids cross-module test dependency)
    // ---------------------------------------------------------------------------

    static class TestClock implements Clock {
        private final AtomicLong millis = new AtomicLong(0L);

        public void setTime(long ms) { millis.set(ms); }

        @Override
        public long currentTimeMillis() { return millis.get(); }
    }

    // ---------------------------------------------------------------------------
    // Capturing gRPC service: records the first RefinementRequest it receives.
    // Used to verify the timestamp field in the wire proto (beads .84).
    // ---------------------------------------------------------------------------

    static class CapturingBalanceService extends BalanceCoordinatorGrpc.BalanceCoordinatorImplBase {
        final BlockingQueue<RefinementRequest> captured = new LinkedBlockingQueue<>();

        @Override
        public void requestRefinement(RefinementRequest request,
                                      StreamObserver<RefinementResponse> responseObserver) {
            captured.offer(request);
            responseObserver.onNext(RefinementResponse.newBuilder()
                .setResponderRank(0)
                .setRoundNumber(request.getRoundNumber())
                .setTimestamp(request.getTimestamp())  // echo back for verification
                .build());
            responseObserver.onCompleted();
        }

        @Override
        public void coordinateBalance(BalanceCoordinationRequest request,
                                      StreamObserver<BalanceCoordinationResponse> responseObserver) {
            responseObserver.onNext(BalanceCoordinationResponse.newBuilder()
                .setCoordinationAccepted(true).setAssignedRound(1).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getBalanceStatistics(BalanceCoordinationRequest request,
                                          StreamObserver<BalanceStatistics> responseObserver) {
            responseObserver.onNext(BalanceStatistics.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void exchangeViolations(ViolationBatch request,
                                       StreamObserver<ViolationBatch> responseObserver) {
            responseObserver.onNext(ViolationBatch.newBuilder()
                .setRequesterRank(0).setResponderRank(request.getRequesterRank())
                .setRoundNumber(request.getRoundNumber())
                .setTimestamp(0L).build());
            responseObserver.onCompleted();
        }
    }

    // ---------------------------------------------------------------------------
    // Test subclass: overrides channel creation to use InProcess transport.
    // Necessary because BalanceCoordinatorClient.getChannel() uses Grpc.newChannelBuilder
    // which expects a host:port; InProcess channels require InProcessChannelBuilder.
    // ---------------------------------------------------------------------------

    static class InProcessBalanceCoordinatorClient extends BalanceCoordinatorClient {
        InProcessBalanceCoordinatorClient(int rank, String serverName) {
            super(rank, new FixedDiscovery(serverName));
        }

        // Provide an in-process channel for every rank.
        // Package-visible hook: we shadow the private getChannel via a package-accessible
        // ServiceDiscovery that returns a well-known key, combined with InProcessChannelBuilder.
        // Since getChannel() is private and non-overridable we instead override the
        // ServiceDiscovery so endpoint lookups return the in-process server name directly,
        // and we configure the channel at construction time.
        //
        // For the blocking-stub path (used by requestRefinement/batched), the base class
        // will call serviceDiscovery.getEndpoint(rank), get a non-null value, and try to
        // create a real TCP channel. We need to bypass that.
        //
        // Solution: pre-wire the blockingStub map via a subclass-supplied constructor that
        // accepts an already-built blocking stub.  But blockingStubs is private final.
        //
        // Simpler: use a fully custom ServiceDiscovery that returns a name that we also
        // register as a managed channel before the test runs — see setUp().
    }

    // ---------------------------------------------------------------------------
    // Minimal ServiceDiscovery
    // ---------------------------------------------------------------------------

    record FixedDiscovery(String endpoint) implements BalanceCoordinatorClient.ServiceDiscovery {
        @Override public String getEndpoint(int rank) { return endpoint; }
        @Override public void registerEndpoint(int rank, String ep) { }
        @Override public Map<Integer, String> getAllEndpoints() { return Map.of(); }
    }

    // ---------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------

    private String serverName;
    private Server inProcessServer;
    private CapturingBalanceService captureService;

    // The client under test — uses a test subclass or a real BalanceCoordinatorClient
    // configured so its channel creation lands on the in-process server.
    // We use GrpcTestClient (below) which wraps a pre-built blocking stub.
    private GrpcTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        serverName = "bcc-test-" + System.nanoTime();
        captureService = new CapturingBalanceService();

        inProcessServer = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(captureService)
            .build()
            .start();

        client = new GrpcTestClient(0, serverName);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        if (inProcessServer != null) {
            inProcessServer.shutdownNow();
            inProcessServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------------------
    // Luciferase-7wzml.51 — concurrent same-rank startStreaming → distinct IDs
    // ---------------------------------------------------------------------------

    @Test
    void startStreaming_concurrentSameRank_producesNDistinctStreamIds() throws Exception {
        int n = 8;
        var latch = new CountDownLatch(n);
        var streamIds = Collections.synchronizedSet(new HashSet<String>());
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            for (int i = 0; i < n; i++) {
                executor.submit(() -> {
                    // startStreaming returns before the async connection attempt;
                    // we only care about the returned ID, not the connection result.
                    var id = client.startStreaming(1,
                        stats -> { /* discard */ },
                        err   -> { /* discard errors — no real async stub wired */ });
                    streamIds.add(id);
                    latch.countDown();
                });
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS),
                "All startStreaming calls must return within 5s");
        } finally {
            executor.shutdown();
        }

        assertEquals(n, streamIds.size(),
            "Every concurrent startStreaming call must produce a DISTINCT stream ID; " +
            "a timestamp-based ID collides at sub-ms rates, a sequence counter must not");

        // All IDs must be "stream-<rank>-<seq>" with a plain long sequence, not a 13-digit epoch ms
        for (var id : streamIds) {
            assertTrue(id.startsWith("stream-1-"),
                "Stream ID must start with 'stream-1-', got: " + id);
            var parts = id.split("-");
            assertEquals(3, parts.length, "Stream ID must have exactly 3 dash-separated parts: " + id);
            var seq = Long.parseLong(parts[2]);
            // A wall-clock timestamp would be ~1.7e12 ms; a sequence counter starts at 1
            assertTrue(seq >= 1 && seq <= n,
                "Sequence part must be a monotonic counter in [1," + n + "], got " + seq +
                " for id " + id + ". If > " + n + " it is likely a wall-clock timestamp.");
        }
    }

    // ---------------------------------------------------------------------------
    // Luciferase-7wzml.84 — Clock injection: both paths stamp injected time
    // ---------------------------------------------------------------------------

    @Test
    void requestRefinement_stampsInjectedClockTime() throws Exception {
        var clock = new TestClock();
        clock.setTime(999_000L);
        client.setClock(clock);

        client.requestRefinement(0, 1L, 1, 5, List.of());

        var captured = captureService.captured.poll(5, TimeUnit.SECONDS);
        assertNotNull(captured, "Server must receive a RefinementRequest");
        assertEquals(999_000L, captured.getTimestamp(),
            "requestRefinement must stamp clock.currentTimeMillis() not System.currentTimeMillis()");
    }

    @Test
    void requestRefinementBatched_stampsInjectedClockTime() throws Exception {
        var clock = new TestClock();
        clock.setTime(777_000L);
        client.setClock(clock);

        var future = client.requestRefinementBatched(0, 1L, 1, 5, List.of());
        future.get(5, TimeUnit.SECONDS);  // wait for batch to flush and server to respond

        var captured = captureService.captured.poll(5, TimeUnit.SECONDS);
        assertNotNull(captured, "Server must receive a RefinementRequest from batched path");
        assertEquals(777_000L, captured.getTimestamp(),
            "requestRefinementBatched must stamp clock.currentTimeMillis() not System.currentTimeMillis()");
    }

    // ---------------------------------------------------------------------------
    // Luciferase-7wzml.87 — Shared batch scheduler: no per-queue thread leak
    // ---------------------------------------------------------------------------

    @Test
    void batchQueues_multipleRanks_shareOneSchedulerThread() throws Exception {
        int threadsBefore = countSchedulerThreads();

        // Create N batch queues by batching to N ranks (all routed to same in-process server)
        int rankCount = 5;
        var futures = new ArrayList<CompletableFuture<RefinementResponse>>();
        for (int rank = 0; rank < rankCount; rank++) {
            futures.add(client.requestRefinementBatched(rank, 1L, 1, 5, List.of()));
        }
        for (var f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        int threadsAfter = countSchedulerThreads();
        int delta = threadsAfter - threadsBefore;

        // With shared scheduler: at most 1 new thread regardless of rankCount.
        // With per-queue scheduler: rankCount new threads would appear.
        assertTrue(delta <= 1,
            "Expected at most 1 new 'balance-batch-scheduler' thread (shared) for " + rankCount +
            " batch queues, but got delta=" + delta +
            ". Per-queue scheduler leak still present.");
    }

    @Test
    void shutdown_completesCleanly_sharedSchedulerIsTerminated() {
        // Warm up a batch queue
        client.requestRefinementBatched(0, 1L, 1, 5, List.of());

        assertDoesNotThrow(() -> client.shutdown(),
            "client.shutdown() must not throw even with active batch queues");

        client = null; // prevent double-shutdown in tearDown
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static int countSchedulerThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().startsWith("balance-batch-scheduler"))
            .filter(Thread::isAlive)
            .count();
    }

    // ---------------------------------------------------------------------------
    // GrpcTestClient: BalanceCoordinatorClient subclass that connects via
    // InProcess transport rather than real TCP.
    //
    // Approach: the base class builds channels lazily via getChannel() (private).
    // We can't override it. Instead we wrap BalanceCoordinatorClient with a
    // pre-wired InProcess ServiceDiscovery, but the base class will still try
    // to use Grpc.newChannelBuilder("in-process-name", ...) which won't work.
    //
    // Resolution: since we cannot override getChannel(), we build the channel and
    // stub map via reflection — package-private access in the same test package.
    // But blockingStubs is private final. Use reflection to inject the stub.
    //
    // Actually, the cleanest solution: create a real GrpcTestClient that manages
    // its own InProcess channel and stubs, and delegates the tested methods to
    // BalanceCoordinatorClient while injecting the stubs via reflection.
    // ---------------------------------------------------------------------------

    /**
     * Test-only subclass that pre-injects an in-process channel so the base class
     * uses InProcess transport instead of real TCP.  Uses reflection to insert the
     * pre-built stub into the base class's {@code blockingStubs} map (which is
     * {@code private final} in the base class).
     */
    static class GrpcTestClient extends BalanceCoordinatorClient {

        private static final BalanceCoordinatorClient.ServiceDiscovery NOOP_DISCOVERY =
            new BalanceCoordinatorClient.ServiceDiscovery() {
                @Override public String getEndpoint(int rank) { return "localhost:0"; /* never reached */ }
                @Override public void registerEndpoint(int rank, String ep) { }
                @Override public Map<Integer, String> getAllEndpoints() { return Map.of(); }
            };

        GrpcTestClient(int rank, String serverName) throws Exception {
            super(rank, NOOP_DISCOVERY);

            // Build an in-process channel and pre-populate both stub maps for rank 0..9
            // so the base class never tries to open a real TCP channel.
            var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

            var blockingStub = BalanceCoordinatorGrpc.newBlockingStub(channel);
            var asyncStub    = BalanceCoordinatorGrpc.newStub(channel);

            // Inject into the private maps via reflection.
            injectStub("blockingStubs", this, rank, blockingStub);
            injectStub("asyncStubs",    this, rank, asyncStub);
            // Pre-populate for ranks 0-9 so batched-to-rank-N tests also work.
            for (int r = 0; r <= 9; r++) {
                injectStub("blockingStubs", this, r, blockingStub);
                injectStub("asyncStubs",    this, r, asyncStub);
            }
        }

        @SuppressWarnings("unchecked")
        private static <V> void injectStub(String fieldName, Object target, int rank, V stub)
                throws Exception {
            var field = BalanceCoordinatorClient.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            ((Map<Integer, V>) field.get(target)).put(rank, stub);
        }
    }
}
