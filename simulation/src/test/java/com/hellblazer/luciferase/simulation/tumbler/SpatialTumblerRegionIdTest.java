/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.tumbler;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.117: region IDs must not collide between negative and
 * large-positive grid coordinates (sign-extension truncation bug).
 *
 * @author hal.hildebrand
 */
class SpatialTumblerRegionIdTest {

    @Test
    void regionIdIsInjectiveOverOriginCenteredBandIncludingNegatives() {
        // regionLevel 0 → regionGridSize = 1, so position == grid coordinate.
        var tumbler = new SpatialTumbler((byte) 0, 16.0);

        // The fix biases each signed grid coordinate into the unsigned 20-bit window before
        // masking, so an origin-centered world (mixed sign) packs with no per-axis aliasing.
        // Sweep a dense band of negative AND positive coordinates per axis and require all
        // resulting region IDs to be distinct (a collision would collapse spatially-distant
        // bubbles onto one region — the defect in Luciferase-0frcy.117).
        var ids = new java.util.HashSet<Long>();
        int n = 0;
        for (int x = -40; x <= 40; x++) {
            for (int y = -40; y <= 40; y += 13) {
                for (int z = -40; z <= 40; z += 17) {
                    long id = tumbler.getRegion(new Point3f(x, y, z)).regionId();
                    ids.add(id);
                    n++;
                }
            }
        }
        assertEquals(n, ids.size(),
                     "every distinct origin-centered grid coordinate must yield a distinct region ID");
    }

    @Test
    void distinctNegativeCoordinatesGetDistinctRegions() {
        var tumbler = new SpatialTumbler((byte) 0, 16.0);

        long origin = tumbler.getRegion(new Point3f(0f, 0f, 0f)).regionId();
        long negX = tumbler.getRegion(new Point3f(-1f, 0f, 0f)).regionId();
        long negY = tumbler.getRegion(new Point3f(0f, -1f, 0f)).regionId();
        long negZ = tumbler.getRegion(new Point3f(0f, 0f, -1f)).regionId();

        // All four must be distinct — a world centered at the origin must not collapse its
        // octants into a single region.
        assertEquals(4, java.util.Set.of(origin, negX, negY, negZ).size(),
                     "origin and its three negative neighbors must map to distinct regions");
    }
}
