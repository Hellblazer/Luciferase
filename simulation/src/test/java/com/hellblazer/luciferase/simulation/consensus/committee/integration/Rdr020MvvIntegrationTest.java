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
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubbleMigrationIntegration;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.consensus.committee.*;
import com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver;
import com.hellblazer.luciferase.simulation.consensus.ownership.FirefliesBubbleOwnershipResolver;
import com.hellblazer.luciferase.simulation.consensus.ownership.RendezvousOwnershipFunction;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.distributed.migration.OptimisticMigratorImpl;
import com.hellblazer.luciferase.simulation.distributed.network.BubbleNetworkChannel;
import com.hellblazer.luciferase.simulation.distributed.network.DistributedBubbleNode;
import com.hellblazer.luciferase.simulation.topology.SplitPlane;
import com.hellblazer.luciferase.simulation.topology.SplitProposal;
import com.hellblazer.luciferase.simulation.topology.TopologyConsensusCoordinator;
import com.hellblazer.luciferase.simulation.von.FirefliesMemberLookup;
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
 * RDR-020 Minimum Viable Validation (MVV) integration test.
 * <p>
 * Exercises the real wired path end-to-end: FirefliesBubbleOwnershipResolver (HRW, S1–S2),
 * TopologyConsensusCoordinator (S3), OptimisticMigratorImpl + OptimisticMigratorIntegration (S4),
 * DistributedBubbleNode hint validation (S5), ViewCommitteeConsensus active-only gate (S6).
 * <p>
 * Identity-alignment constraint (RDR-020 MVV requirement): the same member Digests must flow
 * through BOTH the Mockito DynamicContext stubs (for ViewCommitteeConsensus.validateProposal's
 * active() gate) AND the MockFirefliesView (for FirefliesBubbleOwnershipResolver's activeMembers()
 * path). One {@code List<MockMember> members} is shared between both sides.
 * <p>
 * Gap-2 coverage: Scenario 1b drives a cross-node ENTITY_MIGRATION whose target is a DIFFERENT
 * active member through the real committee to quorum — the path the old {@code digestOf} broke. The
 * S5 node-hint scenarios 6a–6c use inline stub resolvers to isolate {@code DistributedBubbleNode}'s
 * guard logic; 6d exercises the same guard through the REAL {@code FirefliesBubbleOwnershipResolver}
 * (canonical {@code memberDigestForNode} + {@code isActiveMember}).
 *
 * @author hal.hildebrand
 */
class Rdr020MvvIntegrationTest {

    // -------------------------------------------------------------------------
    // Shared state wired once per test
    // -------------------------------------------------------------------------

    private static final int MEMBER_COUNT = 3;

    private List<MockMember>              members;
    private DynamicContext<Member>        context;
    private ViewCommitteeSelector         selector;
    private CommitteeVotingProtocol       votingProtocol;
    private ViewCommitteeConsensus        consensus;
    private ScheduledViewMonitor          viewMonitor;
    private ScheduledExecutorService      scheduler;
    private Digest                        viewId;

    // Fixed deterministic clock
    private static final long FIXED_MILLIS = 1_000_000L;
    private final TestClock testClock = new TestClock(FIXED_MILLIS);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        viewId = DigestAlgorithm.DEFAULT.digest("mvv-view-id".getBytes());

        // Build members — MockMember from com.hellblazer.delos.membership.MockMember
        members = new ArrayList<>();
        for (int i = 0; i < MEMBER_COUNT; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }

        // Wire DynamicContext with active() returning every member
        // (identity-alignment: same member instances as MockFirefliesView below)
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(MEMBER_COUNT);
        when(context.toleranceLevel()).thenReturn(1); // t=1 → quorum=2
        var committee = new LinkedHashSet<Member>(members);
        when(context.bftSubset(any(Digest.class))).thenReturn(committee);
        when(context.allMembers()).thenAnswer(inv -> members.stream().map(m -> (Member) m));
        when(context.active()).thenAnswer(inv -> members.stream().map(m -> (Member) m));
        when(context.isActive(org.mockito.Mockito.any(com.hellblazer.delos.cryptography.Digest.class))).thenReturn(true);


        viewMonitor = new ScheduledViewMonitor(viewId);
        selector    = new ViewCommitteeSelector(context);

        var config = CommitteeConfig.defaultConfig();
        scheduler  = Executors.newScheduledThreadPool(2);
        votingProtocol = new CommitteeVotingProtocol(context, config, scheduler);

        consensus = new ViewCommitteeConsensus();
        consensus.setViewMonitor(viewMonitor);
        consensus.setCommitteeSelector(selector);
        consensus.setVotingProtocol(votingProtocol);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // Helper: build a real FirefliesBubbleOwnershipResolver
    // -------------------------------------------------------------------------

    /**
     * Builds a production {@link FirefliesBubbleOwnershipResolver} aligned with {@link #members}.
     * All members are added to the MockFirefliesView; {@code evicted} members are marked inactive.
     * {@code bubbleKeys} is the grid map (bubbleId → TetreeKey).
     */
    @SuppressWarnings("unchecked")
    private FirefliesBubbleOwnershipResolver buildResolver(
            MockMember localMember,
            List<MockMember> evicted,
            Map<UUID, TetreeKey<?>> bubbleKeys) {

        var mockView = new MockFirefliesView<MockMember>();
        for (var m : members) {
            mockView.addMember(m);
        }
        for (var m : evicted) {
            mockView.markInactive(m);
        }

        MembershipView<Member> membershipView =
            (MembershipView<Member>) (MembershipView<?>) mockView;

        return new FirefliesBubbleOwnershipResolver(
            () -> localMember,
            // nodeResolver: node UUID → Optional<Member> via the CANONICAL derivation (RDR-020 B4):
            // node UUID == FirefliesMemberLookup.digestToUuid(member.getId()), matching how
            // FirefliesMemberLookup.getMemberByUuid resolves the reverse mapping.
            nodeUuid -> members.stream()
                               .filter(m -> FirefliesMemberLookup.digestToUuid(m.getId()).equals(nodeUuid))
                               .map(m -> (Member) m)
                               .findFirst(),
            bubbleKeys::get,
            membershipView,
            new RendezvousOwnershipFunction()
        );
    }

    /** Helper: same as above but no evicted members. */
    private FirefliesBubbleOwnershipResolver buildResolver(
            MockMember localMember,
            Map<UUID, TetreeKey<?>> bubbleKeys) {
        return buildResolver(localMember, List.of(), bubbleKeys);
    }

    // -------------------------------------------------------------------------
    // Helper: add entities to a bubble for valid split (>5000)
    // -------------------------------------------------------------------------

    private void addEntities(EnhancedBubble bubble, int count) {
        for (int i = 0; i < count; i++) {
            bubble.addEntity(
                "e-" + i,
                new Point3f(i * 0.001f, i * 0.001f, i * 0.001f),
                null
            );
        }
    }

    // -------------------------------------------------------------------------
    // Helper: createSplitProposal following TopologyConsensusCoordinatorTest pattern
    // -------------------------------------------------------------------------

    private SplitProposal createSplitProposal(TetreeBubbleGrid grid, UUID bubbleId) {
        var bubble = grid.getBubbleById(bubbleId);

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        for (var record : bubble.getAllEntityRecords()) {
            var pos = record.position();
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
        }
        float centroidX = (minX + maxX) / 2.0f;

        var splitPlane = new SplitPlane(new Point3f(1.0f, 0.0f, 0.0f), centroidX);
        return new SplitProposal(UUID.randomUUID(), bubbleId, splitPlane, viewId, testClock.currentTimeMillis());
    }

    // -------------------------------------------------------------------------
    // Helper: record quorum votes for a proposal
    // -------------------------------------------------------------------------

    private void castQuorumVotes(MigrationProposal mp, int quorum) {
        for (int i = 0; i < quorum; i++) {
            votingProtocol.recordVote(new Vote(mp.proposalId(), members.get(i).getId(), true, viewId));
        }
    }

    // -------------------------------------------------------------------------
    // Scenario (1): TOPOLOGY path — committee reaches real quorum on a split proposal
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 1: TOPOLOGY split reaches real committee quorum through wired path")
    void topologySplitReachesRealQuorum() throws Exception {
        // t=1 → quorum=2 (toleranceLevel+1)
        int quorum = context.toleranceLevel() + 1;

        var bubbleGrid = new TetreeBubbleGrid((byte) 2);
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100);
        var bubbleId = bubble.id();

        // Determine the HRW owner for this bubble
        var bubbleKey = bubbleGrid.getKeyForBubble(bubbleId);
        assertNotNull(bubbleKey, "Grid must have a TetreeKey for the created bubble");

        // Probe ownership with any member as local — we care about the owner Digest
        Map<UUID, TetreeKey<?>> probeKeys = new HashMap<>();
        probeKeys.put(bubbleId, bubbleKey);
        var probeResolver = buildResolver(members.get(0), probeKeys);
        var ownerDigest = probeResolver.resolveOwningMember(bubbleId);

        // Find the MockMember whose Digest matches the HRW owner
        var ownerMember = members.stream()
                                 .filter(m -> m.getId().equals(ownerDigest))
                                 .findFirst()
                                 .orElseThrow(() -> new AssertionError(
                                     "Owner digest not found in member list: " + ownerDigest));

        // Build the real resolver with localMember == owner (so ownership guard passes)
        Map<UUID, TetreeKey<?>> resolverKeys = new HashMap<>();
        resolverKeys.put(bubbleId, bubbleKey);
        var resolver = buildResolver(ownerMember, resolverKeys);

        // Wire a real TopologyConsensusCoordinator with the real consensus and resolver
        var coordinator = new TopologyConsensusCoordinator(bubbleGrid);
        coordinator.setClock(testClock);
        coordinator.setConsensusProtocol(consensus);
        coordinator.setOwnershipResolver(resolver);

        var splitProposal = createSplitProposal(bubbleGrid, bubbleId);

        // Submit — should be pending (validateProposal passes: owner is active in both sides)
        var future = coordinator.requestConsensus(splitProposal);
        assertFalse(future.isDone(), "Future must be pending — proposal passed validateProposal, awaiting committee votes");

        // Capture the MigrationProposal that arrived at the consensus engine
        // (we need its proposalId to cast votes; record it before voting)
        // Cast quorum YES votes with real member Digests (in the active() set)
        for (int i = 0; i < quorum; i++) {
            // The MigrationProposal built by TopologyConsensusCoordinator.toMigrationProposal uses
            // the SplitProposal's proposalId as the MigrationProposal's proposalId.
            votingProtocol.recordVote(
                new Vote(splitProposal.proposalId(), members.get(i).getId(), true, viewId));
        }

        var approved = future.get(3, TimeUnit.SECONDS);
        assertTrue(approved, "Split proposal must be approved after real quorum YES votes");
    }

    // -------------------------------------------------------------------------
    // Scenario (1b): GAP-2 CLOSING — cross-node ENTITY_MIGRATION whose target bubble is owned
    // by a DIFFERENT current-view member passes validateProposal (isNodeInView for a NON-LOCAL
    // digest) and reaches real quorum. This is the exact path the old digestOf hash broke: it
    // produced a digest unrelated to any member, rejected at isNodeInView. The TOPOLOGY scenario
    // above can only ever exercise owner==local (single-owner guard), so it does NOT cover this.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 1b (Gap-2): cross-node ENTITY_MIGRATION to a DIFFERENT active owner reaches real quorum")
    void entityMigrationCrossNodeReachesRealQuorum() throws Exception {
        int quorum = context.toleranceLevel() + 1;
        var local = members.get(0);

        // Find a target bubble whose HRW owner is a DIFFERENT member than local. The owner depends
        // only on the TetreeKey + the (fixed) member digests, so the first qualifying key is
        // deterministic across runs.
        long[] candidateBits = {0x1L, 0x2L, 0x3L, 0x4L, 0x5L, 0x6L, 0x7L, 0x8L, 0x9L, 0xAL, 0xBL, 0xCL};
        UUID targetBubble = null;
        Digest ownerDigest = null;
        Map<UUID, TetreeKey<?>> resolverKeys = null;
        for (long bits : candidateBits) {
            var bid = UUID.nameUUIDFromBytes(("mvv-cross-node-" + bits).getBytes());
            Map<UUID, TetreeKey<?>> km = new HashMap<>();
            km.put(bid, TetreeKey.create((byte) 3, bits, 0L));
            var owner = buildResolver(local, km).resolveOwningMember(bid);
            if (!owner.equals(local.getId())) {
                targetBubble = bid;
                ownerDigest = owner;
                resolverKeys = km;
                break;
            }
        }
        assertNotNull(targetBubble, "must find a target bubble owned by a non-local member");
        assertNotEquals(local.getId(), ownerDigest, "precondition: owner must be a DIFFERENT member than local");

        var resolver = buildResolver(local, resolverKeys);

        // Wire the real S4 path: OptimisticMigratorImpl -> real OptimisticMigratorIntegration -> real consensus.
        var integration = new OptimisticMigratorIntegration(consensus, viewMonitor);
        integration.setClock(testClock);
        var migrator = new OptimisticMigratorImpl();
        migrator.setConsensusIntegration(integration);
        migrator.setOwnershipResolver(resolver);

        var entityId = UUID.randomUUID();
        var future = migrator.requestMigrationApproval(entityId, targetBubble);

        // The integration built the proposal synchronously before submitting; read it to vote.
        var mp = integration.getLastProposal();
        assertNotNull(mp, "integration must have built a MigrationProposal");
        assertEquals(local.getId(), mp.sourceNodeId(), "source must be the local member (possession)");
        assertEquals(ownerDigest, mp.targetNodeId(), "target must be the HRW owner — a DIFFERENT member");
        assertNotEquals(mp.sourceNodeId(), mp.targetNodeId(), "cross-node: source != target");

        assertFalse(future.isDone(),
            "future must be pending — a non-local target digest PASSED isNodeInView and entered voting "
            + "(this is the Gap-2 path the old digestOf broke)");

        // Cast real quorum YES votes on the integration-generated proposalId.
        for (int i = 0; i < quorum; i++) {
            votingProtocol.recordVote(new Vote(mp.proposalId(), members.get(i).getId(), true, viewId));
        }
        assertTrue(future.get(3, TimeUnit.SECONDS),
            "cross-node ENTITY_MIGRATION must reach real quorum and be approved");
    }

    // -------------------------------------------------------------------------
    // Scenario (2): HRW convergence — two independent resolvers return identical owner
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 2: Two independent resolvers over same active set return identical HRW owner")
    void hrwConvergenceTwoIndependentResolvers() {
        // Build several (bubbleId, TetreeKey) pairs
        Map<UUID, TetreeKey<?>> keys = new HashMap<>();
        keys.put(UUID.randomUUID(), TetreeKey.create((byte) 3, 0xCAFEBABE_DEADBEEFL, 0L));
        keys.put(UUID.randomUUID(), TetreeKey.create((byte) 5, 0x1234567890ABCDEFL, 0L));
        keys.put(UUID.randomUUID(), TetreeKey.create((byte) 7, 0xFEEDFACE0BAD_C0DEL, 0L));

        // Two completely independent resolver instances — same members, same keys, no shared state
        var resolver1 = buildResolver(members.get(0), new HashMap<>(keys));
        var resolver2 = buildResolver(members.get(1), new HashMap<>(keys));

        for (var entry : keys.entrySet()) {
            var bubbleId = entry.getKey();
            var owner1 = resolver1.resolveOwningMember(bubbleId);
            var owner2 = resolver2.resolveOwningMember(bubbleId);
            assertEquals(owner1, owner2,
                "Two independent HRW resolvers must agree on owner for bubble " + bubbleId
                + " — this is the view-derived registry-substitute property");
            // Falsifying check: result must be a real member in the active set
            var activeDigests = members.stream().map(MockMember::getId).toList();
            assertTrue(activeDigests.contains(owner1),
                "Resolved owner must be in the active member set");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario (3): Single-member view — ENTITY_MIGRATION self-migration rejected
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 3: ENTITY_MIGRATION source==target is rejected by validateProposal (cross-node semantics)")
    void entityMigrationSelfMigrationRejectedBySingleMemberView() throws Exception {
        // Use only one member in the context to represent single-member view
        var soloMember = members.get(0);
        @SuppressWarnings("unchecked")
        DynamicContext<Member> soloContext = Mockito.mock(DynamicContext.class);
        when(soloContext.size()).thenReturn(1);
        when(soloContext.toleranceLevel()).thenReturn(0); // t=0 → quorum=1
        var soloCommittee = new LinkedHashSet<Member>(List.of(soloMember));
        when(soloContext.bftSubset(any(Digest.class))).thenReturn(soloCommittee);
        when(soloContext.allMembers()).thenAnswer(inv -> Stream.of((Member) soloMember));
        when(soloContext.active()).thenAnswer(inv -> Stream.of((Member) soloMember));
        when(soloContext.isActive(org.mockito.Mockito.any(com.hellblazer.delos.cryptography.Digest.class))).thenReturn(true);


        var soloSelector = new ViewCommitteeSelector(soloContext);
        var soloProtocol = new CommitteeVotingProtocol(soloContext, CommitteeConfig.defaultConfig(),
            scheduler);
        var soloConsensus = new ViewCommitteeConsensus();
        soloConsensus.setViewMonitor(viewMonitor);
        soloConsensus.setCommitteeSelector(soloSelector);
        soloConsensus.setVotingProtocol(soloProtocol);

        // Build ENTITY_MIGRATION proposal with source == target == the one member
        var soloDigest = soloMember.getId();
        var migrationProposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),     // entityId
            soloDigest,           // source == the one member
            soloDigest,           // target == the one member (self-migration)
            viewId,
            testClock.currentTimeMillis()
            // kind defaults to ENTITY_MIGRATION via the 6-arg ctor
        );

        var future = soloConsensus.requestConsensus(migrationProposal);

        // validateProposal rejects source==target for ENTITY_MIGRATION synchronously
        // future must be done and false (not pending, not approved)
        assertTrue(future.isDone(),
            "Future must be immediately done — validateProposal rejects self-migration synchronously");
        assertFalse(future.get(1, TimeUnit.SECONDS),
            "Self-migration ENTITY_MIGRATION proposal must be rejected (cross-node-only semantics)");
    }

    // -------------------------------------------------------------------------
    // Scenario (4a): Fail-loud on unresolvable bubble — TopologyConsensusCoordinator
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 4a: SplitProposal for bubble not in resolver's key map throws (fail-loud, no silent approve)")
    void topologyUnresolvableBubbleThrows() {
        var bubbleGrid = new TetreeBubbleGrid((byte) 2);
        bubbleGrid.createBubbles(1, (byte) 1, 10);
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        addEntities(bubble, 5100); // valid entity count so SplitProposal.validate() passes

        // Build resolver with an EMPTY key map — the bubble EXISTS in the grid (validate passes)
        // but the resolver has no key entry for it, so resolveOwningMember throws IllegalStateException.
        var emptyResolver = buildResolver(members.get(0), Map.of());

        var coordinator = new TopologyConsensusCoordinator(bubbleGrid);
        coordinator.setClock(testClock);
        coordinator.setConsensusProtocol(consensus);
        coordinator.setOwnershipResolver(emptyResolver);

        var splitProposal = createSplitProposal(bubbleGrid, bubble.id());

        // resolveOwningMember throws IllegalStateException because bubbleKeyResolver has no entry
        assertThrows(IllegalStateException.class,
            () -> coordinator.requestConsensus(splitProposal),
            "requestConsensus for bubble absent from resolver key map must throw fail-loud — no silent approve");
    }

    // -------------------------------------------------------------------------
    // Scenario (4b): Fail-loud on unresolvable bubble — OptimisticMigratorImpl path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 4b: OptimisticMigratorImpl.requestMigrationApproval for unknown bubble throws (no silent approve)")
    void optimisticMigratorUnresolvableTargetThrows() {
        // Build resolver with empty bubble grid — resolveOwningMember(unknownBubble) throws
        var emptyResolver = buildResolver(members.get(0), Map.of());

        var migrator = new OptimisticMigratorImpl();
        migrator.setConsensusIntegration(new OptimisticMigratorIntegration(consensus, viewMonitor));
        migrator.setOwnershipResolver(emptyResolver);

        var entityId = UUID.randomUUID();
        var unknownBubble = UUID.randomUUID();

        // resolveOwningMember(unknownBubble) throws because emptyResolver has no key for it
        assertThrows(IllegalStateException.class,
            () -> migrator.requestMigrationApproval(entityId, unknownBubble),
            "requestMigrationApproval for unresolvable target bubble must throw — no silent approve");
    }

    // -------------------------------------------------------------------------
    // Scenario (5): Active-only ownership invariant via MockFirefliesView
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 5: Evicted-but-not-GC'd member is never owner; isActiveMember(evicted)==false")
    void activeOnlyOwnershipEvictedMemberNeverReturned() {
        // Add an evicted member beyond the base set (not in the DynamicContext stubs)
        var evictedDigest = DigestAlgorithm.DEFAULT.digest("evicted-mvv-member".getBytes());
        var evictedMember = new MockMember(evictedDigest);

        // Add evictedMember to members for the MockFirefliesView but mark it inactive
        members.add(evictedMember);

        var bubbleId = UUID.randomUUID();
        TetreeKey<?> key = TetreeKey.create((byte) 3, 0xDEAD_C0DE_CAFE_1234L, 0L);
        Map<UUID, TetreeKey<?>> initialKeys = new HashMap<>();
        initialKeys.put(bubbleId, key);

        var resolver = buildResolver(members.get(0), List.of(evictedMember), initialKeys);

        // isActiveMember must return false for the evicted member
        assertFalse(resolver.isActiveMember(evictedDigest),
            "resolver.isActiveMember(evictedDigest) must return false — evicted member not active");

        // resolveOwningMember must never return the evicted digest across many keys
        var activeDigests = members.subList(0, members.size() - 1)
                                   .stream()
                                   .map(MockMember::getId)
                                   .toList();
        for (int i = 0; i < 20; i++) {
            TetreeKey<?> testKey = TetreeKey.create((byte) 3, (long) (i * 0xABCDEF13L + 3), 0L);
            var testBubble = UUID.randomUUID();
            Map<UUID, TetreeKey<?>> testKeys = new HashMap<>();
            testKeys.put(testBubble, testKey);
            var testResolver = buildResolver(members.get(0), List.of(evictedMember), testKeys);

            var owner = testResolver.resolveOwningMember(testBubble);
            assertNotEquals(evictedDigest, owner,
                "Evicted-but-inactive member must never be returned as owner (key " + i + ")");
            assertTrue(activeDigests.contains(owner),
                "Owner must be in the active (non-evicted) member set (key " + i + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario (6): Node-UUID hint validation in DistributedBubbleNode (S5 guard)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 6a: DistributedBubbleNode — canonical active node UUID passes hint validation")
    void distributedBubbleNodeActiveNodePasses() {
        var targetNodeId = UUID.randomUUID();
        var targetDigest = members.get(1).getId();
        var entityId = UUID.randomUUID();

        // Inline resolver for S5 guard: targetNodeId maps to an active member digest
        var hintResolver = stubHintResolver(targetNodeId, targetDigest, true);

        var node = buildDistributedBubbleNode(hintResolver);
        var initiated = node.initiateRemoteMigration(entityId, targetNodeId);

        assertTrue(initiated, "Active canonical node UUID must pass S5 hint validation and initiate migration");
    }

    @Test
    @DisplayName("Scenario 6b: DistributedBubbleNode — unknown node UUID throws (fail-loud)")
    void distributedBubbleNodeUnknownNodeThrows() {
        var unknownNodeId = UUID.randomUUID();
        var entityId = UUID.randomUUID();

        // Resolver throws for any unknown UUID — matches FirefliesBubbleOwnershipResolver behavior
        var hintResolver = new BubbleOwnershipResolver() {
            @Override
            public Digest resolveOwningMember(UUID bubbleId) {
                throw new UnsupportedOperationException("not used in node-hint test");
            }
            @Override
            public Digest localMember() {
                return members.get(0).getId();
            }
            @Override
            public Digest memberDigestForNode(UUID nodeId) {
                throw new IllegalStateException("No Fireflies member found for node UUID: " + nodeId);
            }
            @Override
            public boolean isActiveMember(Digest member) {
                return false;
            }
        };

        var node = buildDistributedBubbleNode(hintResolver);

        assertThrows(IllegalStateException.class,
            () -> node.initiateRemoteMigration(entityId, unknownNodeId),
            "Unknown node UUID must throw — no silent migration toward non-member");
    }

    @Test
    @DisplayName("Scenario 6c: DistributedBubbleNode — inactive (evicted) node UUID throws")
    void distributedBubbleNodeInactiveNodeThrows() {
        var evictedNodeId = UUID.randomUUID();
        var evictedDigest = DigestAlgorithm.DEFAULT.digest("evicted-node-mvv".getBytes());
        var entityId = UUID.randomUUID();

        // Resolver resolves the UUID but returns inactive
        var hintResolver = stubHintResolver(evictedNodeId, evictedDigest, false);

        var node = buildDistributedBubbleNode(hintResolver);

        var ex = assertThrows(IllegalStateException.class,
            () -> node.initiateRemoteMigration(entityId, evictedNodeId),
            "Evicted (inactive) node must throw — cannot migrate to non-active-view member");
        assertTrue(ex.getMessage().contains("not a current-view active member"),
            "Exception message must name inactive-member cause, was: " + ex.getMessage());
    }

    @Test
    @DisplayName("Scenario 6d: DistributedBubbleNode S5 guard through the REAL resolver — canonical active node UUID passes")
    void distributedBubbleNodeRealResolverActiveNodePasses() {
        // Exercise the REAL FirefliesBubbleOwnershipResolver chain (memberDigestForNode via the
        // canonical digestToUuid lambda + isActiveMember via MockFirefliesView.activeMembers) through
        // DistributedBubbleNode, not an inline stub. The node UUID is canonically derived from an
        // active member's digest (RDR-020 B4).
        var targetMember = members.get(1);
        var canonicalNodeId = FirefliesMemberLookup.digestToUuid(targetMember.getId());
        var resolver = buildResolver(members.get(0), Map.of()); // node-UUID path only; grid unused

        // Sanity: the real resolver resolves the canonical UUID to the member and reports it active.
        assertEquals(targetMember.getId(), resolver.memberDigestForNode(canonicalNodeId),
            "real memberDigestForNode must resolve the canonical node UUID to its member digest");
        assertTrue(resolver.isActiveMember(targetMember.getId()),
            "real isActiveMember must report the active member as active");

        var node = buildDistributedBubbleNode(resolver);
        assertTrue(node.initiateRemoteMigration(UUID.randomUUID(), canonicalNodeId),
            "canonical active node UUID must pass S5 hint validation through the real resolver");
    }

    // -------------------------------------------------------------------------
    // Private helpers for Scenario (6)
    // -------------------------------------------------------------------------

    private BubbleOwnershipResolver stubHintResolver(UUID nodeId, Digest digest, boolean active) {
        return new BubbleOwnershipResolver() {
            @Override
            public Digest resolveOwningMember(UUID bubbleId) {
                throw new UnsupportedOperationException("not used in node-hint test");
            }
            @Override
            public Digest localMember() {
                return members.get(0).getId();
            }
            @Override
            public Digest memberDigestForNode(UUID id) {
                if (!id.equals(nodeId)) {
                    throw new IllegalStateException("No Fireflies member found for node UUID: " + id);
                }
                return digest;
            }
            @Override
            public boolean isActiveMember(Digest member) {
                return active && member.equals(digest);
            }
        };
    }

    private DistributedBubbleNode buildDistributedBubbleNode(BubbleOwnershipResolver resolver) {
        var bubble           = new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
        var networkChannel   = Mockito.mock(BubbleNetworkChannel.class);
        when(networkChannel.isNodeReachable(any())).thenReturn(true);
        var migrationInteg   = Mockito.mock(EnhancedBubbleMigrationIntegration.class);
        var migrator         = new OptimisticMigratorImpl();
        when(migrationInteg.getOptimisticMigrator()).thenReturn(migrator);
        var fsm              = Mockito.mock(EntityMigrationStateMachine.class);

        var node = new DistributedBubbleNode(UUID.randomUUID(), bubble, networkChannel, migrationInteg, fsm);
        node.setOwnershipResolver(resolver);
        return node;
    }

    // -------------------------------------------------------------------------
    // Inner helpers
    // -------------------------------------------------------------------------

    /** Deterministic clock backed by a mutable long. */
    private static class TestClock implements Clock {
        private long millis;

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
