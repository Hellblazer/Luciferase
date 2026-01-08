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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Idempotency token for cross-process entity migrations.
 * <p>
 * Uniquely identifies a migration attempt using entity ID, source/destination process IDs,
 * timestamp, and a random nonce. Multiple messages with the same token represent the same
 * migration attempt and should be deduplicated.
 * <p>
 * Example usage:
 * <pre>
 * var token = new IdempotencyToken(
 *     "entity-123",
 *     sourceProcessId,
 *     destProcessId,
 *     System.currentTimeMillis(),
 *     UUID.randomUUID()
 * );
 * UUID tokenId = token.toUUID(); // Deterministic UUID for storage
 * </pre>
 * <p>
 * Architecture Decision D6B.8: Idempotency guarantees exactly-once migration semantics
 * even with duplicate messages from network retries or partitions.
 *
 * @param entityId        Entity being migrated
 * @param sourceProcessId Source process UUID
 * @param destProcessId   Destination process UUID
 * @param timestamp       Migration initiation timestamp
 * @param nonce           Random nonce for uniqueness
 * @author hal.hildebrand
 */
public record IdempotencyToken(
    String entityId,
    UUID sourceProcessId,
    UUID destProcessId,
    long timestamp,
    UUID nonce
) {

    /**
     * Generate a deterministic UUID from token components.
     * <p>
     * The UUID is generated using UUID.nameUUIDFromBytes() which applies MD5 hashing
     * to the concatenation of all token fields. This ensures:
     * - Same token components always produce the same UUID (deterministic)
     * - Different token components produce different UUIDs (collision-resistant)
     * - UUID can be used as a compact storage key
     *
     * @return Deterministic UUID for this token
     */
    public UUID toUUID() {
        // Concatenate all fields to create a unique string
        var combined = entityId + ":"
                       + sourceProcessId + ":"
                       + destProcessId + ":"
                       + timestamp + ":"
                       + nonce;

        // Generate deterministic UUID using MD5 hash
        return UUID.nameUUIDFromBytes(combined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate a migration key UUID based only on (entity, source, dest).
     * <p>
     * This is used for application-level idempotency - preventing duplicate
     * migrations of the same entity from source to dest, regardless of when
     * they were initiated or what nonce they have.
     * <p>
     * This is DIFFERENT from toUUID() which includes timestamp and nonce for
     * network-level deduplication (same gRPC message retried).
     *
     * @return Migration key UUID for application-level idempotency
     */
    public UUID migrationKey() {
        // Only entity + source + dest (no timestamp or nonce)
        var combined = entityId + ":"
                       + sourceProcessId + ":"
                       + destProcessId;

        // Generate deterministic UUID using MD5 hash
        return UUID.nameUUIDFromBytes(combined.getBytes(StandardCharsets.UTF_8));
    }
}
