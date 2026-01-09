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

import java.util.UUID;

/**
 * Immutable transaction state for Write-Ahead Log (WAL) persistence.
 * <p>
 * Captures all transaction data needed for crash recovery:
 * - Transaction identifiers (transactionId, idempotencyToken)
 * - Participating processes (sourceProcess, destProcess)
 * - Bubble endpoints (sourceBubble, destBubble)
 * - Entity state snapshot (for rollback - serializable form)
 * - Transaction phase (PREPARE, COMMIT, ABORT)
 * - Timestamps for TTL enforcement
 * <p>
 * Used by MigrationLogPersistence to record transactions in WAL.
 * On process restart, incomplete transactions are recovered and resolved.
 * <p>
 * Note: The snapshot is stored in a Jackson-serializable form (without Point3D).
 * For full entity restoration, recovery reads from source bubble state.
 *
 * @param transactionId    Unique transaction identifier (UUID)
 * @param entityId         String identifier of entity being migrated
 * @param sourceProcess    UUID of source process
 * @param destProcess      UUID of destination process
 * @param sourceBubble     UUID of source bubble
 * @param destBubble       UUID of destination bubble
 * @param snapshot         EntitySnapshot for rollback (serializable)
 * @param idempotencyToken UUID for idempotency deduplication
 * @param phase            MigrationPhase (PREPARE, COMMIT, ABORT)
 * @param timestamp        WAL record creation timestamp (ms)
 * @author hal.hildebrand
 */
public record TransactionState(
    UUID transactionId,
    String entityId,
    UUID sourceProcess,
    UUID destProcess,
    UUID sourceBubble,
    UUID destBubble,
    EntitySnapshot snapshot,
    UUID idempotencyToken,
    MigrationPhase phase,
    long timestamp
) {
    /**
     * MigrationPhase enum for transaction state tracking.
     */
    public enum MigrationPhase {
        PREPARE,  // Entity removed from source, awaiting commit
        COMMIT,   // Entity added to destination, migration complete
        ABORT     // Entity rolled back to source
    }

    /**
     * Jackson-serializable snapshot of essential entity state for WAL storage.
     * <p>
     * Stores only fields that Jackson can serialize (no Point3D).
     * Position is not persisted - recovery restores entity from source bubble state.
     *
     * @param entityId          Entity identifier
     * @param authorityBubbleId Authority bubble (for epoch tracking)
     * @param epoch             Entity epoch at snapshot time
     * @param version           Entity version within epoch
     * @param timestamp         Snapshot creation timestamp
     */
    public record SerializedSnapshot(
        String entityId,
        UUID authorityBubbleId,
        long epoch,
        long version,
        long timestamp
    ) {
    }
}
