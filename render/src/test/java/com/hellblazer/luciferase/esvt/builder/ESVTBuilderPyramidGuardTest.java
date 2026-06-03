/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvt.builder;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-rzn79: {@link ESVTBuilder#computeMortonChildIndex} calls {@link Tet#computeType(byte)},
 * which is undefined for pyramid-rooted tetrahedra (deferred to Luciferase-q3p). Verify the call site
 * fails loud and attributable rather than letting a deep IllegalStateException surface.
 *
 * @author hal.hildebrand
 */
class ESVTBuilderPyramidGuardTest {

    @Test
    void computeMortonChildIndex_rejectsPyramidRootedTet() {
        var builder = new ESVTBuilder();
        int cellSize = Constants.lengthAtLevel((byte) 5);
        // 6-arg constructor: pyramid-rooted (minTetLevel in [0, l]).
        Tet pyramidRooted = new Tet(2 * cellSize, 2 * cellSize, 2 * cellSize, (byte) 5, (byte) 0, (byte) 2);

        var ex = assertThrows(IllegalArgumentException.class,
                              () -> builder.computeMortonChildIndex(pyramidRooted));
        assertTrue(ex.getMessage().contains("computeMortonChildIndex"),
                   "message must name the operation, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Luciferase-q3p"),
                   "message must reference the deferral, got: " + ex.getMessage());
    }

    @Test
    void computeMortonChildIndex_acceptsPureTetree() {
        var builder = new ESVTBuilder();
        int cellSize = Constants.lengthAtLevel((byte) 5);
        Tet pure = new Tet(2 * cellSize, 2 * cellSize, 2 * cellSize, (byte) 5, (byte) 0);
        assertDoesNotThrow(() -> builder.computeMortonChildIndex(pure));
    }
}
