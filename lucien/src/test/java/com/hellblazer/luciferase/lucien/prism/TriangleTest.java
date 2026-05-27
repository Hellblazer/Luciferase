/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.prism;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Anchor tests for {@link Triangle#consecutiveIndex()}.
 *
 * <p>RDR-009 Phase 2 replaced the prior positional packing
 * ({@code x + y·2^L + n·2^{2L} + type·2^{3L}}, the literature-rejected "semiquadcode") with the
 * real t8code tetrahedral-Morton <em>consecutive</em> index I(T). The collision-freeness tests
 * that pinned the old packing over arbitrary {@code (x, y, n, type)} tuples are obsolete — the
 * index no longer depends on {@code n}, and only valid S0 anchors ({@code y ≤ x}) occur. The new
 * contract (ancestor-grouping / contiguous children, dense per-level range, MAX_LEVEL ordering)
 * is pinned by {@link TriangleTmSfcTest}.
 */
class TriangleTest {

    @Test
    @DisplayName("consecutiveIndex root (level=0) is 0")
    void testRootIndexZero() {
        // Anchor for the SFC: the root triangle always indexes to 0. Used by PrismKey.createRoot
        // and asserted by PrismKeyTest.testCompositeSFC / PrismKeyTmSfcTest.
        assertEquals(0L, new Triangle(0, 0, 0, 0).consecutiveIndex());
    }
}
