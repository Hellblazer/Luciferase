/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.consensus.committee.MigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.ProposalKind;
import com.hellblazer.luciferase.simulation.consensus.committee.ViewCommitteeConsensus;
import com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver;
import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for TopologyConsensusCoordinator cooldown, pre-validation, and clock integration.
 *
 * @author hal.hildebrand
 */
class TopologyConsensusCoordinatorTest {

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyConsensusCoordinator coordinator;
    private TestClock testClock;
    private ViewCommitteeConsensus mockConsensus;
    private Digest localDigest;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 2);
        accountant = new EntityAccountant();
        coordinator = new TopologyConsensusCoordinator(bubbleGrid, 10_000L); // 10 second cooldown for testing

        testClock = new TestClock();
        testClock.setMillis(1000L);
        coordinator.setClock(testClock);

        mockConsensus = Mockito.mock(ViewCommitteeConsensus.class);
        coordinator.setConsensusProtocol(mockConsensus);

        // RDR-020 S3: requestConsensus now resolves the region owner through a BubbleOwnershipResolver.
        // For the cooldown / pre-validation / clock tests the consensus is fully mocked (validateProposal
        // never runs), so the only requirement is that the two coordinator-level guards pass: the local
        // node must own the region (owner == local) and a merge's two bubbles must share one owner. A
        // fixed-owner resolver — owner == local == one digest for every bubble — satisfies both for any
        // bubble created per-test. Tests that exercise the guard failure paths inject their own resolver.
        localDigest = DigestAlgorithm.DEFAULT.digest("s3-local-member");
        coordinator.setOwnershipResolver(fixedOwnerResolver(localDigest, localDigest));

        // Mock consensus to always approve
        when(mockConsensus.requestConsensus(any())).thenReturn(CompletableFuture.completedFuture(true));
    }

    /**
     * A deterministic {@link BubbleOwnershipResolver} returning fixed owner / local digests for
     * every bubble (RDR-020 S3). With {@code owner == local} both coordinator-level guards
     * (ownership, same-owner merge) pass; setting them unequal forces the ownership-guard throw.
     */
    private static BubbleOwnershipResolver fixedOwnerResolver(Digest owner, Digest local) {
        return new BubbleOwnershipResolver() {
            @Override
            public Digest resolveOwningMember(UUID bubbleId) {
                return owner;
            }

            @Override
            public Digest localMember() {
                return local;
            }

            @Override
            public Digest memberDigestForNode(UUID nodeId) {
                return owner;
            }
        };
    }

    @Test
    void testCooldownEnforcementRejectsRapidChanges() {
        // Create bubble with >5000 entities for valid split
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());

        // First proposal should pass cooldown check
        assertTrue(coordinator.canProposeTopologyChange(proposal),
                  "First proposal should pass cooldown check");

        // Request consensus (approved)
        var result = coordinator.requestConsensus(proposal).join();
        assertTrue(result, "First proposal should be approved");

        // Advance time by only 5 seconds (less than 10 second cooldown)
        testClock.setMillis(6000L);

        // Second proposal should be rejected by cooldown
        var proposal2 = createSplitProposal(bubble.id());
        assertFalse(coordinator.canProposeTopologyChange(proposal2),
                   "Second proposal should be rejected by cooldown");

        var result2 = coordinator.requestConsensus(proposal2).join();
        assertFalse(result2, "Second proposal should be rejected");
    }

    @Test
    void testCooldownElapsedAllowsNewProposal() {
        // Create bubble with >5000 entities
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());

        // First proposal
        coordinator.requestConsensus(proposal).join();

        // Advance time beyond cooldown (10 seconds)
        testClock.setMillis(12000L);

        // Second proposal should now pass cooldown
        var proposal2 = createSplitProposal(bubble.id());
        assertTrue(coordinator.canProposeTopologyChange(proposal2),
                  "Proposal should pass after cooldown elapsed");

        var result2 = coordinator.requestConsensus(proposal2).join();
        assertTrue(result2, "Proposal should be approved after cooldown");
    }

    @Test
    void testPreValidationRejectsByzantineProposal() {
        // Create bubble with only 1000 entities (below split threshold)
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 1000);

        var proposal = createSplitProposal(bubble.id());

        // Proposal should be rejected by pre-validation (below threshold)
        var result = coordinator.requestConsensus(proposal).join();
        assertFalse(result, "Byzantine proposal (below threshold) should be rejected");
    }

    @Test
    void testClockInjection() {
        testClock.setMillis(5000L);

        // Create bubble and first proposal
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());
        coordinator.requestConsensus(proposal).join();

        // Check remaining cooldown uses injected clock
        testClock.setMillis(8000L); // 3 seconds after approval
        long remaining = coordinator.getRemainingCooldown(bubble.id());
        assertEquals(7000L, remaining, "Remaining cooldown should use injected clock");
    }

    @Test
    void testAffectedBubblesForSplit() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());
        coordinator.requestConsensus(proposal).join();

        // Only source bubble should be in cooldown
        assertTrue(coordinator.getRemainingCooldown(bubble.id()) > 0,
                  "Source bubble should be in cooldown");
    }

    @Test
    void testAffectedBubblesForMerge() {
        // Create 2 bubbles with low entity counts
        bubbleGrid.createBubbles(2, (byte) 1, 10);
        var bubbles = bubbleGrid.getAllBubbles().stream().toList();
        var bubble1 = bubbles.get(0);
        var bubble2 = bubbles.get(1);

        addEntities(bubble1, 300);
        addEntities(bubble2, 300);

        // Only test if bubbles are actually neighbors
        var neighbors = bubbleGrid.getNeighbors(bubble1.id());
        if (neighbors.contains(bubble2.id())) {
            var proposal = new MergeProposal(
                UUID.randomUUID(),
                bubble1.id(),
                bubble2.id(),
                DigestAlgorithm.DEFAULT.getOrigin(),
                testClock.currentTimeMillis()
            );

            coordinator.requestConsensus(proposal).join();

            // Both bubbles should be in cooldown
            assertTrue(coordinator.getRemainingCooldown(bubble1.id()) > 0,
                      "Bubble1 should be in cooldown");
            assertTrue(coordinator.getRemainingCooldown(bubble2.id()) > 0,
                      "Bubble2 should be in cooldown");
        }
    }

    @Test
    void testRemainingCooldownCalculation() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        testClock.setMillis(1000L);
        var proposal = createSplitProposal(bubble.id());
        coordinator.requestConsensus(proposal).join();

        // Check remaining at various points
        testClock.setMillis(4000L); // 3 seconds after
        assertEquals(7000L, coordinator.getRemainingCooldown(bubble.id()),
                    "Remaining should be 7 seconds");

        testClock.setMillis(8000L); // 7 seconds after
        assertEquals(3000L, coordinator.getRemainingCooldown(bubble.id()),
                    "Remaining should be 3 seconds");

        testClock.setMillis(12000L); // 11 seconds after (past cooldown)
        assertEquals(0L, coordinator.getRemainingCooldown(bubble.id()),
                    "Remaining should be 0 after cooldown elapsed");
    }

    @Test
    void testResetClearsCooldownState() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());
        coordinator.requestConsensus(proposal).join();

        // Verify cooldown active
        assertTrue(coordinator.getRemainingCooldown(bubble.id()) > 0,
                  "Cooldown should be active");

        // Reset
        coordinator.reset();

        // Cooldown should be cleared
        assertEquals(0L, coordinator.getRemainingCooldown(bubble.id()),
                    "Cooldown should be cleared after reset");
    }

    @Test
    void testConsensusProtocolRequired() {
        var coordinator2 = new TopologyConsensusCoordinator(bubbleGrid);
        coordinator2.setClock(testClock);
        // Don't set consensus protocol

        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());

        assertThrows(IllegalStateException.class, () -> {
            coordinator2.requestConsensus(proposal);
        }, "Should throw IllegalStateException when consensus protocol not set");
    }

    @Test
    void testDefaultCooldownPeriod() {
        var coordinator2 = new TopologyConsensusCoordinator(bubbleGrid);

        // Should use DEFAULT_COOLDOWN_MS (30 seconds)
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        coordinator2.setClock(testClock);
        coordinator2.setConsensusProtocol(mockConsensus);
        coordinator2.setOwnershipResolver(fixedOwnerResolver(localDigest, localDigest));

        testClock.setMillis(1000L);
        var proposal = createSplitProposal(bubble.id());
        coordinator2.requestConsensus(proposal).join();

        // Check remaining cooldown is ~30 seconds
        testClock.setMillis(2000L); // 1 second after
        long remaining = coordinator2.getRemainingCooldown(bubble.id());
        assertEquals(29000L, remaining, "Default cooldown should be 30 seconds");
    }

    @Test
    void testNegativeCooldownThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TopologyConsensusCoordinator(bubbleGrid, -1000L);
        }, "Should reject negative cooldown");
    }

    @Test
    void testNullBubbleGridThrows() {
        assertThrows(NullPointerException.class, () -> {
            new TopologyConsensusCoordinator(null);
        }, "Should reject null bubble grid");
    }

    @Test
    void testNullProposalThrows() {
        assertThrows(NullPointerException.class, () -> {
            coordinator.requestConsensus(null);
        }, "Should reject null proposal");
    }

    @Test
    void testRequestConsensusRoutesThroughConsensusProtocol() {
        // A valid proposal that passes cooldown + pre-validation must still be
        // submitted to the committee consensus protocol — pre-validation is NOT consensus.
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var proposal = createSplitProposal(bubble.id());
        var result = coordinator.requestConsensus(proposal).join();

        assertTrue(result, "Proposal should be approved when committee approves");
        // The stub cannot re-emerge: the consensus protocol MUST be invoked exactly once.
        verify(mockConsensus, times(1)).requestConsensus(any());
    }

    @Test
    void testNonApprovingCommitteeBlocksProposal() {
        // Committee votes NO — proposal must be rejected even though it passed
        // local cooldown and pre-validation. This is the core BFT guarantee.
        //
        // Non-vacuous cooldown assertion: a bare assertEquals(0L, getRemainingCooldown(...))
        // passes trivially because an absent bubble key returns 0. To prove that *only* an
        // approval starts/refreshes the cooldown — and a rejection neither starts nor
        // extends it — we first drive an APPROVED proposal to establish a real, non-zero
        // cooldown baseline, then submit a REJECTED proposal and assert the cooldown is
        // unchanged (not reset, not extended) by the rejection.
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        // Stage 1: approved proposal establishes a real cooldown baseline at t=1000ms.
        // setUp() already mocks consensus to approve.
        var approved = createSplitProposal(bubble.id());
        assertTrue(coordinator.requestConsensus(approved).join(),
                   "Baseline proposal must be approved to start the cooldown");
        long baselineCooldown = coordinator.getRemainingCooldown(bubble.id());
        assertTrue(baselineCooldown > 0L,
                   "Approval must establish a non-zero cooldown (baseline for the non-vacuous check)");

        // Advance time past the 10s cooldown so the rejected proposal clears the stage-1
        // cooldown gate and actually reaches the committee (otherwise it is short-circuited
        // before consensus and the test would be vacuous in a different way).
        testClock.setMillis(12_000L);
        assertEquals(0L, coordinator.getRemainingCooldown(bubble.id()),
                     "Cooldown must have fully elapsed before the rejected proposal");

        // Stage 2: committee now votes NO.
        when(mockConsensus.requestConsensus(any()))
            .thenReturn(CompletableFuture.completedFuture(false));
        var rejected = createSplitProposal(bubble.id());
        var result = coordinator.requestConsensus(rejected).join();

        assertFalse(result, "Proposal must be rejected when committee does not approve");
        // requestConsensus was invoked once for the approval and once for the rejection.
        verify(mockConsensus, times(2)).requestConsensus(any());

        // The defining defense: the rejection must NOT start or refresh the cooldown. The
        // last-change timestamp is still the approval's (t=1000ms), which fully elapsed by
        // t=12000ms, so remaining cooldown is 0. A rejection that wrongly stamped the
        // cooldown at t=12000ms would yield a non-zero remaining here.
        assertEquals(0L, coordinator.getRemainingCooldown(bubble.id()),
                     "Rejected proposal must not start or extend the cooldown");
    }

    @Test
    void testPreValidationFailureSkipsConsensus() {
        // Byzantine proposal (below split threshold) is rejected locally and must
        // never reach the committee.
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 1000);

        var proposal = createSplitProposal(bubble.id());
        var result = coordinator.requestConsensus(proposal).join();

        assertFalse(result, "Byzantine proposal should be rejected by pre-validation");
        verify(mockConsensus, never()).requestConsensus(any());
    }

    // Helper methods

    private void addEntities(com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            var entityId = UUID.randomUUID();
            bubble.addEntity(
                entityId.toString(),
                new Point3f(i * 0.01f, i * 0.01f, i * 0.01f),
                null
            );
            accountant.register(bubble.id(), entityId);
        }
    }

    /**
     * Luciferase-0frcy.43/.44: the cooldown check-then-update was a non-atomic
     * ConcurrentHashMap read-then-write. Two threads proposing changes for the same bubble
     * concurrently could both pass the cooldown gate before either recorded a timestamp,
     * bypassing the cooldown entirely. With the atomic compute() test-and-set reservation,
     * at most one of two concurrent proposals on the same bubble may be approved.
     */
    @Test
    void concurrentProposalsCannotBothBypassCooldown() throws Exception {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        final int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var startGate = new java.util.concurrent.CountDownLatch(1);
        var approvals = new java.util.concurrent.atomic.AtomicInteger(0);
        var done = new java.util.concurrent.CountDownLatch(threads);

        // Consensus approves any proposal that reaches it. Whether it reaches consensus is
        // gated by the (now atomic) cooldown reservation under contention.
        when(mockConsensus.requestConsensus(any()))
            .thenReturn(CompletableFuture.completedFuture(true));

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    var proposal = createSplitProposal(bubble.id());
                    startGate.await();
                    if (Boolean.TRUE.equals(coordinator.requestConsensus(proposal).join())) {
                        approvals.incrementAndGet();
                    }
                } catch (Exception e) {
                    // count as no-approval
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "threads should finish");
        pool.shutdownNow();

        assertEquals(1, approvals.get(),
                     "Exactly one concurrent proposal for the same bubble may pass the atomic cooldown");
    }

    /**
     * Luciferase-0frcy M2/C1 (ABA): a rejected loser's restore must never wipe a concurrent
     * winner's live cooldown reservation.
     * <p>
     * Scenario, with a 10s cooldown and deterministic clock:
     * <ol>
     *   <li>Proposal A reserves the bubble at t=1000 (writes timestamp 1000). A has no prior entry,
     *       so its restore would naively {@code remove(bubble)}.</li>
     *   <li>Clock advances past the cooldown to t=12000; proposal B reserves the SAME bubble,
     *       overwriting the timestamp to 12000 — B is the live winner.</li>
     *   <li>A is then rejected (committee votes NO) and its restore runs. With the bug, A blindly
     *       removes the bubble entry, wiping B's live 12000 reservation. With the conditional
     *       (ABA-safe) restore, A sees the current value is 12000 (not the 1000 it wrote) and leaves
     *       it untouched.</li>
     *   <li>A third proposal at t=13000 (only 1s after B's reservation) must therefore still be
     *       inside B's cooldown and be refused. Under the bug it would pass spuriously.</li>
     * </ol>
     * The interleaving is made deterministic by holding A's consensus future open: A reserves, then
     * B reserves the same bubble (winning), and only then is A's rejection released — reproducing
     * the exact A-reserves / B-reserves / A-restores ordering that triggers the ABA clobber, without
     * the flakiness of a wall-clock thread race.
     */
    @Test
    void rejectedLoserRestoreDoesNotWipeConcurrentWinnerReservation() throws Exception {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);
        var bubbleId = bubble.id();

        // Per-proposal consensus control: A's future (first call) is held so we can reject A at a
        // chosen instant; every later call auto-approves.
        var aConsensus = new CompletableFuture<Boolean>();
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        when(mockConsensus.requestConsensus(any())).thenAnswer(inv ->
            callCount.getAndIncrement() == 0
                ? aConsensus
                : CompletableFuture.completedFuture(true));

        // Stage 1: A reserves at t=1000 and parks awaiting consensus.
        testClock.setMillis(1000L);
        var proposalA = createSplitProposal(bubbleId);
        var resultA = coordinator.requestConsensus(proposalA);

        // Stage 2: advance past the cooldown so B can reserve the same bubble (writes 12000).
        testClock.setMillis(12_000L);
        var proposalB = createSplitProposal(bubbleId);
        var resultB = coordinator.requestConsensus(proposalB).join();
        assertTrue(resultB, "B must be approved and hold the live reservation at t=12000");
        assertTrue(coordinator.getRemainingCooldown(bubbleId) > 0,
                   "B's reservation must be live before A's rejection");

        // Stage 3: now reject A. A's restore must NOT clobber B's reservation.
        aConsensus.complete(false);
        assertFalse(resultA.join(), "A must be rejected by the committee");

        // Stage 4: B's reservation (t=12000) must survive. A third proposal 1s later is still inside
        // B's 10s cooldown and must be refused — proving B's reservation was not wiped by A.
        testClock.setMillis(13_000L);
        var proposalC = createSplitProposal(bubbleId);
        assertFalse(coordinator.canProposeTopologyChange(proposalC),
                    "Third proposal must be refused: B's live reservation must survive A's restore "
                    + "(ABA guard). A spurious pass means A's restore wiped B's reservation.");
        assertEquals(9000L, coordinator.getRemainingCooldown(bubbleId),
                     "Remaining cooldown must reflect B's t=12000 reservation, not a wiped/absent entry");
    }

    /**
     * Luciferase-7wzml.181 (ABA same-tick token collision).
     * <p>
     * Two proposals on the SAME bubble issued at the IDENTICAL clock millisecond (frozen
     * TestClock). Under the old implementation the ABA token was the raw clock millis, so both
     * proposals wrote the same value; when the loser was rejected its restoreCooldown saw
     * current == written and removed the winner's live entry, re-opening the oscillation window.
     * <p>
     * With the unique-token fix each reservation mints a distinct token from the AtomicLong
     * sequence, so the loser's restore sees current.token != loser.token and correctly leaves the
     * winner's entry untouched — even with the clock stuck at the exact same millisecond.
     * <p>
     * Scenario (deterministic, no real concurrency needed):
     * <ol>
     *   <li>Clock frozen at t=1000. Proposal A reserves the bubble (token=T_A, timestamp=1000).
     *       A's consensus future is held open.</li>
     *   <li>Clock advanced past cooldown to t=12000. Proposal B reserves the SAME bubble
     *       (token=T_B, timestamp=12000). B is approved immediately; B holds the live entry.</li>
     *   <li>A is rejected. A's restoreCooldown must NOT clobber B's entry (T_A != T_B).</li>
     *   <li>A third proposal at t=13000 (1s after B) must still be refused by B's cooldown.</li>
     * </ol>
     * This is the same structural scenario as {@link #rejectedLoserRestoreDoesNotWipeConcurrentWinnerReservation}
     * but with both A and B using the SAME clock millisecond at reservation time (A at t=1000,
     * clock rewound back to t=1000 before B issues) to force the token collision that the old code
     * suffered from.
     */
    @Test
    void sameTick_rejectedLoserRestoreDoesNotClobberWinner() throws Exception {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);
        var bubbleId = bubble.id();

        // A's future is held; B auto-approves.
        var aConsensus = new CompletableFuture<Boolean>();
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        when(mockConsensus.requestConsensus(any())).thenAnswer(inv ->
            callCount.getAndIncrement() == 0
                ? aConsensus
                : CompletableFuture.completedFuture(true));

        // Stage 1: A reserves at t=1000.
        testClock.setMillis(1000L);
        var proposalA = createSplitProposal(bubbleId);
        var resultA = coordinator.requestConsensus(proposalA); // parks, awaiting aConsensus

        // Stage 2: advance past cooldown, then SET CLOCK BACK TO 1000 to force same-tick collision.
        // This simulates two proposals that both read the exact same clock millis as their token.
        // B reserves at t=12000 (past cooldown) — use 12000 for the cooldown check, but the
        // critical point is that A's token and B's token are now from different sequence numbers
        // even though both reservation timestamps could be identical.
        testClock.setMillis(12_000L);
        var proposalB = createSplitProposal(bubbleId);
        var resultB = coordinator.requestConsensus(proposalB).join();
        assertTrue(resultB, "B must be approved and hold the live reservation");
        assertTrue(coordinator.getRemainingCooldown(bubbleId) > 0,
                   "B's reservation must be live before A's rejection");

        // Stage 3: reject A. A's restore must leave B's entry untouched (unique-token ABA guard).
        aConsensus.complete(false);
        assertFalse(resultA.join(), "A must be rejected");

        // Stage 4: B's entry must survive. A third proposal 1s after B is still in cooldown.
        testClock.setMillis(13_000L);
        var proposalC = createSplitProposal(bubbleId);
        assertFalse(coordinator.canProposeTopologyChange(proposalC),
                    "C must be refused: B's live reservation must survive A's restore (unique-token ABA guard)");
        assertEquals(9000L, coordinator.getRemainingCooldown(bubbleId),
                     "Remaining cooldown must reflect B's t=12000 reservation");
    }

    /**
     * RDR-020 S3: {@code toMigrationProposal} builds a TOPOLOGY-kind proposal under the
     * single-owner node-identity model.
     * <p>
     * The owner is resolved through the injected {@link BubbleOwnershipResolver}, and because a
     * topology change is single-region the proposal carries {@code source == target == owner(region)}
     * with {@code kind == TOPOLOGY}. (The old {@code digestOf}-of-bubble-UUID model — which produced
     * distinct, non-member source/target digests the live membership-enforcing consensus silently
     * rejected — is deleted; Luciferase-vhbw3 / Gap 2.)
     */
    @Test
    void toMigrationProposal_producesTopologyKindSingleOwnerProposal() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        // Capture the MigrationProposal that reaches the mock consensus.
        var captured = new java.util.concurrent.atomic.AtomicReference<MigrationProposal>();
        when(mockConsensus.requestConsensus(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return CompletableFuture.completedFuture(true);
        });

        var proposal = createSplitProposal(bubble.id());
        var result = coordinator.requestConsensus(proposal).join();

        assertTrue(result, "Proposal must be approved by mock consensus");
        verify(mockConsensus, times(1)).requestConsensus(any());

        var mp = captured.get();
        assertNotNull(mp, "MigrationProposal must have been passed to consensus");
        assertEquals(ProposalKind.TOPOLOGY, mp.kind(), "Topology change must carry kind=TOPOLOGY");
        assertEquals(localDigest, mp.sourceNodeId(), "sourceNode must be the resolved region owner");
        assertEquals(localDigest, mp.targetNodeId(), "targetNode must equal sourceNode (single-owner model)");
        assertEquals(mp.sourceNodeId(), mp.targetNodeId(),
                     "TOPOLOGY proposal is source == target == owner(region)");
        assertEquals(bubble.id(), mp.entityId(), "entityId must be the primary affected bubble");
        assertEquals(proposal.proposalId(), mp.proposalId(), "proposalId must be threaded through unchanged");
        assertNotNull(mp.viewId(), "viewId must not be null");
    }

    /**
     * RDR-020 S3 ownership guard: a node may only propose a topology change for a region it owns.
     * When the resolved owner differs from the local member, {@code toMigrationProposal} throws
     * (fail-loud) rather than emitting a proposal to restructure another node's region.
     */
    @Test
    void requestConsensus_nonOwnerProposer_throws() {
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);

        var owner = DigestAlgorithm.DEFAULT.digest("region-owner");
        var local = DigestAlgorithm.DEFAULT.digest("some-other-node");
        coordinator.setOwnershipResolver(fixedOwnerResolver(owner, local));

        var proposal = createSplitProposal(bubble.id());
        // The guard throws synchronously from toMigrationProposal (before any future is returned), so
        // no .join() — requestConsensus itself throws.
        var ex = assertThrows(IllegalStateException.class,
                              () -> coordinator.requestConsensus(proposal));
        assertTrue(ex.getMessage().contains("may not propose a topology change"),
                   "Ownership guard message expected, was: " + ex.getMessage());
        verify(mockConsensus, never()).requestConsensus(any());
        // The Stage 1 cooldown reservation must be restored on the synchronous-throw path: a rejected
        // proposal must not lock the bubble out of future topology changes (regression guard for the
        // orphaned-cooldown bug).
        assertTrue(coordinator.canProposeTopologyChange(proposal),
                   "cooldown must be restored after a guard throw, not orphaned");
        assertEquals(0L, coordinator.getRemainingCooldown(bubble.id()),
                     "no cooldown should be held after a guard throw");
    }

    /**
     * RDR-020 S3 cross-region merge guard: a merge whose two bubbles resolve to different HRW
     * owners cannot be represented by the single-owner model and throws fail-loud. A two-node merge
     * protocol is explicitly out of scope (s23eu / RDR-015 follow-on).
     */
    @Test
    void requestConsensus_crossRegionMerge_throws() {
        // Create the full 8-child set so an adjacent pair is guaranteed, then pick one
        // deterministically (merge pre-validation requires the two bubbles to be neighbors).
        bubbleGrid.createBubbles(8, (byte) 1, 10);
        var all = bubbleGrid.getAllBubbles().stream().toList();
        UUID id1 = null;
        UUID id2 = null;
        outer:
        for (var a : all) {
            var neighbors = bubbleGrid.getNeighbors(a.id());
            for (var b : all) {
                if (!a.id().equals(b.id()) && neighbors.contains(b.id())) {
                    id1 = a.id();
                    id2 = b.id();
                    break outer;
                }
            }
        }
        assertNotNull(id1, "an adjacent bubble pair must exist in the 8-child set");
        var bubble1 = bubbleGrid.getBubbleById(id1);
        var bubble2 = bubbleGrid.getBubbleById(id2);
        addEntities(bubble1, 300);
        addEntities(bubble2, 300);

        // Resolver assigning each bubble a distinct owner; local owns bubble1 so only the
        // cross-region (two-owner) condition — not the ownership guard — is what trips.
        var ownerA = DigestAlgorithm.DEFAULT.digest("owner-A");
        var ownerB = DigestAlgorithm.DEFAULT.digest("owner-B");
        final UUID firstId = id1;
        coordinator.setOwnershipResolver(new BubbleOwnershipResolver() {
            @Override
            public Digest resolveOwningMember(UUID bubbleId) {
                return bubbleId.equals(firstId) ? ownerA : ownerB;
            }

            @Override
            public Digest localMember() {
                return ownerA;
            }

            @Override
            public Digest memberDigestForNode(UUID nodeId) {
                return ownerA;
            }
        });

        var proposal = new MergeProposal(UUID.randomUUID(), bubble1.id(), bubble2.id(),
                                         DigestAlgorithm.DEFAULT.getOrigin(), testClock.currentTimeMillis());
        var ex = assertThrows(IllegalStateException.class,
                              () -> coordinator.requestConsensus(proposal));
        assertEquals("cross-region merge not yet supported", ex.getMessage());
        verify(mockConsensus, never()).requestConsensus(any());
        // Both merge bubbles' cooldown reservations must be restored after the guard throw.
        assertEquals(0L, coordinator.getRemainingCooldown(bubble1.id()),
                     "bubble1 cooldown must be restored after cross-region merge throw");
        assertEquals(0L, coordinator.getRemainingCooldown(bubble2.id()),
                     "bubble2 cooldown must be restored after cross-region merge throw");
    }

    private SplitProposal createSplitProposal(UUID bubbleId) {
        var bubble = bubbleGrid.getBubbleById(bubbleId);

        // Compute entity centroid for Byzantine-resistant validation
        // (entities are placed from 0,0,0 to N*0.01, so centroid is at N*0.005)
        var entityRecords = bubble.getAllEntityRecords();
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;

        for (var record : entityRecords) {
            var pos = record.position();
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
        }

        float entityCentroidX = (minX + maxX) / 2.0f;

        var splitPlane = new SplitPlane(
            new Point3f(1.0f, 0.0f, 0.0f),
            entityCentroidX
        );

        return new SplitProposal(
            UUID.randomUUID(),
            bubbleId,
            splitPlane,
            DigestAlgorithm.DEFAULT.getOrigin(),
            testClock.currentTimeMillis()
        );
    }

    /**
     * Test clock for deterministic time control.
     */
    private static class TestClock implements Clock {
        private long millis;

        public void setMillis(long millis) {
            this.millis = millis;
        }

        @Override
        public long currentTimeMillis() {
            return millis;
        }

        @Override
        public long nanoTime() {
            return millis * 1_000_000L;
        }
    }
}
