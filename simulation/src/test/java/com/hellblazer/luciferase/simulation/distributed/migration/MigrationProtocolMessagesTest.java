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

import javafx.geometry.Point3D;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MigrationProtocolMessages.
 * <p>
 * Verifies:
 * - Message creation with required fields
 * - Message equality and timestamps
 * - All 6 message types (PrepareRequest/Response, CommitRequest/Response, AbortRequest/Response)
 * - Message interface implementation
 *
 * @author hal.hildebrand
 */
class MigrationProtocolMessagesTest {

    @Test
    void testPrepareRequestAndResponse() {
        var txnId = UUID.randomUUID();
        var token = new IdempotencyToken("entity-1", UUID.randomUUID(), UUID.randomUUID(),
                                         System.currentTimeMillis(), UUID.randomUUID());
        var snapshot = new EntitySnapshot("entity-1", new Point3D(0, 0, 0), "Content", UUID.randomUUID(), 1L, 1L,
                                          System.currentTimeMillis());

        // Create request
        var request = new MigrationProtocolMessages.PrepareRequest(txnId, token, snapshot, UUID.randomUUID(),
                                                                    UUID.randomUUID());

        assertNotNull(request);
        assertEquals(txnId, request.transactionId());
        assertEquals(token, request.idempotencyToken());
        assertEquals(snapshot, request.entitySnapshot());
        assertTrue(request.timestamp() > 0);

        // Create response
        var response = new MigrationProtocolMessages.PrepareResponse(txnId, true, null, UUID.randomUUID());

        assertNotNull(response);
        assertEquals(txnId, response.transactionId());
        assertTrue(response.success());
        assertNull(response.reason());
        assertTrue(response.timestamp() > 0);

        // Failure response
        var failResponse = new MigrationProtocolMessages.PrepareResponse(txnId, false, "Destination unreachable",
                                                                          null);

        assertFalse(failResponse.success());
        assertEquals("Destination unreachable", failResponse.reason());
    }

    @Test
    void testCommitRequestAndResponse() {
        var txnId = UUID.randomUUID();

        // Create request
        var request = new MigrationProtocolMessages.CommitRequest(txnId, true);

        assertNotNull(request);
        assertEquals(txnId, request.transactionId());
        assertTrue(request.confirmed());
        assertTrue(request.timestamp() > 0);

        // Create response
        var response = new MigrationProtocolMessages.CommitResponse(txnId, true, null);

        assertNotNull(response);
        assertEquals(txnId, response.transactionId());
        assertTrue(response.success());
        assertNull(response.reason());
        assertTrue(response.timestamp() > 0);

        // Failure response
        var failResponse = new MigrationProtocolMessages.CommitResponse(txnId, false, "Add entity failed");

        assertFalse(failResponse.success());
        assertEquals("Add entity failed", failResponse.reason());
    }

    @Test
    void testAbortRequestAndResponse() {
        var txnId = UUID.randomUUID();

        // Create request
        var request = new MigrationProtocolMessages.AbortRequest(txnId, "Timeout exceeded");

        assertNotNull(request);
        assertEquals(txnId, request.transactionId());
        assertEquals("Timeout exceeded", request.reason());
        assertTrue(request.timestamp() > 0);

        // Create response
        var response = new MigrationProtocolMessages.AbortResponse(txnId, true);

        assertNotNull(response);
        assertEquals(txnId, response.transactionId());
        assertTrue(response.rolledBack());
        assertTrue(response.timestamp() > 0);
    }

    @Test
    void testMessageTimestamps() throws InterruptedException {
        var txnId = UUID.randomUUID();

        var request1 = new MigrationProtocolMessages.PrepareRequest(txnId, null, null, null, null);

        Thread.sleep(10);

        var request2 = new MigrationProtocolMessages.PrepareRequest(txnId, null, null, null, null);

        // Second message should have later timestamp
        assertTrue(request2.timestamp() >= request1.timestamp());

        // Messages with same data but different timestamps should be different
        // (timestamp is part of the record)
        assertNotEquals(request1.timestamp(), request2.timestamp());
    }
}
