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
 * Tests for {@link RetentionPolicy} enum.
 * Verifies SVO retention policies and default behavior.
 *
 * @author hal.hildebrand
 */
class RetentionPolicyTest {

    @Test
    void testAllValuesExist() {
        var values = RetentionPolicy.values();
        assertEquals(3, values.length, "Should have exactly 3 retention policies");
    }

    @Test
    void testEnumValuesDefined() {
        assertNotNull(RetentionPolicy.DISCARD);
        assertNotNull(RetentionPolicy.RETAIN);
        assertNotNull(RetentionPolicy.CACHE);
    }

    @Test
    void testValuesAreDistinct() {
        var discard = RetentionPolicy.DISCARD;
        var retain = RetentionPolicy.RETAIN;
        var cache = RetentionPolicy.CACHE;

        assertNotEquals(discard, retain);
        assertNotEquals(discard, cache);
        assertNotEquals(retain, cache);
    }

    @Test
    void testDefaultPolicyReturnsDiscard() {
        assertEquals(RetentionPolicy.DISCARD, RetentionPolicy.defaultPolicy(),
                     "Default retention policy should be DISCARD");
    }

    @Test
    void testValueOfWorks() {
        assertEquals(RetentionPolicy.DISCARD, RetentionPolicy.valueOf("DISCARD"));
        assertEquals(RetentionPolicy.RETAIN, RetentionPolicy.valueOf("RETAIN"));
        assertEquals(RetentionPolicy.CACHE, RetentionPolicy.valueOf("CACHE"));
    }

    @Test
    void testValueOfInvalidThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.valueOf("INVALID"));
    }

    @Test
    void testToStringReturnsName() {
        assertEquals("DISCARD", RetentionPolicy.DISCARD.toString());
        assertEquals("RETAIN", RetentionPolicy.RETAIN.toString());
        assertEquals("CACHE", RetentionPolicy.CACHE.toString());
    }
}
