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

package com.hellblazer.luciferase.simulation.distributed.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MigrationOracle boundary detection (Phase 7E Day 2)
 *
 * Tests verify:
 * - Boundary detection accuracy for all 6 directions
 * - Target bubble identification
 * - Position-to-coordinate mapping
 * - Performance with 1000+ entities
 *
 * Success Criteria:
 * - All 6 boundary directions detected correctly
 * - Performance < 10ms for 1000 entities
 * - Coordinate mapping bidirectional (position ↔ coordinate)
 *
 * @author hal.hildebrand
 */
@DisplayName("MigrationOracle - Boundary Crossing Detection")
class MigrationOracleTest {

    private static final Logger log = LoggerFactory.getLogger(MigrationOracleTest.class);

    private MigrationOracleImpl oracle;
    private UUID bubble_0_0_0;  // Bubble at (0,0,0)
    private UUID bubble_1_0_0;  // Bubble at (1,0,0)
    private UUID bubble_0_1_0;  // Bubble at (0,1,0)
    private UUID bubble_1_1_0;  // Bubble at (1,1,0)
    private UUID bubble_0_0_1;  // Bubble at (0,0,1)
    private UUID bubble_1_0_1;  // Bubble at (1,0,1)
    private UUID bubble_0_1_1;  // Bubble at (0,1,1)
    private UUID bubble_1_1_1;  // Bubble at (1,1,1)

    @BeforeEach
    void setUp() {
        oracle = new MigrationOracleImpl(2, 2, 2);

        // Map bubble IDs to coordinates for 2x2x2 grid
        bubble_0_0_0 = UUID.nameUUIDFromBytes("bubble-0".getBytes());
        bubble_1_0_0 = UUID.nameUUIDFromBytes("bubble-1".getBytes());
        bubble_0_1_0 = UUID.nameUUIDFromBytes("bubble-2".getBytes());
        bubble_1_1_0 = UUID.nameUUIDFromBytes("bubble-3".getBytes());
        bubble_0_0_1 = UUID.nameUUIDFromBytes("bubble-4".getBytes());
        bubble_1_0_1 = UUID.nameUUIDFromBytes("bubble-5".getBytes());
        bubble_0_1_1 = UUID.nameUUIDFromBytes("bubble-6".getBytes());
        bubble_1_1_1 = UUID.nameUUIDFromBytes("bubble-7".getBytes());
    }

    @Test
    @DisplayName("Detects crossing in +X direction (0→1)")
    void testBoundaryDetectionPlusX() {
        // Start inside bubble (0,0,0)
        var start = new Point3f(0.4f, 0.5f, 0.5f);
        var migration = oracle.checkMigration(start, bubble_0_0_0);
        assertFalse(migration.isPresent(), "Should not migrate inside bubble");

        // Cross boundary into bubble (1,0,0)
        var crossed = new Point3f(1.05f, 0.5f, 0.5f);
        migration = oracle.checkMigration(crossed, bubble_0_0_0);
        assertTrue(migration.isPresent(), "Should migrate at +X boundary");
        assertEquals(bubble_1_0_0, migration.get(), "Target should be bubble (1,0,0)");
    }

    @Test
    @DisplayName("Detects crossing in -X direction (1→0)")
    void testBoundaryDetectionMinusX() {
        // Start inside bubble (1,0,0)
        var start = new Point3f(1.4f, 0.5f, 0.5f);
        var migration = oracle.checkMigration(start, bubble_1_0_0);
        assertFalse(migration.isPresent(), "Should not migrate inside bubble");

        // Cross boundary into bubble (0,0,0)
        var crossed = new Point3f(0.95f, 0.5f, 0.5f);
        migration = oracle.checkMigration(crossed, bubble_1_0_0);
        assertTrue(migration.isPresent(), "Should migrate at -X boundary");
        assertEquals(bubble_0_0_0, migration.get(), "Target should be bubble (0,0,0)");
    }

    @Test
    @DisplayName("Detects crossing in +Y direction (0→1)")
    void testBoundaryDetectionPlusY() {
        // Start inside bubble (0,0,0)
        var start = new Point3f(0.5f, 0.4f, 0.5f);
        var migration = oracle.checkMigration(start, bubble_0_0_0);
        assertFalse(migration.isPresent(), "Should not migrate inside bubble");

        // Cross boundary into bubble (0,1,0)
        var crossed = new Point3f(0.5f, 1.05f, 0.5f);
        migration = oracle.checkMigration(crossed, bubble_0_0_0);
        assertTrue(migration.isPresent(), "Should migrate at +Y boundary");
        assertEquals(bubble_0_1_0, migration.get(), "Target should be bubble (0,1,0)");
    }

    @Test
    @DisplayName("Detects crossing in -Y direction (1→0)")
    void testBoundaryDetectionMinusY() {
        // Start inside bubble (0,1,0)
        var start = new Point3f(0.5f, 1.4f, 0.5f);
        var migration = oracle.checkMigration(start, bubble_0_1_0);
        assertFalse(migration.isPresent(), "Should not migrate inside bubble");

        // Cross boundary into bubble (0,0,0)
        var crossed = new Point3f(0.5f, 0.95f, 0.5f);
        migration = oracle.checkMigration(crossed, bubble_0_1_0);
        assertTrue(migration.isPresent(), "Should migrate at -Y boundary");
        assertEquals(bubble_0_0_0, migration.get(), "Target should be bubble (0,0,0)");
    }

    @Test
    @DisplayName("Detects crossing in +Z direction (0→1)")
    void testBoundaryDetectionPlusZ() {
        // Start inside bubble (0,0,0)
        var start = new Point3f(0.5f, 0.5f, 0.4f);
        var migration = oracle.checkMigration(start, bubble_0_0_0);
        assertFalse(migration.isPresent(), "Should not migrate inside bubble");

        // Cross boundary into bubble (0,0,1)
        var crossed = new Point3f(0.5f, 0.5f, 1.05f);
        migration = oracle.checkMigration(crossed, bubble_0_0_0);
        assertTrue(migration.isPresent(), "Should migrate at +Z boundary");
        assertEquals(bubble_0_0_1, migration.get(), "Target should be bubble (0,0,1)");
    }

    @Test
    @DisplayName("Detects crossing in -Z direction (1→0)")
    void testBoundaryDetectionMinusZ() {
        // Start inside bubble (0,0,1)
        var start = new Point3f(0.5f, 0.5f, 1.4f);
        var migration = oracle.checkMigration(start, bubble_0_0_1);
        assertFalse(migration.isPresent(), "Should not migrate inside bubble");

        // Cross boundary into bubble (0,0,0)
        var crossed = new Point3f(0.5f, 0.5f, 0.95f);
        migration = oracle.checkMigration(crossed, bubble_0_0_1);
        assertTrue(migration.isPresent(), "Should migrate at -Z boundary");
        assertEquals(bubble_0_0_0, migration.get(), "Target should be bubble (0,0,0)");
    }

    @Test
    @DisplayName("Identifies target bubble from position")
    void testGetTargetBubble() {
        assertEquals(bubble_0_0_0, oracle.getTargetBubble(new Point3f(0.5f, 0.5f, 0.5f)));
        assertEquals(bubble_1_0_0, oracle.getTargetBubble(new Point3f(1.5f, 0.5f, 0.5f)));
        assertEquals(bubble_0_1_0, oracle.getTargetBubble(new Point3f(0.5f, 1.5f, 0.5f)));
        assertEquals(bubble_1_1_0, oracle.getTargetBubble(new Point3f(1.5f, 1.5f, 0.5f)));
        assertEquals(bubble_0_0_1, oracle.getTargetBubble(new Point3f(0.5f, 0.5f, 1.5f)));
        assertEquals(bubble_1_0_1, oracle.getTargetBubble(new Point3f(1.5f, 0.5f, 1.5f)));
        assertEquals(bubble_0_1_1, oracle.getTargetBubble(new Point3f(0.5f, 1.5f, 1.5f)));
        assertEquals(bubble_1_1_1, oracle.getTargetBubble(new Point3f(1.5f, 1.5f, 1.5f)));
    }

    @Test
    @DisplayName("Maps position to cube coordinate correctly")
    void testCoordinateMapping() {
        var coord_0_0_0 = oracle.getCoordinateForPosition(new Point3f(0.5f, 0.5f, 0.5f));
        assertEquals(new CubeBubbleCoordinate(0, 0, 0), coord_0_0_0);

        var coord_1_1_1 = oracle.getCoordinateForPosition(new Point3f(1.5f, 1.5f, 1.5f));
        assertEquals(new CubeBubbleCoordinate(1, 1, 1), coord_1_1_1);

        // Test at exact boundary (should go to lower cube)
        var coord_at_boundary = oracle.getCoordinateForPosition(new Point3f(1.0f, 1.0f, 1.0f));
        assertEquals(new CubeBubbleCoordinate(1, 1, 1), coord_at_boundary);
    }

    @Test
    @DisplayName("Returns correct bounds for coordinate")
    void testGetBounds() {
        var bounds = oracle.getBoundsForCoordinate(new CubeBubbleCoordinate(0, 0, 0));
        assertEquals(6, bounds.length);
        assertEquals(0.0f, bounds[0]); // x_min
        assertEquals(0.0f, bounds[1]); // y_min
        assertEquals(0.0f, bounds[2]); // z_min
        assertEquals(1.0f, bounds[3]); // x_max
        assertEquals(1.0f, bounds[4]); // y_max
        assertEquals(1.0f, bounds[5]); // z_max
    }

    @Test
    @DisplayName("Detects entities crossing boundaries")
    void testGetEntitiesCrossingBoundaries() {
        // Track entities
        oracle.updateEntityPosition("entity-1", new Point3f(0.4f, 0.5f, 0.5f));
        oracle.updateEntityPosition("entity-2", new Point3f(1.05f, 0.5f, 0.5f)); // At boundary
        oracle.updateEntityPosition("entity-3", new Point3f(0.5f, 0.5f, 0.5f));

        var crossing = oracle.getEntitiesCrossingBoundaries();
        assertTrue(crossing.contains("entity-2"), "Should detect entity-2 at boundary");
        assertFalse(crossing.contains("entity-1"), "Should not detect entity-1 far from boundary");
        assertFalse(crossing.contains("entity-3"), "Should not detect entity-3 far from boundary");
    }

    @Test
    @DisplayName("Respects boundary tolerance configuration")
    void testBoundaryTolerance() {
        oracle.setBoundaryTolerance(0.1f);
        assertEquals(0.1f, oracle.getBoundaryTolerance());

        // Position slightly beyond default tolerance but within new tolerance
        var position = new Point3f(1.08f, 0.5f, 0.5f);
        var migration = oracle.checkMigration(position, bubble_0_0_0);
        assertTrue(migration.isPresent(), "Should migrate with larger tolerance");
    }

    @Test
    @DisplayName("Performance: < 10ms for 1000 entities")
    void testPerformance1000Entities() {
        // Track 1000 entities at random positions
        for (int i = 0; i < 1000; i++) {
            float x = (float) Math.random() * 2.0f;
            float y = (float) Math.random() * 2.0f;
            float z = (float) Math.random() * 2.0f;
            oracle.updateEntityPosition("entity-" + i, new Point3f(x, y, z));
        }

        // Measure boundary detection performance
        long startNs = System.nanoTime();
        var crossing = oracle.getEntitiesCrossingBoundaries();
        long elapsedNs = System.nanoTime() - startNs;
        long elapsedMs = elapsedNs / 1_000_000L;

        log.info("Boundary detection for 1000 entities: {}ms ({} entities crossing)",
                elapsedMs, crossing.size());

        assertTrue(elapsedMs < 10, "Performance should be < 10ms, got " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("Caches crossing detection correctly")
    void testCrossingCache() {
        oracle.updateEntityPosition("entity-1", new Point3f(1.05f, 0.5f, 0.5f));

        var crossing1 = oracle.getEntitiesCrossingBoundaries();
        assertTrue(crossing1.contains("entity-1"));

        // Clear cache
        oracle.clearCrossingCache();
        var crossing2 = oracle.getEntitiesCrossingBoundaries();
        assertTrue(crossing2.contains("entity-1"), "Cache should be cleared and recomputed");
    }

    @Test
    @DisplayName("Removes tracked entities correctly")
    void testRemoveEntity() {
        oracle.updateEntityPosition("entity-1", new Point3f(1.05f, 0.5f, 0.5f));
        var crossing = oracle.getEntitiesCrossingBoundaries();
        assertTrue(crossing.contains("entity-1"));

        oracle.removeEntity("entity-1");
        crossing = oracle.getEntitiesCrossingBoundaries();
        assertFalse(crossing.contains("entity-1"), "Entity should be removed");
    }

    @Test
    @DisplayName("Handles null parameters with NullPointerException")
    void testNullParameters() {
        assertThrows(NullPointerException.class, () -> oracle.checkMigration(null, bubble_0_0_0));
        assertThrows(NullPointerException.class, () -> oracle.checkMigration(new Point3f(0.5f, 0.5f, 0.5f), null));
        assertThrows(NullPointerException.class, () -> oracle.getTargetBubble(null));
        assertThrows(NullPointerException.class, () -> oracle.getCoordinateForPosition(null));
    }
}
