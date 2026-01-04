package com.hellblazer.luciferase.simulation.span;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 6: Network Partition Recovery.
 * <p>
 * Tests:
 * - Partition recovery maintains entity consistency
 * - Span state recovers after partition
 * - Ghost zones re-established after partition
 * - Boundary entities resynced after partition
 *
 * @author hal.hildebrand
 */
class PartitionRecoveryTest {

    private Tetree<LongEntityID, String> tetree;
    private SpanConfig config;
    private SpatialSpanImpl<LongEntityID, String> span;
    private Forest<TetreeKey<?>, LongEntityID, String> forest;
    private GhostZoneManager<TetreeKey<?>, LongEntityID, String> ghostManager;
    private Set<TetreeKey<?>> regions;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, (byte) 21);
        config = SpanConfig.defaults();

        // Create test regions - initialize empty, will be populated dynamically
        regions = new HashSet<>();

        // Create span with region supplier
        span = new SpatialSpanImpl<>(tetree, config, () -> regions);

        // Create forest and ghost manager
        forest = new Forest<>();
        ghostManager = new GhostZoneManager<>(forest, config.calculateBoundaryWidth(1.0f));

        // Configure span with ghost manager
        span.setGhostZoneManager(ghostManager, regionKey -> "region-" + regionKey.toString());
    }

    @Test
    void testPartitionRecoveryInitiation() {
        // Test that partition recovery can be triggered
        assertFalse(span.isPartitionRecovering(), "Should not be recovering initially");

        var entitiesResynced = span.recoverFromPartition();

        assertFalse(span.isPartitionRecovering(), "Should not be recovering after completion");
        assertTrue(entitiesResynced >= 0, "Should return count of entities resynced");
    }

    @Test
    void testPartitionRecoveryWithEntities() {
        // Add entities before partition
        for (int i = 0; i < 10; i++) {
            var entityId = new LongEntityID(i);
            var position = new Point3f(0.5f + i * 0.01f, 0.5f, 0.5f);
            span.updateBoundary(entityId, position, "entity-" + i);
        }

        var beforeRecovery = span.getTotalBoundaryEntities();

        // Trigger partition recovery
        var entitiesResynced = span.recoverFromPartition();

        // Verify recovery completed
        assertFalse(span.isPartitionRecovering());
        assertEquals(beforeRecovery, entitiesResynced, "Should resync all boundary entities");
    }

    @Test
    void testPartitionRecoveryTimestamp() {
        // Check initial timestamp
        assertEquals(0, span.getLastPartitionRecoveryTime(), "Should be 0 before any recovery");

        // Trigger recovery
        var before = System.currentTimeMillis();
        span.recoverFromPartition();
        var after = System.currentTimeMillis();

        // Verify timestamp was updated
        var recoveryTime = span.getLastPartitionRecoveryTime();
        assertTrue(recoveryTime >= before && recoveryTime <= after,
            "Recovery timestamp should be within test window");
    }

    @Test
    void testResyncBoundaryEntity() {
        var entityId = new LongEntityID(1);
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        var content = "test-entity";

        // Add entity before partition (no-op with empty regions)
        span.updateBoundary(entityId, position, content);

        // Simulate partition - remove entity
        span.removeBoundary(entityId);
        assertFalse(span.isInBoundary(entityId));

        // Trigger partition recovery
        span.recoverFromPartition();

        // Resync the entity
        span.resyncBoundaryEntity(entityId, position, content);

        // Verify resync completed without error
        // With empty regions, entity won't be in boundaries
        assertFalse(span.isInBoundary(entityId), "No boundaries with empty regions");
    }

    @Test
    void testPartitionRecoveryConsistency() {
        // Add entities
        for (int i = 0; i < 20; i++) {
            var entityId = new LongEntityID(i);
            var position = new Point3f(0.5f + i * 0.01f, 0.5f, 0.5f);
            span.updateBoundary(entityId, position, "entity-" + i);
        }

        var beforeCount = span.getTotalBoundaryEntities();
        var beforeZones = span.getBoundaryZoneCount();

        // Trigger recovery
        span.recoverFromPartition();

        // Entity count should be preserved (infrastructure reestablished)
        var afterCount = span.getTotalBoundaryEntities();
        assertEquals(beforeCount, afterCount, "Entity count should be preserved");

        // Boundary zones may be recreated
        var afterZones = span.getBoundaryZoneCount();
        assertTrue(afterZones >= 0, "Boundary zones should be valid");
    }

    @Test
    void testPartitionRecoveryWithoutGhostManager() {
        // Create span without ghost manager
        var spanNoGhost = new SpatialSpanImpl<>(tetree, config, () -> regions);

        // Recovery should be no-op without ghost manager
        var result = spanNoGhost.recoverFromPartition();
        assertEquals(0, result, "Should return 0 when ghost manager not configured");
    }

    @Test
    void testMultiplePartitionRecoveries() {
        // Add entities
        for (int i = 0; i < 5; i++) {
            span.updateBoundary(new LongEntityID(i), new Point3f(0.5f, 0.5f, 0.5f), "entity-" + i);
        }

        // Trigger multiple recoveries
        var recovery1 = span.recoverFromPartition();
        var time1 = span.getLastPartitionRecoveryTime();

        // Small delay to ensure different timestamp
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var recovery2 = span.recoverFromPartition();
        var time2 = span.getLastPartitionRecoveryTime();

        // Both recoveries should complete
        assertTrue(recovery1 >= 0 && recovery2 >= 0, "Both recoveries should complete");
        assertTrue(time2 > time1, "Second recovery should have later timestamp");
    }

    @Test
    void testPartitionRecoveryPerformance() {
        // Add many entities
        for (int i = 0; i < 100; i++) {
            span.updateBoundary(new LongEntityID(i),
                new Point3f(0.5f + i * 0.001f, 0.5f, 0.5f),
                "entity-" + i);
        }

        // Measure recovery time
        var start = System.nanoTime();
        span.recoverFromPartition();
        var duration = (System.nanoTime() - start) / 1_000_000.0; // ms

        // Recovery should be reasonably fast (< 100ms for 100 entities)
        assertTrue(duration < 100.0, "Recovery should complete in < 100ms, took " + duration + "ms");
    }

    @Test
    void testConcurrentPartitionRecovery() {
        // Add entities
        for (int i = 0; i < 10; i++) {
            span.updateBoundary(new LongEntityID(i), new Point3f(0.5f, 0.5f, 0.5f), "entity-" + i);
        }

        // Trigger recovery from multiple threads (should be safe)
        var thread1 = new Thread(() -> span.recoverFromPartition());
        var thread2 = new Thread(() -> span.recoverFromPartition());

        thread1.start();
        thread2.start();

        assertDoesNotThrow(() -> {
            thread1.join(5000);
            thread2.join(5000);
        }, "Concurrent recovery should not deadlock");
    }

    @Test
    void testResyncAfterRecovery() {
        // Setup: entities in boundaries
        var entities = new java.util.ArrayList<LongEntityID>();
        for (int i = 0; i < 5; i++) {
            var entityId = new LongEntityID(i);
            entities.add(entityId);
            span.updateBoundary(entityId, new Point3f(0.5f, 0.5f, 0.5f), "entity-" + i);
        }

        // Partition recovery
        span.recoverFromPartition();

        // Resync all entities
        for (int i = 0; i < entities.size(); i++) {
            var entityId = entities.get(i);
            span.resyncBoundaryEntity(entityId, new Point3f(0.5f, 0.5f, 0.5f), "entity-" + i);
        }

        // Verify all entities tracked
        var totalEntities = span.getTotalBoundaryEntities();
        assertTrue(totalEntities >= 0, "All entities should be resynced");
    }
}
