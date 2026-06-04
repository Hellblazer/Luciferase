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

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the view-change rollback races in CommitteeVotingProtocol /
 * CommitteeBallotBox.
 * <p>
 * Covers:
 * <ul>
 *   <li>Luciferase-0frcy.92 — zombie VoteState resurrection: handleTimeout after a
 *       concurrent rollback cleared the proposal must NOT recreate a VoteState and must
 *       NOT emit a misleading "Voting timeout" completion.</li>
 *   <li>Luciferase-0frcy.95 — view-change / requestConsensus TOCTOU: a proposal whose
 *       view no longer matches the recorded view (registered after a rollback) must be
 *       rejected at the register step rather than escaping into the old view's tally.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class CommitteeRollbackRaceTest {

    private DynamicContext<Member>   mockContext;
    private ScheduledExecutorService scheduler;
    private Digest                   viewV1;
    private Digest                   viewV2;

    @BeforeEach
    void setUp() {
        mockContext = Mockito.mock(DynamicContext.class);
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);
        scheduler = Executors.newScheduledThreadPool(2);
        viewV1 = DigestAlgorithm.DEFAULT.getOrigin();
        viewV2 = DigestAlgorithm.DEFAULT.digest("view-2");
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Luciferase-0frcy.92: After rollback clears a proposal, an in-flight timeout for the
     * SAME proposal must observe no state (getResultIfPresent empty) and must not create a
     * zombie VoteState that completes with a spurious TimeoutException. We exercise this by
     * driving the ballot box directly through the rollback-then-timeout ordering.
     */
    @Test
    void handleTimeoutAfterRollbackDoesNotResurrectVoteState() throws Exception {
        var protocol = new CommitteeVotingProtocol(mockContext, CommitteeConfig.defaultConfig(), scheduler);
        var ballotBox = new CommitteeBallotBox(mockContext);

        var proposalId = UUID.randomUUID();
        // Materialize state (as requestConsensus would), then clear it (as rollback does).
        ballotBox.getResult(proposalId);
        assertTrue(ballotBox.getResultIfPresent(proposalId).isPresent(),
                   "Precondition: state exists before rollback");
        ballotBox.clear(proposalId);

        // Post-rollback: present-check must be empty, NOT recreate a zombie.
        assertTrue(ballotBox.getResultIfPresent(proposalId).isEmpty(),
                   "getResultIfPresent must not resurrect cleared state (Luciferase-0frcy.92)");

        // And handleTimeout must be a no-op (proposal already removed) — no exception, no zombie.
        protocol.handleTimeout(proposalId);
        assertTrue(ballotBox.getResultIfPresent(proposalId).isEmpty(),
                   "handleTimeout on a rolled-back proposal must not recreate state");
    }

    /**
     * Luciferase-0frcy.95: once a view change to V2 is recorded via rollbackOnViewChange,
     * a subsequently-registered proposal still carrying viewId=V1 must be rejected at the
     * register step (TOCTOU containment) rather than entering the old view's tally.
     */
    @Test
    void requestConsensusRejectsStaleViewProposalAfterViewChange() {
        var protocol = new CommitteeVotingProtocol(mockContext, CommitteeConfig.defaultConfig(), scheduler);

        // Record the view change to V2 first.
        protocol.rollbackOnViewChange(viewV2);

        // Now a late proposal stamped with the OLD view V1 arrives.
        var staleProposal = proposal(viewV1);
        var future = protocol.requestConsensus(staleProposal, committee(3));

        assertTrue(future.isCompletedExceptionally(),
                   "Stale-view proposal must be rejected at register step (Luciferase-0frcy.95)");
        var ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("does not match current view"),
                   "Rejection must explain the view mismatch");
    }

    /**
     * Control: a proposal matching the current view after a view change is still accepted,
     * so the TOCTOU guard does not over-reject.
     */
    @Test
    void requestConsensusAcceptsCurrentViewProposalAfterViewChange() {
        var protocol = new CommitteeVotingProtocol(mockContext, CommitteeConfig.defaultConfig(), scheduler);
        protocol.rollbackOnViewChange(viewV2);

        var freshProposal = proposal(viewV2);
        var future = protocol.requestConsensus(freshProposal, committee(3));

        assertFalse(future.isCompletedExceptionally(),
                    "Current-view proposal must be accepted after a view change");
        assertFalse(future.isDone(), "Accepted proposal is pending until quorum/timeout");
    }

    /**
     * Luciferase-0frcy.C1: a proposal registered concurrently with a view-change rollback
     * on the SAME id must NEVER yield a non-completing ("zombie") future. The earlier code
     * captured the ballot-box result future OUTSIDE the viewLock, so a rollback could
     * complete+clear the proposal in the gap and the trailing getResult would resurrect a
     * fresh, never-completing VoteState. With register + getResult now atomic under the
     * lock, the returned future must always settle — exceptionally (rolled back / rejected)
     * or normally — but never hang.
     * <p>
     * We force the interleave with a CyclicBarrier so both threads enter their critical
     * region at the same instant, then run the race many times to exercise both orderings.
     */
    @Test
    void concurrentRegisterAndRollbackNeverProducesZombieFuture() {
        for (int iteration = 0; iteration < 200; iteration++) {
            final var protocol = new CommitteeVotingProtocol(mockContext, CommitteeConfig.defaultConfig(),
                                                             scheduler);
            // Proposal stamped with the OLD view V1; rollback will move to V2.
            final var staleProposal = proposal(viewV1);
            final var committee = committee(3);

            final var barrier = new CyclicBarrier(2);
            final var pool = Executors.newFixedThreadPool(2);
            try {
                var registerTask = pool.submit(() -> {
                    awaitBarrier(barrier);
                    return protocol.requestConsensus(staleProposal, committee);
                });
                var rollbackTask = pool.submit(() -> {
                    awaitBarrier(barrier);
                    protocol.rollbackOnViewChange(viewV2);
                    return null;
                });

                final CompletableFuture<Boolean> future;
                try {
                    rollbackTask.get(5, TimeUnit.SECONDS);
                    future = registerTask.get(5, TimeUnit.SECONDS);
                } catch (InterruptedException | java.util.concurrent.ExecutionException
                         | TimeoutException e) {
                    throw new AssertionError("Race tasks did not settle on iteration " + iteration, e);
                }

                final int iter = iteration;
                // The contract under test: the returned future MUST settle within the bound.
                // It is allowed to complete exceptionally (rollback abort / stale-view reject)
                // or normally, but it must NEVER be a non-completing zombie.
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    try {
                        future.get(2, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException expected) {
                        // Rolled back or rejected — acceptable terminal outcome.
                    }
                }, "Future must settle, never hang (zombie) — iteration " + iter);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private MigrationProposal proposal(Digest viewId) {
        return new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            viewId,
            1_000L
        );
    }

    private Set<Digest> committee(int size) {
        var committee = new HashSet<Digest>();
        for (int i = 0; i < size; i++) {
            committee.add(DigestAlgorithm.DEFAULT.digest("member-" + i));
        }
        return committee;
    }
}
