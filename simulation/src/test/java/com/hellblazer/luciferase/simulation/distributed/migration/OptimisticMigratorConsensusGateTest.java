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

package com.hellblazer.luciferase.simulation.distributed.migration;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.consensus.committee.OptimisticMigratorIntegration;
import com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consensus-gate behavior for {@link OptimisticMigratorImpl#requestMigrationApproval}
 * (Luciferase-0frcy.35 → RDR-020 S4).
 *
 * <p>History: the method once silently returned {@code completedFuture(true)} when a consensus
 * integration was wired, bypassing the committee-quorum gate; that was replaced by a fail-loud
 * {@code UnsupportedOperationException} because no UUID→Digest mapping existed. RDR-020 S4 supplies
 * that mapping via {@link BubbleOwnershipResolver}: the target bubble UUID resolves to its HRW owning
 * member (target node), the local member is the source node, and approval delegates to the committee.
 * The fail-loud invariant is preserved for the misconfigured (integration-without-resolver) and
 * unresolvable-target cases; the no-integration path keeps its documented default-approve behavior.
 *
 * @author hal.hildebrand
 */
class OptimisticMigratorConsensusGateTest {

    private static BubbleOwnershipResolver resolver(Digest local, Digest owner) {
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

            @Override
            public boolean isActiveMember(Digest member) {
                return true;
            }
        };
    }

    @Test
    void delegatesToCommitteeWithResolvedDigestsWhenIntegrationAndResolverSet() throws Exception {
        var local = DigestAlgorithm.DEFAULT.digest("s4-local-source");
        var owner = DigestAlgorithm.DEFAULT.digest("s4-target-owner");
        var entityId = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        var integration = mock(OptimisticMigratorIntegration.class);
        when(integration.requestMigrationApproval(eq(entityId), eq(local), eq(owner)))
            .thenReturn(CompletableFuture.completedFuture(true));

        // Mock resolver (not the input-blind helper) so we can pin WHICH UUID is resolved as the
        // target — it must be the BUBBLE uuid, resolved via HRW directly, not a node uuid or the
        // entity id.
        var resolver = mock(BubbleOwnershipResolver.class);
        when(resolver.resolveOwningMember(eq(targetBubble))).thenReturn(owner);
        when(resolver.localMember()).thenReturn(local);

        var migrator = new OptimisticMigratorImpl();
        migrator.setConsensusIntegration(integration);
        migrator.setOwnershipResolver(resolver);

        var approved = migrator.requestMigrationApproval(entityId, targetBubble).get(2, TimeUnit.SECONDS);

        assertTrue(approved, "approval must reflect the committee's decision (true here)");
        // target = HRW owner of the target BUBBLE uuid (pin the bubble-uuid-not-node-uuid invariant);
        // source = local member (possession).
        verify(resolver, times(1)).resolveOwningMember(targetBubble);
        verify(resolver, times(1)).localMember();
        verify(integration, times(1)).requestMigrationApproval(entityId, local, owner);
    }

    @Test
    void propagatesCommitteeRejection() throws Exception {
        var local = DigestAlgorithm.DEFAULT.digest("s4-local-source");
        var owner = DigestAlgorithm.DEFAULT.digest("s4-target-owner");
        var entityId = UUID.randomUUID();

        var integration = mock(OptimisticMigratorIntegration.class);
        when(integration.requestMigrationApproval(eq(entityId), eq(local), eq(owner)))
            .thenReturn(CompletableFuture.completedFuture(false));

        var migrator = new OptimisticMigratorImpl();
        migrator.setConsensusIntegration(integration);
        migrator.setOwnershipResolver(resolver(local, owner));

        var approved = migrator.requestMigrationApproval(entityId, UUID.randomUUID()).get(2, TimeUnit.SECONDS);
        assertFalse(approved, "a committee rejection must propagate, not be overridden to approve");
        verify(integration, times(1)).requestMigrationApproval(entityId, local, owner);
    }

    @Test
    void failsLoudWhenIntegrationSetButNoResolver() {
        var migrator = new OptimisticMigratorImpl();
        migrator.setConsensusIntegration(mock(OptimisticMigratorIntegration.class));
        // No resolver injected.

        // Must NOT silently approve — without a resolver the bubble→member mapping is unavailable,
        // so the quorum gate cannot be enforced. Fail loud.
        assertThrows(IllegalStateException.class,
                     () -> migrator.requestMigrationApproval(UUID.randomUUID(), UUID.randomUUID()),
                     "integration wired without a resolver must fail loud, not bypass the quorum gate");
    }

    @Test
    void failsLoudWhenTargetUnresolvable() {
        var entityId = UUID.randomUUID();
        var integration = mock(OptimisticMigratorIntegration.class);

        var migrator = new OptimisticMigratorImpl();
        migrator.setConsensusIntegration(integration);
        // Resolver that cannot resolve the target bubble (fail-loud per the S1 contract).
        migrator.setOwnershipResolver(new BubbleOwnershipResolver() {
            @Override
            public Digest resolveOwningMember(UUID bubbleId) {
                throw new IllegalStateException("unresolvable target bubble (test): " + bubbleId);
            }

            @Override
            public Digest localMember() {
                return DigestAlgorithm.DEFAULT.digest("s4-local");
            }

            @Override
            public Digest memberDigestForNode(UUID nodeId) {
                return DigestAlgorithm.DEFAULT.digest("s4-local");
            }

            @Override
            public boolean isActiveMember(Digest member) {
                return true;
            }
        });

        assertThrows(IllegalStateException.class,
                     () -> migrator.requestMigrationApproval(entityId, UUID.randomUUID()),
                     "an unresolvable target must throw, never silently approve");
        // The committee must never be consulted for an unresolvable target.
        verify(integration, never()).requestMigrationApproval(org.mockito.ArgumentMatchers.any(),
                                                               org.mockito.ArgumentMatchers.any(),
                                                               org.mockito.ArgumentMatchers.any());
    }

    @Test
    void defaultApprovesWhenNoConsensusIntegration() throws Exception {
        var migrator = new OptimisticMigratorImpl();
        var approved = migrator.requestMigrationApproval(UUID.randomUUID(), UUID.randomUUID())
                               .get(2, TimeUnit.SECONDS);
        assertTrue(approved, "Without a consensus integration, approval defaults to true (documented)");
    }
}
