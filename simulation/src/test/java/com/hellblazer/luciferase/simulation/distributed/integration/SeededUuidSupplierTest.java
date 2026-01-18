/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SeededUuidSupplier determinism and correctness.
 *
 * @author hal.hildebrand
 */
class SeededUuidSupplierTest {

    @Test
    void testDeterministicGeneration() {
        long seed = 12345L;
        var supplier1 = new SeededUuidSupplier(seed);
        var supplier2 = new SeededUuidSupplier(seed);

        // Same seed should produce same sequence
        for (int i = 0; i < 100; i++) {
            assertEquals(supplier1.get(), supplier2.get(),
                "UUID #" + i + " should be identical for same seed");
        }
    }

    @Test
    void testDifferentSeedsProduceDifferentUuids() {
        var supplier1 = new SeededUuidSupplier(12345L);
        var supplier2 = new SeededUuidSupplier(54321L);

        // Different seeds should produce different UUIDs
        assertNotEquals(supplier1.get(), supplier2.get(),
            "Different seeds should produce different UUIDs");
    }

    @Test
    void testUuidsAreUnique() {
        var supplier = new SeededUuidSupplier(42L);
        var seen = new HashSet<UUID>();

        // Generate many UUIDs and ensure uniqueness
        for (int i = 0; i < 10000; i++) {
            UUID uuid = supplier.get();
            assertTrue(seen.add(uuid), "UUID should be unique, got duplicate at index " + i);
        }
    }

    @Test
    void testUuidVersion() {
        var supplier = new SeededUuidSupplier(99L);

        // All generated UUIDs should be version 4
        for (int i = 0; i < 100; i++) {
            UUID uuid = supplier.get();
            assertEquals(4, uuid.version(), "UUID should be version 4");
        }
    }

    @Test
    void testUuidVariant() {
        var supplier = new SeededUuidSupplier(99L);

        // All generated UUIDs should be variant 2 (IETF/RFC 4122)
        for (int i = 0; i < 100; i++) {
            UUID uuid = supplier.get();
            assertEquals(2, uuid.variant(), "UUID should be variant 2 (IETF)");
        }
    }

    @Test
    void testReset() {
        long seed = 7777L;
        var supplier = new SeededUuidSupplier(seed);

        // Generate some UUIDs
        UUID first = supplier.get();
        UUID second = supplier.get();
        supplier.get(); // third

        // Reset and verify same sequence
        supplier.reset(seed);
        assertEquals(first, supplier.get(), "First UUID should match after reset");
        assertEquals(second, supplier.get(), "Second UUID should match after reset");
    }

    @Test
    void testReproducibility() {
        // Test that same seed produces same first 10 UUIDs across multiple runs
        long[] seeds = {1L, 100L, Long.MAX_VALUE, Long.MIN_VALUE};

        for (long seed : seeds) {
            var run1 = new SeededUuidSupplier(seed);
            var run2 = new SeededUuidSupplier(seed);

            for (int i = 0; i < 10; i++) {
                assertEquals(run1.get(), run2.get(),
                    "Seed " + seed + " UUID #" + i + " should be reproducible");
            }
        }
    }
}
