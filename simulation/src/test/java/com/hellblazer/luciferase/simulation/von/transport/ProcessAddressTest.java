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

package com.hellblazer.luciferase.simulation.von.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProcessAddress record.
 *
 * @author hal.hildebrand
 */
class ProcessAddressTest {

    @Test
    void testValidConstruction() {
        var addr = new ProcessAddress("process-1", "127.0.0.1", 9999);
        assertEquals("process-1", addr.processId());
        assertEquals("127.0.0.1", addr.hostname());
        assertEquals(9999, addr.port());
    }

    @Test
    void testLocalhostFactory() {
        var addr = ProcessAddress.localhost("process-2", 8888);
        assertEquals("process-2", addr.processId());
        assertEquals("127.0.0.1", addr.hostname());
        assertEquals(8888, addr.port());
    }

    @Test
    void testToUrl() {
        var addr = new ProcessAddress("p1", "localhost", 1234);
        assertEquals("socket://localhost:1234", addr.toUrl());
    }

    @Test
    void testValidPort_Zero() {
        // Port 0 is valid for dynamic port allocation (standard Java practice)
        assertDoesNotThrow(() -> new ProcessAddress("p1", "localhost", 0),
            "Port 0 should be accepted for dynamic allocation");
    }

    @Test
    void testInvalidPort_Negative() {
        assertThrows(IllegalArgumentException.class, () ->
            new ProcessAddress("p1", "localhost", -1),
            "Negative port should be rejected"
        );
    }

    @Test
    void testInvalidPort_TooLarge() {
        assertThrows(IllegalArgumentException.class, () ->
            new ProcessAddress("p1", "localhost", 65536),
            "Port > 65535 should be rejected"
        );
    }

    @Test
    void testNullProcessId() {
        assertThrows(NullPointerException.class, () ->
            new ProcessAddress(null, "localhost", 9999),
            "Null processId should be rejected"
        );
    }

    @Test
    void testNullHostname() {
        assertThrows(NullPointerException.class, () ->
            new ProcessAddress("p1", null, 9999),
            "Null hostname should be rejected"
        );
    }

    @Test
    void testPortBoundaryValues() {
        // Port 1 (minimum valid)
        assertDoesNotThrow(() -> new ProcessAddress("p1", "localhost", 1));

        // Port 65535 (maximum valid)
        assertDoesNotThrow(() -> new ProcessAddress("p1", "localhost", 65535));
    }

    @Test
    void testEquality() {
        var addr1 = new ProcessAddress("p1", "127.0.0.1", 9999);
        var addr2 = new ProcessAddress("p1", "127.0.0.1", 9999);
        var addr3 = new ProcessAddress("p2", "127.0.0.1", 9999);

        assertEquals(addr1, addr2, "Same values should be equal");
        assertNotEquals(addr1, addr3, "Different processId should not be equal");
    }

    @Test
    void testHashCode() {
        var addr1 = new ProcessAddress("p1", "127.0.0.1", 9999);
        var addr2 = new ProcessAddress("p1", "127.0.0.1", 9999);

        assertEquals(addr1.hashCode(), addr2.hashCode(),
            "Equal objects should have same hash code");
    }
}
