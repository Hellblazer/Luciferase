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

import com.hellblazer.luciferase.simulation.consensus.committee.OptimisticMigratorIntegration;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Consensus-gate regression for {@link OptimisticMigratorImpl#requestMigrationApproval}
 * (Luciferase-0frcy.35).
 *
 * <p>Pre-fix: when a consensus integration was wired, the method logged "Delegating ..." but
 * returned {@code completedFuture(true)} unconditionally — silently bypassing the committee-quorum
 * gate. Any caller relying on the gate would skip it with no signal. The fix fails loud
 * (UnsupportedOperationException) rather than silently approving, because the UUID→Digest mapping
 * needed to actually delegate is not available in this impl. The no-integration path keeps its
 * documented default-approve behavior.
 *
 * @author hal.hildebrand
 */
class OptimisticMigratorConsensusGateTest {

    @Test
    void doesNotSilentlyApproveWhenConsensusIntegrationIsSet() {
        var migrator = new OptimisticMigratorImpl();
        migrator.setConsensusIntegration(mock(OptimisticMigratorIntegration.class));

        var entityId = UUID.randomUUID();
        var targetBubble = UUID.randomUUID();

        // Must NOT silently return an approved future — it must fail loud so callers know the
        // quorum gate is not actually being enforced.
        assertThrows(UnsupportedOperationException.class,
                     () -> migrator.requestMigrationApproval(entityId, targetBubble),
                     "With a consensus integration wired but no UUID→Digest mapping, approval must "
                     + "fail loud rather than silently bypass the quorum gate");
    }

    @Test
    void defaultApprovesWhenNoConsensusIntegration() throws Exception {
        var migrator = new OptimisticMigratorImpl();
        var approved = migrator.requestMigrationApproval(UUID.randomUUID(), UUID.randomUUID())
                               .get(2, TimeUnit.SECONDS);
        assertTrue(approved, "Without a consensus integration, approval defaults to true (documented)");
    }
}
