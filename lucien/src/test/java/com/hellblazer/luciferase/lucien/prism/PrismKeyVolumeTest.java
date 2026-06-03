/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.prism;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Luciferase-6hqr4: {@code PrismKey.getVolume} used a full-bbox base area (1/4^level) instead of the Kuhn half-cell
 * triangle area (0.5/4^level), giving load-balancing / partition-weight callers twice the true volume. It must now
 * agree with {@link PrismGeometry#computeVolume} (true triangle area * line height).
 *
 * @author hal.hildebrand
 */
class PrismKeyVolumeTest {

    @Test
    void getVolumeMatchesPrismGeometryComputeVolume() {
        for (int level = 1; level <= 6; level++) {
            var key = PrismKey.fromWorldCoordinates(0.2f, 0.1f, 0.3f, level);
            assertEquals(PrismGeometry.computeVolume(key), key.getVolume(), 1e-9f,
                         "PrismKey.getVolume must equal PrismGeometry.computeVolume at level " + level
                         + " (Luciferase-6hqr4)");
        }
    }
}
