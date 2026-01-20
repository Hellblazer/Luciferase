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
 * Tests for {@link CompressionMode} enum.
 * Verifies enum values, default mode, and string conversion.
 *
 * @author hal.hildebrand
 */
class CompressionModeTest {

    @Test
    void testAllValuesExist() {
        var values = CompressionMode.values();
        assertEquals(4, values.length, "Should have exactly 4 compression modes");
    }

    @Test
    void testEnumValuesDefined() {
        // Verify each expected enum value exists
        assertNotNull(CompressionMode.DISABLED);
        assertNotNull(CompressionMode.EXPLICIT);
        assertNotNull(CompressionMode.AUTO_ON_LOAD);
        assertNotNull(CompressionMode.AUTO_ON_IDLE);
    }

    @Test
    void testValuesAreDistinct() {
        var disabled = CompressionMode.DISABLED;
        var explicit = CompressionMode.EXPLICIT;
        var autoLoad = CompressionMode.AUTO_ON_LOAD;
        var autoIdle = CompressionMode.AUTO_ON_IDLE;

        assertNotEquals(disabled, explicit);
        assertNotEquals(disabled, autoLoad);
        assertNotEquals(disabled, autoIdle);
        assertNotEquals(explicit, autoLoad);
        assertNotEquals(explicit, autoIdle);
        assertNotEquals(autoLoad, autoIdle);
    }

    @Test
    void testDefaultModeReturnsExplicit() {
        assertEquals(CompressionMode.EXPLICIT, CompressionMode.defaultMode(),
                     "Default compression mode should be EXPLICIT");
    }

    @Test
    void testValueOfWorks() {
        assertEquals(CompressionMode.DISABLED, CompressionMode.valueOf("DISABLED"));
        assertEquals(CompressionMode.EXPLICIT, CompressionMode.valueOf("EXPLICIT"));
        assertEquals(CompressionMode.AUTO_ON_LOAD, CompressionMode.valueOf("AUTO_ON_LOAD"));
        assertEquals(CompressionMode.AUTO_ON_IDLE, CompressionMode.valueOf("AUTO_ON_IDLE"));
    }

    @Test
    void testValueOfInvalidThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> CompressionMode.valueOf("INVALID"));
    }

    @Test
    void testToStringReturnsName() {
        assertEquals("DISABLED", CompressionMode.DISABLED.toString());
        assertEquals("EXPLICIT", CompressionMode.EXPLICIT.toString());
        assertEquals("AUTO_ON_LOAD", CompressionMode.AUTO_ON_LOAD.toString());
        assertEquals("AUTO_ON_IDLE", CompressionMode.AUTO_ON_IDLE.toString());
    }
}
