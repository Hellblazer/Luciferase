/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.committee;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeMigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.QuorumAchieved;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.google.protobuf.Empty;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CommitteeServiceImpl eviction + Clock injection behaviour.
 *
 * <p>Test plan:
 * <ol>
 *   <li>TTL eviction: N proposals added without being queried; after advancing
 *       the TestClock past RESULT_TTL_MS and triggering eviction (via a
 *       getQuorumResult call with a dummy id), all stale entries are removed.</li>
 *   <li>Deterministic TTL boundary: entries expire exactly when the injected
 *       clock crosses RESULT_TTL_MS, not before.</li>
 *   <li>Read-then-evict path: getQuorumResult still returns the correct result
 *       and removes the entry (existing tombstone-on-read semantics unchanged).</li>
 *   <li>Size-cap: inserting entries beyond MAX_RESULTS causes oldest-first
 *       pruning so the map stays bounded.</li>
 * </ol>
 *
 * Eviction is private but is triggered by both {@code submitMigrationProposal}
 * and {@code getQuorumResult}. Tests drive it via {@code getQuorumResult} with
 * a nonexistent proposal id, relying on the fact that the method evicts BEFORE
 * looking up the id (so the eviction side-effect is observable even when the
 * lookup fails with "not yet available").
 *
 * @author hal.hildebrand
 */
public class CommitteeServiceImplTest {

    private CommitteeServiceImpl service;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        var mockContext = Mockito.mock(com.hellblazer.delos.context.DynamicContext.class);
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);
        when(mockContext.bftSubset(Mockito.any())).thenReturn(new java.util.LinkedHashSet<>());
        when(mockContext.allMembers()).thenReturn(java.util.stream.Stream.empty());

        var executor = Executors.newScheduledThreadPool(1);
        var votingProtocol = new CommitteeVotingProtocol(mockContext, CommitteeConfig.defaultConfig(), executor);
        var consensus = new ViewCommitteeConsensus();
        consensus.setViewMonitor(new StubViewMonitor(DigestAlgorithm.DEFAULT.digest("view1")));
        consensus.setCommitteeSelector(new ViewCommitteeSelector(mockContext));
        consensus.setVotingProtocol(votingProtocol);

        service = new CommitteeServiceImpl(consensus, votingProtocol);
        testClock = new TestClock(1_000_000L);
        service.setClock(testClock);
    }

    // ------------------------------------------------------------------
    // Test 1: N un-queried proposals are evicted after TTL elapses
    // ------------------------------------------------------------------

    @Test
    void staleSweep_removesAllExpiredEntriesOnNextEvictTrigger() {
        int n = 5;
        long baseTime = testClock.currentTimeMillis();

        for (int i = 0; i < n; i++) {
            service.putResultEntry("stale-" + i,
                                   new CommitteeServiceImpl.ResultEntry(true, baseTime));
        }
        assertEquals(n, service.getCachedResultCount());

        // Advance clock past TTL — entries are now stale
        testClock.advance(CommitteeServiceImpl.RESULT_TTL_MS + 1);

        // Trigger eviction by calling getQuorumResult with a nonexistent id.
        // evictStaleAndOversize() runs BEFORE the lookup so stale entries are swept.
        triggerEviction();

        assertEquals(0, service.getCachedResultCount(),
                     "All stale entries must be evicted; actual=" + service.getCachedResultCount());
    }

    // ------------------------------------------------------------------
    // Test 2: TTL boundary is deterministic under injected TestClock
    // ------------------------------------------------------------------

    @Test
    void ttlBoundary_deterministic_underTestClock() {
        long t0 = testClock.currentTimeMillis();

        service.putResultEntry("p1", new CommitteeServiceImpl.ResultEntry(true, t0));

        // Advance to TTL - 1 ms. cutoff = (t0 + TTL - 1) - TTL = t0 - 1.
        // completedAtMs == t0, and t0 > t0 - 1, so the entry survives.
        testClock.advance(CommitteeServiceImpl.RESULT_TTL_MS - 1);
        triggerEviction();
        assertTrue(service.hasResultEntry("p1"),
                   "p1 must survive when clock is still 1 ms inside TTL");

        // Advance exactly 1 more ms. cutoff = (t0 + TTL) - TTL = t0.
        // completedAtMs == t0 <= t0 == cutoff => evicted.
        testClock.advance(1);
        triggerEviction();
        assertFalse(service.hasResultEntry("p1"),
                    "p1 must be evicted exactly at the TTL boundary");
    }

    // ------------------------------------------------------------------
    // Test 3: read-then-evict path returns correct result and removes entry
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Luciferase-933d8: a definitively-failed proposal must be distinguishable
    // from a still-pending one (not the generic "not yet available").
    // ------------------------------------------------------------------

    @Test
    void getQuorumResult_failedProposal_returnsDistinctTerminalFailure_notPending() {
        // Consensus future completes EXCEPTIONALLY (timeout / view change). failedFuture is already completed,
        // so submitMigrationProposal's whenComplete runs synchronously and stores the failure sentinel.
        var mockConsensus = Mockito.mock(ViewCommitteeConsensus.class);
        when(mockConsensus.requestConsensus(Mockito.any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom: view change")));
        var mockVoting = Mockito.mock(CommitteeVotingProtocol.class);
        var failingService = new CommitteeServiceImpl(mockConsensus, mockVoting);
        failingService.setClock(testClock);

        var proposalId = UUID.randomUUID();
        var proto = buildProto(proposalId);

        failingService.submitMigrationProposal(proto, new StreamObserver<>() {
            @Override public void onNext(Empty v) {}
            @Override public void onError(Throwable t) {}
            @Override public void onCompleted() {}
        });

        var resultHolder = new AtomicReference<Boolean>();
        var errorHolder = new AtomicReference<Throwable>();
        failingService.getQuorumResult(proto, new StreamObserver<>() {
            @Override public void onNext(QuorumAchieved v) { resultHolder.set(v.getResult()); }
            @Override public void onError(Throwable t) { errorHolder.set(t); }
            @Override public void onCompleted() {}
        });

        assertNull(resultHolder.get(), "a failed proposal must not deliver a QuorumAchieved result");
        assertNotNull(errorHolder.get(), "a failed proposal must signal a terminal error");
        var msg = errorHolder.get().getMessage();
        assertTrue(msg != null && msg.contains("consensus failed"),
                   "failed proposal must yield the definitive failure signal, got: " + msg);
        assertFalse(msg.contains("not yet available"),
                    "a definitively-failed proposal must NOT be reported as still-pending");
    }

    @Test
    void readThenEvict_returnsResultAndTombstonesEntry() {
        var proposalId = UUID.randomUUID();
        service.putResultEntry(proposalId.toString(),
                               new CommitteeServiceImpl.ResultEntry(true, testClock.currentTimeMillis()));
        assertEquals(1, service.getCachedResultCount());

        var resultHolder = new AtomicReference<Boolean>();
        var errorHolder = new AtomicReference<Throwable>();

        service.getQuorumResult(buildProto(proposalId), new StreamObserver<>() {
            @Override
            public void onNext(QuorumAchieved v) { resultHolder.set(v.getResult()); }
            @Override
            public void onError(Throwable t) { errorHolder.set(t); }
            @Override
            public void onCompleted() {}
        });

        assertNull(errorHolder.get(), "No error expected on a present entry");
        assertNotNull(resultHolder.get(), "Should have returned a result");
        assertTrue(resultHolder.get(), "Result value must be true");
        assertEquals(0, service.getCachedResultCount(), "Entry must be tombstoned on read");
    }

    // ------------------------------------------------------------------
    // Test 4: size-cap — inserting beyond MAX_RESULTS trims oldest entries
    // ------------------------------------------------------------------

    @Test
    void sizeCap_prunesOldestWhenOverMaxResults() {
        long baseTime = testClock.currentTimeMillis();

        // Fill to exactly MAX_RESULTS with monotonically increasing timestamps
        for (int i = 0; i < CommitteeServiceImpl.MAX_RESULTS; i++) {
            service.putResultEntry("entry-" + i,
                                   new CommitteeServiceImpl.ResultEntry(true, baseTime + i));
        }
        assertEquals(CommitteeServiceImpl.MAX_RESULTS, service.getCachedResultCount());

        // Insert one more entry (clock still at baseTime so TTL pass removes nothing)
        service.putResultEntry("overflow",
                               new CommitteeServiceImpl.ResultEntry(false,
                                                                     baseTime + CommitteeServiceImpl.MAX_RESULTS));

        // Trigger eviction: map is now at MAX_RESULTS + 1, size-cap must fire
        triggerEviction();

        assertTrue(service.getCachedResultCount() <= CommitteeServiceImpl.MAX_RESULTS,
                   "Size cap must hold; actual=" + service.getCachedResultCount());
    }

    // ------------------------------------------------------------------
    // Test 5: H2 — concurrent getQuorumResult for same id: exactly one winner
    // ------------------------------------------------------------------

    /**
     * Two threads race to call getQuorumResult for the same proposalId.
     * After the atomic remove fix, exactly one caller gets a non-null entry and
     * emits onNext()+onCompleted(); the other falls through to the absent path
     * and emits onError("not yet available"). Assert: exactly 1 onCompleted and
     * exactly 1 onError across the two observers.
     */
    @Test
    void concurrentGetQuorumResult_exactlyOneWinner() throws Exception {
        var proposalId = UUID.randomUUID();
        service.putResultEntry(proposalId.toString(),
                               new CommitteeServiceImpl.ResultEntry(true, testClock.currentTimeMillis()));

        var completedCount = new AtomicInteger(0);
        var errorCount = new AtomicInteger(0);

        // Barrier ensures both threads call getQuorumResult as simultaneously as possible
        var barrier = new CyclicBarrier(2);
        var proto = buildProto(proposalId);

        StreamObserver<QuorumAchieved> obs1 = new StreamObserver<>() {
            @Override public void onNext(QuorumAchieved v) {}
            @Override public void onError(Throwable t) { errorCount.incrementAndGet(); }
            @Override public void onCompleted() { completedCount.incrementAndGet(); }
        };
        StreamObserver<QuorumAchieved> obs2 = new StreamObserver<>() {
            @Override public void onNext(QuorumAchieved v) {}
            @Override public void onError(Throwable t) { errorCount.incrementAndGet(); }
            @Override public void onCompleted() { completedCount.incrementAndGet(); }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            try { barrier.await(); } catch (Exception ignored) {}
            service.getQuorumResult(proto, obs1);
        });
        pool.submit(() -> {
            try { barrier.await(); } catch (Exception ignored) {}
            service.getQuorumResult(proto, obs2);
        });

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Threads must finish within 5s");

        assertEquals(1, completedCount.get(), "Exactly one caller must win and call onCompleted()");
        assertEquals(1, errorCount.get(), "Exactly one caller must fall through to onError()");
        assertEquals(0, service.getCachedResultCount(), "Entry must be consumed after the race");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Fires eviction by calling getQuorumResult with a nonexistent proposal id.
     * evictStaleAndOversize() runs before the map lookup, so the eviction side-effect
     * is observable regardless of whether the lookup finds an entry. The resulting
     * "not yet available" error response is silently ignored.
     */
    private void triggerEviction() {
        service.getQuorumResult(buildProto(UUID.randomUUID()), noopObserver());
    }

    private CommitteeMigrationProposal buildProto(UUID proposalId) {
        var proposal = new MigrationProposal(
            proposalId,
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            DigestAlgorithm.DEFAULT.digest("view1"),
            testClock.currentTimeMillis()
        );
        return CommitteeProtoConverter.toProto(proposal, "localhost:0");
    }

    private static StreamObserver<QuorumAchieved> noopObserver() {
        return new StreamObserver<>() {
            @Override public void onNext(QuorumAchieved v) {}
            @Override public void onError(Throwable t) {}    // "not yet available" is expected
            @Override public void onCompleted() {}
        };
    }

    // ------------------------------------------------------------------
    // Inner helpers (mirrors CommitteeP2PIntegrationTest pattern)
    // ------------------------------------------------------------------

    private static class StubViewMonitor
        extends com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor {
        private final com.hellblazer.delos.cryptography.Digest viewId;

        StubViewMonitor(com.hellblazer.delos.cryptography.Digest viewId) {
            super(new StubMembershipView());
            this.viewId = viewId;
        }

        @Override
        public com.hellblazer.delos.cryptography.Digest getCurrentViewId() {
            return viewId;
        }
    }

    private static class StubMembershipView
        implements com.hellblazer.luciferase.simulation.delos.MembershipView<
            com.hellblazer.delos.membership.Member> {
        @Override public void addListener(java.util.function.Consumer listener) {}
        @Override
        public java.util.stream.Stream<com.hellblazer.delos.membership.Member> getMembers() {
            return java.util.stream.Stream.empty();
        }
        @Override
        public java.util.stream.Stream<com.hellblazer.delos.membership.Member> activeMembers() {
            return java.util.stream.Stream.empty();
        }
    }
}
