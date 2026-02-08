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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.migration.EntitySnapshot;
import com.hellblazer.luciferase.simulation.distributed.migration.IdempotencyToken;

import java.util.UUID;

/**
 * Two-Phase Commit protocol messages for cross-process entity migration.
 * <p>
 * Implements Message for P2P transport between processes. All messages
 * include transaction ID for correlation and timestamp for ordering.
 * <p>
 * Protocol flow:
 * <pre>
 * Coordinator                          Participant
 *     |                                     |
 *     |--- PrepareRequest ----------------▶|
 *     |                                     | (remove entity from source)
 *     |◀---------------- PrepareResponse --|
 *     |                                     |
 *     |--- CommitRequest -----------------▶|
 *     |                                     | (add entity to dest)
 *     |◀---------------- CommitResponse ---|
 *     |                                     |
 *
 * OR (on failure):
 *     |--- AbortRequest ------------------▶|
 *     |                                     | (rollback - restore to source)
 *     |◀---------------- AbortResponse ----|
 * </pre>
 * <p>
 * Architecture Decision D6B.8: Messages extend Message for P2P transport.
 *
 * @author hal.hildebrand
 */
public sealed interface MigrationProtocolMessages extends Message {

    /**
     * PREPARE phase request: Initiate migration.
     * <p>
     * Sent from coordinator to source process.
     * Source should:
     * 1. Validate destination exists
     * 2. Remove entity from source bubble
     * 3. Store migration transaction
     * 4. Respond with PrepareResponse
     *
     * @param transactionId    Transaction UUID
     * @param idempotencyToken Idempotency token for deduplication
     * @param entitySnapshot   Entity state snapshot (for rollback)
     * @param sourceId         Source bubble UUID
     * @param destId           Destination bubble UUID
     * @param timestamp        Message timestamp
     */
    record PrepareRequest(UUID transactionId, IdempotencyToken idempotencyToken, EntitySnapshot entitySnapshot,
                          UUID sourceId, UUID destId, long timestamp) implements MigrationProtocolMessages {
        public PrepareRequest(UUID transactionId, IdempotencyToken idempotencyToken, EntitySnapshot entitySnapshot,
                              UUID sourceId, UUID destId, Clock clock) {
            this(transactionId, idempotencyToken, entitySnapshot, sourceId, destId, clock.currentTimeMillis());
        }
    }

    /**
     * PREPARE phase response: Acknowledge preparation.
     * <p>
     * Sent from source process back to coordinator.
     *
     * @param transactionId Transaction UUID
     * @param success       True if prepare succeeded, false if failed
     * @param reason        Failure reason (null if success)
     * @param destProcessId Destination process UUID (null if failed)
     * @param timestamp     Message timestamp
     */
    record PrepareResponse(UUID transactionId, boolean success, String reason, UUID destProcessId,
                           long timestamp) implements MigrationProtocolMessages {
        public PrepareResponse(UUID transactionId, boolean success, String reason, UUID destProcessId, Clock clock) {
            this(transactionId, success, reason, destProcessId, clock.currentTimeMillis());
        }
    }

    /**
     * COMMIT phase request: Finalize migration.
     * <p>
     * Sent from coordinator to destination process.
     * Destination should:
     * 1. Add entity to destination bubble
     * 2. Increment entity epoch
     * 3. Persist idempotency token
     * 4. Respond with CommitResponse
     *
     * @param transactionId Transaction UUID
     * @param confirmed     True to commit, false to abort (should not happen)
     * @param timestamp     Message timestamp
     */
    record CommitRequest(UUID transactionId, boolean confirmed, long timestamp) implements MigrationProtocolMessages {
        public CommitRequest(UUID transactionId, boolean confirmed, Clock clock) {
            this(transactionId, confirmed, clock.currentTimeMillis());
        }
    }

    /**
     * COMMIT phase response: Acknowledge commit.
     * <p>
     * Sent from destination process back to coordinator.
     *
     * @param transactionId Transaction UUID
     * @param success       True if commit succeeded, false if failed
     * @param reason        Failure reason (null if success)
     * @param timestamp     Message timestamp
     */
    record CommitResponse(UUID transactionId, boolean success, String reason,
                          long timestamp) implements MigrationProtocolMessages {
        public CommitResponse(UUID transactionId, boolean success, String reason, Clock clock) {
            this(transactionId, success, reason, clock.currentTimeMillis());
        }
    }

    /**
     * ABORT phase request: Rollback migration.
     * <p>
     * Sent from coordinator to source process on failure.
     * Source should:
     * 1. Restore entity to source bubble (from snapshot)
     * 2. Remove idempotency token (allow retry)
     * 3. Respond with AbortResponse
     *
     * @param transactionId Transaction UUID
     * @param reason        Abort reason
     * @param timestamp     Message timestamp
     */
    record AbortRequest(UUID transactionId, String reason, long timestamp) implements MigrationProtocolMessages {
        public AbortRequest(UUID transactionId, String reason, Clock clock) {
            this(transactionId, reason, clock.currentTimeMillis());
        }
    }

    /**
     * ABORT phase response: Acknowledge rollback.
     * <p>
     * Sent from source process back to coordinator.
     *
     * @param transactionId Transaction UUID
     * @param rolledBack    True if rollback succeeded, false if failed
     * @param timestamp     Message timestamp
     */
    record AbortResponse(UUID transactionId, boolean rolledBack, long timestamp) implements MigrationProtocolMessages {
        public AbortResponse(UUID transactionId, boolean rolledBack, Clock clock) {
            this(transactionId, rolledBack, clock.currentTimeMillis());
        }
    }
}
