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
package com.hellblazer.luciferase.esvo.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VOLLoader - VOL format voxel file loader.
 *
 * @author hal.hildebrand
 */
class VOLLoaderTest {

    @Test
    @DisplayName("Load Stanford bunny 64³ from resources")
    void testLoadBunny64() throws Exception {
        var loader = new VOLLoader();
        var data = loader.loadResource("/voxels/bunny-64.vol");

        assertNotNull(data);
        assertNotNull(data.header());
        assertNotNull(data.voxels());

        var header = data.header();
        assertEquals(64, header.dimX());
        assertEquals(64, header.dimY());
        assertEquals(64, header.dimZ());
        assertEquals(3, header.version());

        // Bunny should have significant occupancy
        assertTrue(data.voxels().size() > 1000, "Bunny should have >1000 voxels");
        assertTrue(data.occupancyRatio() > 0.01f, "Occupancy should be >1%");
        assertTrue(data.occupancyRatio() < 0.5f, "Occupancy should be <50%");

        System.out.println("Bunny 64³: " + data.voxels().size() + " voxels (" +
                          String.format("%.1f%%", data.occupancyRatio() * 100) + " occupancy)");
    }

    @Test
    @DisplayName("Load Stanford bunny 128³ from resources")
    void testLoadBunny128() throws Exception {
        var loader = new VOLLoader();
        var data = loader.loadResource("/voxels/bunny-128.vol");

        assertNotNull(data);
        var header = data.header();
        assertEquals(128, header.dimX());
        assertEquals(128, header.dimY());
        assertEquals(128, header.dimZ());

        // Higher resolution should have more voxels
        assertTrue(data.voxels().size() > 5000, "128³ bunny should have >5000 voxels");

        System.out.println("Bunny 128³: " + data.voxels().size() + " voxels (" +
                          String.format("%.1f%%", data.occupancyRatio() * 100) + " occupancy)");
    }

    @Test
    @DisplayName("Scale voxels to target resolution")
    void testScaleVoxels() throws Exception {
        var loader = new VOLLoader();
        var data = loader.loadResource("/voxels/bunny-128.vol");

        // Scale 128³ down to 64³
        var scaled = loader.scaleVoxels(data, 64);

        assertNotNull(scaled);
        assertFalse(scaled.isEmpty());

        // All scaled coordinates should be within bounds
        for (var voxel : scaled) {
            assertTrue(voxel.x >= 0 && voxel.x < 64, "X should be in [0,64)");
            assertTrue(voxel.y >= 0 && voxel.y < 64, "Y should be in [0,64)");
            assertTrue(voxel.z >= 0 && voxel.z < 64, "Z should be in [0,64)");
        }

        System.out.println("Scaled from 128³ to 64³: " + data.voxels().size() +
                          " -> " + scaled.size() + " voxels");
    }

    @Test
    @DisplayName("VOL header parsing")
    void testHeaderParsing() throws Exception {
        var loader = new VOLLoader();
        var data = loader.loadResource("/voxels/bunny-64.vol");

        var header = data.header();

        // Verify expected header fields
        assertTrue(header.centerX() > 0, "Center-X should be positive");
        assertTrue(header.centerY() > 0, "Center-Y should be positive");
        assertTrue(header.centerZ() > 0, "Center-Z should be positive");
        assertEquals(1.0f, header.voxelSize(), 0.01f);
        assertEquals(0, header.alphaColor());
        assertEquals(64 * 64 * 64, header.totalVoxels());
    }
}
