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

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import com.hellblazer.luciferase.simulation.von.MigrationProtocolMessages;
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
        var testClock = new TestClock();
        testClock.setTime(1000L);

        var txnId = UUID.randomUUID();
        var token = new IdempotencyToken("entity-1", UUID.randomUUID(), UUID.randomUUID(),
                                         testClock.currentTimeMillis(), UUID.randomUUID());
        var snapshot = new EntitySnapshot("entity-1", new Point3D(0, 0, 0), "Content", UUID.randomUUID(), 1L, 1L,
                                          testClock.currentTimeMillis());

        // Create request
        var request = new MigrationProtocolMessages.PrepareRequest(txnId, token, snapshot, UUID.randomUUID(),
                                                                    UUID.randomUUID(), testClock);

        assertNotNull(request);
        assertEquals(txnId, request.transactionId());
        assertEquals(token, request.idempotencyToken());
        assertEquals(snapshot, request.entitySnapshot());
        assertEquals(1000L, request.timestamp());

        // Create response
        var response = new MigrationProtocolMessages.PrepareResponse(txnId, true, null, UUID.randomUUID(), testClock);

        assertNotNull(response);
        assertEquals(txnId, response.transactionId());
        assertTrue(response.success());
        assertNull(response.reason());
        assertEquals(1000L, response.timestamp());

        // Failure response
        var failResponse = new MigrationProtocolMessages.PrepareResponse(txnId, false, "Destination unreachable",
                                                                          null, testClock);

        assertFalse(failResponse.success());
        assertEquals("Destination unreachable", failResponse.reason());
    }

    @Test
    void testCommitRequestAndResponse() {
        var testClock = new TestClock();
        testClock.setTime(2000L);

        var txnId = UUID.randomUUID();

        // Create request
        var request = new MigrationProtocolMessages.CommitRequest(txnId, true, testClock);

        assertNotNull(request);
        assertEquals(txnId, request.transactionId());
        assertTrue(request.confirmed());
        assertEquals(2000L, request.timestamp());

        // Create response
        var response = new MigrationProtocolMessages.CommitResponse(txnId, true, null, testClock);

        assertNotNull(response);
        assertEquals(txnId, response.transactionId());
        assertTrue(response.success());
        assertNull(response.reason());
        assertEquals(2000L, response.timestamp());

        // Failure response
        var failResponse = new MigrationProtocolMessages.CommitResponse(txnId, false, "Add entity failed", testClock);

        assertFalse(failResponse.success());
        assertEquals("Add entity failed", failResponse.reason());
    }

    @Test
    void testAbortRequestAndResponse() {
        var testClock = new TestClock();
        testClock.setTime(3000L);

        var txnId = UUID.randomUUID();

        // Create request
        var request = new MigrationProtocolMessages.AbortRequest(txnId, "Timeout exceeded", testClock);

        assertNotNull(request);
        assertEquals(txnId, request.transactionId());
        assertEquals("Timeout exceeded", request.reason());
        assertEquals(3000L, request.timestamp());

        // Create response
        var response = new MigrationProtocolMessages.AbortResponse(txnId, true, testClock);

        assertNotNull(response);
        assertEquals(txnId, response.transactionId());
        assertTrue(response.rolledBack());
        assertEquals(3000L, response.timestamp());
    }

    @Test
    void testMessageTimestamps() {
        var testClock = new TestClock();
        testClock.setTime(1000L);

        var txnId = UUID.randomUUID();

        var request1 = new MigrationProtocolMessages.PrepareRequest(txnId, null, null, null, null, testClock);
        assertEquals(1000L, request1.timestamp());

        // Advance time
        testClock.setTime(1500L);

        var request2 = new MigrationProtocolMessages.PrepareRequest(txnId, null, null, null, null, testClock);
        assertEquals(1500L, request2.timestamp());

        // Second message should have later timestamp
        assertTrue(request2.timestamp() > request1.timestamp());

        // Messages with same data but different timestamps should be different
        // (timestamp is part of the record)
        assertNotEquals(request1.timestamp(), request2.timestamp());
    }
}
