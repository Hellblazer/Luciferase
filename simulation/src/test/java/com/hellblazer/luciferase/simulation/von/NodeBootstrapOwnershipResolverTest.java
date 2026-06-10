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

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver;
import com.hellblazer.luciferase.simulation.consensus.ownership.RendezvousOwnershipFunction;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-022 S1 (Luciferase-0frcy.136.1): unit tests for
 * {@link NodeBootstrap#assembleOwnershipResolver}.
 * <p>
 * Verifies the factory threads each injected seam through to the assembled
 * {@link BubbleOwnershipResolver} (non-vacuous: assertions distinguish the specific member/seam
 * passed from its siblings), fixes {@link RendezvousOwnershipFunction} internally (locked
 * decision 1), enforces the active-only membership invariant (RDR-020 B4 / RDR-005), and
 * preserves the RDR-020 fail-loud contract end-to-end.
 *
 * @author hal.hildebrand
 */
class NodeBootstrapOwnershipResolverTest {

    private static final int MEMBER_COUNT = 3;

    private List<MockMember>       members;
    private MockFirefliesView<Member> mockView;
    private Map<UUID, TetreeKey<?>> bubbleKeys;

    @BeforeEach
    void setUp() {
        members = new ArrayList<>();
        for (int i = 0; i < MEMBER_COUNT; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }
        mockView = new MockFirefliesView<>();
        for (var m : members) {
            mockView.addMember(m);
        }
        bubbleKeys = new HashMap<>();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Canonical node-UUID → Member resolution (RDR-020 B4): UUID == digestToUuid(member id). */
    private Optional<Member> canonicalNodeResolver(UUID nodeUuid) {
        return members.stream()
                      .filter(m -> FirefliesMemberLookup.digestToUuid(m.getId()).equals(nodeUuid))
                      .map(m -> (Member) m)
                      .findFirst();
    }

    private BubbleOwnershipResolver assemble(Member localMember) {
        return NodeBootstrap.assembleOwnershipResolver(
            () -> localMember,
            this::canonicalNodeResolver,
            bubbleKeys::get,
            mockView);
    }

    // -------------------------------------------------------------------------
    // Seam threading (non-vacuous — gate finding S2)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("localMemberSupplier threads through: localMember() is the SPECIFIC member passed")
    void localMemberSupplierThreadsThrough() {
        var resolver = assemble(members.get(1));

        assertNotNull(resolver);
        assertEquals(members.get(1).getId(), resolver.localMember(),
                     "localMember() must be the digest of the specific member passed as supplier");
        // Non-vacuous: distinguishes the passed member from its siblings (a digestToUuid
        // round-trip would be trivially true for ANY member — gate finding S2).
        assertNotEquals(members.get(0).getId(), resolver.localMember());
        assertNotEquals(members.get(2).getId(), resolver.localMember());
    }

    @Test
    @DisplayName("nodeResolver threads through: canonical node UUID resolves to that member's digest")
    void nodeResolverThreadsThrough() {
        var resolver = assemble(members.get(0));

        var node2Uuid = FirefliesMemberLookup.digestToUuid(members.get(2).getId());
        assertEquals(members.get(2).getId(), resolver.memberDigestForNode(node2Uuid));

        // Unknown node UUID fails loud (RDR-020 contract preserved through the factory).
        assertThrows(IllegalStateException.class,
                     () -> resolver.memberDigestForNode(UUID.randomUUID()));
    }

    @Test
    @DisplayName("bubbleKeyResolver threads through and HRW is fixed internally (locked decision 1)")
    void hrwOwnerMatchesRendezvousFunctionAndIsDeterministic() {
        var bubbleId = UUID.randomUUID();
        var key = TetreeKey.getRoot();
        bubbleKeys.put(bubbleId, key);

        var resolver = assemble(members.get(0));
        var owner = resolver.resolveOwningMember(bubbleId);

        // The factory must construct RendezvousOwnershipFunction internally: the resolved owner
        // equals a direct HRW computation over the same active digests.
        List<Digest> activeDigests = members.stream().map(m -> (Digest) m.getId()).toList();
        var expected = new RendezvousOwnershipFunction().owner(key, activeDigests);
        assertEquals(expected, owner, "Factory-assembled resolver must use rendezvous (HRW) ownership");

        // Deterministic across calls.
        assertEquals(owner, resolver.resolveOwningMember(bubbleId));
        assertTrue(activeDigests.contains(owner), "Owner must be an active member digest");
    }

    // -------------------------------------------------------------------------
    // Active-only invariant (RDR-020 B4 / RDR-005)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("membershipView threads through with the active-only invariant")
    void activeOnlyInvariantHolds() {
        mockView.markInactive(members.get(2));

        var resolver = assemble(members.get(0));

        assertTrue(resolver.isActiveMember(members.get(0).getId()));
        assertFalse(resolver.isActiveMember(members.get(2).getId()),
                    "Evicted-but-known member must not be active (RDR-005)");

        // Ownership is computed over the ACTIVE set only: equals HRW over the two active digests.
        var bubbleId = UUID.randomUUID();
        var key = TetreeKey.getRoot();
        bubbleKeys.put(bubbleId, key);
        List<Digest> activeOnly = List.of(members.get(0).getId(), members.get(1).getId());
        assertEquals(new RendezvousOwnershipFunction().owner(key, activeOnly),
                     resolver.resolveOwningMember(bubbleId));
    }

    // -------------------------------------------------------------------------
    // Fail-loud contract survives factory assembly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("unresolvable bubble fails loud through the factory-assembled resolver")
    void unresolvableBubbleFailsLoud() {
        var resolver = assemble(members.get(0));
        assertThrows(IllegalStateException.class,
                     () -> resolver.resolveOwningMember(UUID.randomUUID()),
                     "A bubble with no key must throw, never return a silently-rejectable digest");
    }

    @Test
    @DisplayName("empty active member set fails loud")
    void emptyActiveSetFailsLoud() {
        var emptyView = new MockFirefliesView<Member>();
        var bubbleId = UUID.randomUUID();
        bubbleKeys.put(bubbleId, TetreeKey.getRoot());

        var resolver = NodeBootstrap.assembleOwnershipResolver(
            () -> members.get(0),
            this::canonicalNodeResolver,
            bubbleKeys::get,
            emptyView);

        assertThrows(IllegalStateException.class, () -> resolver.resolveOwningMember(bubbleId));
    }

    // -------------------------------------------------------------------------
    // Null-argument fail-loud (both overloads)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("narrow-seam overload fails loud on every null argument")
    void narrowSeamOverloadNullArgsFailLoud() {
        Supplier<Member> local = () -> members.get(0);
        Function<UUID, Optional<Member>> nodes = this::canonicalNodeResolver;
        Function<UUID, TetreeKey<?>> keys = bubbleKeys::get;

        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleOwnershipResolver(null, nodes, keys, mockView));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleOwnershipResolver(local, null, keys, mockView));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleOwnershipResolver(local, nodes, null, mockView));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleOwnershipResolver(local, nodes, keys,
                                                                   (MembershipView<Member>) null));
    }

    @Test
    @DisplayName("production overload fails loud on null arguments at assembly time")
    void productionOverloadNullArgsFailLoud() {
        Function<UUID, TetreeKey<?>> keys = bubbleKeys::get;

        // Null memberLookup must fail at the factory call, not defer an NPE to first use.
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleOwnershipResolver((FirefliesMemberLookup) null,
                                                                   mockView, keys));
        // Remaining nulls are rejected by the resolver's own ctor (fail-loud preserved). A live
        // FirefliesMemberLookup needs a Fireflies View (RDR-017 P1), so the behavioral production
        // path is exercised by the MVV/narrow-seam tests; null-rejection is asserted with a
        // view-less lookup instance, which the factory must not dereference during assembly.
        var viewlessLookup = new FirefliesMemberLookup(null);
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleOwnershipResolver(viewlessLookup, null, keys));
        assertThrows(NullPointerException.class,
                     () -> NodeBootstrap.assembleOwnershipResolver(viewlessLookup, mockView, null));
    }

    @Test
    @DisplayName("production overload assembles without dereferencing the lookup (lazy seams)")
    void productionOverloadAssemblesLazily() {
        // FirefliesMemberLookup(view=null) is unusable at call time but must be acceptable at
        // assembly time: the factory wires method references, it does not invoke them.
        var viewlessLookup = new FirefliesMemberLookup(null);
        var resolver = NodeBootstrap.assembleOwnershipResolver(viewlessLookup, mockView,
                                                               bubbleKeys::get);
        assertNotNull(resolver);
        // First USE fails (null view), proving the seam is wired to the lookup — fail-loud at
        // call time, not silently absorbed.
        assertThrows(NullPointerException.class, resolver::localMember);
    }
}
