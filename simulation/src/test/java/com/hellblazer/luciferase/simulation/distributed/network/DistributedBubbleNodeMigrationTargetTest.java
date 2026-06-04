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

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubbleMigrationIntegration;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.distributed.migration.OptimisticMigrator;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for Luciferase-t7bwr: DistributedBubbleNode.initiateRemoteMigration must pass
 * the caller-supplied targetNodeId (not its own bubble id) to the optimistic migrator, so
 * the entity migrates to the intended remote target rather than to itself.
 *
 * @author hal.hildebrand
 */
class DistributedBubbleNodeMigrationTargetTest {

    @Test
    void initiateRemoteMigrationPassesTargetNodeIdNotSelf() {
        var nodeId = UUID.randomUUID();
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 5, 16);
        var targetNodeId = UUID.randomUUID();
        var entityId = UUID.randomUUID();

        var recordedTarget = new AtomicReference<UUID>();
        var recordingMigrator = mock(OptimisticMigrator.class);
        doAnswer(inv -> {
            recordedTarget.set(inv.getArgument(1));
            return null;
        }).when(recordingMigrator).initiateOptimisticMigration(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        var networkChannel = mock(BubbleNetworkChannel.class);
        when(networkChannel.isNodeReachable(targetNodeId)).thenReturn(true);

        var integration = mock(EnhancedBubbleMigrationIntegration.class);
        when(integration.getOptimisticMigrator()).thenReturn(recordingMigrator);

        var fsm = mock(EntityMigrationStateMachine.class);

        var node = new DistributedBubbleNode(nodeId, bubble, networkChannel, integration, fsm);

        var initiated = node.initiateRemoteMigration(entityId, targetNodeId);

        assertTrue(initiated, "Migration should be initiated when target is reachable");
        assertEquals(targetNodeId, recordedTarget.get(),
                     "Optimistic migrator must record the intended remote target, not the node's own bubble");
    }
}
