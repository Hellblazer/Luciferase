/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.causality;

import java.util.UUID;

/**
 * Transport seam for the two-phase-commit (2PC) messages that {@link MigrationCoordinator}
 * dispatches when bridging FSM state transitions to the cross-process migration protocol.
 *
 * <p>This is a typed replacement for the previous reflection-based dispatch
 * (Luciferase-4k66e). Reflection silently swallowed {@code NoSuchMethodException} when the
 * underlying object lacked the expected method, orphaning entities: the source FSM advanced
 * to {@code DEPARTED} while the target stayed stuck in {@code MIGRATING_IN}. With a typed
 * interface the wiring is verified at construction time, and each send returns a boolean so
 * delivery failures propagate to the coordinator's compensation logic instead of being
 * logged-and-lost.
 *
 * <p><b>Contract:</b> each method returns {@code true} only when the request was successfully
 * dispatched to the target bubble. A {@code false} return (or, by extension, a thrown
 * exception that an implementation chooses to surface) signals the coordinator to run the
 * appropriate compensating action (abort / rollback) so the distributed state stays
 * consistent.
 *
 * @author hal.hildebrand
 */
public interface CrossProcessMigrationProtocol {

    /**
     * Send a 2PC PrepareRequest to the target bubble (outbound migration, phase 1).
     *
     * @param entityId     entity being migrated
     * @param sourceBubble bubble currently owning the entity
     * @param targetBubble bubble the entity is migrating to
     * @return {@code true} if the request was dispatched successfully
     */
    boolean sendPrepareRequest(Object entityId, UUID sourceBubble, UUID targetBubble);

    /**
     * Send a 2PC CommitRequest to the target bubble (outbound migration, phase 2).
     *
     * @param entityId     entity being migrated
     * @param targetBubble bubble the entity is migrating to
     * @return {@code true} if the request was dispatched successfully
     */
    boolean sendCommitRequest(Object entityId, UUID targetBubble);

    /**
     * Send a 2PC AbortRequest to the target bubble (migration aborted / rolled back).
     *
     * @param entityId     entity whose migration is being aborted
     * @param targetBubble bubble the entity was migrating to
     * @return {@code true} if the request was dispatched successfully
     */
    boolean sendAbortRequest(Object entityId, UUID targetBubble);
}
