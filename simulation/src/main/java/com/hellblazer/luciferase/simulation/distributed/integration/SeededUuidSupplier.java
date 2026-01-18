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

import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * UUID supplier that generates deterministic UUIDs from a seeded random source.
 * <p>
 * Use for deterministic testing where UUID generation must be reproducible.
 * Given the same seed, the sequence of generated UUIDs will always be identical.
 * <p>
 * Thread-safety: This class is NOT thread-safe. If multiple threads need deterministic
 * UUIDs, each should have its own SeededUuidSupplier instance.
 * <p>
 * Usage:
 * <pre>
 * // Create supplier with known seed
 * var supplier = new SeededUuidSupplier(12345L);
 *
 * // Generate deterministic UUIDs
 * UUID first = supplier.get();   // Always same UUID for same seed
 * UUID second = supplier.get();  // Next UUID in sequence
 *
 * // Inject into component
 * bubbleSplitter.setUuidSupplier(supplier);
 * </pre>
 *
 * @author hal.hildebrand
 * @see UUID#randomUUID()
 */
public class SeededUuidSupplier implements Supplier<UUID> {

    private final Random random;

    /**
     * Creates a UUID supplier with the given seed.
     * <p>
     * Same seed produces same sequence of UUIDs.
     *
     * @param seed the random seed
     */
    public SeededUuidSupplier(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Generates the next UUID in the deterministic sequence.
     * <p>
     * UUID is generated from two 64-bit random values using the seeded Random.
     * The UUID format follows standard UUID structure (version 4, variant 2)
     * but with predictable randomness.
     *
     * @return deterministic UUID
     */
    @Override
    public UUID get() {
        long mostSigBits = random.nextLong();
        long leastSigBits = random.nextLong();

        // Apply version 4 (random) bits: set version to 0100 (4)
        mostSigBits &= 0xffffffffffff0fffL;  // Clear version bits
        mostSigBits |= 0x0000000000004000L;  // Set version to 4

        // Apply variant bits: set variant to 10xx
        leastSigBits &= 0x3fffffffffffffffL;  // Clear variant bits
        leastSigBits |= 0x8000000000000000L;  // Set variant to 2

        return new UUID(mostSigBits, leastSigBits);
    }

    /**
     * Resets the random generator to its initial seed state.
     * <p>
     * After reset, the supplier will produce the same sequence of UUIDs
     * as when first created.
     *
     * @param seed the seed to reset to
     */
    public void reset(long seed) {
        random.setSeed(seed);
    }
}
