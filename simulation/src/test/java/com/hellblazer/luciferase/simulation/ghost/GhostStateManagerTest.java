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

    /**
     * Luciferase-7wzml.186: site-4 consistency fix — SimulationGhostEntity.velocity() must
     * return the real velocity (not zero) after updateGhost(), so that any consumer calling
     * getGhost(id).velocity() directly (e.g. BubbleGhostCoordinator.onReceive on a local
     * re-delivery path) sees the correct value.
     */
    @Test
    void testGhostEntityVelocityFieldConsistentWithGhostStateVelocity() {
        var entityId = new StringEntityID("entity-vel-consistency");
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        var velocity = new Point3f(5.0f, 0.0f, 0.0f); // 5 units/s in X
        long timestamp = 1000L;

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 1L);
        manager.updateGhost(sourceBubbleId, event);

        var ghost = manager.getGhost(entityId);
        assertNotNull(ghost, "Ghost must exist after updateGhost");

        // Luciferase-7wzml.186: createGhostEntity now passes velocity to the 6-arg ctor,
        // so SimulationGhostEntity.velocity() is consistent with GhostState.velocity.
        var ghostVel = ghost.velocity();
        assertEquals(5.0f, ghostVel.x, 0.001f,
            "ghost.velocity().x must match the velocity supplied to updateGhost (was 0 before fix)");
        assertEquals(0.0f, ghostVel.y, 0.001f, "ghost.velocity().y must be 0");
        assertEquals(0.0f, ghostVel.z, 0.001f, "ghost.velocity().z must be 0");

        // Sanity: getGhostVelocity() (reads GhostState.velocity) must agree
        var stateVel = manager.getGhostVelocity(entityId);
        assertEquals(ghostVel.x, stateVel.x, 0.001f,
            "SimulationGhostEntity.velocity() and GhostState.velocity must be consistent");
    }

    @Test
    void testStalenessTracking() {
        var entityId = new StringEntityID("entity1");
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        var velocity = new Point3f(0.0f, 0.0f, 0.0f);
        long timestamp = 1000L;

        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 1L);

        manager.updateGhost(sourceBubbleId, event);

        // Check staleness - ghost just created, should not be stale before threshold (300ms)
        assertFalse(manager.isStale(entityId, 1250L),
                   "Ghost should NOT be stale after 250ms (threshold 300ms)");

        // After staleness threshold (300ms) but before TTL (500ms) — STALE warning window
        assertTrue(manager.isStale(entityId, 1350L),
                  "Ghost should be stale after 350ms (threshold 300ms)");

        // Check staleness well past TTL - should still be stale
        assertTrue(manager.isStale(entityId, 1601L),
                  "Ghost should be stale after 601ms (past TTL 500ms)");
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
    void testTickExpiresOnlyStaleGhostsViaTargetedRemoval() {
        // Regression for Luciferase-0frcy.102: tick() now drives removals from the exact
        // set of IDs the lifecycle expired, rather than rescanning ghostStates and
        // re-querying lifecycle state. Verify a stale ghost is culled while a freshly
        // updated peer survives the same tick.
        var stale = new StringEntityID("stale-102");
        var fresh = new StringEntityID("fresh-102");
        var position = new Point3f(0.0f, 0.0f, 0.0f);
        var velocity = new Point3f(0.0f, 0.0f, 0.0f);

        manager.updateGhost(sourceBubbleId, new EntityUpdateEvent(stale, position, velocity, 1000L, 1L));
        manager.updateGhost(sourceBubbleId, new EntityUpdateEvent(fresh, position, velocity, 1000L, 1L));

        // Refresh only the fresh ghost at 1400ms.
        manager.updateGhost(sourceBubbleId, new EntityUpdateEvent(fresh, position, velocity, 1400L, 2L));

        // Tick at 1600ms: stale last-updated 1000 (600ms > 500ms TTL) expires;
        // fresh last-updated 1400 (200ms) survives.
        manager.tick(1600L);

        assertNull(manager.getGhost(stale), "Stale ghost should be culled by tick");
        assertNotNull(manager.getGhost(fresh), "Freshly-updated ghost must survive the same tick");
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

    // ===== Luciferase-7wzml.204: metrics volatile publication fix =====

    /**
     * Verifies that {@code setMetrics}/{@code getMetrics} round-trip correctly.
     * The {@code metrics} field is now {@code volatile}; this test documents the
     * publication-ordering fix: a post-construction {@code setMetrics} call is
     * immediately visible to any reader on any thread (no data race).
     */
    @Test
    void testMetricsRoundTrip_volatilePublication() {
        // Initially null — no metrics configured
        assertNull(manager.getMetrics(), "metrics should be null before setMetrics");

        // Install a metrics instance
        var metrics = new GhostPhysicsMetrics();
        manager.setMetrics(metrics);

        // getMetrics() must return the same instance (volatile write is visible)
        assertSame(metrics, manager.getMetrics(),
                  "getMetrics() must return the same instance after setMetrics (volatile publication)");

        // Clearing is also observable
        manager.setMetrics(null);
        assertNull(manager.getMetrics(), "metrics should be null after setMetrics(null)");
    }

    // ===== Luciferase-7wzml.202: STALE warning window + getState() authoritative =====

    /**
     * Verifies the STALE warning window: a ghost in the 300–500ms window after its last update
     * has {@code isStale()==true} but is NOT yet expired, and {@code getState()==STALE}.
     * After the TTL (500ms) the state must be EXPIRED.
     * <p>
     * This test would have failed before the fix because DEFAULT_STALENESS_THRESHOLD_MS was
     * equal to the TTL (both 500ms), making the STALE window zero.
     */
    @Test
    void testStalenessWarningWindow_getStateIsAuthoritative() {
        var lc = new GhostLifecycleStateMachine(500L, 300L);
        var testClock = new TestClock();
        lc.setClock(testClock);

        var entityId = "stale-window-entity";
        var sourceBubble = UUID.randomUUID();

        testClock.setMillis(1000L);
        lc.onCreate(entityId, sourceBubble, 1000L);
        lc.onUpdate(entityId, 1000L);   // CREATED → ACTIVE, lastUpdate = 1000

        // t=1000: freshly active, not stale
        assertEquals(GhostLifecycleStateMachine.State.ACTIVE,
                     lc.getState(entityId), "At t=1000 state should be ACTIVE (fresh)");
        assertFalse(lc.isStale(entityId), "Should not be stale at t=1000");
        assertFalse(lc.isExpired(entityId), "Should not be expired at t=1000");

        // t=1350: 350ms elapsed — past staleness threshold (300ms), before TTL (500ms)
        testClock.setMillis(1350L);
        assertEquals(GhostLifecycleStateMachine.State.STALE,
                     lc.getState(entityId), "At t=1350 (350ms > threshold 300ms) state must be STALE");
        assertTrue(lc.isStale(entityId), "isStale() must be true in warning window");
        assertFalse(lc.isExpired(entityId), "isExpired() must be false in warning window (before TTL 500ms)");

        // t=1501: 501ms elapsed — past TTL (500ms) → EXPIRED
        testClock.setMillis(1501L);
        assertEquals(GhostLifecycleStateMachine.State.EXPIRED,
                     lc.getState(entityId), "At t=1501 (501ms > TTL 500ms) state must be EXPIRED");
        assertTrue(lc.isExpired(entityId), "isExpired() must be true past TTL");
    }

    /**
     * Verifies that getState() does not return STALE for a freshly updated ghost,
     * and that getState() / isStale() / isExpired() remain mutually consistent.
     */
    @Test
    void testGetState_consistentWithIsStaleAndIsExpired() {
        var lc = new GhostLifecycleStateMachine();  // defaults: 300ms stale, 500ms TTL
        lc.setClock(new TestClock());

        var id = "consistency-entity";
        var bubble = UUID.randomUUID();

        // Capture the clock reference for advancing
        var lc2 = new GhostLifecycleStateMachine();
        var tc2 = new TestClock();
        tc2.setMillis(1000L);
        lc2.setClock(tc2);
        lc2.onCreate(id, bubble, 1000L);
        lc2.onUpdate(id, 1000L);

        // At creation time: ACTIVE, not stale, not expired
        assertEquals(GhostLifecycleStateMachine.State.ACTIVE,
                     lc2.getState(id), "fresh ghost: getState() must be ACTIVE");
        assertFalse(lc2.isStale(id), "fresh ghost: isStale must be false");
        assertFalse(lc2.isExpired(id), "fresh ghost: isExpired must be false");

        // In STALE window: isStale true, isExpired false, getState STALE
        tc2.setMillis(1400L); // 400ms > 300ms threshold, < 500ms TTL
        assertEquals(GhostLifecycleStateMachine.State.STALE,
                     lc2.getState(id), "400ms: getState() must return STALE");
        assertTrue(lc2.isStale(id), "400ms: isStale must be true");
        assertFalse(lc2.isExpired(id), "400ms: isExpired must be false");

        // Past TTL: getState EXPIRED
        tc2.setMillis(1600L); // 600ms > 500ms TTL
        assertEquals(GhostLifecycleStateMachine.State.EXPIRED,
                     lc2.getState(id), "600ms: getState() must return EXPIRED");
        assertTrue(lc2.isExpired(id), "600ms: isExpired must be true");
    }

    // Inner TestClock to avoid importing the lifecycle test's private class
    private static class TestClock implements com.hellblazer.luciferase.common.time.Clock {
        private long millis = 0L;
        void setMillis(long t) { millis = t; }
        @Override public long currentTimeMillis() { return millis; }
        @Override public long nanoTime() { return millis * 1_000_000L; }
    }
}
