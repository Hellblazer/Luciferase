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
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * Regression test for Luciferase-0frcy.18.
 * <p>
 * When {@link FirefliesViewMonitor#getCurrentViewId()} returns null (e.g. the underlying membership view is a
 * mock/non-Fireflies view), {@code ViewCommitteeConsensus.requestConsensus()} previously executed
 * {@code proposal.viewId().equals(currentViewId)} with a null {@code currentViewId} on the right-hand side and,
 * more critically, accepted a proposal carrying a {@code null} viewId straight through
 * {@code validateProposal()} (which never checked viewId). A proposal with a null viewId would then NPE
 * downstream on the vote path.
 * <p>
 * Expected behaviour after fix: a proposal with a null viewId, and/or a null current view, is rejected cleanly
 * (future resolves to {@code false}) without throwing.
 *
 * @author hal.hildebrand
 */
public class NullViewIdConsensusTest {

    private DynamicContext<Member>      context;
    private ViewCommitteeSelector       selector;
    private CommitteeVotingProtocol     votingProtocol;
    private ViewCommitteeConsensus      consensus;
    private ScheduledExecutorService    scheduler;
    private List<Member>                members;

    @BeforeEach
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void setUp() {
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(5);
        when(context.toleranceLevel()).thenReturn(1);

        members = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }
        var committee = new java.util.LinkedHashSet<>(members.subList(0, 3));
        when(context.bftSubset(Mockito.any(Digest.class))).thenReturn((java.util.SequencedSet) committee);
        when(context.allMembers()).thenAnswer(invocation -> members.stream());

        selector = new ViewCommitteeSelector(context);
        var config = CommitteeConfig.defaultConfig();
        scheduler = Executors.newScheduledThreadPool(1);
        votingProtocol = new CommitteeVotingProtocol(context, config, scheduler);

        consensus = new ViewCommitteeConsensus();
        // Monitor whose getCurrentViewId() always returns null (mock view, as in production with non-Fireflies view)
        consensus.setViewMonitor(new NullViewMonitor());
        consensus.setCommitteeSelector(selector);
        consensus.setVotingProtocol(votingProtocol);
    }

    @AfterEach
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void nullCurrentViewIdDoesNotThrowAndRejects() throws Exception {
        var view = DigestAlgorithm.DEFAULT.digest("view1".getBytes());
        var proposal = new MigrationProposal(UUID.randomUUID(), UUID.randomUUID(), members.get(0).getId(),
                                             members.get(1).getId(), view, 1_000L);

        var future = assertDoesNotThrow(() -> consensus.requestConsensus(proposal),
                                        "requestConsensus must not throw when current view ID is null");
        assertFalse(future.get(1, TimeUnit.SECONDS),
                    "Proposal must be rejected (false) when current view ID is null, not NPE");
    }

    @Test
    public void nullProposalViewIdIsRejectedByValidation() throws Exception {
        // Proposal carrying a null viewId must be rejected by validateProposal(), not passed downstream.
        var proposal = new MigrationProposal(UUID.randomUUID(), UUID.randomUUID(), members.get(0).getId(),
                                             members.get(1).getId(), null, 1_000L);

        var future = assertDoesNotThrow(() -> consensus.requestConsensus(proposal),
                                        "requestConsensus must not throw on null proposal viewId");
        assertFalse(future.get(1, TimeUnit.SECONDS), "Proposal with null viewId must be rejected (false)");
    }

    /** View monitor that always reports a null current view ID. */
    private static final class NullViewMonitor extends FirefliesViewMonitor {
        NullViewMonitor() {
            super(new EmptyMembershipView());
        }

        @Override
        public Digest getCurrentViewId() {
            return null;
        }
    }

    private static final class EmptyMembershipView implements MembershipView<Member> {
        @Override
        public void addListener(java.util.function.Consumer<ViewChange<Member>> listener) {
        }

        @Override
        public java.util.stream.Stream<Member> getMembers() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public java.util.stream.Stream<Member> activeMembers() {
            return java.util.stream.Stream.empty();
        }
    }

    /** Minimal Member stub identified by a Digest. */
    private static final class MockMember implements Member {
        private final Digest id;

        MockMember(Digest id) {
            this.id = id;
        }

        @Override
        public Digest getId() {
            return id;
        }

        @Override
        public int compareTo(Member o) {
            return id.compareTo(o.getId());
        }

        @Override
        public boolean verify(com.hellblazer.delos.cryptography.SigningThreshold threshold,
                              com.hellblazer.delos.cryptography.JohnHancock signature, java.io.InputStream is) {
            return true;
        }

        @Override
        public boolean verify(com.hellblazer.delos.cryptography.JohnHancock signature, java.io.InputStream is) {
            return true;
        }
    }
}
