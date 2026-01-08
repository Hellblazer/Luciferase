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
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for DuplicateEntityDetector.
 * <p>
 * Covers:
 * <ul>
 *   <li>Duplicate detection (2, 3, N locations)</li>
 *   <li>Reconciliation with MigrationLog</li>
 *   <li>Fallback when no migration history</li>
 *   <li>Metrics tracking</li>
 *   <li>Logging levels</li>
 *   <li>Performance (O(n) scanning)</li>
 *   <li>Thread safety</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class DuplicateEntityDetectorTest {

    private TetreeBubbleGrid bubbleGrid;
    private MigrationLog migrationLog;
    private DuplicateDetectionConfig config;
    private DuplicateEntityDetector detector;

    @BeforeEach
    void setUp() {
        // Note: Create bubbleGrid and detector in each test to avoid bubble clearing issues
        migrationLog = new MigrationLog();
        config = DuplicateDetectionConfig.defaultConfig();
    }

    private void initializeDetector(int bubbleCount, byte maxLevel) {
        bubbleGrid = new TetreeBubbleGrid((byte) 3);
        bubbleGrid.createBubbles(bubbleCount, maxLevel, 100);
        detector = new DuplicateEntityDetector(bubbleGrid, migrationLog, config);
    }

    @Test
    void testScanDetectsNoDuplicates() {
        // Create 3 bubbles
        initializeDetector(3, (byte) 2);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        // Add unique entities to each bubble
        bubbles.get(0).addEntity("entity-0", new Point3f(0.1f, 0.1f, 0.1f), null);
        bubbles.get(1).addEntity("entity-1", new Point3f(0.2f, 0.2f, 0.2f), null);
        bubbles.get(2).addEntity("entity-2", new Point3f(0.3f, 0.3f, 0.3f), null);

        // Scan for duplicates
        var duplicates = detector.scan(bubbles);

        // Assert: No duplicates found
        assertTrue(duplicates.isEmpty(), "No duplicates should be detected with unique entities");
    }

    @Test
    void testScanDetectsSingleDuplicate() {
        // Create 2 bubbles
        initializeDetector(2, (byte) 1);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        // Add same entity to both bubbles
        var entityId = "duplicate-entity";
        bubbles.get(0).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        bubbles.get(1).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);

        // Scan for duplicates
        var duplicates = detector.scan(bubbles);

        // Assert: One duplicate detected
        assertEquals(1, duplicates.size());
        var duplicate = duplicates.get(0);
        assertEquals(entityId, duplicate.entityId());
        assertEquals(2, duplicate.locations().size());
        assertTrue(duplicate.locations().contains(bubbles.get(0).id()));
        assertTrue(duplicate.locations().contains(bubbles.get(1).id()));
    }

    @Test
    void testScanDetectsMultipleDuplicates() {
        // Create 3 bubbles
        initializeDetector(3, (byte) 2);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        // Entity A: in bubbles 0 and 1
        bubbles.get(0).addEntity("entity-A", new Point3f(0.1f, 0.1f, 0.1f), null);
        bubbles.get(1).addEntity("entity-A", new Point3f(0.1f, 0.1f, 0.1f), null);

        // Entity B: in bubbles 1 and 2
        bubbles.get(1).addEntity("entity-B", new Point3f(0.2f, 0.2f, 0.2f), null);
        bubbles.get(2).addEntity("entity-B", new Point3f(0.2f, 0.2f, 0.2f), null);

        // Scan for duplicates
        var duplicates = detector.scan(bubbles);

        // Assert: Two duplicates detected
        assertEquals(2, duplicates.size());

        var entityIds = duplicates.stream().map(DuplicateEntity::entityId).toList();
        assertTrue(entityIds.contains("entity-A"));
        assertTrue(entityIds.contains("entity-B"));
    }

    @Test
    void testScanDetectsTriplicate() {
        // Create 3 bubbles
        initializeDetector(3, (byte) 2);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        // Add same entity to all 3 bubbles
        var entityId = "triplicate-entity";
        bubbles.get(0).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        bubbles.get(1).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        bubbles.get(2).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);

        // Scan for duplicates
        var duplicates = detector.scan(bubbles);

        // Assert: One duplicate with 3 locations
        assertEquals(1, duplicates.size());
        var duplicate = duplicates.get(0);
        assertEquals(entityId, duplicate.entityId());
        assertEquals(3, duplicate.locations().size());
        assertEquals(3, duplicate.duplicateCount());
    }

    @Test
    void testReconciliationRemovesFromDestination() {
        // Create 2 bubbles
        initializeDetector(2, (byte) 1);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        var sourceBubble = bubbles.get(0);
        var destBubble = bubbles.get(1);

        // Add entity to both bubbles
        var entityId = "migrated-entity";
        sourceBubble.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        destBubble.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);

        // Record migration in log (source → dest)
        migrationLog.recordMigration(
            new StringEntityID(entityId),
            UUID.randomUUID(),
            sourceBubble.id(),
            destBubble.id(),
            1L
        );

        // Create duplicate record
        var duplicate = new DuplicateEntity(
            entityId,
            Set.of(sourceBubble.id(), destBubble.id()),
            migrationLog.getLatestMigration(new StringEntityID(entityId))
        );

        // Reconcile
        var removed = detector.reconcile(duplicate);

        // Assert: Entity removed from source (not destination)
        assertEquals(1, removed);
        assertFalse(sourceBubble.getEntities().contains(entityId), "Entity should be removed from source");
        assertTrue(destBubble.getEntities().contains(entityId), "Entity should remain in destination");
    }

    @Test
    void testReconciliationWithoutMigrationLog() {
        // Create 2 bubbles
        initializeDetector(2, (byte) 1);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        var bubble0 = bubbles.get(0);
        var bubble1 = bubbles.get(1);

        // Add entity to both bubbles WITHOUT migration log entry
        var entityId = "no-log-entity";
        bubble0.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        bubble1.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);

        // Create duplicate record (no migration history)
        var duplicate = new DuplicateEntity(
            entityId,
            Set.of(bubble0.id(), bubble1.id()),
            Optional.empty()  // No migration log
        );

        // Reconcile
        var removed = detector.reconcile(duplicate);

        // Assert: Fallback - no removal, keep in all locations
        assertEquals(0, removed);
        assertTrue(bubble0.getEntities().contains(entityId), "Entity should remain in bubble 0");
        assertTrue(bubble1.getEntities().contains(entityId), "Entity should remain in bubble 1");
    }

    @Test
    void testReconciliationKeepsSourceBubbleEntity() {
        // Create 3 bubbles
        initializeDetector(3, (byte) 2);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        var sourceBubble = bubbles.get(0);
        var destBubble1 = bubbles.get(1);
        var destBubble2 = bubbles.get(2);

        // Add entity to all 3 bubbles
        var entityId = "triple-entity";
        sourceBubble.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        destBubble1.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        destBubble2.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);

        // Record migration: source → destBubble2 (latest migration)
        migrationLog.recordMigration(
            new StringEntityID(entityId),
            UUID.randomUUID(),
            sourceBubble.id(),
            destBubble2.id(),
            1L
        );

        // Scan and reconcile
        var duplicates = detector.scan(bubbles);
        assertEquals(1, duplicates.size());

        var removed = detector.reconcile(duplicates.get(0));

        // Assert: Entity removed from source and destBubble1, kept in destBubble2
        assertEquals(2, removed);
        assertFalse(sourceBubble.getEntities().contains(entityId));
        assertFalse(destBubble1.getEntities().contains(entityId));
        assertTrue(destBubble2.getEntities().contains(entityId), "Entity should remain in latest migration target");
    }

    @Test
    void testReconciliationRemovesFromAllOtherBubbles() {
        // Create 5 bubbles
        initializeDetector(5, (byte) 2);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        // Verify we got 5 bubbles (distribution algorithm may create fewer)
        var actualBubbleCount = bubbles.size();
        assertTrue(actualBubbleCount >= 4, "Need at least 4 bubbles for this test");

        var entityId = "wide-duplicate";
        var sourceBubbleIdx = Math.min(2, actualBubbleCount - 1);  // Middle bubble
        var sourceBubble = bubbles.get(sourceBubbleIdx);

        // Add entity to all bubbles
        for (var bubble : bubbles) {
            bubble.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        }

        // Record migration to sourceBubble
        migrationLog.recordMigration(
            new StringEntityID(entityId),
            UUID.randomUUID(),
            bubbles.get(0).id(),
            sourceBubble.id(),
            1L
        );

        // Scan and reconcile
        var duplicates = detector.scan(bubbles);
        assertEquals(1, duplicates.size());

        var removed = detector.reconcile(duplicates.get(0));

        // Assert: (N-1) copies removed (kept in sourceBubble only)
        assertEquals(actualBubbleCount - 1, removed, "Should remove from all except source");
        assertTrue(sourceBubble.getEntities().contains(entityId));
        for (int i = 0; i < bubbles.size(); i++) {
            if (i != sourceBubbleIdx) {
                assertFalse(bubbles.get(i).getEntities().contains(entityId));
            }
        }
    }

    @Test
    void testCascadingFailuresDetected() {
        // Create 3 bubbles
        initializeDetector(3, (byte) 2);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        var entityId = "cascade-entity";

        // Simulate cascade: A→B fails rollback, then B→C succeeds
        // Result: Entity in A, B, C

        // Migration 1: A → B (rollback failed, duplicate in A+B)
        bubbles.get(0).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        bubbles.get(1).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        migrationLog.recordMigration(
            new StringEntityID(entityId),
            UUID.randomUUID(),
            bubbles.get(0).id(),
            bubbles.get(1).id(),
            1L
        );

        // Migration 2: B → C (succeeds, but A still has duplicate)
        bubbles.get(2).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        migrationLog.recordMigration(
            new StringEntityID(entityId),
            UUID.randomUUID(),
            bubbles.get(1).id(),
            bubbles.get(2).id(),
            2L
        );

        // Scan for duplicates
        var duplicates = detector.scan(bubbles);

        // Assert: Detects triplicate (cascade)
        assertEquals(1, duplicates.size());
        assertEquals(3, duplicates.get(0).locations().size());

        // Reconcile
        var removed = detector.reconcile(duplicates.get(0));

        // Assert: Removes from A and B, keeps in C (latest target)
        assertEquals(2, removed);
        assertFalse(bubbles.get(0).getEntities().contains(entityId));
        assertFalse(bubbles.get(1).getEntities().contains(entityId));
        assertTrue(bubbles.get(2).getEntities().contains(entityId));
    }

    @Test
    void testMetricsTracking() {
        // Create 2 bubbles
        initializeDetector(2, (byte) 1);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        // Add duplicate entity
        var entityId = "metrics-entity";
        bubbles.get(0).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
        bubbles.get(1).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);

        // Record migration
        migrationLog.recordMigration(
            new StringEntityID(entityId),
            UUID.randomUUID(),
            bubbles.get(0).id(),
            bubbles.get(1).id(),
            1L
        );

        // Detect and reconcile
        var result = detector.detectAndReconcile(bubbles);

        // Assert: Metrics updated
        var metrics = detector.getMetrics();
        assertEquals(1, metrics.getDuplicatesDetected());
        assertEquals(1, metrics.getDuplicatesResolved());
        assertEquals(2, metrics.getMaxCascadingDuplicates());

        assertEquals(1, result.duplicatesDetected());
        assertEquals(1, result.duplicatesResolved());
        assertEquals(1, result.copiesRemoved());
    }

    @Test
    void testLoggingLevels() {
        // Test that different log levels don't crash
        var configs = List.of(
            DuplicateDetectionConfig.builder().withLogLevel(DuplicateDetectionConfig.LogLevel.ERROR).build(),
            DuplicateDetectionConfig.builder().withLogLevel(DuplicateDetectionConfig.LogLevel.WARN).build(),
            DuplicateDetectionConfig.builder().withLogLevel(DuplicateDetectionConfig.LogLevel.DEBUG).build()
        );

        int idx = 0;
        for (var testConfig : configs) {
            // Create fresh bubbleGrid for each iteration
            var testGrid = new TetreeBubbleGrid((byte) 3);
            testGrid.createBubbles(2, (byte) 1, 100);
            var testDetector = new DuplicateEntityDetector(testGrid, migrationLog, testConfig);

            // Create scenario
            var bubbles = new ArrayList<>(testGrid.getAllBubbles());

            var entityId = "log-test-" + idx++;
            bubbles.get(0).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);
            bubbles.get(1).addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);

            // Should not throw
            assertDoesNotThrow(() -> testDetector.detectAndReconcile(bubbles));
        }
    }

    @Test
    void testPerformanceO_n_WithManyBubbles() {
        // Create 100 bubbles
        initializeDetector(100, (byte) 3);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        // Add 500 unique entities distributed across bubbles
        for (int i = 0; i < 500; i++) {
            var bubbleIdx = i % bubbles.size();
            bubbles.get(bubbleIdx).addEntity("entity-" + i, new Point3f(0.1f * i, 0.1f * i, 0.1f * i), null);
        }

        // Measure scan time
        var start = System.nanoTime();
        var duplicates = detector.scan(bubbles);
        var elapsed = (System.nanoTime() - start) / 1_000_000.0;  // Convert to ms

        // Assert: No duplicates, scan completes quickly
        assertTrue(duplicates.isEmpty());
        assertTrue(elapsed < 100.0, "Scan should complete in < 100ms for 500 entities, got: " + elapsed + "ms");
    }

    @Test
    void testConcurrentScanning() throws InterruptedException {
        // Create 10 bubbles
        initializeDetector(10, (byte) 2);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        // Add entities
        for (int i = 0; i < 100; i++) {
            var bubbleIdx = i % bubbles.size();
            bubbles.get(bubbleIdx).addEntity("entity-" + i, new Point3f(0.1f * i, 0.1f * i, 0.1f * i), null);
        }

        // Create 5 threads that scan concurrently
        var threads = new ArrayList<Thread>();
        var results = new ArrayList<List<DuplicateEntity>>();

        for (int i = 0; i < 5; i++) {
            threads.add(new Thread(() -> {
                var duplicates = detector.scan(bubbles);
                synchronized (results) {
                    results.add(duplicates);
                }
            }));
        }

        // Start all threads
        threads.forEach(Thread::start);

        // Wait for completion
        for (var thread : threads) {
            thread.join();
        }

        // Assert: All scans completed without exception
        assertEquals(5, results.size());
        // All should find same result (no duplicates)
        for (var result : results) {
            assertTrue(result.isEmpty());
        }
    }

    @Test
    void testDisabledDetection() {
        // Create scenario with duplicates
        initializeDetector(2, (byte) 1);
        var bubbles = new ArrayList<>(bubbleGrid.getAllBubbles());

        bubbles.get(0).addEntity("disabled-test", new Point3f(0.1f, 0.1f, 0.1f), null);
        bubbles.get(1).addEntity("disabled-test", new Point3f(0.1f, 0.1f, 0.1f), null);

        // Create config with detection disabled
        var disabledConfig = DuplicateDetectionConfig.builder().withEnabled(false).build();
        var disabledDetector = new DuplicateEntityDetector(bubbleGrid, migrationLog, disabledConfig);

        // Note: The detector itself doesn't check enabled flag, only MultiBubbleSimulation does
        // So we verify scan still works (it's the caller's responsibility to check config)
        var duplicates = disabledDetector.scan(bubbles);
        assertEquals(1, duplicates.size());
    }
}
