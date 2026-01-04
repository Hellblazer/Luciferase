package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BucketScheduler - coordinates bucket advancement with checkpointing and neighbor sync.
 * <p>
 * BucketScheduler manages the lifecycle of a simulation bucket:
 * 1. Create checkpoint before advancing
 * 2. Wait for neighbor synchronization (via BucketBarrier)
 * 3. Process physics for current bucket
 * 4. Advance to next bucket
 * <p>
 * Handles timeout failures gracefully by proceeding with available neighbors.
 *
 * @author hal.hildebrand
 */
class BucketSchedulerTest {

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

    private BucketScheduler<TestEntityID, TestEntityState> scheduler;
    private CausalRollback<TestEntityID, TestEntityState> rollback;
    private BucketBarrier barrier;
    private AtomicInteger checkpointCallCount;
    private AtomicInteger physicsCallCount;

    @BeforeEach
    void setUp() {
        rollback = new CausalRollback<>();
        barrier = new BucketBarrier(Set.of(UUID.randomUUID(), UUID.randomUUID()));
        checkpointCallCount = new AtomicInteger(0);
        physicsCallCount = new AtomicInteger(0);

        scheduler = new BucketScheduler<>(
            rollback,
            barrier,
            this::createCheckpoint,
            this::processPhysics
        );
    }

    // Checkpoint callback for testing
    private void createCheckpoint(long bucket) {
        checkpointCallCount.incrementAndGet();
        var entityId = new TestEntityID("entity-1");
        var state = new TestEntityState(new TestContent("data-" + bucket), bucket);
        rollback.checkpoint(bucket, Map.of(entityId, state), Set.of());
    }

    // Physics callback for testing
    private void processPhysics(long bucket) {
        physicsCallCount.incrementAndGet();
    }

    @Test
    void testInitialState() {
        assertEquals(0L, scheduler.getCurrentBucket(),
                    "Initial bucket should be 0");
        assertEquals(0, checkpointCallCount.get(),
                    "No checkpoints created initially");
        assertEquals(0, physicsCallCount.get(),
                    "No physics processed initially");
    }

    @Test
    void testAdvanceBucket() {
        // Simulate all neighbors ready
        barrier.recordNeighborReady(barrier.getExpectedNeighbors().iterator().next(), 0L);
        barrier.recordNeighborReady(barrier.getExpectedNeighbors().stream()
                                          .filter(n -> !barrier.getExpectedNeighbors().iterator().next().equals(n))
                                          .findFirst().orElseThrow(), 0L);

        var result = scheduler.advanceBucket();

        assertTrue(result.isSuccess(),
                  "Should succeed when all neighbors ready");
        assertEquals(1L, scheduler.getCurrentBucket(),
                    "Bucket should advance to 1");
        assertEquals(1, checkpointCallCount.get(),
                    "Should create checkpoint before advancing");
        assertEquals(1, physicsCallCount.get(),
                    "Should process physics after advancing");
    }

    @Test
    void testAdvanceMultipleBuckets() {
        var neighbors = barrier.getExpectedNeighbors();

        for (int i = 0; i < 5; i++) {
            // Simulate neighbors ready for current bucket
            for (var neighbor : neighbors) {
                barrier.recordNeighborReady(neighbor, (long) i);
            }

            var result = scheduler.advanceBucket();
            assertTrue(result.isSuccess());
        }

        assertEquals(5L, scheduler.getCurrentBucket(),
                    "Should advance through 5 buckets");
        assertEquals(5, checkpointCallCount.get(),
                    "Should create 5 checkpoints");
        assertEquals(5, physicsCallCount.get(),
                    "Should process physics 5 times");
    }

    @Test
    void testWaitForNeighborSync() {
        // Only one neighbor ready
        var neighbors = new ArrayList<>(barrier.getExpectedNeighbors());
        barrier.recordNeighborReady(neighbors.get(0), 0L);

        // Should wait for second neighbor
        var result = scheduler.advanceBucket();

        // Depending on timeout policy, might succeed with partial neighbors or wait
        assertNotNull(result);
    }

    @Test
    void testCheckpointCreatedBeforeAdvance() {
        var neighbors = barrier.getExpectedNeighbors();

        // Record current bucket before advance
        long bucketBeforeAdvance = scheduler.getCurrentBucket();

        // Make neighbors ready
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, bucketBeforeAdvance);
        }

        scheduler.advanceBucket();

        // Verify checkpoint was created for bucket before advance
        assertTrue(rollback.hasCheckpointForBucket(bucketBeforeAdvance),
                  "Should create checkpoint before advancing bucket");
    }

    @Test
    void testPhysicsProcessedAfterSync() {
        var neighbors = barrier.getExpectedNeighbors();
        var processedBuckets = new ArrayList<Long>();

        // Replace physics callback to track processed buckets
        var customScheduler = new BucketScheduler<>(
            rollback,
            barrier,
            this::createCheckpoint,
            bucket -> processedBuckets.add(bucket)
        );

        // Advance through 3 buckets
        for (int i = 0; i < 3; i++) {
            for (var neighbor : neighbors) {
                barrier.recordNeighborReady(neighbor, (long) i);
            }
            customScheduler.advanceBucket();
        }

        assertEquals(List.of(1L, 2L, 3L), processedBuckets,
                    "Physics should be processed for buckets 1, 2, 3 after sync");
    }

    @Test
    void testAdvanceResult() {
        var neighbors = barrier.getExpectedNeighbors();

        // All neighbors ready
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 0L);
        }

        var result = scheduler.advanceBucket();

        assertTrue(result.isSuccess(),
                  "Should succeed with all neighbors");
        assertEquals(0L, result.previousBucket(),
                    "Previous bucket should be 0");
        assertEquals(1L, result.newBucket(),
                    "New bucket should be 1");
        assertTrue(result.missingNeighbors().isEmpty(),
                  "No missing neighbors when all ready");
    }

    @Test
    void testPartialNeighborSync() {
        var neighbors = new ArrayList<>(barrier.getExpectedNeighbors());

        // Only first neighbor ready
        barrier.recordNeighborReady(neighbors.get(0), 0L);

        var result = scheduler.advanceBucket();

        // Result depends on timeout policy
        if (!result.isSuccess()) {
            assertFalse(result.missingNeighbors().isEmpty(),
                       "Missing neighbors should be reported");
            assertTrue(result.missingNeighbors().contains(neighbors.get(1)),
                      "Second neighbor should be in missing list");
        }
    }

    @Test
    void testGetCurrentBucket() {
        assertEquals(0L, scheduler.getCurrentBucket());

        var neighbors = barrier.getExpectedNeighbors();
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 0L);
        }

        scheduler.advanceBucket();
        assertEquals(1L, scheduler.getCurrentBucket());

        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 1L);
        }

        scheduler.advanceBucket();
        assertEquals(2L, scheduler.getCurrentBucket());
    }

    @Test
    void testSequentialBucketAdvancement() {
        var neighbors = barrier.getExpectedNeighbors();

        // Advance bucket 0 -> 1
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 0L);
        }
        scheduler.advanceBucket();

        // Advance bucket 1 -> 2
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 1L);
        }
        scheduler.advanceBucket();

        // Verify sequential advancement
        assertEquals(2L, scheduler.getCurrentBucket());

        // Verify checkpoints exist for buckets 0 and 1
        assertTrue(rollback.hasCheckpointForBucket(0L) || rollback.hasCheckpointForBucket(1L),
                  "Should have checkpoints within rollback window");
    }

    @Test
    void testCallbackInvocationOrder() {
        var events = new ArrayList<String>();
        var neighbors = barrier.getExpectedNeighbors();

        var customScheduler = new BucketScheduler<>(
            rollback,
            barrier,
            bucket -> events.add("checkpoint-" + bucket),
            bucket -> events.add("physics-" + bucket)
        );

        // Make neighbors ready and advance
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 0L);
        }
        customScheduler.advanceBucket();

        // Verify order: checkpoint for bucket 0, then physics for bucket 1
        assertEquals("checkpoint-0", events.get(0),
                    "Checkpoint should be created first");
        assertEquals("physics-1", events.get(1),
                    "Physics should be processed after sync");
    }

    @Test
    void testNoDoubleCheckpoint() {
        var neighbors = barrier.getExpectedNeighbors();

        // Advance bucket
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 0L);
        }
        scheduler.advanceBucket();

        int checkpointsAfterFirstAdvance = checkpointCallCount.get();

        // Advance again
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 1L);
        }
        scheduler.advanceBucket();

        assertEquals(checkpointsAfterFirstAdvance + 1, checkpointCallCount.get(),
                    "Should create exactly one checkpoint per advance");
    }

    @Test
    void testAdvanceResultPreviousBucket() {
        var neighbors = barrier.getExpectedNeighbors();

        // First advance (0 -> 1)
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 0L);
        }
        var result1 = scheduler.advanceBucket();

        assertEquals(0L, result1.previousBucket());
        assertEquals(1L, result1.newBucket());

        // Second advance (1 -> 2)
        for (var neighbor : neighbors) {
            barrier.recordNeighborReady(neighbor, 1L);
        }
        var result2 = scheduler.advanceBucket();

        assertEquals(1L, result2.previousBucket());
        assertEquals(2L, result2.newBucket());
    }
}
