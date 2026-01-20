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
package com.hellblazer.luciferase.esvo.dag.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MemoryPolicy} enum.
 * Verifies memory enforcement policies and default behavior.
 *
 * @author hal.hildebrand
 */
class MemoryPolicyTest {

    @Test
    void testAllValuesExist() {
        var values = MemoryPolicy.values();
        assertEquals(3, values.length, "Should have exactly 3 memory policies");
    }

    @Test
    void testEnumValuesDefined() {
        assertNotNull(MemoryPolicy.STRICT);
        assertNotNull(MemoryPolicy.WARN);
        assertNotNull(MemoryPolicy.ADAPTIVE);
    }

    @Test
    void testValuesAreDistinct() {
        var strict = MemoryPolicy.STRICT;
        var warn = MemoryPolicy.WARN;
        var adaptive = MemoryPolicy.ADAPTIVE;

        assertNotEquals(strict, warn);
        assertNotEquals(strict, adaptive);
        assertNotEquals(warn, adaptive);
    }

    @Test
    void testDefaultPolicyReturnsAdaptive() {
        assertEquals(MemoryPolicy.ADAPTIVE, MemoryPolicy.defaultPolicy(),
                     "Default memory policy should be ADAPTIVE");
    }

    @Test
    void testValueOfWorks() {
        assertEquals(MemoryPolicy.STRICT, MemoryPolicy.valueOf("STRICT"));
        assertEquals(MemoryPolicy.WARN, MemoryPolicy.valueOf("WARN"));
        assertEquals(MemoryPolicy.ADAPTIVE, MemoryPolicy.valueOf("ADAPTIVE"));
    }

    @Test
    void testValueOfInvalidThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> MemoryPolicy.valueOf("INVALID"));
    }

    @Test
    void testToStringReturnsName() {
        assertEquals("STRICT", MemoryPolicy.STRICT.toString());
        assertEquals("WARN", MemoryPolicy.WARN.toString());
        assertEquals("ADAPTIVE", MemoryPolicy.ADAPTIVE.toString());
    }
}
