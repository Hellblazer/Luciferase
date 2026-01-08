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

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IdempotencyToken record.
 * <p>
 * Verifies:
 * - Token generation and uniqueness
 * - Equality and hashCode semantics
 * - Deterministic UUID generation from components
 * - Different tokens for different entities/processes
 *
 * @author hal.hildebrand
 */
class IdempotencyTokenTest {

    @Test
    void testTokenGeneration() {
        var entityId = "entity-1";
        var sourceProcess = UUID.randomUUID();
        var destProcess = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();
        var nonce = UUID.randomUUID();

        var token = new IdempotencyToken(entityId, sourceProcess, destProcess, timestamp, nonce);

        assertNotNull(token);
        assertEquals(entityId, token.entityId());
        assertEquals(sourceProcess, token.sourceProcessId());
        assertEquals(destProcess, token.destProcessId());
        assertEquals(timestamp, token.timestamp());
        assertEquals(nonce, token.nonce());
    }

    @Test
    void testTokenUUIDGeneration() {
        var entityId = "entity-1";
        var sourceProcess = UUID.randomUUID();
        var destProcess = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();
        var nonce = UUID.randomUUID();

        var token = new IdempotencyToken(entityId, sourceProcess, destProcess, timestamp, nonce);
        var uuid = token.toUUID();

        assertNotNull(uuid);

        // Same token should generate same UUID (deterministic)
        var uuid2 = token.toUUID();
        assertEquals(uuid, uuid2);
    }

    @Test
    void testTokenEqualityAndHashCode() {
        var entityId = "entity-1";
        var sourceProcess = UUID.randomUUID();
        var destProcess = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();
        var nonce = UUID.randomUUID();

        var token1 = new IdempotencyToken(entityId, sourceProcess, destProcess, timestamp, nonce);
        var token2 = new IdempotencyToken(entityId, sourceProcess, destProcess, timestamp, nonce);

        // Same components = equal tokens
        assertEquals(token1, token2);
        assertEquals(token1.hashCode(), token2.hashCode());

        // Equal tokens should generate same UUID
        assertEquals(token1.toUUID(), token2.toUUID());
    }

    @Test
    void testDifferentTokensForDifferentEntities() {
        var sourceProcess = UUID.randomUUID();
        var destProcess = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();
        var nonce = UUID.randomUUID();

        var token1 = new IdempotencyToken("entity-1", sourceProcess, destProcess, timestamp, nonce);
        var token2 = new IdempotencyToken("entity-2", sourceProcess, destProcess, timestamp, nonce);

        // Different entities = different tokens
        assertNotEquals(token1, token2);

        // Different tokens = different UUIDs
        assertNotEquals(token1.toUUID(), token2.toUUID());
    }

    @Test
    void testDifferentTokensForDifferentProcesses() {
        var entityId = "entity-1";
        var timestamp = System.currentTimeMillis();
        var nonce = UUID.randomUUID();

        var token1 = new IdempotencyToken(entityId, UUID.randomUUID(), UUID.randomUUID(), timestamp, nonce);
        var token2 = new IdempotencyToken(entityId, UUID.randomUUID(), UUID.randomUUID(), timestamp, nonce);

        // Different processes = different tokens
        assertNotEquals(token1, token2);
        assertNotEquals(token1.toUUID(), token2.toUUID());
    }

    @Test
    void testDifferentTokensForDifferentNonces() {
        var entityId = "entity-1";
        var sourceProcess = UUID.randomUUID();
        var destProcess = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();

        var token1 = new IdempotencyToken(entityId, sourceProcess, destProcess, timestamp, UUID.randomUUID());
        var token2 = new IdempotencyToken(entityId, sourceProcess, destProcess, timestamp, UUID.randomUUID());

        // Different nonces = different tokens
        assertNotEquals(token1, token2);
        assertNotEquals(token1.toUUID(), token2.toUUID());
    }

    @Test
    void testTokenUUIDsAreUnique() {
        var sourceProcess = UUID.randomUUID();
        var destProcess = UUID.randomUUID();

        var uuids = new HashSet<UUID>();

        // Generate 100 different tokens
        for (int i = 0; i < 100; i++) {
            var token = new IdempotencyToken(
                "entity-" + i,
                sourceProcess,
                destProcess,
                System.currentTimeMillis(),
                UUID.randomUUID()
            );
            uuids.add(token.toUUID());
        }

        // All UUIDs should be unique
        assertEquals(100, uuids.size());
    }
}
