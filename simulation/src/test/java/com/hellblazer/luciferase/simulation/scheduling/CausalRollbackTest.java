package com.hellblazer.luciferase.simulation.scheduling;

import com.hellblazer.luciferase.simulation.scheduling.*;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CausalRollback - GGPO-style bounded rollback for late message handling.
 * <p>
 * CausalRollback manages a bounded window of checkpoints (200ms = 2 buckets) to handle
 * late-arriving messages without unbounded state retention.
 * <p>
 * Key behaviors:
 * - Checkpoint creation at bucket boundaries
 * - Rollback to previous bucket when late message arrives
 * - Bounded window enforcement (discard old checkpoints)
 * - Graceful rejection of messages beyond rollback window
 *
 * @author hal.hildebrand
 */
class CausalRollbackTest {

    // Simple EntityID implementation for testing
    static class TestEntityID implements EntityID {
        private final String id;

        TestEntityID(String id) {
            this.id = id;
        }

        @Override
        public String toDebugString() {
            return id;
        }

        @Override
        public int compareTo(EntityID other) {
            return id.compareTo(other.toDebugString());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestEntityID that)) return false;
            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    // Simple test content
    static class TestContent {
        private final String data;

        TestContent(String data) {
            this.data = data;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestContent that)) return false;
            return data.equals(that.data);
        }

        @Override
        public int hashCode() {
            return data.hashCode();
        }
    }

    // Simple EntityState for testing
    static class TestEntityState {
        private final TestContent content;
        private final long version;

        TestEntityState(TestContent content, long version) {
            this.content = content;
            this.version = version;
        }

        public TestContent getContent() {
            return content;
        }

        public long getVersion() {
            return version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestEntityState that)) return false;
            return version == that.version && content.equals(that.content);
        }

        @Override
        public int hashCode() {
            return Objects.hash(content, version);
        }
    }

    private CausalRollback<TestEntityID, TestEntityState> rollback;

    @BeforeEach
    void setUp() {
        rollback = new CausalRollback<>();
    }

    @Test
    void testInitialState() {
        assertTrue(rollback.isEmpty(),
                  "Initially rollback should have no checkpoints");
        assertEquals(0, rollback.getCheckpointCount());
    }

    @Test
    void testCreateCheckpoint() {
        var entityId = new TestEntityID("entity-1");
        var state = new TestEntityState(new TestContent("data-1"), 1L);
        var states = Map.of(entityId, state);
        var processedMigrations = Set.of(UUID.randomUUID());

        rollback.checkpoint(100L, states, processedMigrations);

        assertEquals(1, rollback.getCheckpointCount(),
                    "Should have one checkpoint after creation");
        assertFalse(rollback.isEmpty());
    }

    @Test
    void testMultipleCheckpoints() {
        var entityId = new TestEntityID("entity-1");

        // Create checkpoints for buckets 100, 200, 300
        for (long bucket = 100; bucket <= 300; bucket += 100) {
            var state = new TestEntityState(
                new TestContent("data-bucket-" + bucket),
                bucket
            );
            rollback.checkpoint(bucket, Map.of(entityId, state), Set.of());
        }

        assertEquals(2, rollback.getCheckpointCount(),
                    "Should maintain bounded window of 2 checkpoints");

        // Most recent 2 should be retained (200 and 300)
        assertTrue(rollback.hasCheckpointForBucket(200L));
        assertTrue(rollback.hasCheckpointForBucket(300L));
        assertFalse(rollback.hasCheckpointForBucket(100L),
                   "Oldest checkpoint should be evicted");
    }

    @Test
    void testBoundedWindow() {
        var entityId = new TestEntityID("entity-1");

        // Create checkpoints beyond MAX_ROLLBACK_BUCKETS (2)
        // Should keep only the most recent 2 checkpoints
        for (long bucket = 100; bucket <= 500; bucket += 100) {
            var state = new TestEntityState(
                new TestContent("data-bucket-" + bucket),
                bucket
            );
            rollback.checkpoint(bucket, Map.of(entityId, state), Set.of());
        }

        assertEquals(2, rollback.getCheckpointCount(),
                    "Should maintain only 2 most recent checkpoints (bounded window)");

        // Verify we have checkpoints for buckets 400 and 500 (most recent)
        assertTrue(rollback.hasCheckpointForBucket(400L));
        assertTrue(rollback.hasCheckpointForBucket(500L));
        assertFalse(rollback.hasCheckpointForBucket(300L),
                   "Older checkpoints should be discarded");
    }

    @Test
    void testRollbackToExistingCheckpoint() {
        var entityId = new TestEntityID("entity-1");

        // Create checkpoint at bucket 100
        var state100 = new TestEntityState(new TestContent("data-100"), 100L);
        rollback.checkpoint(100L, Map.of(entityId, state100), Set.of());

        // Create checkpoint at bucket 200
        var state200 = new TestEntityState(new TestContent("data-200"), 200L);
        rollback.checkpoint(200L, Map.of(entityId, state200), Set.of());

        // Late message arrives for bucket 150 (between 100 and 200)
        // Should rollback to bucket 100
        var result = rollback.rollbackIfNeeded(150L);

        assertTrue(result.didRollback(),
                  "Should rollback when message arrives for past bucket");
        assertEquals(100L, result.targetBucket(),
                    "Should rollback to bucket 100 (most recent checkpoint before 150)");
    }

    @Test
    void testNoRollbackForCurrentBucket() {
        var entityId = new TestEntityID("entity-1");

        // Create checkpoint at bucket 100
        var state = new TestEntityState(new TestContent("data-100"), 100L);
        rollback.checkpoint(100L, Map.of(entityId, state), Set.of());

        // Message arrives for current bucket (100)
        var result = rollback.rollbackIfNeeded(100L);

        assertFalse(result.didRollback(),
                   "Should not rollback for current bucket message");
    }

    @Test
    void testNoRollbackForFutureBucket() {
        var entityId = new TestEntityID("entity-1");

        // Create checkpoint at bucket 100
        var state = new TestEntityState(new TestContent("data-100"), 100L);
        rollback.checkpoint(100L, Map.of(entityId, state), Set.of());

        // Message arrives for future bucket (200)
        var result = rollback.rollbackIfNeeded(200L);

        assertFalse(result.didRollback(),
                   "Should not rollback for future bucket message");
    }

    @Test
    void testRejectMessageBeyondWindow() {
        var entityId = new TestEntityID("entity-1");

        // Create checkpoints for buckets 300 and 400
        rollback.checkpoint(300L, Map.of(entityId, new TestEntityState(new TestContent("data-300"), 300L)), Set.of());
        rollback.checkpoint(400L, Map.of(entityId, new TestEntityState(new TestContent("data-400"), 400L)), Set.of());

        // Message arrives for bucket 100 (before oldest checkpoint at 300)
        var result = rollback.rollbackIfNeeded(100L);

        assertTrue(result.isRejected(),
                  "Should reject message beyond rollback window");
        assertFalse(result.didRollback(),
                   "Should not rollback for rejected message");
    }

    @Test
    void testRestoreCheckpointState() {
        var entityId = new TestEntityID("entity-1");
        var state100 = new TestEntityState(new TestContent("data-100"), 100L);
        var processedMigrations = Set.of(UUID.randomUUID());

        rollback.checkpoint(100L, Map.of(entityId, state100), processedMigrations);

        var checkpoint = rollback.getCheckpoint(100L);

        assertTrue(checkpoint.isPresent(), "Should retrieve checkpoint");
        assertEquals(100L, checkpoint.get().bucket());
        assertEquals(state100, checkpoint.get().states().get(entityId));
        assertEquals(processedMigrations, checkpoint.get().processedMigrations());
    }

    @Test
    void testDiscardCheckpointsAfterRollback() {
        var entityId = new TestEntityID("entity-1");

        // Create checkpoints for buckets 200 and 300 (stay within window)
        rollback.checkpoint(200L, Map.of(entityId, new TestEntityState(new TestContent("data-200"), 200L)), Set.of());
        rollback.checkpoint(300L, Map.of(entityId, new TestEntityState(new TestContent("data-300"), 300L)), Set.of());

        // Late message for bucket 250 (between 200 and 300)
        // Should rollback to bucket 200 and discard bucket 300
        var result = rollback.rollbackIfNeeded(250L);

        assertTrue(result.didRollback(), "Should rollback for message in past bucket");
        assertEquals(200L, result.targetBucket());

        // Verify bucket 200 still exists (rollback target)
        assertTrue(rollback.hasCheckpointForBucket(200L),
                  "Rollback target checkpoint should be retained");

        // Verify bucket 300 is discarded (after rollback target)
        assertFalse(rollback.hasCheckpointForBucket(300L),
                   "Checkpoints after rollback target should be discarded");
    }

    @Test
    void testEmptyCheckpoint() {
        // Checkpoint with no entities or migrations
        rollback.checkpoint(100L, Map.of(), Set.of());

        assertEquals(1, rollback.getCheckpointCount());
        var checkpoint = rollback.getCheckpoint(100L);

        assertTrue(checkpoint.isPresent());
        assertTrue(checkpoint.get().states().isEmpty());
        assertTrue(checkpoint.get().processedMigrations().isEmpty());
    }

    @Test
    void testMultipleEntitiesInCheckpoint() {
        var entity1 = new TestEntityID("entity-1");
        var entity2 = new TestEntityID("entity-2");
        var entity3 = new TestEntityID("entity-3");

        var states = Map.of(
            entity1, new TestEntityState(new TestContent("data-1"), 1L),
            entity2, new TestEntityState(new TestContent("data-2"), 2L),
            entity3, new TestEntityState(new TestContent("data-3"), 3L)
        );

        rollback.checkpoint(100L, states, Set.of());

        var checkpoint = rollback.getCheckpoint(100L);

        assertTrue(checkpoint.isPresent());
        assertEquals(3, checkpoint.get().states().size());
        assertEquals(states, checkpoint.get().states());
    }

    @Test
    void testRollbackResult() {
        var entityId = new TestEntityID("entity-1");

        rollback.checkpoint(100L, Map.of(entityId, new TestEntityState(new TestContent("data-100"), 100L)), Set.of());
        rollback.checkpoint(200L, Map.of(entityId, new TestEntityState(new TestContent("data-200"), 200L)), Set.of());

        // Late message for bucket 150
        var result = rollback.rollbackIfNeeded(150L);

        assertTrue(result.didRollback());
        assertEquals(100L, result.targetBucket());
        assertFalse(result.isRejected());

        // Verify result provides checkpoint data
        assertTrue(result.restoredStates().isPresent());
        assertEquals(1, result.restoredStates().get().size());
    }

    @Test
    void testConcurrentCheckpointCreation() throws InterruptedException {
        int threadCount = 10;
        int checkpointsPerThread = 10;

        var threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < checkpointsPerThread; j++) {
                    long bucket = (threadId * checkpointsPerThread + j) * 100L;
                    var entityId = new TestEntityID("entity-" + threadId);
                    var state = new TestEntityState(
                        new TestContent("data-" + bucket),
                        bucket
                    );
                    rollback.checkpoint(bucket, Map.of(entityId, state), Set.of());
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // Should maintain bounded window (2 checkpoints)
        assertEquals(2, rollback.getCheckpointCount(),
                    "Concurrent checkpoints should still maintain bounded window");
    }

    @Test
    void testOldestCheckpointTracking() {
        var entityId = new TestEntityID("entity-1");

        rollback.checkpoint(100L, Map.of(entityId, new TestEntityState(new TestContent("data-100"), 100L)), Set.of());
        assertEquals(100L, rollback.getOldestBucket(),
                    "Oldest bucket should be 100");

        rollback.checkpoint(200L, Map.of(entityId, new TestEntityState(new TestContent("data-200"), 200L)), Set.of());
        assertEquals(100L, rollback.getOldestBucket(),
                    "Oldest bucket should still be 100");

        rollback.checkpoint(300L, Map.of(entityId, new TestEntityState(new TestContent("data-300"), 300L)), Set.of());
        assertEquals(200L, rollback.getOldestBucket(),
                    "Oldest bucket should be 200 after window eviction");
    }

    @Test
    void testGetLatestBucket() {
        var entityId = new TestEntityID("entity-1");

        rollback.checkpoint(100L, Map.of(entityId, new TestEntityState(new TestContent("data-100"), 100L)), Set.of());
        assertEquals(100L, rollback.getLatestBucket(),
                    "Latest bucket should be 100");

        rollback.checkpoint(200L, Map.of(entityId, new TestEntityState(new TestContent("data-200"), 200L)), Set.of());
        assertEquals(200L, rollback.getLatestBucket(),
                    "Latest bucket should be 200");
    }
}
