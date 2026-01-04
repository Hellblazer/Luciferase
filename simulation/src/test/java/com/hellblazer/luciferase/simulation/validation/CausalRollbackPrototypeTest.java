package com.hellblazer.luciferase.simulation.validation;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Validation: V2 - Causal Rollback Prototype
 * <p>
 * Tests checkpoint/restore mechanism for bounded rollback (GGPO-style).
 * Success criteria:
 * - Checkpoint captures full state snapshot
 * - Restore returns to exact checkpointed state
 * - Replay of events produces deterministic results
 * - 200ms rollback window (2-3 checkpoints) is manageable
 *
 * @author claude
 */
public class CausalRollbackPrototypeTest {

    /**
     * Simple entity state for testing rollback
     */
    static class EntityState {
        final String id;
        float x, y, z;
        float vx, vy, vz;
        int health;
        long lastUpdate;

        EntityState(String id, float x, float y, float z) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.health = 100;
        }

        EntityState copy() {
            var copy = new EntityState(id, x, y, z);
            copy.vx = vx;
            copy.vy = vy;
            copy.vz = vz;
            copy.health = health;
            copy.lastUpdate = lastUpdate;
            return copy;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EntityState that)) return false;
            return Float.compare(x, that.x) == 0 &&
                   Float.compare(y, that.y) == 0 &&
                   Float.compare(z, that.z) == 0 &&
                   Float.compare(vx, that.vx) == 0 &&
                   Float.compare(vy, that.vy) == 0 &&
                   Float.compare(vz, that.vz) == 0 &&
                   health == that.health &&
                   lastUpdate == that.lastUpdate &&
                   id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, x, y, z, vx, vy, vz, health, lastUpdate);
        }
    }

    /**
     * Event applied to entities
     */
    record Event(String entityId, String type, Object data, long bucket) {
    }

    /**
     * Checkpoint of simulation state at a specific bucket
     */
    static class Checkpoint {
        final long bucket;
        final Map<String, EntityState> entities;

        Checkpoint(long bucket, Map<String, EntityState> entities) {
            this.bucket = bucket;
            // Deep copy entities
            this.entities = new HashMap<>();
            entities.forEach((id, state) -> this.entities.put(id, state.copy()));
        }
    }

    /**
     * Causal rollback manager - bounded window of checkpoints
     */
    static class CausalRollback {
        private final Deque<Checkpoint> checkpoints = new ArrayDeque<>();
        private final int maxCheckpoints; // 200ms window @ 100ms/bucket = 2-3 checkpoints

        CausalRollback(int maxCheckpoints) {
            this.maxCheckpoints = maxCheckpoints;
        }

        void checkpoint(long bucket, Map<String, EntityState> entities) {
            checkpoints.addLast(new Checkpoint(bucket, entities));

            // Maintain bounded window
            while (checkpoints.size() > maxCheckpoints) {
                checkpoints.removeFirst();
            }
        }

        Checkpoint rollbackTo(long bucket) {
            // Find checkpoint at or before target bucket
            // If target is before all checkpoints, return earliest available
            if (checkpoints.isEmpty()) {
                return null;
            }

            Checkpoint target = null;
            for (var cp : checkpoints) {
                if (cp.bucket <= bucket) {
                    target = cp; // Keep updating to get the latest valid checkpoint
                } else {
                    break; // Stop when we find a checkpoint after target
                }
            }

            // If no checkpoint found at or before bucket, return earliest (rollback as far as possible)
            return target != null ? target : checkpoints.getFirst();
        }

        Checkpoint getLatest() {
            return checkpoints.isEmpty() ? null : checkpoints.getLast();
        }

        int checkpointCount() {
            return checkpoints.size();
        }
    }

    @Test
    void testCheckpointRestore() {
        // Setup: Create initial state
        Map<String, EntityState> entities = new ConcurrentHashMap<>();
        entities.put("E1", new EntityState("E1", 0f, 0f, 0f));
        entities.put("E2", new EntityState("E2", 10f, 10f, 10f));

        var rollback = new CausalRollback(3);

        // Checkpoint at bucket 0
        rollback.checkpoint(0, entities);

        // Modify state
        entities.get("E1").x = 5f;
        entities.get("E1").health = 80;
        entities.get("E2").vx = 2f;

        // Checkpoint at bucket 1
        rollback.checkpoint(1, entities);

        // Modify more
        entities.get("E1").x = 10f;
        entities.get("E1").health = 60;

        // Rollback to bucket 0
        var checkpoint = rollback.rollbackTo(0);
        assertNotNull(checkpoint);
        assertEquals(0, checkpoint.bucket);

        // Restore state
        entities.clear();
        checkpoint.entities.forEach((id, state) -> entities.put(id, state.copy()));

        // Verify state matches checkpoint
        assertEquals(0f, entities.get("E1").x, 0.001f);
        assertEquals(100, entities.get("E1").health);
        assertEquals(10f, entities.get("E2").x, 0.001f);
        assertEquals(0f, entities.get("E2").vx, 0.001f);
    }

    @Test
    void testReplayProducesSameResult() {
        // Setup: Initial state
        Map<String, EntityState> entities = new ConcurrentHashMap<>();
        entities.put("E1", new EntityState("E1", 0f, 0f, 0f));

        var rollback = new CausalRollback(3);

        // Record events
        List<Event> events = new ArrayList<>();
        events.add(new Event("E1", "MOVE", new float[]{1f, 0f, 0f}, 1));
        events.add(new Event("E1", "DAMAGE", -10, 1));
        events.add(new Event("E1", "MOVE", new float[]{1f, 0f, 0f}, 2));

        // First execution
        rollback.checkpoint(0, entities);
        applyEvents(entities, events);
        float firstX = entities.get("E1").x;
        int firstHealth = entities.get("E1").health;

        // Rollback to bucket 0
        var checkpoint = rollback.rollbackTo(0);
        entities.clear();
        checkpoint.entities.forEach((id, state) -> entities.put(id, state.copy()));

        // Replay events
        applyEvents(entities, events);
        float replayX = entities.get("E1").x;
        int replayHealth = entities.get("E1").health;

        // Verify deterministic replay
        assertEquals(firstX, replayX, 0.001f, "Replay X position should match");
        assertEquals(firstHealth, replayHealth, "Replay health should match");
    }

    @Test
    void testBoundedRollbackWindow() {
        Map<String, EntityState> entities = new ConcurrentHashMap<>();
        entities.put("E1", new EntityState("E1", 0f, 0f, 0f));

        var rollback = new CausalRollback(3); // 3 checkpoint max

        // Create 5 checkpoints
        for (long bucket = 0; bucket < 5; bucket++) {
            entities.get("E1").x = bucket * 10f;
            rollback.checkpoint(bucket, entities);
        }

        // Should only retain last 3
        assertEquals(3, rollback.checkpointCount());

        // Can rollback to bucket 2 (3rd from last)
        var checkpoint = rollback.rollbackTo(2);
        assertNotNull(checkpoint);
        assertEquals(2, checkpoint.bucket);

        // Cannot rollback to bucket 0 or 1 (expired)
        checkpoint = rollback.rollbackTo(0);
        assertNotNull(checkpoint); // Returns earliest available (bucket 2)
        assertEquals(2, checkpoint.bucket);
    }

    @Test
    void testRollbackWindowSufficient() {
        // Simulate 200ms rollback window @ 100ms per bucket = 2 buckets
        Map<String, EntityState> entities = new ConcurrentHashMap<>();
        entities.put("E1", new EntityState("E1", 0f, 0f, 0f));
        entities.put("E2", new EntityState("E2", 5f, 5f, 5f));

        var rollback = new CausalRollback(3); // 300ms window (generous)

        // Simulate collision detection arriving late
        // Bucket 0: Initial state
        rollback.checkpoint(0, entities);
        entities.get("E1").x = 2f;
        entities.get("E2").x = 3f;

        // Bucket 1: Entities moved closer
        rollback.checkpoint(1, entities);
        entities.get("E1").x = 4f;
        entities.get("E2").x = 4.5f;

        // Bucket 2: Collision should have been detected at bucket 1
        // Late collision message arrives - rollback to bucket 1
        var checkpoint = rollback.rollbackTo(1);
        assertNotNull(checkpoint, "Should be able to rollback within 200ms window");

        entities.clear();
        checkpoint.entities.forEach((id, state) -> entities.put(id, state.copy()));

        // Verify we can replay with correct collision handling
        assertEquals(2f, entities.get("E1").x, 0.001f);
        assertEquals(3f, entities.get("E2").x, 0.001f);
    }

    @Test
    void testMemoryFootprint() {
        // Verify memory usage is reasonable for typical scenarios
        Map<String, EntityState> entities = new ConcurrentHashMap<>();

        // 1000 entities
        for (int i = 0; i < 1000; i++) {
            entities.put("E" + i, new EntityState("E" + i, i * 0.1f, i * 0.1f, i * 0.1f));
        }

        var rollback = new CausalRollback(3);

        // Measure memory per checkpoint (rough estimate)
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();

        rollback.checkpoint(0, entities);

        long after = runtime.totalMemory() - runtime.freeMemory();
        long checkpointSize = after - before;

        // 1000 entities * ~100 bytes per entity = ~100KB per checkpoint
        // 3 checkpoints = ~300KB total
        // Should be < 1MB for 1000 entities
        assertTrue(checkpointSize < 1_000_000,
                   "Checkpoint memory should be < 1MB for 1000 entities, was: " + checkpointSize);
    }

    /**
     * Apply events to entities (deterministic simulation)
     */
    private void applyEvents(Map<String, EntityState> entities, List<Event> events) {
        for (var event : events) {
            var entity = entities.get(event.entityId);
            if (entity == null) continue;

            switch (event.type) {
                case "MOVE" -> {
                    float[] delta = (float[]) event.data;
                    entity.x += delta[0];
                    entity.y += delta[1];
                    entity.z += delta[2];
                }
                case "DAMAGE" -> {
                    int damage = (int) event.data;
                    entity.health += damage; // damage is negative
                }
            }
            entity.lastUpdate = event.bucket;
        }
    }
}
