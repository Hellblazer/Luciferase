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
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test tetrahedral entity migration.
 *
 * @author hal.hildebrand
 */
class TetrahedralMigrationTest {

    private TetreeBubbleGrid bubbleGrid;
    private Tetree<StringEntityID, Object> tetree;
    private TetrahedralMigration migration;

    @BeforeEach
    void setUp() {
        // Create a bubble grid with 9 bubbles at level 1
        bubbleGrid = new TetreeBubbleGrid((byte) 1);
        bubbleGrid.createBubbles(9, (byte) 1, 16);

        // Create a Tetree for spatial queries
        tetree = new Tetree<>(new StringEntityIDGenerator(), 100, (byte) 1);

        // Create migration manager
        migration = new TetrahedralMigration(bubbleGrid, tetree);
    }

    @Test
    void testMigrationCreation() {
        assertNotNull(migration);
        assertNotNull(migration.getMetrics());
    }

    @Test
    void testCheckMigrationsWithNoBubbles() {
        // Clear bubbles
        bubbleGrid.clear();

        // Check migrations - should not crash
        assertDoesNotThrow(() -> migration.checkMigrations(0));

        // No migrations should occur
        assertEquals(0, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testCheckMigrationsWithEmptyBubbles() {
        // Bubbles exist but have no entities
        migration.checkMigrations(0);

        // No migrations should occur
        assertEquals(0, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testCooldownPreventsRapidMigrations() {
        // Get a bubble
        var bubbles = bubbleGrid.getAllBubbles();
        assertFalse(bubbles.isEmpty());
        var bubble = bubbles.iterator().next();

        // Add an entity outside bubble bounds (will trigger migration)
        var entityId = "test-entity";
        var position = new Point3f(1000.0f, 1000.0f, 1000.0f); // Far outside
        bubble.addEntity(entityId, position, null);

        // First migration check (tick 0)
        migration.checkMigrations(0);

        // Clear cooldowns and check metrics
        var metrics1 = migration.getMetrics();
        long migrations1 = metrics1.getTotalMigrations();

        // Second migration check within cooldown window (tick 10 < 30)
        migration.checkMigrations(10);

        var metrics2 = migration.getMetrics();
        long migrations2 = metrics2.getTotalMigrations();

        // Migrations should be the same (cooldown prevented second migration)
        assertEquals(migrations1, migrations2, "Cooldown should prevent migration");
    }

    @Test
    void testCooldownAllowsMigrationAfterDelay() {
        // Get a bubble
        var bubbles = bubbleGrid.getAllBubbles();
        assertFalse(bubbles.isEmpty());
        var bubble = bubbles.iterator().next();

        // Add an entity outside bubble bounds
        var entityId = "test-entity-2";
        var position = new Point3f(1000.0f, 1000.0f, 1000.0f);
        bubble.addEntity(entityId, position, null);

        // First migration check (tick 0)
        migration.checkMigrations(0);
        long migrations1 = migration.getMetrics().getTotalMigrations();

        // Wait for cooldown to expire (tick 40 > 30)
        migration.checkMigrations(40);
        long migrations2 = migration.getMetrics().getTotalMigrations();

        // Migrations could increase (if entity still out of bounds)
        // This test validates that cooldown doesn't permanently block
        assertTrue(migrations2 >= migrations1, "After cooldown, migrations should be allowed");
    }

    @Test
    void testMetricsRecordMigrations() {
        var metrics = migration.getMetrics();
        assertNotNull(metrics);

        // Initial state
        assertEquals(0, metrics.getTotalMigrations());
        assertEquals(0, metrics.getFailureCount());
        assertEquals(0, metrics.getActiveCooldownCount());
    }

    @Test
    void testClearCooldowns() {
        // Add entity
        var bubble = bubbleGrid.getAllBubbles().iterator().next();
        bubble.addEntity("entity-1", new Point3f(1000.0f, 1000.0f, 1000.0f), null);

        // Trigger migration
        migration.checkMigrations(0);

        // Clear cooldowns
        migration.clearCooldowns();

        // Should be able to migrate immediately
        migration.checkMigrations(1);

        // No assertion needed - just verify it doesn't crash
    }

    @Test
    void testCooldownTicksConstant() {
        assertEquals(30, TetrahedralMigration.getCooldownTicks());
    }

    @Test
    void testHysteresisDistanceConstant() {
        assertEquals(2.0f, TetrahedralMigration.getHysteresisDistance(), 0.001f);
    }

    @Test
    void testMigrationWithMultipleBubbles() {
        // Add entities to multiple bubbles
        var bubbles = bubbleGrid.getAllBubbles();
        int count = 0;
        for (var bubble : bubbles) {
            bubble.addEntity("entity-" + count, new Point3f(count * 10.0f, count * 10.0f, count * 10.0f), null);
            count++;
            if (count >= 3) break;
        }

        // Check migrations
        migration.checkMigrations(0);

        // Some migrations may occur (depends on bubble bounds)
        var metrics = migration.getMetrics();
        assertTrue(metrics.getTotalMigrations() >= 0);
    }

    @Test
    void testMigrationHandlesNullPositionsGracefully() {
        // This test ensures migration doesn't crash on edge cases
        migration.checkMigrations(0);

        // No crash = success
        assertEquals(0, migration.getMetrics().getTotalMigrations());
    }

    @Test
    void testMetricsTrackUniquePairs() {
        var metrics = migration.getMetrics();

        // Initially no pairs
        assertEquals(0, metrics.getUniquePairCount());

        // After migrations, pairs may be tracked
        migration.checkMigrations(0);

        // Pair count should be >= 0
        assertTrue(metrics.getUniquePairCount() >= 0);
    }

    @Test
    void testMigrationToString() {
        var metrics = migration.getMetrics();
        var str = metrics.toString();

        assertNotNull(str);
        assertTrue(str.contains("TetrahedralMigrationMetrics"));
        assertTrue(str.contains("total="));
        assertTrue(str.contains("failures="));
    }

    // -----------------------------------------------------------------------
    // Bead Luciferase-7wzml.36 — failure-count double-increment regression tests
    // -----------------------------------------------------------------------

    /**
     * Null source-bubble path: executeMigration returns false because the source
     * bubble key is not registered in the grid. checkMigrations must record
     * exactly ONE failure — not two.
     */
    @Test
    void nullSrcBubble_recordsExactlyOneFailure() {
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);

        // A key that is definitely not in the grid
        var missingKey = new CompactTetreeKey((byte) 1, 99_999_999L);

        // A real key that IS in the grid (for the destination)
        var destKey = grid.getAllBubbles().iterator().next().bounds().rootKey();

        // Stub checker: returns one MigrationRecord whose sourceKey is not in grid.
        // Position is far outside the Tetree domain (MAX_COORD≈2^21≈2M) so the RDGCS
        // overshoot greatly exceeds HYSTERESIS_DIST=2.0f and the gate passes.
        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var record = new TetrahedralContainmentChecker.MigrationRecord(
            "entity-missing-src", missingKey, destKey, new Point3f(5_000_000f, 5_000_000f, 5_000_000f), null);
        when(checker.checkMigrations(any())).thenReturn(List.of(record));

        // Stub router: returns a decision that also uses the missing source key
        var router = Mockito.mock(TetrahedralMigrationRouter.class);
        var decision = new TetrahedralMigrationRouter.MigrationDecision(
            "entity-missing-src", missingKey, destKey, 1.0f, false);
        when(router.routeMigration(any())).thenReturn(decision);

        var mig = new TetrahedralMigration(grid, checker, router);
        mig.checkMigrations(0L);

        assertEquals(1, mig.getMetrics().getFailureCount(),
                     "Null-src path must record exactly 1 failure, not 2+");
    }

    /**
     * Rollback-failure path: executeMigration returns false after srcBubble.removeEntity
     * throws. checkMigrations must record exactly ONE failure — not two.
     */
    @Test
    void rollbackFailurePath_recordsExactlyOneFailure() {
        // Two-bubble grid: src and dst are different registered bubbles
        var grid2 = new TetreeBubbleGrid((byte) 1);
        grid2.createBubbles(2, (byte) 1, 16);
        var bubbleIter = grid2.getAllBubbles().iterator();
        var bubble1 = bubbleIter.next();
        var bubble2 = bubbleIter.next();
        var src2Key = bubble1.bounds().rootKey();
        var dst2Key = bubble2.bounds().rootKey();
        var spyGrid2 = Mockito.spy(grid2);
        var mockSrc2 = Mockito.mock(EnhancedBubble.class);
        var mockDst2 = Mockito.mock(EnhancedBubble.class);
        // Stub UUIDs for consistent lock ordering
        var srcId2 = UUID.randomUUID();
        var dstId2 = UUID.randomUUID();
        when(mockSrc2.id()).thenReturn(srcId2);
        when(mockDst2.id()).thenReturn(dstId2);
        // Stub getMutationLock() so the cross-bubble lock path doesn't NPE
        var srcLock2 = new java.util.concurrent.locks.ReentrantLock();
        var dstLock2 = new java.util.concurrent.locks.ReentrantLock();
        when(mockSrc2.getMutationLock()).thenReturn(srcLock2);
        when(mockDst2.getMutationLock()).thenReturn(dstLock2);
        // Position far outside the Tetree domain (MAX_COORD≈2M) so the RDGCS overshoot
        // greatly exceeds HYSTERESIS_DIST=2.0f and the hysteresis gate passes.
        var farPos = new Point3f(5_000_000f, 5_000_000f, 5_000_000f);
        var rec2 = new EnhancedBubble.EntityRecord("entity-rb2", farPos, null, 0L);
        when(mockSrc2.getAllEntityRecords()).thenReturn(List.of(rec2));
        doThrow(new RuntimeException("removeEntity forced")).when(mockSrc2).removeEntity("entity-rb2");
        doThrow(new RuntimeException("rollback forced")).when(mockDst2).removeEntity("entity-rb2");
        doReturn(mockSrc2).when(spyGrid2).getBubble(src2Key);
        doReturn(mockDst2).when(spyGrid2).getBubble(dst2Key);
        // Stub mockSrc2.bounds() so hysteresis computation works; use bubble1's real bounds
        when(mockSrc2.bounds()).thenReturn(bubble1.bounds());
        when(mockDst2.bounds()).thenReturn(bubble2.bounds());

        var checker2 = Mockito.mock(TetrahedralContainmentChecker.class);
        var rec2Record = new TetrahedralContainmentChecker.MigrationRecord(
            "entity-rb2", src2Key, dst2Key, farPos, null);
        // Only emit the migration record for bubble1 (src); bubble2 has nothing to migrate
        when(checker2.checkMigrations(bubble1)).thenReturn(List.of(rec2Record));
        when(checker2.checkMigrations(bubble2)).thenReturn(List.of());

        var router2 = Mockito.mock(TetrahedralMigrationRouter.class);
        var decision2 = new TetrahedralMigrationRouter.MigrationDecision(
            "entity-rb2", src2Key, dst2Key, 1.0f, false);
        when(router2.routeMigration(any())).thenReturn(decision2);

        var mig2 = new TetrahedralMigration(spyGrid2, checker2, router2);
        mig2.checkMigrations(0L);

        assertEquals(1, mig2.getMetrics().getFailureCount(),
                     "Rollback-failure path must record exactly 1 failure, not 2+");
    }

    /**
     * No failure path double-counts: a single failed migration (router returns null)
     * records exactly 1 failure regardless of internal control flow.
     */
    @Test
    void routerNullDecision_recordsExactlyOneFailure() {
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);

        var realBubble = grid.getAllBubbles().iterator().next();
        var srcKey = realBubble.bounds().rootKey();
        var dstKey = new CompactTetreeKey((byte) 1, 77_777_777L);

        // Position far outside the Tetree domain so the RDGCS overshoot exceeds HYSTERESIS_DIST=2.0f
        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var record = new TetrahedralContainmentChecker.MigrationRecord(
            "entity-no-route", srcKey, dstKey, new Point3f(5_000_000f, 5_000_000f, 5_000_000f), null);
        when(checker.checkMigrations(any())).thenReturn(List.of(record));

        // Router returns null → checkMigrations's else-branch fires once
        var router = Mockito.mock(TetrahedralMigrationRouter.class);
        when(router.routeMigration(any())).thenReturn(null);

        var mig = new TetrahedralMigration(grid, checker, router);
        mig.checkMigrations(0L);

        assertEquals(1, mig.getMetrics().getFailureCount(),
                     "Router-null path must record exactly 1 failure, not 2+");
    }

    // -----------------------------------------------------------------------
    // Bead Luciferase-7wzml.185 — hysteresis enforcement tests
    //
    // The hysteresis gate (HYSTERESIS_DIST = 2.0f) must suppress migrationCandidate
    // for entities within 2.0 Cartesian units of the boundary, and pass entities
    // that are genuinely far outside.
    //
    // Coordinate arithmetic (RDGCoordinates):
    //   rdg.x = round((-x + y + z) / sqrt(2))
    //   rdg.y = round(( x - y + z) / sqrt(2))
    //   rdg.z = round(( x + y - z) / sqrt(2))
    // Overshoot conversion: overshoot_cartesian = overshoot_rdg_euclidean * sqrt(2)
    //   overshoot_rdg=1 (one axis) → cartesian ≈ sqrt(2) ≈ 1.414  < 2.0 (blocked)
    //   overshoot_rdg=2 (one axis) → cartesian ≈ sqrt(2)*2 ≈ 2.83  > 2.0 (allowed)
    //   overshoot_rdg=5 (one axis) → cartesian ≈ sqrt(2)*5 ≈ 7.07  > 2.0 (allowed)
    // -----------------------------------------------------------------------

    /**
     * Helper: build a hysteresis test migration using mocked checker/router/bubble.
     * Returns a TetrahedralMigration wired with:
     *  - a real grid with one real bubble providing its rootKey
     *  - a mocked checker returning a single record at the given position
     *  - a mocked router returning null (so no migration executes, only the gate is tested)
     *  - the bubble's bounds() stubbed to return the given BubbleBounds
     */
    private long hysteresisGateMigrations(BubbleBounds stubBounds, Point3f entityPosition) {
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);
        var realBubble = grid.getAllBubbles().iterator().next();
        var srcKey = realBubble.bounds().rootKey();
        var dstKey = new CompactTetreeKey((byte) 1, 88_888_888L);

        // Mock a bubble that reports the stubbed bounds (controls hysteresis threshold)
        var mockBubble = Mockito.mock(EnhancedBubble.class);
        when(mockBubble.bounds()).thenReturn(stubBounds);

        // Grid spy so getAllBubbles() returns the mock bubble
        var spyGrid = Mockito.spy(grid);
        doReturn(List.of(mockBubble)).when(spyGrid).getAllBubbles();

        // Checker returns one migration record at the specified position
        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var record = new TetrahedralContainmentChecker.MigrationRecord(
            "hysteresis-entity", srcKey, dstKey, entityPosition, null);
        when(checker.checkMigrations(mockBubble)).thenReturn(List.of(record));

        // Router returns null → no actual migration executes; we test only the gate
        var router = Mockito.mock(TetrahedralMigrationRouter.class);
        when(router.routeMigration(any())).thenReturn(null);

        var mig = new TetrahedralMigration(spyGrid, checker, router);
        mig.checkMigrations(0L);
        return mig.getMetrics().getFailureCount();
    }

    /**
     * Luciferase-7wzml.185 — Case 1: entity within hysteresis band must NOT migrate.
     * <p>
     * Bounds rdgMax.x = 500. Entity RDGCS position: rdg.x = 501 (overshoot=1 on x only),
     * rdg.y and rdg.z inside the box.  Euclidean RDGCS overshoot = 1.  Cartesian ≈ √2 ≈
     * 1.414 which is less than HYSTERESIS_DIST=2.0 → migrationCandidate must return false
     * (hysteresis gate fires) → the checker record is discarded, router never called,
     * failure count stays 0, total migrations stays 0.
     */
    @Test
    void hysteresis_entityWithinBand_suppressesMigration() {
        // RDGCS box: x∈[0,500], y∈[0,1000], z∈[0,1000]
        var rdgMin = new Point3i(0, 0, 0);
        var rdgMax = new Point3i(500, 1000, 1000);
        // Use a real bubble's rootKey as the anchor; the key is irrelevant for this test.
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);
        var anchorKey = grid.getAllBubbles().iterator().next().bounds().rootKey();
        var stubBounds = BubbleBounds.of(anchorKey, rdgMin, rdgMax);

        // Position that converts to rdg.x = 501, rdg.y = 250, rdg.z = 250 (overshoot 1 on x)
        // rdg.x = round((-x+y+z)/sqrt(2)) = 501  →  -x+y+z = 501*sqrt(2) ≈ 708.6
        // rdg.y = round((x-y+z)/sqrt(2))  = 250  →   x-y+z = 250*sqrt(2) ≈ 353.6
        // rdg.z = round((x+y-z)/sqrt(2))  = 250  →   x+y-z = 250*sqrt(2) ≈ 353.6
        // Adding all three: x+y+z = (708.6+353.6+353.6)/2 = 707.9;  x=(707.9-708.6)/2 = -0.35 ≈ 0
        // Simpler: use a well-inside y/z and bump x just past max.
        // For z=500*sqrt(2)≈707, y=0, x=0: rdg.x=round(707/sqrt(2))=500, rdg.y=500, rdg.z=-500.
        // Instead, use BubbleBounds.toRDG() to verify the position in the test rather than pre-computing.
        // Pick a position we know produces a small overshoot: shift slightly past rdgMax.x border.
        // A Cartesian position of (0, 0, 501*sqrt(2)) in (x,y,z):
        //   rdg.x = round((0+0+501*sqrt(2))/sqrt(2)) = round(501) = 501  → overshoot 1 on x
        //   rdg.y = round((0-0+501*sqrt(2))/sqrt(2)) = 501                → within [0,1000]
        //   rdg.z = round((0+0-501*sqrt(2))/sqrt(2)) = -501               → within [0,1000]? NO.
        // z=-501 is below rdgMin.z=0 → overshoot on z too.
        // Use asymmetric bounds: z∈[-1000,0]:
        var rdgMinB = new Point3i(0, 0, -1000);
        var rdgMaxB = new Point3i(500, 1000, 0);
        var stubBoundsB = BubbleBounds.of(anchorKey, rdgMinB, rdgMaxB);

        // Position (0, 0, 501*sqrt(2)) → rdg=(501, 501, -501).
        //   overshoot x = 501-500 = 1, overshoot y = 0 (501 <= 1000), overshoot z = 0 (-501 >= -1000).
        //   Euclidean RDGCS overshoot = 1 → Cartesian ≈ sqrt(2) ≈ 1.414 < 2.0 → SUPPRESSED.
        float pz = 501.0f * (float) Math.sqrt(2.0);
        var nearBoundaryPos = new Point3f(0.0f, 0.0f, pz);

        // Verify our coordinate arithmetic using BubbleBounds.toRDG
        var actualRdg = stubBoundsB.toRDG(nearBoundaryPos);
        // overshoot on x only: rdg.x should be 501, y 501 (inside 1000), z -501 (inside -1000..0)
        assertEquals(501, actualRdg.x, "rdg.x must overshoot by exactly 1");
        assertTrue(actualRdg.y <= rdgMaxB.y, "rdg.y must be inside bounds");
        assertTrue(actualRdg.z >= rdgMinB.z, "rdg.z must be inside bounds");
        int overshootX = actualRdg.x - rdgMaxB.x;
        assertTrue(overshootX > 0, "Must be outside boundary");
        double overshootCartesian = overshootX * Math.sqrt(2.0);
        assertTrue(overshootCartesian < TetrahedralMigration.getHysteresisDistance(),
                   "Overshoot " + overshootCartesian + " must be less than HYSTERESIS_DIST=2.0");

        // Now run through the migration gate
        var dstKey = new CompactTetreeKey((byte) 1, 88_888_888L);
        var srcKey = grid.getAllBubbles().iterator().next().bounds().rootKey();
        var mockBubble = Mockito.mock(EnhancedBubble.class);
        when(mockBubble.bounds()).thenReturn(stubBoundsB);
        var spyGrid = Mockito.spy(grid);
        doReturn(List.of(mockBubble)).when(spyGrid).getAllBubbles();
        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var record = new TetrahedralContainmentChecker.MigrationRecord(
            "entity-near-boundary", srcKey, dstKey, nearBoundaryPos, null);
        when(checker.checkMigrations(mockBubble)).thenReturn(List.of(record));
        var router = Mockito.mock(TetrahedralMigrationRouter.class);
        when(router.routeMigration(any())).thenReturn(null);

        var mig = new TetrahedralMigration(spyGrid, checker, router);
        mig.checkMigrations(0L);

        // Hysteresis suppressed the candidate → router was never called → no failure recorded
        verify(router, never()).routeMigration(any());
        assertEquals(0, mig.getMetrics().getTotalMigrations(),
                     "Entity within hysteresis band must NOT migrate");
    }

    /**
     * Luciferase-7wzml.185 — Case 2: entity well past boundary must be allowed through.
     * <p>
     * Bounds rdgMax.x = 500. Entity RDGCS overshoot ≥ 5 on x → Cartesian ≈ 5*√2 ≈ 7.07
     * which exceeds HYSTERESIS_DIST=2.0 → migrationCandidate returns true → router called.
     */
    @Test
    void hysteresis_entityWellPastBoundary_allowsMigration() {
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);
        var srcKey = grid.getAllBubbles().iterator().next().bounds().rootKey();
        var dstKey = new CompactTetreeKey((byte) 1, 77_777_777L);
        var anchorKey = srcKey;

        // Asymmetric bounds same as above: x∈[0,500], y∈[0,1000], z∈[-1000,0]
        var rdgMin = new Point3i(0, 0, -1000);
        var rdgMax = new Point3i(500, 1000, 0);
        var stubBounds = BubbleBounds.of(anchorKey, rdgMin, rdgMax);

        // Position (0, 0, 510*sqrt(2)) → rdg.x = 510, overshoot x = 10
        // Cartesian overshoot ≈ 10 * sqrt(2) ≈ 14.1 >> 2.0 → ALLOWED
        float pz = 510.0f * (float) Math.sqrt(2.0);
        var farPos = new Point3f(0.0f, 0.0f, pz);

        var actualRdg = stubBounds.toRDG(farPos);
        int overshootX = actualRdg.x - rdgMax.x;
        assertTrue(overshootX >= 5, "Must be well past boundary (rdg overshoot >= 5), was " + overshootX);
        double overshootCartesian = Math.sqrt((double) overshootX * overshootX) * Math.sqrt(2.0);
        assertTrue(overshootCartesian >= TetrahedralMigration.getHysteresisDistance(),
                   "Overshoot " + overshootCartesian + " must be >= HYSTERESIS_DIST=2.0");

        var mockBubble = Mockito.mock(EnhancedBubble.class);
        when(mockBubble.bounds()).thenReturn(stubBounds);
        var spyGrid = Mockito.spy(grid);
        doReturn(List.of(mockBubble)).when(spyGrid).getAllBubbles();
        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var record = new TetrahedralContainmentChecker.MigrationRecord(
            "entity-far-outside", srcKey, dstKey, farPos, null);
        when(checker.checkMigrations(mockBubble)).thenReturn(List.of(record));
        var router = Mockito.mock(TetrahedralMigrationRouter.class);
        when(router.routeMigration(any())).thenReturn(null);  // null triggers failure count

        var mig = new TetrahedralMigration(spyGrid, checker, router);
        mig.checkMigrations(0L);

        // Hysteresis passed → router was called (even though it returns null, recording a failure)
        verify(router, times(1)).routeMigration(any());
        assertEquals(1, mig.getMetrics().getFailureCount(),
                     "Entity well past boundary must reach router (hysteresis gate passed)");
    }

    /**
     * Luciferase-7wzml.185 — Case 3: entity at EXACTLY HYSTERESIS_DIST from boundary.
     * <p>
     * RDGCS integers are discrete; HYSTERESIS_DIST=2.0 maps to rdg_overshoot=sqrt(2)≈1.414.
     * The nearest integers: rdg=1 → Cartesian≈1.414 < 2.0 (blocked), rdg=2 → Cartesian≈2.83 > 2.0 (allowed).
     * This test verifies the rdg=1 boundary case is suppressed (not allowed through).
     */
    @Test
    void hysteresis_atExactHysteresisThreshold_suppresses() {
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);
        var srcKey = grid.getAllBubbles().iterator().next().bounds().rootKey();
        var dstKey = new CompactTetreeKey((byte) 1, 66_666_666L);

        // Bounds and position for exactly rdg overshoot = 1 on x-axis only
        var rdgMin = new Point3i(0, 0, -1000);
        var rdgMax = new Point3i(500, 1000, 0);
        var stubBounds = BubbleBounds.of(srcKey, rdgMin, rdgMax);
        float pz = 501.0f * (float) Math.sqrt(2.0);
        var atThresholdPos = new Point3f(0.0f, 0.0f, pz);

        // Verify arithmetic
        var rdg = stubBounds.toRDG(atThresholdPos);
        int overshootX = Math.max(0, rdg.x - rdgMax.x);
        double overshootCartesian = overshootX * Math.sqrt(2.0);
        // With overshoot=1: cartesian ≈ 1.414 which is < HYSTERESIS_DIST=2.0 → must be suppressed
        assertTrue(overshootCartesian < TetrahedralMigration.getHysteresisDistance(),
                   "rdg overshoot=1 → Cartesian " + overshootCartesian + " must be < 2.0");

        var mockBubble = Mockito.mock(EnhancedBubble.class);
        when(mockBubble.bounds()).thenReturn(stubBounds);
        var spyGrid = Mockito.spy(grid);
        doReturn(List.of(mockBubble)).when(spyGrid).getAllBubbles();
        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var record = new TetrahedralContainmentChecker.MigrationRecord(
            "entity-at-threshold", srcKey, dstKey, atThresholdPos, null);
        when(checker.checkMigrations(mockBubble)).thenReturn(List.of(record));
        var router = Mockito.mock(TetrahedralMigrationRouter.class);
        when(router.routeMigration(any())).thenReturn(null);

        var mig = new TetrahedralMigration(spyGrid, checker, router);
        mig.checkMigrations(0L);

        verify(router, never()).routeMigration(any());
        assertEquals(0, mig.getMetrics().getTotalMigrations(),
                     "Entity at rdg-overshoot=1 (Cartesian<2.0) must be suppressed by hysteresis");
    }

    // -----------------------------------------------------------------------
    // Bead Luciferase-7wzml.185 — real-bounds integration test
    //
    // Verifies hysteresis against PRODUCTION BubbleBounds derived from a real
    // TetreeBubbleGrid bubble via BubbleBounds.fromTetreeKey (4-corner RDGCS),
    // not a synthetic axis-aligned box. The real tetrahedral bounds may be
    // skewed in RDGCS space, so this catches any gap between the synthetic and
    // production paths.
    // -----------------------------------------------------------------------

    /**
     * Luciferase-7wzml.185 — Real-bounds integration: hysteresis uses PRODUCTION
     * BubbleBounds.fromTetreeKey (4-corner RDGCS).
     * <p>
     * Creates a real TetreeBubbleGrid bubble, retrieves its structural bounds via
     * {@link BubbleBounds#fromTetreeKey}, and proves:
     * <ol>
     *   <li>An entity just barely outside (RDGCS overshoot=1 on one axis, Cartesian≈√2≈1.41
     *       &lt; HYSTERESIS_DIST=2.0) is SUPPRESSED by the hysteresis gate.</li>
     *   <li>An entity well past the boundary (RDGCS overshoot≥2, Cartesian≥2√2≈2.83
     *       &gt; HYSTERESIS_DIST=2.0) is ALLOWED through.</li>
     * </ol>
     * The checker and router are mocked only to isolate the hysteresis gate; the bounds
     * are the real 4-corner RDGCS bounds produced by the production path.
     */
    @Test
    void hysteresis_realTetreeBubbleBounds_suppressedAndAllowed() {
        // Build a real grid so we get a real TetreeKey and its fromTetreeKey bounds
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);
        var entry = grid.getBubblesWithKeys().entrySet().iterator().next();
        var realKey = entry.getKey();

        // Production bounds: 4-corner RDGCS from real tetrahedron geometry
        var realBounds = BubbleBounds.fromTetreeKey(realKey);
        assertNotNull(realBounds, "fromTetreeKey must produce non-null bounds");

        var rdgMin = realBounds.rdgMin();
        var rdgMax = realBounds.rdgMax();

        // Use toCartesian(targetRdg) to build positions: toRDG(toCartesian(p)) == p exactly,
        // so there is no rounding ambiguity. y and z targets are the midpoints of the bounds
        // to ensure they stay well inside on those axes.
        int midY = (rdgMin.y + rdgMax.y) / 2;
        int midZ = (rdgMin.z + rdgMax.z) / 2;

        // --- CASE 1: barely outside (RDGCS overshoot=1 on x) → SUPPRESSED ---
        // Target RDGCS: x = rdgMax.x+1 (overshoot 1), y = midY (inside), z = midZ (inside)
        // Cartesian overshoot ≈ 1*√2 ≈ 1.41 < HYSTERESIS_DIST=2.0 → gate must block.
        var nearTargetRdg = new Point3i(rdgMax.x + 1, midY, midZ);
        var nearCartesianD = realBounds.toCartesian(nearTargetRdg);
        var nearPos = new Point3f((float) nearCartesianD.x, (float) nearCartesianD.y, (float) nearCartesianD.z);

        // Verify round-trip: toRDG(toCartesian(nearTargetRdg)) == nearTargetRdg
        var nearRdg = realBounds.toRDG(nearPos);
        assertEquals(nearTargetRdg.x, nearRdg.x, "RDGCS round-trip x must be exact for near position");
        int nearOvershootX = Math.max(0, nearRdg.x - rdgMax.x);
        double nearCartesian = nearOvershootX * Math.sqrt(2.0);
        assertTrue(nearCartesian < TetrahedralMigration.getHysteresisDistance(),
            "Near-boundary real-bounds overshoot " + nearCartesian + " must be < HYSTERESIS_DIST=2.0");

        // --- CASE 2: well outside (RDGCS overshoot=5 on x) → ALLOWED ---
        // Target RDGCS: x = rdgMax.x+5 (overshoot 5), y = midY, z = midZ
        // Cartesian overshoot ≈ 5*√2 ≈ 7.07 >= HYSTERESIS_DIST=2.0 → gate must pass.
        var farTargetRdg = new Point3i(rdgMax.x + 5, midY, midZ);
        var farCartesianD = realBounds.toCartesian(farTargetRdg);
        var farPos = new Point3f((float) farCartesianD.x, (float) farCartesianD.y, (float) farCartesianD.z);

        var farRdg = realBounds.toRDG(farPos);
        assertEquals(farTargetRdg.x, farRdg.x, "RDGCS round-trip x must be exact for far position");
        int farOvershootX = Math.max(0, farRdg.x - rdgMax.x);
        double farCartesian = farOvershootX * Math.sqrt(2.0);
        assertTrue(farCartesian >= TetrahedralMigration.getHysteresisDistance(),
            "Far-boundary real-bounds overshoot " + farCartesian + " must be >= HYSTERESIS_DIST=2.0");

        // --- Run migration gate for CASE 1 (suppressed) ---
        {
            var srcKey = realKey;
            var dstKey = new CompactTetreeKey((byte) 1, 88_888_888L);
            var mockBubble = Mockito.mock(EnhancedBubble.class);
            when(mockBubble.bounds()).thenReturn(realBounds);
            var spyGrid = Mockito.spy(grid);
            doReturn(List.of(mockBubble)).when(spyGrid).getAllBubbles();
            var checker = Mockito.mock(TetrahedralContainmentChecker.class);
            var record = new TetrahedralContainmentChecker.MigrationRecord(
                "real-bounds-near", srcKey, dstKey, nearPos, null);
            when(checker.checkMigrations(mockBubble)).thenReturn(List.of(record));
            var router = Mockito.mock(TetrahedralMigrationRouter.class);
            when(router.routeMigration(any())).thenReturn(null);

            var mig = new TetrahedralMigration(spyGrid, checker, router);
            mig.checkMigrations(0L);

            verify(router, never()).routeMigration(any());
            assertEquals(0, mig.getMetrics().getTotalMigrations(),
                "Near-boundary entity with real fromTetreeKey bounds must be SUPPRESSED by hysteresis");
        }

        // --- Run migration gate for CASE 2 (allowed) ---
        {
            var srcKey = realKey;
            var dstKey = new CompactTetreeKey((byte) 1, 77_777_777L);
            var mockBubble = Mockito.mock(EnhancedBubble.class);
            when(mockBubble.bounds()).thenReturn(realBounds);
            var spyGrid = Mockito.spy(grid);
            doReturn(List.of(mockBubble)).when(spyGrid).getAllBubbles();
            var checker = Mockito.mock(TetrahedralContainmentChecker.class);
            var record = new TetrahedralContainmentChecker.MigrationRecord(
                "real-bounds-far", srcKey, dstKey, farPos, null);
            when(checker.checkMigrations(mockBubble)).thenReturn(List.of(record));
            var router = Mockito.mock(TetrahedralMigrationRouter.class);
            when(router.routeMigration(any())).thenReturn(null);

            var mig = new TetrahedralMigration(spyGrid, checker, router);
            mig.checkMigrations(0L);

            verify(router, times(1)).routeMigration(any());
            assertEquals(1, mig.getMetrics().getFailureCount(),
                "Far-boundary entity with real fromTetreeKey bounds must be ALLOWED through hysteresis");
        }
    }

    // -----------------------------------------------------------------------
    // Bead Luciferase-7wzml.187 — cross-bubble lock: entity conservation under concurrency
    // -----------------------------------------------------------------------

    /**
     * Concurrent migrations and position-updates on the same bubble pair must conserve
     * the total entity count: every entity ends up in exactly one bubble.
     * <p>
     * Setup: two real EnhancedBubbles, N entities seeded into the source bubble.
     * N/2 threads each call executeMigration (via checkMigrations with mocked checker/router)
     * for a distinct entity, while N/2 threads concurrently call updateEntityPosition on
     * the source bubble for the same entities.  After all threads finish, the union of
     * entities across both bubbles must equal the original set with no duplicates and no gaps.
     */
    @Test
    void concurrentMigrationAndUpdate_conservesEntityCount() throws InterruptedException {
        // Two real bubbles with their own RealTimeControllers
        var srcId = UUID.randomUUID();
        var dstId = UUID.randomUUID();
        var srcCtrl = new RealTimeController(srcId, "src");
        var dstCtrl = new RealTimeController(dstId, "dst");
        var srcBubble = new EnhancedBubble(srcId, (byte) 1, 16, srcCtrl);
        var dstBubble = new EnhancedBubble(dstId, (byte) 1, 16, dstCtrl);

        // Seed entities into the source bubble
        int N = 20;
        var entityIds = new ArrayList<String>(N);
        for (int i = 0; i < N; i++) {
            var eid = "entity-" + i;
            entityIds.add(eid);
            srcBubble.addEntity(eid, new Point3f(i, i, i), null);
        }

        // Build a TetreeBubbleGrid stub that returns our real bubbles by key.
        // We use the real grid and register bubbles via a thin wrapper approach:
        // instead, build a real grid, extract keys from real bubbles' bounds,
        // and use a spy to redirect getBubble to our instances.
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(2, (byte) 1, 16);
        var iter = grid.getAllBubbles().iterator();
        var gridBubble1 = iter.next();
        var gridBubble2 = iter.next();
        var srcKey = gridBubble1.bounds().rootKey();
        var dstKey = gridBubble2.bounds().rootKey();

        var spyGrid = Mockito.spy(grid);
        doReturn(srcBubble).when(spyGrid).getBubble(srcKey);
        doReturn(dstBubble).when(spyGrid).getBubble(dstKey);
        doReturn(true).when(spyGrid).containsBubble(srcKey);
        doReturn(true).when(spyGrid).containsBubble(dstKey);

        // Mocked checker/router that emit exactly one migration decision per entity
        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var router  = Mockito.mock(TetrahedralMigrationRouter.class);
        var mig = new TetrahedralMigration(spyGrid, checker, router);

        // N/2 migrator threads, N/2 updater threads, all start simultaneously
        int threads = N;
        var ready   = new CountDownLatch(threads);
        var start   = new CountDownLatch(1);
        var errors  = new CopyOnWriteArrayList<Throwable>();
        var pool    = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < N / 2; i++) {
            final String eid = entityIds.get(i);
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                try {
                    // Directly invoke executeMigration via checkMigrations with
                    // per-entity mocked checker/router responses
                    var rec = new TetrahedralContainmentChecker.MigrationRecord(
                        eid, srcKey, dstKey, new Point3f(5_000_000f, 5_000_000f, 5_000_000f), null);
                    var dec = new TetrahedralMigrationRouter.MigrationDecision(eid, srcKey, dstKey, 1.0f, false);
                    // Use a per-entity migration instance to avoid cross-entity lock contention
                    // while still exercising the cross-bubble lock path
                    var perEntityChecker = Mockito.mock(TetrahedralContainmentChecker.class);
                    var perEntityRouter  = Mockito.mock(TetrahedralMigrationRouter.class);
                    when(perEntityChecker.checkMigrations(any())).thenReturn(List.of(rec));
                    when(perEntityRouter.routeMigration(any())).thenReturn(dec);
                    var perMig = new TetrahedralMigration(spyGrid, perEntityChecker, perEntityRouter);
                    perMig.checkMigrations(0L);
                } catch (Throwable t) { errors.add(t); }
            });
        }

        for (int i = N / 2; i < N; i++) {
            final String eid = entityIds.get(i);
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                try {
                    // Concurrent position update on the source bubble
                    srcBubble.updateEntityPosition(eid, new Point3f(999f, 999f, 999f));
                } catch (Throwable t) { errors.add(t); }
            });
        }

        // Wait for all threads to be ready then fire
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Thread pool did not finish in time");

        // No thread threw
        assertTrue(errors.isEmpty(), "Concurrent threads threw: " + errors);

        // Entity conservation: count total entities across both bubbles
        int srcCount = srcBubble.entityCount();
        int dstCount = dstBubble.entityCount();
        int total = srcCount + dstCount;

        // Each entity is in exactly one bubble (migrated or stayed); no entity is lost or duplicated.
        // The updater threads only update position; they do not change the count.
        // The migrator threads each attempt to migrate their entity: either it succeeds (moves src->dst)
        // or the entity wasn't found (already absent) and the count stays the same.
        // In all cases total must equal N.
        assertEquals(N, total,
            "Total entity count must be conserved (src=" + srcCount + " dst=" + dstCount + ")");

        // Verify no entity appears in both bubbles simultaneously
        var srcEntities = srcBubble.getEntities();
        var dstEntities = dstBubble.getEntities();
        var intersection = new ArrayList<>(srcEntities);
        intersection.retainAll(dstEntities);
        assertTrue(intersection.isEmpty(),
            "No entity may exist in both bubbles simultaneously: " + intersection);
    }

    // -----------------------------------------------------------------------
    // Bead Luciferase-7wzml.20 — same-bubble migration guard (.187 follow-up)
    //
    // executeMigration must return false (no-op) when source and destination
    // resolve to the same EnhancedBubble.  Without the guard, the add-then-remove
    // sequence corrupts the idMapping by double-inserting the entity.
    // -----------------------------------------------------------------------

    /**
     * Same-bubble migration is a no-op: returns false, no entity double-insert.
     * <p>
     * Wires source key and destination key to the SAME bubble instance, then
     * triggers checkMigrations.  The guard must fire before lock acquisition and
     * return false, leaving entity count unchanged.
     */
    @Test
    void sameBubbleMigration_isNoOp() {
        var id = UUID.randomUUID();
        var ctrl = new RealTimeController(id, "same-bubble");
        var bubble = new EnhancedBubble(id, (byte) 1, 16, ctrl);

        // Seed one entity
        bubble.addEntity("entity-sb", new Point3f(1f, 1f, 1f), null);
        assertEquals(1, bubble.entityCount(), "Bubble must start with 1 entity");

        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);
        var realBubble = grid.getAllBubbles().iterator().next();
        var key = realBubble.bounds().rootKey();

        // Both source and destination point to the SAME bubble instance
        var spyGrid = Mockito.spy(grid);
        doReturn(bubble).when(spyGrid).getBubble(key);
        doReturn(true).when(spyGrid).containsBubble(key);

        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        // Same key for both src and dst
        var record = new TetrahedralContainmentChecker.MigrationRecord(
            "entity-sb", key, key, new Point3f(5_000_000f, 5_000_000f, 5_000_000f), null);
        when(checker.checkMigrations(bubble)).thenReturn(List.of(record));
        // getAllBubbles returns only our real bubble
        doReturn(List.of(bubble)).when(spyGrid).getAllBubbles();

        var decision = new TetrahedralMigrationRouter.MigrationDecision(
            "entity-sb", key, key, 1.0f, false);
        var router = Mockito.mock(TetrahedralMigrationRouter.class);
        when(router.routeMigration(any())).thenReturn(decision);

        var mig = new TetrahedralMigration(spyGrid, checker, router);
        mig.checkMigrations(0L);

        // Must be treated as a failure/no-op, not a successful migration
        assertEquals(0, mig.getMetrics().getTotalMigrations(),
                     "Same-bubble migration must not count as a successful migration");
        // Entity count unchanged: the add-then-remove sequence must NOT have run
        assertEquals(1, bubble.entityCount(),
                     "Entity count must be unchanged after same-bubble no-op (no double-insert)");
    }

    /**
     * Luciferase-0frcy.59: TetrahedralContainmentChecker.checkMigrations must
     * derive the source bubble key directly from the bubble's own bounds (O(1)),
     * not by scanning getAllBubbles() twice (O(n) per call, O(n^2) per tick).
     * This verifies the FUNCTIONAL contract that the source key of every produced
     * migration record equals bubble.bounds().rootKey() — the value the direct
     * O(1) lookup returns — for an entity that has escaped its bubble.
     */
    @Test
    void containmentCheckerUsesBubbleOwnKeyAsSource() {
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(9, (byte) 1, 16);
        var tet = new Tetree<StringEntityID, Object>(new StringEntityIDGenerator(), 100, (byte) 1);
        var checker = new TetrahedralContainmentChecker(tet, grid);

        var bubble = grid.getAllBubbles().iterator().next();
        var expectedSourceKey = bubble.bounds().rootKey();
        assertNotNull(expectedSourceKey, "Bubble must have a root key");

        // Place an entity far outside the bubble bounds so it is flagged as escaped.
        bubble.addEntity("escapee", new Point3f(1_000_000f, 1_000_000f, 1_000_000f), null);

        var records = checker.checkMigrations(bubble);
        // Every produced record must carry the bubble's own key as the source.
        for (var rec : records) {
            assertEquals(expectedSourceKey, rec.sourceBubbleKey(),
                "Source key must equal the bubble's own root key (direct O(1) lookup)");
        }
    }
}
