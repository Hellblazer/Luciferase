/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression test for Luciferase-0frcy.21: MigrationRouter.getValidDirections() must not throw
 * IllegalArgumentException for edge/corner cells. Pre-fix, validateTarget() called
 * direction.apply(source) before any bounds check; BubbleCoordinate's compact constructor throws
 * IAE for negative coordinates (e.g. row=0 + SOUTH, col=0 + WEST), so every edge cell exploded.
 */
class MigrationRouterEdgeCellTest {

    private MigrationRouter router(int rows, int columns) {
        var config = GridConfiguration.of(rows, columns, 100f, 100f);
        return new MigrationRouter(config);
    }

    @Test
    void getValidDirectionsAtOriginCornerDoesNotThrow() {
        var router = router(3, 3);
        var corner = new BubbleCoordinate(0, 0);  // SOUTH and WEST would underflow

        assertDoesNotThrow(() -> {
            var dirs = router.getValidDirections(corner);
            assertNotNull(dirs);
            // No direction whose target leaves the grid should be reported.
            assertFalse(dirs.contains(MigrationDirection.SOUTH), "SOUTH from row 0 is out of bounds");
            assertFalse(dirs.contains(MigrationDirection.WEST), "WEST from col 0 is out of bounds");
        });
    }

    @Test
    void getValidDirectionsAtFarCornerDoesNotThrow() {
        var router = router(3, 3);
        var farCorner = new BubbleCoordinate(2, 2);  // NORTH/EAST overflow the upper bound

        assertDoesNotThrow(() -> {
            var dirs = router.getValidDirections(farCorner);
            assertNotNull(dirs);
            assertFalse(dirs.contains(MigrationDirection.NORTH), "NORTH from last row is out of bounds");
            assertFalse(dirs.contains(MigrationDirection.EAST), "EAST from last column is out of bounds");
        });
    }

    @Test
    void getValidDirectionsAtEveryCellDoesNotThrow() {
        var router = router(4, 5);
        assertDoesNotThrow(() -> {
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 5; c++) {
                    var dirs = router.getValidDirections(new BubbleCoordinate(r, c));
                    assertNotNull(dirs);
                }
            }
        });
    }
}
