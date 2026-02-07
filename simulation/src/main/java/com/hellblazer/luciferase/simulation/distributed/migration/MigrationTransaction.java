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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;

import java.util.UUID;

/**
 * Immutable transaction state for cross-process entity migration.
 * <p>
 * Represents an in-flight 2PC migration with:
 * - Transaction ID (globally unique)
 * - Idempotency token (for deduplication)
 * - Entity snapshot (for rollback)
 * - Source/destination references
 * - Current phase (PREPARE/COMMIT/ABORT)
 * - Start timestamp (for timeout detection)
 * <p>
 * Immutability ensures thread-safety and simplifies reasoning about state transitions.
 * Phase transitions create new transaction instances via advancePhase().
 * <p>
 * Example usage:
 * <pre>
 * // Create transaction
 * var txn = new MigrationTransaction(txnId, token, snapshot, source, dest);
 *
 * // Check timeout
 * if (txn.isTimedOut(300)) {
 *     txn = txn.advancePhase(MigrationPhase.ABORT);
 *     // ... rollback ...
 * } else {
 *     txn = txn.advancePhase(MigrationPhase.COMMIT);
 *     // ... commit ...
 * }
 * </pre>
 *
 * @param transactionId    Globally unique transaction ID
 * @param idempotencyToken Token for deduplication
 * @param entitySnapshot   Entity state snapshot (for rollback)
 * @param sourceRef        Source bubble reference
 * @param destRef          Destination bubble reference
 * @param phase            Current 2PC phase
 * @param startTime        Transaction start timestamp (milliseconds)
 * @author hal.hildebrand
 */
public record MigrationTransaction(UUID transactionId, IdempotencyToken idempotencyToken,
                                    EntitySnapshot entitySnapshot, BubbleReference sourceRef,
                                    BubbleReference destRef, MigrationPhase phase, long startTime) {

    /**
     * Create a new transaction in PREPARE phase.
     *
     * @param transactionId    Transaction ID
     * @param idempotencyToken Idempotency token
     * @param entitySnapshot   Entity snapshot
     * @param sourceRef        Source bubble
     * @param destRef          Destination bubble
     */
    public MigrationTransaction(UUID transactionId, IdempotencyToken idempotencyToken, EntitySnapshot entitySnapshot,
                                BubbleReference sourceRef, BubbleReference destRef) {
        this(transactionId, idempotencyToken, entitySnapshot, sourceRef, destRef, MigrationPhase.PREPARE,
             Clock.system().currentTimeMillis());
    }

    /**
     * Advance transaction to a new phase.
     * <p>
     * Returns a new MigrationTransaction instance with updated phase.
     * Original transaction is unchanged (immutable).
     *
     * @param newPhase New phase
     * @return New transaction with updated phase
     */
    public MigrationTransaction advancePhase(MigrationPhase newPhase) {
        return new MigrationTransaction(transactionId, idempotencyToken, entitySnapshot, sourceRef, destRef, newPhase,
                                        startTime);
    }

    /**
     * Check if transaction has exceeded timeout.
     *
     * @param timeoutMs Timeout in milliseconds
     * @return True if elapsed time > timeoutMs
     */
    public boolean isTimedOut(long timeoutMs) {
        return (Clock.system().currentTimeMillis() - startTime) > timeoutMs;
    }

    /**
     * Get elapsed time since transaction start.
     *
     * @return Elapsed milliseconds
     */
    public long elapsedTime() {
        return Clock.system().currentTimeMillis() - startTime;
    }
}
