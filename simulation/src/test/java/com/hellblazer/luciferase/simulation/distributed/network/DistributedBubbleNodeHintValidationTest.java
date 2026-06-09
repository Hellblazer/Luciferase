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

package com.hellblazer.luciferase.simulation.distributed.network;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubbleMigrationIntegration;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.consensus.ownership.BubbleOwnershipResolver;
import com.hellblazer.luciferase.simulation.distributed.migration.OptimisticMigrator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RDR-020 S5: explicit node-UUID hint validation in
 * {@link DistributedBubbleNode#initiateRemoteMigration} (B1/B4).
 *
 * <p>{@code targetNodeId} carries no destination region key, so the HRW {@code owner(destKey)}
 * cross-check is not applicable here (that lives at the bubble-keyed consensus entry points, S3/S4).
 * The implementable guard is the canonical node-UUID → member-Digest resolution plus an active-only
 * membership check: a random/unknown UUID resolves to no member (throws); an evicted-but-not-GC'd
 * node resolves but is inactive (throws); a canonical active node passes. When no resolver is wired
 * the hint is not validated (backward compatibility) — covered by
 * {@link DistributedBubbleNodeMigrationTargetTest}.
 *
 * @author hal.hildebrand
 */
class DistributedBubbleNodeHintValidationTest {

    private record Fixture(DistributedBubbleNode node, OptimisticMigrator migrator, UUID targetNodeId) {
    }

    /**
     * Build a node whose target is reachable, wiring the supplied resolver. The migrator is a mock so
     * we can assert whether the migration was initiated (valid hint) or short-circuited (bad hint).
     */
    private static Fixture nodeWith(BubbleOwnershipResolver resolver, UUID targetNodeId) {
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
        var migrator = mock(OptimisticMigrator.class);
        var networkChannel = mock(BubbleNetworkChannel.class);
        when(networkChannel.isNodeReachable(any())).thenReturn(true);
        var integration = mock(EnhancedBubbleMigrationIntegration.class);
        when(integration.getOptimisticMigrator()).thenReturn(migrator);
        var fsm = mock(EntityMigrationStateMachine.class);

        var node = new DistributedBubbleNode(UUID.randomUUID(), bubble, networkChannel, integration, fsm);
        node.setOwnershipResolver(resolver);
        return new Fixture(node, migrator, targetNodeId);
    }

    @Test
    void canonicalActiveNodeUuidPasses() {
        var targetNodeId = UUID.randomUUID();
        var targetDigest = DigestAlgorithm.DEFAULT.digest("active-target");
        var entityId = UUID.randomUUID();

        var fx = nodeWith(resolver(targetNodeId, targetDigest, /*active*/ true), targetNodeId);

        var initiated = fx.node().initiateRemoteMigration(entityId, targetNodeId);

        assertTrue(initiated, "a canonical, active target node must pass hint validation");
        verify(fx.migrator()).initiateOptimisticMigration(entityId, targetNodeId);
    }

    @Test
    void unknownNodeUuidThrows() {
        var targetNodeId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        // Resolver that resolves no member for this UUID (random / not digestToUuid-derived).
        var resolver = mock(BubbleOwnershipResolver.class);
        when(resolver.memberDigestForNode(targetNodeId))
            .thenThrow(new IllegalStateException("No Fireflies member found for node UUID: " + targetNodeId));

        var fx = nodeWith(resolver, targetNodeId);

        assertThrows(IllegalStateException.class,
                     () -> fx.node().initiateRemoteMigration(entityId, targetNodeId),
                     "an unknown node UUID must fail loud, not initiate a migration toward a non-member");
        verify(fx.migrator(), never()).initiateOptimisticMigration(any(), any());
    }

    @Test
    void inactiveNodeUuidThrows() {
        var targetNodeId = UUID.randomUUID();
        var targetDigest = DigestAlgorithm.DEFAULT.digest("evicted-target");
        var entityId = UUID.randomUUID();
        // Resolves to a member (still in all-members backing) but it is NOT active (evicted-not-GC'd).
        var fx = nodeWith(resolver(targetNodeId, targetDigest, /*active*/ false), targetNodeId);

        var ex = assertThrows(IllegalStateException.class,
                              () -> fx.node().initiateRemoteMigration(entityId, targetNodeId),
                              "an inactive (evicted) node must fail loud");
        assertTrue(ex.getMessage().contains("not a current-view active member"),
                   "message must name the inactive-member cause, was: " + ex.getMessage());
        verify(fx.migrator(), never()).initiateOptimisticMigration(any(), any());
    }

    @Test
    void noResolverWired_randomNodeUuidIsNotValidated() {
        // RDR-020 S7 (Luciferase-h3lc6): the S5 hint validation is OPT-IN — it runs only when a
        // resolver is wired. With no resolver (the default), an arbitrary/random node UUID that does
        // not resolve to any member is NOT validated and the migration proceeds. This is the contract
        // the performance/resilience suites rely on (they drive initiateRemoteMigration with random
        // node UUIDs and no resolver); pinning it here guards against a regression that made
        // validation unconditional. Canonical (digestToUuid-derived) identities become required only
        // once a resolver is wired by the multi-node bootstrap (bead Luciferase-s23eu).
        var node = nodeWith(/* resolver */ null, UUID.randomUUID());
        var initiated = node.node().initiateRemoteMigration(UUID.randomUUID(), node.targetNodeId());
        assertTrue(initiated, "with no resolver wired, a random node UUID must not be validated (no throw)");
        verify(node.migrator()).initiateOptimisticMigration(any(), any());
    }

    @Test
    void clearingResolverDisablesValidation() {
        var targetNodeId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        // Resolver that would reject this node (inactive) — proves validation is active while wired.
        var fx = nodeWith(resolver(targetNodeId, DigestAlgorithm.DEFAULT.digest("evicted"), /*active*/ false),
                          targetNodeId);
        assertThrows(IllegalStateException.class,
                     () -> fx.node().initiateRemoteMigration(entityId, targetNodeId),
                     "while wired, the inactive node must be rejected");

        // Clearing the resolver returns to the backward-compat (unvalidated) path: the same call now
        // proceeds. This pins the null-downgrade bridge used until S7 wires resolvers everywhere.
        fx.node().setOwnershipResolver(null);
        var initiated = fx.node().initiateRemoteMigration(entityId, targetNodeId);
        assertTrue(initiated, "with no resolver wired, the hint is not validated (backward compatibility)");
        verify(fx.migrator()).initiateOptimisticMigration(entityId, targetNodeId);
    }

    private static BubbleOwnershipResolver resolver(UUID nodeId, Digest digest, boolean active) {
        return new BubbleOwnershipResolver() {
            @Override
            public Digest resolveOwningMember(UUID bubbleId) {
                throw new UnsupportedOperationException("not used in node-hint validation");
            }

            @Override
            public Digest localMember() {
                return DigestAlgorithm.DEFAULT.digest("local");
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
}
