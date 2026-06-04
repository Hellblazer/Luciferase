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
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression test for Luciferase-0frcy.20.
 * <p>
 * {@code ViewCommitteeConsensus} uses setter injection for its three collaborators (viewMonitor, committeeSelector,
 * votingProtocol). Previously, calling {@code requestConsensus()} before all setters were invoked produced an
 * opaque {@link NullPointerException} from deep inside the call (e.g. {@code committeeSelector.selectCommittee}).
 * <p>
 * Expected behaviour after fix: an un-/partially-initialized instance fails fast with a meaningful
 * {@link IllegalStateException} from {@code requestConsensus()}.
 *
 * @author hal.hildebrand
 */
public class ViewCommitteeConsensusLifecycleTest {

    private static MigrationProposal proposal() {
        var algo = DigestAlgorithm.DEFAULT;
        return new MigrationProposal(UUID.randomUUID(), UUID.randomUUID(), algo.getOrigin().prefix(1),
                                     algo.getOrigin().prefix(2), algo.digest("v".getBytes()), 1_000L);
    }

    @Test
    public void requestConsensusBeforeAnyInjectionThrowsIllegalState() {
        var consensus = new ViewCommitteeConsensus();
        assertThrows(IllegalStateException.class, () -> consensus.requestConsensus(proposal()),
                     "Uninitialized consensus must fail fast with IllegalStateException, not NPE");
    }

    @Test
    public void requestConsensusWithPartialInjectionThrowsIllegalState() {
        var consensus = new ViewCommitteeConsensus();
        consensus.setViewMonitor(new com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor(
            new EmptyView()) {
        });
        // committeeSelector and votingProtocol still unset
        assertThrows(IllegalStateException.class, () -> consensus.requestConsensus(proposal()),
                     "Partially-initialized consensus must fail fast with IllegalStateException, not NPE");
    }

    private static final class EmptyView
        implements com.hellblazer.luciferase.simulation.delos.MembershipView<com.hellblazer.delos.membership.Member> {
        @Override
        public void addListener(
            java.util.function.Consumer<ViewChange<com.hellblazer.delos.membership.Member>> listener) {
        }

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
