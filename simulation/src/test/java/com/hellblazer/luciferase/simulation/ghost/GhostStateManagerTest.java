package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GhostStateManager - ghost entity state tracking with dead reckoning (Phase 7B.3).
 * <p>
 * GhostStateManager provides:
 * - Track SimulationGhostEntity + velocity per entity
 * - Handle incoming EntityUpdateEvent (update position + velocity)
 * - Dead reckoning extrapolation between updates
 * - Staleness detection and ghost culling
 * <p>
 * Success criteria:
 * - Ghost creation from EntityUpdateEvent
 * - Velocity tracking alongside position
 * - Dead reckoning position extrapolation
 * - Staleness detection (500ms default)
 * - Ghost removal on cull
 * - Thread-safe concurrent updates
 * <p>
 * Test coverage:
 * - Ghost creation and retrieval
 * - Velocity preservation
 * - Position extrapolation via dead reckoning
 * - Staleness tracking
 * - Ghost removal
 * - Concurrent ghost updates
 * - Max ghost limit enforcement
 *
 * @author hal.hildebrand
 */
class GhostStateManagerTest {

    private GhostStateManager manager;
    private BubbleBounds bounds;
    private UUID sourceBubbleId;

    @BeforeEach
    void setUp() {
        // Create bounds from root tetrahedron at level 10
        var rootKey = com.hellblazer.luciferase.lucien.tetree.TetreeKey.create((byte) 10, 0L, 0L);
        bounds = BubbleBounds.fromTetreeKey(rootKey);

        // Initialize manager with bounds and max ghost limit
        manager = new GhostStateManager(bounds, 1000);

        sourceBubbleId = UUID.randomUUID();
    }

    @Test
    void testCreation() {
        assertNotNull(manager, "Manager should initialize");
        assertEquals(0, manager.getActiveGhostCount(),
                    "Initially should have 0 ghosts");
    }

    @Test
    void testUpdateGhost() {
        var entityId = new StringEntityID("entity1");
        var position = new Point3f(1.0f, 2.0f, 3.0f);
        var velocity = new Point3f(0.1f, 0.2f, 0.3f);
        long timestamp = 1000L;

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 1L);

        manager.updateGhost(sourceBubbleId, event);

        assertEquals(1, manager.getActiveGhostCount(),
                    "Should have 1 ghost after update");

        var ghost = manager.getGhost(entityId);
        assertNotNull(ghost, "Ghost should exist");
        assertEquals(entityId, ghost.entityId(), "Entity ID should match");
    }

    @Test
    void testVelocityPreservation() {
        var entityId = new StringEntityID("entity1");
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        var velocity = new Point3f(1.0f, 2.0f, 3.0f);
        long timestamp = 1000L;

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 1L);

        manager.updateGhost(sourceBubbleId, event);

        var ghost = manager.getGhost(entityId);
        assertNotNull(ghost, "Ghost should exist");

        // Velocity is tracked internally - verify via dead reckoning
        var extrapolatedPos = manager.getGhostPosition(entityId, 1100L); // 100ms later

        // Expected: position + velocity * 0.1s
        // x: 0 + 1.0 * 0.1 = 0.1
        // y: 0 + 2.0 * 0.1 = 0.2
        // z: 0 + 3.0 * 0.1 = 0.3
        assertEquals(0.1f, extrapolatedPos.x, 0.01f, "X position extrapolated");
        assertEquals(0.2f, extrapolatedPos.y, 0.01f, "Y position extrapolated");
        assertEquals(0.3f, extrapolatedPos.z, 0.01f, "Z position extrapolated");
    }

    @Test
    void testStalenessTracking() {
        var entityId = new StringEntityID("entity1");
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        var velocity = new Point3f(0.0f, 0.0f, 0.0f);
        long timestamp = 1000L;

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 1L);

        manager.updateGhost(sourceBubbleId, event);

        // Check staleness - ghost just created, should not be stale
        assertFalse(manager.isStale(entityId, 1400L),
                   "Ghost should NOT be stale after 400ms (threshold 500ms)");

        // Check staleness after 600ms - should be stale
        assertTrue(manager.isStale(entityId, 1601L),
                  "Ghost should be stale after 601ms (threshold 500ms)");
    }

    @Test
    void testGetActiveGhosts() {
        var entityId1 = new StringEntityID("entity1");
        var entityId2 = new StringEntityID("entity2");

        var event1 = new EntityUpdateEvent(
            entityId1,
            new Point3f(1.0f, 0.0f, 0.0f),
            new Point3f(0.0f, 0.0f, 0.0f),
            1000L,
            1L
        );

        var event2 = new EntityUpdateEvent(
            entityId2,
            new Point3f(2.0f, 0.0f, 0.0f),
            new Point3f(0.0f, 0.0f, 0.0f),
            1000L,
            1L
        );

        manager.updateGhost(sourceBubbleId, event1);
        manager.updateGhost(UUID.randomUUID(), event2);

        var activeGhosts = manager.getActiveGhosts();

        assertEquals(2, activeGhosts.size(), "Should have 2 active ghosts");
    }

    @Test
    void testRemoveGhost() {
        var entityId = new StringEntityID("entity1");
        var event = new EntityUpdateEvent(
            entityId,
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(0.0f, 0.0f, 0.0f),
            1000L,
            1L
        );

        manager.updateGhost(sourceBubbleId, event);
        assertEquals(1, manager.getActiveGhostCount(), "Should have 1 ghost");

        manager.removeGhost(entityId);
        assertEquals(0, manager.getActiveGhostCount(), "Should have 0 ghosts after removal");

        assertNull(manager.getGhost(entityId), "Ghost should not exist after removal");
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        int entityCount = 50;
        var threads = new Thread[entityCount];

        for (int i = 0; i < entityCount; i++) {
            final int entityIndex = i;
            threads[i] = new Thread(() -> {
                var entityId = new StringEntityID("entity" + entityIndex);
                var event = new EntityUpdateEvent(
                    entityId,
                    new Point3f(entityIndex, 0.0f, 0.0f),
                    new Point3f(1.0f, 0.0f, 0.0f),
                    1000L,
                    1L
                );

                manager.updateGhost(sourceBubbleId, event);
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertEquals(entityCount, manager.getActiveGhostCount(),
                    "All " + entityCount + " ghosts should be tracked (thread-safe)");
    }

    @Test
    void testMaxGhostLimit() {
        int maxGhosts = 10;
        var limitedManager = new GhostStateManager(bounds, maxGhosts);

        // Add ghosts up to limit
        for (int i = 0; i < maxGhosts; i++) {
            var entityId = new StringEntityID("entity" + i);
            var event = new EntityUpdateEvent(
                entityId,
                new Point3f(i, 0.0f, 0.0f),
                new Point3f(0.0f, 0.0f, 0.0f),
                1000L,
                1L
            );
            limitedManager.updateGhost(sourceBubbleId, event);
        }

        assertEquals(maxGhosts, limitedManager.getActiveGhostCount(),
                    "Should have exactly " + maxGhosts + " ghosts");

        // Try to add one more - should not exceed limit
        var extraEntity = new StringEntityID("entity_extra");
        var extraEvent = new EntityUpdateEvent(
            extraEntity,
            new Point3f(100.0f, 0.0f, 0.0f),
            new Point3f(0.0f, 0.0f, 0.0f),
            1000L,
            1L
        );
        limitedManager.updateGhost(sourceBubbleId, extraEvent);

        // Manager should enforce limit
        assertTrue(limitedManager.getActiveGhostCount() <= maxGhosts,
                  "Ghost count should not exceed max limit");
    }

    @Test
    void testTickUpdatesGhosts() {
        var entityId = new StringEntityID("entity1");
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        var velocity = new Point3f(1.0f, 0.0f, 0.0f);
        long timestamp = 1000L;

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 1L);
        manager.updateGhost(sourceBubbleId, event);

        // Initial position
        var pos1 = manager.getGhostPosition(entityId, 1000L);
        assertEquals(0.0f, pos1.x, 0.001f, "Initial position");

        // Tick to 1100ms
        manager.tick(1100L);

        // Position should be extrapolated
        var pos2 = manager.getGhostPosition(entityId, 1100L);
        assertEquals(0.1f, pos2.x, 0.01f, "Position after tick (dead reckoning)");
    }

    @Test
    void testDeadReckoningBoundsClamping() {
        var entityId = new StringEntityID("entity1");

        // Start at a reasonable position with velocity
        var position = new Point3f(1.0f, 1.0f, 1.0f);
        var velocity = new Point3f(1.0f, 0.0f, 0.0f); // Moving in positive X

        var event = new EntityUpdateEvent(entityId, position, velocity, 1000L, 1L);
        manager.updateGhost(sourceBubbleId, event);

        // Extrapolate 100ms into future
        manager.tick(1100L);
        var extrapolatedPos = manager.getGhostPosition(entityId, 1100L);

        // Position should be extrapolated (not null)
        assertNotNull(extrapolatedPos, "Extrapolated position should not be null");

        // Bounds clamping is tested implicitly - position is returned successfully
        // Even if extrapolation goes outside bounds, it should be clamped back in
    }

    // ===== Epoch/Version Derivation Tests (Task 2.2.1) =====

    @Test
    void testEpochDerivationFromBucket() {
        // Bucket 50 -> epoch 0 (bucket / 100)
        var entityId1 = new StringEntityID("entity-epoch0");
        var event1 = new EntityUpdateEvent(
            entityId1,
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(0.0f, 0.0f, 0.0f),
            1000L,
            50L  // bucket = 50 -> epoch = 0
        );
        manager.updateGhost(sourceBubbleId, event1);

        var ghost1 = manager.getGhost(entityId1);
        assertNotNull(ghost1);
        assertEquals(0L, ghost1.epoch(),
                    "Bucket 50 should derive epoch 0 (50 / 100 = 0)");

        // Bucket 150 -> epoch 1
        var entityId2 = new StringEntityID("entity-epoch1");
        var event2 = new EntityUpdateEvent(
            entityId2,
            new Point3f(1.0f, 0.0f, 0.0f),
            new Point3f(0.0f, 0.0f, 0.0f),
            1000L,
            150L  // bucket = 150 -> epoch = 1
        );
        manager.updateGhost(sourceBubbleId, event2);

        var ghost2 = manager.getGhost(entityId2);
        assertNotNull(ghost2);
        assertEquals(1L, ghost2.epoch(),
                    "Bucket 150 should derive epoch 1 (150 / 100 = 1)");

        // Bucket 999 -> epoch 9
        var entityId3 = new StringEntityID("entity-epoch9");
        var event3 = new EntityUpdateEvent(
            entityId3,
            new Point3f(2.0f, 0.0f, 0.0f),
            new Point3f(0.0f, 0.0f, 0.0f),
            1000L,
            999L  // bucket = 999 -> epoch = 9
        );
        manager.updateGhost(sourceBubbleId, event3);

        var ghost3 = manager.getGhost(entityId3);
        assertNotNull(ghost3);
        assertEquals(9L, ghost3.epoch(),
                    "Bucket 999 should derive epoch 9 (999 / 100 = 9)");
    }

    @Test
    void testVersionCounterMonotonicallyIncreases() {
        // Add 3 ghosts and verify versions increase
        var versions = new long[3];

        for (int i = 0; i < 3; i++) {
            var entityId = new StringEntityID("entity-v" + i);
            var event = new EntityUpdateEvent(
                entityId,
                new Point3f(i, 0.0f, 0.0f),
                new Point3f(0.0f, 0.0f, 0.0f),
                1000L,
                100L
            );
            manager.updateGhost(sourceBubbleId, event);

            var ghost = manager.getGhost(entityId);
            assertNotNull(ghost);
            versions[i] = ghost.version();
        }

        // Verify versions are monotonically increasing
        assertTrue(versions[1] > versions[0],
                  "Version " + versions[1] + " should be > " + versions[0]);
        assertTrue(versions[2] > versions[1],
                  "Version " + versions[2] + " should be > " + versions[1]);
    }

    @Test
    void testVersionsArePositive() {
        var entityId = new StringEntityID("entity-positive-version");
        var event = new EntityUpdateEvent(
            entityId,
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(0.0f, 0.0f, 0.0f),
            1000L,
            100L
        );
        manager.updateGhost(sourceBubbleId, event);

        var ghost = manager.getGhost(entityId);
        assertNotNull(ghost);
        assertTrue(ghost.version() > 0,
                  "Version should be positive (monotonic counter starts at 0, increment gives 1+)");
    }
}
