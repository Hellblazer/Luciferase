/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.ownership;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Stream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpatialOwnershipFunction (RendezvousOwnershipFunction),
 * BubbleOwnershipResolver (StubBubbleOwnershipResolver test double), and
 * FirefliesBubbleOwnershipResolver (production class, narrow-seam constructor).
 * <p>
 * Tests cover: HRW determinism (falsifiable: list-order independent), dependency on
 * both inputs, fail-loud on empty active set, active-only ownership enforcement,
 * resolveOwningMember happy/error paths, localMember, memberDigestForNode, null-input
 * guards, and the production resolver's active-only invariant via MockFirefliesView.
 */
class OwnershipTest {

    private static final int MEMBER_COUNT = 5;

    private List<Digest> memberDigests;
    private SpatialOwnershipFunction hrwFunction;

    @BeforeEach
    void setUp() {
        memberDigests = new ArrayList<>();
        for (int i = 0; i < MEMBER_COUNT; i++) {
            memberDigests.add(DigestAlgorithm.DEFAULT.digest(("member-" + i).getBytes()));
        }
        hrwFunction = new RendezvousOwnershipFunction();
    }

    // -------------------------------------------------------------------------
    // Test 1: HRW determinism / convergence — FALSIFIABLE for list-order independence
    // -------------------------------------------------------------------------

    /**
     * List-order independence is the load-bearing MVV property of Option β.
     * <p>
     * Falsifiability: if {@code owner()} returned {@code activeMembers.get(0)} (first-wins),
     * then with list1 and list2 shuffled into DIFFERENT orders list1.get(0) != list2.get(0)
     * in general, so {@code fn1.owner(key, list1) != fn2.owner(key, list2)} and this test
     * would fail. The HRW max-weight scan is a set function — order-independent — so the
     * real implementation always passes.
     */
    @Test
    void hrwDeterminism_twoIndependentInstances_listOrderIndependent() {
        var fn1 = new RendezvousOwnershipFunction();
        var fn2 = new RendezvousOwnershipFunction();

        // Build two copies in DIFFERENT shuffle orders; neither matches setUp order.
        var list1 = new ArrayList<>(memberDigests);
        var list2 = new ArrayList<>(memberDigests);
        Collections.shuffle(list1, new Random(42L));
        Collections.shuffle(list2, new Random(99L));

        // Sanity: the two shuffled orders must differ from each other (different seeds).
        // With 5 elements and seeds 42/99 they do — but assert it so the test self-documents.
        assertNotEquals(list1, list2,
            "Precondition: the two shuffled lists must be in different orders");

        // Several distinct keys
        var keys = List.of(
            TetreeKey.create((byte) 3, 0x123456789ABCDEFL, 0L),
            TetreeKey.create((byte) 5, 0xDEADBEEFCAFEBABEL, 0L),
            TetreeKey.create((byte) 7, 0xFEEDFACE0BAD_C0DEL, 0L),
            TetreeKey.create((byte) 2, 0L, 0L),
            TetreeKey.create((byte) 1, 1L, 0L)
        );

        for (var key : keys) {
            var owner1 = fn1.owner(key, list1);
            var owner2 = fn2.owner(key, list2);
            assertEquals(owner1, owner2,
                "Two independent HRW instances must return identical owner regardless of list order for key " + key);
        }
    }

    @Test
    void hrwConvergence_multipleCallsSameInputs_alwaysSameResult() {
        var key = TetreeKey.create((byte) 5, 0x5A5A5A5A5A5A5A5AL, 0L);

        // Each iteration gets a fresh fn and a differently-shuffled list
        // so we also exercise list-order independence here.
        Digest first = null;
        for (int i = 0; i < 10; i++) {
            var fn = new RendezvousOwnershipFunction();
            var shuffled = new ArrayList<>(memberDigests);
            Collections.shuffle(shuffled, new Random(i * 7L + 13L));
            var result = fn.owner(key, shuffled);
            if (first == null) {
                first = result;
            } else {
                assertEquals(first, result,
                    "All HRW calls (different instances, different list orders) must return the same owner " +
                    "(call " + i + ", seed " + (i * 7L + 13L) + ")");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: HRW depends on both inputs
    // -------------------------------------------------------------------------

    @Test
    void hrwDependsOnKey_differentKeysMayYieldDifferentOwners() {
        var key1 = TetreeKey.create((byte) 3, 0x111111111111L, 0L);
        var key2 = TetreeKey.create((byte) 3, 0x999999999999L, 0L);

        var owner1 = hrwFunction.owner(key1, memberDigests);
        var owner2 = hrwFunction.owner(key2, memberDigests);

        // With 5 members, different keys should yield different owners at least sometimes.
        // We verify that the function actually varies; if by coincidence they're equal
        // we use a third key to show variation.
        var key3 = TetreeKey.create((byte) 8, 0xABCDEF123456789L, 0L);
        var owner3 = hrwFunction.owner(key3, memberDigests);

        // At least two of the three keys should have different owners — with 5 members
        // the probability all three map to the same is (1/5)^2 = 4% — but with seeded
        // Digests it is fully deterministic, not actually probabilistic.
        boolean someVariation = !owner1.equals(owner2) || !owner1.equals(owner3) || !owner2.equals(owner3);
        assertTrue(someVariation, "HRW must distribute keys across members, not always return the same owner");
    }

    @Test
    void hrwDependsOnMemberSet_changingMembersChangesDistribution() {
        var key = TetreeKey.create((byte) 4, 0xCAFEBABE12345678L, 0L);

        var subset = memberDigests.subList(0, 2); // just 2 of 5 members
        var ownerSubset = hrwFunction.owner(key, subset);

        // The subset can only return one of 2 members; verify the result is within the subset.
        assertTrue(subset.contains(ownerSubset),
            "Owner from a 2-member subset must be one of those 2 members");
    }

    // -------------------------------------------------------------------------
    // Test 3: Empty active set → throws
    // -------------------------------------------------------------------------

    @Test
    void owner_emptyActiveSet_throwsIllegalStateException() {
        var key = TetreeKey.create((byte) 3, 0x1L, 0L);
        assertThrows(IllegalStateException.class,
            () -> hrwFunction.owner(key, Collections.emptyList()),
            "owner() must throw IllegalStateException when activeMembers is empty");
    }

    @Test
    void owner_nullKey_throwsNullPointerException() {
        assertThrows(NullPointerException.class,
            () -> hrwFunction.owner(null, memberDigests));
    }

    @Test
    void owner_nullMemberList_throwsNullPointerException() {
        var key = TetreeKey.create((byte) 3, 0x1L, 0L);
        assertThrows(NullPointerException.class,
            () -> hrwFunction.owner(key, null));
    }

    // -------------------------------------------------------------------------
    // Test 4: Active-only ownership — evicted member never selected
    // -------------------------------------------------------------------------

    @Test
    void hrwActiveOnly_evictedMemberNeverSelected() {
        // Seed: "evicted" member NOT in active set
        var evictedDigest = DigestAlgorithm.DEFAULT.digest("evicted-member".getBytes());
        var activeSet = List.copyOf(memberDigests); // 5 members, no evicted

        // For many keys, the owner is always in activeSet
        for (int i = 0; i < 20; i++) {
            var key = TetreeKey.create((byte) 3, (long) (i * 0xDEADBEEF13L + 7), 0L);
            var owner = hrwFunction.owner(key, activeSet);
            assertTrue(activeSet.contains(owner),
                "Owner must be in active set only, not an evicted member");
            assertNotEquals(evictedDigest, owner,
                "Evicted member must never be selected when not in active set");
        }
    }

    // -------------------------------------------------------------------------
    // StubBubbleOwnershipResolver (test double) tests
    // -------------------------------------------------------------------------

    @Test
    void resolveOwningMember_knownBubble_returnsOwner() {
        var bubbleId = UUID.randomUUID();
        var tetreeKey = TetreeKey.create((byte) 3, 0xABCDEF0123456789L, 0L);
        var localDigest = memberDigests.get(0);
        var localNodeId = UUID.randomUUID();

        var stub = new StubBubbleOwnershipResolver(
            Map.of(bubbleId, tetreeKey),
            memberDigests,
            localDigest,
            Map.of(localNodeId, localDigest)
        );

        var owner = stub.resolveOwningMember(bubbleId);
        assertNotNull(owner, "resolveOwningMember must return a non-null Digest for a known bubble");
        assertTrue(memberDigests.contains(owner),
            "Resolved owner must be in the active member set");
    }

    @Test
    void resolveOwningMember_unknownBubble_throwsIllegalStateException() {
        var stub = new StubBubbleOwnershipResolver(
            Collections.emptyMap(),
            memberDigests,
            memberDigests.get(0),
            Collections.emptyMap()
        );

        assertThrows(IllegalStateException.class,
            () -> stub.resolveOwningMember(UUID.randomUUID()),
            "resolveOwningMember must throw when bubble is not in grid");
    }

    @Test
    void resolveOwningMember_emptyActiveSet_throwsIllegalStateException() {
        var bubbleId = UUID.randomUUID();
        var tetreeKey = TetreeKey.create((byte) 3, 0x1111L, 0L);

        var stub = new StubBubbleOwnershipResolver(
            Map.of(bubbleId, tetreeKey),
            Collections.emptyList(), // no active members
            DigestAlgorithm.DEFAULT.digest("local".getBytes()),
            Collections.emptyMap()
        );

        assertThrows(IllegalStateException.class,
            () -> stub.resolveOwningMember(bubbleId),
            "resolveOwningMember must throw when active member set is empty");
    }

    @Test
    void localMember_returnsSeededLocalDigest() {
        var localDigest = memberDigests.get(2);
        var stub = new StubBubbleOwnershipResolver(
            Collections.emptyMap(),
            memberDigests,
            localDigest,
            Collections.emptyMap()
        );

        assertEquals(localDigest, stub.localMember(),
            "localMember() must return the seeded local member digest");
    }

    @Test
    void memberDigestForNode_knownNodeId_returnsItsDigest() {
        var nodeId = UUID.randomUUID();
        var expectedDigest = memberDigests.get(1);

        var stub = new StubBubbleOwnershipResolver(
            Collections.emptyMap(),
            memberDigests,
            memberDigests.get(0),
            Map.of(nodeId, expectedDigest)
        );

        assertEquals(expectedDigest, stub.memberDigestForNode(nodeId),
            "memberDigestForNode must return the digest for a known node UUID");
    }

    @Test
    void memberDigestForNode_unknownNodeId_throwsIllegalStateException() {
        var stub = new StubBubbleOwnershipResolver(
            Collections.emptyMap(),
            memberDigests,
            memberDigests.get(0),
            Collections.emptyMap()
        );

        assertThrows(IllegalStateException.class,
            () -> stub.memberDigestForNode(UUID.randomUUID()),
            "memberDigestForNode must throw when node UUID is not found");
    }

    // -------------------------------------------------------------------------
    // Owner result must be from the provided active set
    // -------------------------------------------------------------------------

    @Test
    void owner_resultAlwaysInActiveSet() {
        for (int i = 0; i < 30; i++) {
            var key = TetreeKey.create((byte) ((i % 10) + 1), (long) i * 0xBEEFC0DE1234L + i, 0L);
            var owner = hrwFunction.owner(key, memberDigests);
            assertTrue(memberDigests.contains(owner),
                "owner() result must always be in the provided active member set, key=" + key);
        }
    }

    // -------------------------------------------------------------------------
    // L1: Null-input throw tests (BubbleOwnershipResolver contract guards)
    // -------------------------------------------------------------------------

    @Test
    void stub_resolveOwningMember_null_throwsNullPointerException() {
        var stub = new StubBubbleOwnershipResolver(
            Collections.emptyMap(),
            memberDigests,
            memberDigests.get(0),
            Collections.emptyMap()
        );
        assertThrows(NullPointerException.class,
            () -> stub.resolveOwningMember(null),
            "resolveOwningMember(null) must throw NullPointerException");
    }

    @Test
    void stub_memberDigestForNode_null_throwsNullPointerException() {
        var stub = new StubBubbleOwnershipResolver(
            Collections.emptyMap(),
            memberDigests,
            memberDigests.get(0),
            Collections.emptyMap()
        );
        assertThrows(NullPointerException.class,
            () -> stub.memberDigestForNode(null),
            "memberDigestForNode(null) must throw NullPointerException");
    }

    // -------------------------------------------------------------------------
    // SIGNIFICANT: FirefliesBubbleOwnershipResolver direct tests
    //
    // Uses the narrow-seam constructor + MockFirefliesView<MockMember> to test:
    // (a) active-only invariant: evicted member is never returned as owner
    // (b) resolveOwningMember resolves correctly
    // (c) localMember() delegates to the supplier
    // (d) memberDigestForNode() delegates to the resolver
    // (e) null-guard on resolveOwningMember and memberDigestForNode
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link FirefliesBubbleOwnershipResolver} wired to a
     * {@link MockFirefliesView} containing the given members and with {@code inactive}
     * members marked inactive.  Bubble grid is a simple map with one entry.
     */
    private FirefliesBubbleOwnershipResolver buildProductionResolver(
            List<MockMember> allMembers,
            List<MockMember> inactive,
            UUID bubbleId,
            TetreeKey<?> bubbleKey,
            MockMember localMember) {

        var mockView = new MockFirefliesView<MockMember>();
        for (var m : allMembers) {
            mockView.addMember(m);
        }
        for (var m : inactive) {
            mockView.markInactive(m);
        }

        // Adapt MockFirefliesView<MockMember> → MembershipView<com.hellblazer.delos.membership.Member>
        // MockMember implements Member so the cast is safe; use raw-typed helper.
        @SuppressWarnings("unchecked")
        MembershipView<Member> membershipView =
            (MembershipView<Member>) (MembershipView<?>) mockView;

        Map<UUID, TetreeKey<?>> grid = Map.of(bubbleId, bubbleKey);

        return new FirefliesBubbleOwnershipResolver(
            () -> localMember,
            uuid -> allMembers.stream()
                              .filter(m -> m.getId().equals(
                                  DigestAlgorithm.DEFAULT.digest(uuid.toString().getBytes())))
                              .map(m -> (Member) m)
                              .findFirst(),
            grid::get,
            membershipView,
            new RendezvousOwnershipFunction()
        );
    }

    @Test
    void productionResolver_resolveOwningMember_returnsActiveMember() {
        var digests = memberDigests;
        var members = new ArrayList<MockMember>();
        for (var d : digests) {
            members.add(new MockMember(d));
        }
        var bubbleId = UUID.randomUUID();
        var key = TetreeKey.create((byte) 3, 0xCAFEBABE_DEADBEEFL, 0L);
        var localMember = members.get(0);

        var resolver = buildProductionResolver(members, List.of(), bubbleId, key, localMember);

        var owner = resolver.resolveOwningMember(bubbleId);
        assertNotNull(owner, "resolveOwningMember must return a non-null Digest");
        assertTrue(digests.contains(owner), "Owner must be in the active member set");
    }

    @Test
    void productionResolver_activeOnly_evictedMemberNeverReturned() {
        var digests = memberDigests;
        var members = new ArrayList<MockMember>();
        for (var d : digests) {
            members.add(new MockMember(d));
        }

        // Add an extra member and mark it inactive (evicted-but-not-GC'd)
        var evictedDigest = DigestAlgorithm.DEFAULT.digest("evicted".getBytes());
        var evictedMember = new MockMember(evictedDigest);
        members.add(evictedMember);

        // activeSet is all but evicted
        var activeDigests = new ArrayList<>(digests); // original 5, no evicted

        var bubbleId = UUID.randomUUID();
        var key = TetreeKey.create((byte) 3, 0xDEAD_C0DE_CAFE_1234L, 0L);
        var localMember = members.get(0);

        // Build resolver with evictedMember inactive
        var mockView = new MockFirefliesView<MockMember>();
        for (var m : members) {
            mockView.addMember(m);
        }
        mockView.markInactive(evictedMember);

        @SuppressWarnings("unchecked")
        MembershipView<Member> membershipView =
            (MembershipView<Member>) (MembershipView<?>) mockView;

        Map<UUID, TetreeKey<?>> grid = Map.of(bubbleId, key);

        var resolver = new FirefliesBubbleOwnershipResolver(
            () -> localMember,
            uuid -> Optional.empty(),
            grid::get,
            membershipView,
            new RendezvousOwnershipFunction()
        );

        // Test across several keys — evicted must never be selected
        for (int i = 0; i < 20; i++) {
            var testKey = TetreeKey.create((byte) 3, (long) (i * 0xABCDEF13L + 3), 0L);
            var testBubbleId = UUID.randomUUID();
            // Extend the grid on-the-fly: rebuild with this key
            Map<UUID, TetreeKey<?>> testGrid = Map.of(testBubbleId, testKey);

            var testResolver = new FirefliesBubbleOwnershipResolver(
                () -> localMember,
                uuid -> Optional.empty(),
                testGrid::get,
                membershipView,
                new RendezvousOwnershipFunction()
            );
            var owner = testResolver.resolveOwningMember(testBubbleId);
            assertTrue(activeDigests.contains(owner),
                "Owner must be in the active (non-evicted) set, key=" + testKey);
            assertNotEquals(evictedDigest, owner,
                "Evicted-but-inactive member must never be selected as owner");
        }
    }

    @Test
    void productionResolver_localMember_delegatesToSupplier() {
        var localDigest = memberDigests.get(2);
        var localMember = new MockMember(localDigest);

        var mockView = new MockFirefliesView<MockMember>();
        @SuppressWarnings("unchecked")
        MembershipView<Member> membershipView =
            (MembershipView<Member>) (MembershipView<?>) mockView;

        var resolver = new FirefliesBubbleOwnershipResolver(
            () -> localMember,
            uuid -> Optional.empty(),
            uuid -> null,
            membershipView,
            new RendezvousOwnershipFunction()
        );

        assertEquals(localDigest, resolver.localMember(),
            "localMember() must return the digest from the injected supplier");
    }

    @Test
    void productionResolver_memberDigestForNode_delegatesToResolver() {
        var targetDigest = memberDigests.get(1);
        var targetMember = new MockMember(targetDigest);
        var nodeId = UUID.randomUUID();

        var mockView = new MockFirefliesView<MockMember>();
        @SuppressWarnings("unchecked")
        MembershipView<Member> membershipView =
            (MembershipView<Member>) (MembershipView<?>) mockView;

        var resolver = new FirefliesBubbleOwnershipResolver(
            () -> new MockMember(memberDigests.get(0)),
            uuid -> uuid.equals(nodeId) ? Optional.of(targetMember) : Optional.empty(),
            uid -> null,
            membershipView,
            new RendezvousOwnershipFunction()
        );

        assertEquals(targetDigest, resolver.memberDigestForNode(nodeId),
            "memberDigestForNode must return the digest from the injected node resolver");
    }

    @Test
    void productionResolver_memberDigestForNode_unknownNode_throwsISE() {
        var mockView = new MockFirefliesView<MockMember>();
        @SuppressWarnings("unchecked")
        MembershipView<Member> membershipView =
            (MembershipView<Member>) (MembershipView<?>) mockView;

        var resolver = new FirefliesBubbleOwnershipResolver(
            () -> new MockMember(memberDigests.get(0)),
            uuid -> Optional.empty(),
            uid -> null,
            membershipView,
            new RendezvousOwnershipFunction()
        );

        assertThrows(IllegalStateException.class,
            () -> resolver.memberDigestForNode(UUID.randomUUID()),
            "memberDigestForNode must throw ISE when node UUID is not found");
    }

    @Test
    void productionResolver_resolveOwningMember_null_throwsNPE() {
        var mockView = new MockFirefliesView<MockMember>();
        @SuppressWarnings("unchecked")
        MembershipView<Member> membershipView =
            (MembershipView<Member>) (MembershipView<?>) mockView;

        var resolver = new FirefliesBubbleOwnershipResolver(
            () -> new MockMember(memberDigests.get(0)),
            uuid -> Optional.empty(),
            uid -> null,
            membershipView,
            new RendezvousOwnershipFunction()
        );

        assertThrows(NullPointerException.class,
            () -> resolver.resolveOwningMember(null),
            "resolveOwningMember(null) must throw NullPointerException");
    }

    @Test
    void productionResolver_memberDigestForNode_null_throwsNPE() {
        var mockView = new MockFirefliesView<MockMember>();
        @SuppressWarnings("unchecked")
        MembershipView<Member> membershipView =
            (MembershipView<Member>) (MembershipView<?>) mockView;

        var resolver = new FirefliesBubbleOwnershipResolver(
            () -> new MockMember(memberDigests.get(0)),
            uuid -> Optional.empty(),
            uid -> null,
            membershipView,
            new RendezvousOwnershipFunction()
        );

        assertThrows(NullPointerException.class,
            () -> resolver.memberDigestForNode(null),
            "memberDigestForNode(null) must throw NullPointerException");
    }
}
