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

package com.hellblazer.luciferase.simulation.consensus.committee.integration;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.consensus.committee.*;
import com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.topology.SplitPlane;
import com.hellblazer.luciferase.simulation.topology.SplitProposal;
import com.hellblazer.luciferase.simulation.topology.TopologyConsensusCoordinator;
import com.hellblazer.luciferase.simulation.von.FirefliesMemberLookup;
import com.hellblazer.luciferase.simulation.von.NodeBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * RDR-022 Minimum Viable Validation (MVV) integration test (Luciferase-0frcy.136.2).
 * <p>
 * Proves the bootstrap-assembled ownership resolver closes the production gap end-to-end against a
 * test-assembled node: the resolver is obtained from
 * {@link NodeBootstrap#assembleOwnershipResolver} (the RDR-022 factory — never a bare {@code new}),
 * backed by a real {@code TetreeBubbleGrid}, a {@code MockFirefliesView} with ≥2 members, and a
 * real {@code TopologyConsensusCoordinator} + {@code ViewCommitteeConsensus} — not mocked at the
 * resolution boundary.
 * <p>
 * Gate-remediated assertions (RDR-022 gate 2026-06-10):
 * <ol>
 *   <li><b>Non-vacuous supplier threading</b> — {@code localMember()} is the digest of the
 *       <em>specific</em> member passed to the factory (distinguished from a sibling member), and
 *       the canonical node-UUID chain round-trips through the resolver's {@code nodeResolver} seam
 *       ({@code resolveNodeId(owner) → memberDigestForNode → owner.getId()}).</li>
 *   <li><b>Negative control then gap closure</b> — the same scenario first throws
 *       {@code "ownershipResolver not set"} on an uninjected coordinator, then passes
 *       {@code validateProposal} and reaches real committee quorum once the factory-assembled
 *       resolver is injected. The <b>HRW probe step</b> discovers which member owns the bubble's
 *       region so the ownership guard passes (pattern: {@code Rdr020MvvIntegrationTest}).</li>
 *   <li><b>Fail-loud survives wiring</b> — an unresolvable bubble still throws through the
 *       factory-assembled resolver.</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
class Rdr022MvvIntegrationTest {

    private static final int MEMBER_COUNT = 3;
    private static final long FIXED_MILLIS = 1_000_000L;

    private List<MockMember>         members;
    private DynamicContext<Member>   context;
    private CommitteeVotingProtocol  votingProtocol;
    private ViewCommitteeConsensus   consensus;
    private ScheduledExecutorService scheduler;
    private Digest                   viewId;

    private final TestClock testClock = new TestClock(FIXED_MILLIS);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        viewId = DigestAlgorithm.DEFAULT.digest("rdr022-mvv-view-id".getBytes());

        members = new ArrayList<>();
        for (int i = 0; i < MEMBER_COUNT; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }

        // Identity alignment (RDR-020 MVV lesson): the same member digests flow through BOTH the
        // DynamicContext stubs (ViewCommitteeConsensus active() gate) AND the MockFirefliesView
        // (the resolver's activeMembers() path).
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(MEMBER_COUNT);
        when(context.toleranceLevel()).thenReturn(1); // t=1 → quorum=2
        when(context.bftSubset(any(Digest.class))).thenReturn(new LinkedHashSet<Member>(members));
        when(context.allMembers()).thenAnswer(inv -> members.stream().map(m -> (Member) m));
        when(context.active()).thenAnswer(inv -> members.stream().map(m -> (Member) m));
        when(context.isActive(any(Digest.class))).thenReturn(true);

        scheduler = Executors.newScheduledThreadPool(2);
        votingProtocol = new CommitteeVotingProtocol(context, CommitteeConfig.defaultConfig(), scheduler);

        consensus = new ViewCommitteeConsensus();
        consensus.setViewMonitor(new ScheduledViewMonitor(viewId));
        consensus.setCommitteeSelector(new ViewCommitteeSelector(context));
        consensus.setVotingProtocol(votingProtocol);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Assembles the resolver through the RDR-022 bootstrap factory (narrow-seam form — the
     * no-live-View MVV path, RDR-022 A3). This is the factory under test: the MVV never calls
     * {@code new FirefliesBubbleOwnershipResolver} directly.
     */
    private BubbleOwnershipResolver assembleViaBootstrap(MockMember localMember, TetreeBubbleGrid grid) {
        var mockView = new MockFirefliesView<Member>();
        for (var m : members) {
            mockView.addMember(m);
        }
        return NodeBootstrap.assembleOwnershipResolver(
            () -> localMember,
            // Canonical node-UUID → Member resolution (RDR-020 B4)
            nodeUuid -> members.stream()
                               .filter(m -> FirefliesMemberLookup.digestToUuid(m.getId()).equals(nodeUuid))
                               .map(m -> (Member) m)
                               .findFirst(),
            grid::getKeyForBubble,
            mockView);
    }

    private void addEntities(EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            bubble.addEntity("e-" + i, new Point3f(i * 0.001f, i * 0.001f, i * 0.001f), null);
        }
    }

    private SplitProposal createSplitProposal(TetreeBubbleGrid grid, UUID bubbleId) {
        var bubble = grid.getBubbleById(bubbleId);
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        for (var record : bubble.getAllEntityRecords()) {
            var pos = record.position();
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
        }
        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), (minX + maxX) / 2.0f);
        return new SplitProposal(UUID.randomUUID(), bubbleId, splitPlane, viewId,
                                 testClock.currentTimeMillis());
    }

    // -------------------------------------------------------------------------
    // MVV
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("MVV: previously-dead topology path reaches committee quorum once the factory-assembled resolver is injected")
    void factoryAssembledResolverClosesTheProductionGap() throws Exception {
        var grid = new TetreeBubbleGrid((byte) 2);
        grid.createBubbles(1, (byte) 1, 10);
        var bubble = grid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);
        var bubbleId = bubble.id();
        assertNotNull(grid.getKeyForBubble(bubbleId), "Grid must hold a TetreeKey for the bubble");

        // --- Negative control (gate observation 3): the SAME scenario against an UNINJECTED
        // coordinator throws — proving the gap exists and is closed BY the factory, not absent.
        var bareCoordinator = new TopologyConsensusCoordinator(grid);
        bareCoordinator.setClock(testClock);
        bareCoordinator.setConsensusProtocol(consensus);
        var negativeProposal = createSplitProposal(grid, bubbleId);
        var unset = assertThrows(IllegalStateException.class,
                                 () -> bareCoordinator.requestConsensus(negativeProposal));
        assertTrue(unset.getMessage().contains("ownershipResolver not set"),
                   "Baseline must be the unset-resolver fail-loud, was: " + unset.getMessage());

        // --- HRW probe step (gate finding S1): discover which member owns the bubble's region so
        // the real resolver's local member passes the ownership guard (single-owner TOPOLOGY model).
        var probeResolver = assembleViaBootstrap(members.get(0), grid);
        var ownerDigest = probeResolver.resolveOwningMember(bubbleId);
        var ownerMember = members.stream()
                                 .filter(m -> m.getId().equals(ownerDigest))
                                 .findFirst()
                                 .orElseThrow(() -> new AssertionError(
                                     "HRW owner not in member list: " + ownerDigest));

        // --- Factory-assembled resolver with localMember == HRW owner.
        var resolver = assembleViaBootstrap(ownerMember, grid);

        // (1) Non-vacuous supplier threading (gate finding S2): localMember() is the SPECIFIC
        // member passed — distinguished from a sibling — and the canonical node-UUID chain
        // round-trips through the resolver's nodeResolver seam in both directions.
        assertEquals(ownerMember.getId(), resolver.localMember(),
                     "localMember() must be the specific member assembled into the factory");
        var sibling = members.stream().filter(m -> !m.getId().equals(ownerDigest)).findFirst().orElseThrow();
        assertNotEquals(sibling.getId(), resolver.localMember());
        var ownerNodeUuid = NodeBootstrap.resolveNodeId(ownerMember);
        assertEquals(ownerMember.getId(), resolver.memberDigestForNode(ownerNodeUuid),
                     "Canonical nodeId → member digest must round-trip through the assembled seam");

        // (2) Gap closure: inject the factory-assembled resolver; the same scenario now passes
        // validateProposal (pending, awaiting votes) and reaches real committee quorum.
        var coordinator = new TopologyConsensusCoordinator(grid);
        coordinator.setClock(testClock);
        coordinator.setConsensusProtocol(consensus);
        coordinator.setOwnershipResolver(resolver);

        var proposal = createSplitProposal(grid, bubbleId);
        var future = coordinator.requestConsensus(proposal);
        assertFalse(future.isDone(),
                    "Proposal must be pending — validateProposal passed, awaiting committee votes");

        int quorum = context.toleranceLevel() + 1;
        for (int i = 0; i < quorum; i++) {
            votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(i).getId(), true, viewId));
        }
        assertTrue(future.get(3, TimeUnit.SECONDS),
                   "Topology proposal must be approved after real quorum votes — the previously-dead "
                   + "path is live through the bootstrap-assembled resolver");
    }

    @Test
    @DisplayName("MVV: fail-loud contract survives factory wiring — unresolvable bubble still throws")
    void failLoudSurvivesFactoryWiring() {
        var grid = new TetreeBubbleGrid((byte) 2);
        var resolver = assembleViaBootstrap(members.get(0), grid);

        var unknownBubble = UUID.randomUUID();
        var ex = assertThrows(IllegalStateException.class,
                              () -> resolver.resolveOwningMember(unknownBubble),
                              "A bubble with no grid key must throw — never a silently-rejectable digest");
        assertTrue(ex.getMessage().contains(unknownBubble.toString()),
                   "Fail-loud message must identify the unresolvable bubble");
    }

    // -------------------------------------------------------------------------
    // Inner helpers (pattern: Rdr020MvvIntegrationTest)
    // -------------------------------------------------------------------------

    /** Deterministic clock backed by a mutable long. */
    private static class TestClock implements Clock {
        private final long millis;

        TestClock(long millis) { this.millis = millis; }

        @Override public long currentTimeMillis() { return millis; }
        @Override public long nanoTime() { return millis * 1_000_000L; }
    }

    /** Minimal FirefliesViewMonitor stand-in providing a fixed viewId. */
    private static class ScheduledViewMonitor extends FirefliesViewMonitor {
        private final Digest currentViewId;

        ScheduledViewMonitor(Digest viewId) {
            super(new NoopMembershipView());
            this.currentViewId = viewId;
        }

        @Override
        public Digest getCurrentViewId() { return currentViewId; }
    }

    /** No-op MembershipView used only to satisfy FirefliesViewMonitor's constructor. */
    private static class NoopMembershipView implements MembershipView<Member> {
        @Override public void addListener(Consumer<MembershipView.ViewChange<Member>> listener) {}
        @Override public Stream<Member> getMembers() { return Stream.empty(); }
        @Override public Stream<Member> activeMembers() { return Stream.empty(); }
    }
}
