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
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

        // A VALID tet key that is definitely not in this level-1 grid (a level-2 cell is never registered by a
        // level-1 partition). It must be a decodable tet because the hysteresis gate now derives the source tet
        // geometry from the key (Luciferase-6kod9); the old fabricated CompactTetreeKey decoded to an invalid type.
        var missingTet = Tet.createValidated(0, 0, 0, (byte) 2, (byte) 0);
        var missingKey = missingTet.tmIndex();

        // A real key that IS in the grid (for the destination)
        var destKey = grid.getAllBubbles().iterator().next().bounds().rootKey();

        // Stub checker: returns one MigrationRecord whose sourceKey is not in grid.
        // Position is placed 1000 Cartesian units past a face of the source tet so the exact face-distance
        // hysteresis gate passes (Luciferase-6kod9) and we reach executeMigration.
        var missingSrcPos = pointPastFace(missingTet, 0, 1000.0);
        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var record = new TetrahedralContainmentChecker.MigrationRecord(
            "entity-missing-src", missingKey, destKey, missingSrcPos, null);
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
    // Bead Luciferase-7wzml.185 / Luciferase-6kod9 — hysteresis enforcement tests
    //
    // The escape test now uses EXACT tetrahedral containment, so the hysteresis gate is the EXACT distance the
    // entity has crossed past the nearest FACE of its source partition tet (Luciferase-6kod9), in raw-Cartesian
    // units, gated on HYSTERESIS_DIST = 2.0f. (It is no longer the RDG-AABB overshoot, which collapsed to ~0 for
    // an entity just over a true face once containment became exact.) These tests place an entity a controlled
    // Cartesian distance past a real source tet's face and assert the gate suppresses (< 2.0) or allows (>= 2.0).
    // -----------------------------------------------------------------------

    /**
     * Test-side, INDEPENDENT computation of a point exactly {@code dist} Cartesian units outward past face
     * {@code faceIdx} of {@code tet} (from that face's centroid, along its outward unit normal). Mirrors — without
     * calling — the production {@code overshootPastNearestFace} geometry, so it validates rather than echoes it.
     */
    private static Point3f pointPastFace(Tet tet, int faceIdx, double dist) {
        var v = tet.coordinates();
        var a = v[(faceIdx + 1) & 3];
        var b = v[(faceIdx + 2) & 3];
        var c = v[(faceIdx + 3) & 3];
        var apex = v[faceIdx];
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
        double nx = aby * acz - abz * acy;
        double ny = abz * acx - abx * acz;
        double nz = abx * acy - aby * acx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double towardApex = nx * (apex.x - a.x) + ny * (apex.y - a.y) + nz * (apex.z - a.z);
        double s = towardApex > 0 ? -1.0 : 1.0;
        double ux = s * nx / len, uy = s * ny / len, uz = s * nz / len; // outward unit normal
        double cx = (a.x + b.x + c.x) / 3.0, cy = (a.y + b.y + c.y) / 3.0, cz = (a.z + b.z + c.z) / 3.0;
        return new Point3f((float) (cx + ux * dist), (float) (cy + uy * dist), (float) (cz + uz * dist));
    }

    /**
     * Run the hysteresis gate for an entity placed {@code dist} Cartesian units past face {@code faceIdx} of a
     * real source partition tet. The mocked router returns null, so a gate PASS records exactly one failure
     * (returns 1) and a gate SUPPRESS drops the record before routing (returns 0). Parameterized over all four
     * faces because the production {@code overshootPastNearestFace} loops every face (Luciferase-6kod9 review).
     */
    private long hysteresisGateFailures(int faceIdx, double dist) {
        var grid = new TetreeBubbleGrid((byte) 1);
        grid.createBubbles(1, (byte) 1, 16);
        var bubble = grid.getAllBubbles().iterator().next();
        TetreeKey<?> srcKey = bubble.bounds().rootKey();
        var pos = pointPastFace(Tet.tetrahedron(srcKey), faceIdx, dist);
        var dstKey = new CompactTetreeKey((byte) 1, 88_888_888L);

        var spyGrid = Mockito.spy(grid);
        doReturn(List.of(bubble)).when(spyGrid).getAllBubbles();

        var checker = Mockito.mock(TetrahedralContainmentChecker.class);
        var record = new TetrahedralContainmentChecker.MigrationRecord("hysteresis-entity", srcKey, dstKey, pos, null);
        when(checker.checkMigrations(bubble)).thenReturn(List.of(record));

        var router = Mockito.mock(TetrahedralMigrationRouter.class);
        when(router.routeMigration(any())).thenReturn(null);

        var mig = new TetrahedralMigration(spyGrid, checker, router);
        mig.checkMigrations(0L);
        return mig.getMetrics().getFailureCount();
    }

    /**
     * Luciferase-6kod9 — entity just past a source-tet face (1.0 < HYSTERESIS_DIST=2.0) is SUPPRESSED, for
     * EVERY face (production overshootPastNearestFace loops all four — review follow-up).
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void hysteresis_justPastFace_suppressesMigration(int faceIdx) {
        assertEquals(0, hysteresisGateFailures(faceIdx, 1.0),
                     "entity 1.0 Cartesian units past face " + faceIdx + " (< 2.0) must be suppressed");
    }

    /**
     * Luciferase-6kod9 — entity well past a source-tet face (10.0 >= HYSTERESIS_DIST=2.0) is ALLOWED through,
     * for EVERY face; the mocked router then returns null, recording exactly one failure (the gate-pass signal).
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void hysteresis_wellPastFace_allowsMigration(int faceIdx) {
        assertEquals(1, hysteresisGateFailures(faceIdx, 10.0),
                     "entity 10.0 Cartesian units past face " + faceIdx + " (>= 2.0) must pass the gate");
    }

    /**
     * Luciferase-6kod9 — threshold straddle on each face: just inside the band is suppressed, just outside is
     * allowed. Reads the production constant so the test tracks any future change to it.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void hysteresis_threshold_suppressesBelow_allowsAbove(int faceIdx) {
        double h = TetrahedralMigration.getHysteresisDistance();
        assertEquals(0, hysteresisGateFailures(faceIdx, h - 0.5),
                     "face " + faceIdx + ": just inside the hysteresis band must be suppressed");
        assertEquals(1, hysteresisGateFailures(faceIdx, h + 0.5),
                     "face " + faceIdx + ": just outside the hysteresis band must be allowed");
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
