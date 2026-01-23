/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.overlay;

import javafx.geometry.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OverlayPosition enum (T9).
 * Validates alignment values map correctly to JavaFX Pos.
 *
 * @author hal.hildebrand
 */
class OverlayPositionTest {

    @Test
    void testTopLeftAlignment() {
        assertEquals(Pos.TOP_LEFT, OverlayPosition.TOP_LEFT.getAlignment());
    }

    @Test
    void testTopRightAlignment() {
        assertEquals(Pos.TOP_RIGHT, OverlayPosition.TOP_RIGHT.getAlignment());
    }

    @Test
    void testBottomLeftAlignment() {
        assertEquals(Pos.BOTTOM_LEFT, OverlayPosition.BOTTOM_LEFT.getAlignment());
    }

    @Test
    void testBottomRightAlignment() {
        assertEquals(Pos.BOTTOM_RIGHT, OverlayPosition.BOTTOM_RIGHT.getAlignment());
    }

    @Test
    void testAllValuesPresent() {
        // Ensure all 4 positions are available
        var values = OverlayPosition.values();
        assertEquals(4, values.length);
    }
}
