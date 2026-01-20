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
package com.hellblazer.luciferase.sparse.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for PointerAddressingMode enum.
 *
 * @author hal.hildebrand
 */
class PointerAddressingModeTest {

    @Test
    void testRelativeMode() {
        assertEquals(PointerAddressingMode.RELATIVE, PointerAddressingMode.valueOf("RELATIVE"));
    }

    @Test
    void testAbsoluteMode() {
        assertEquals(PointerAddressingMode.ABSOLUTE, PointerAddressingMode.valueOf("ABSOLUTE"));
    }

    @Test
    void testValues() {
        assertEquals(2, PointerAddressingMode.values().length);
    }

    @Test
    void testEnumOrdinal() {
        assertEquals(0, PointerAddressingMode.RELATIVE.ordinal());
        assertEquals(1, PointerAddressingMode.ABSOLUTE.ordinal());
    }

    @Test
    void testEnumValuesContents() {
        var values = PointerAddressingMode.values();
        assertEquals(PointerAddressingMode.RELATIVE, values[0]);
        assertEquals(PointerAddressingMode.ABSOLUTE, values[1]);
    }

    @Test
    void testEnumToString() {
        assertEquals("RELATIVE", PointerAddressingMode.RELATIVE.toString());
        assertEquals("ABSOLUTE", PointerAddressingMode.ABSOLUTE.toString());
    }

    @Test
    void testValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> PointerAddressingMode.valueOf("INVALID"));
    }
}
