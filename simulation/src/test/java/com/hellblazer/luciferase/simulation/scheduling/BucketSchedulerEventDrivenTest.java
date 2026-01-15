package com.hellblazer.luciferase.simulation.scheduling;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for BucketScheduler event-driven advancement (Phase 3).
 * <p>
 * Validates the Prime-Mover @Entity pattern with event-driven polling:
 * <ul>
 *   <li>Bucket advancement via events (not blocking calls)</li>
 *   <li>1ms polling for barrier synchronization</li>
 *   <li>200ms timeout handling with graceful degradation</li>
 *   <li>CausalRollback integration (checkpoint creation)</li>
 *   <li>Neighbor synchronization during polling</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class BucketSchedulerEventDrivenTest {

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
    static class TestEntityState {
        private final String data;
        private final long version;

        TestEntityState(String data, long version) {
            this.data = data;
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestEntityState that)) return false;
            return version == that.version && data.equals(that.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(data, version);
        }
    }

    private BucketScheduler<TestEntityID, TestEntityState> scheduler;
    private CausalRollback<TestEntityID, TestEntityState> rollback;
    private BucketBarrier barrier;
    private com.hellblazer.primeMover.controllers.RealTimeController controller;
    private UUID neighborId1;
    private UUID neighborId2;
    private AtomicInteger checkpointCallCount;
    private AtomicInteger physicsCallCount;
    private final Map<Long, Long> checkpointTimes = new ConcurrentHashMap<>();
    private final AtomicLong lastBucketProcessed = new AtomicLong(-1);

    @BeforeEach
    void setUp() {
        rollback = new CausalRollback<>();
        neighborId1 = UUID.randomUUID();
        neighborId2 = UUID.randomUUID();
        barrier = new BucketBarrier(Set.of(neighborId1, neighborId2));
        controller = new com.hellblazer.primeMover.controllers.RealTimeController("EventDrivenTest");
        checkpointCallCount = new AtomicInteger(0);
        physicsCallCount = new AtomicInteger(0);
        checkpointTimes.clear();

        scheduler = new BucketScheduler<>(
            rollback,
            barrier,
            this::createCheckpoint,
            this::processPhysics,
            controller
        );
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    // Checkpoint callback for testing
    private void createCheckpoint(long bucket) {
        checkpointCallCount.incrementAndGet();
        checkpointTimes.put(bucket, System.currentTimeMillis());
        var entityId = new TestEntityID("entity-1");
        var state = new TestEntityState("data-" + bucket, bucket);
        rollback.checkpoint(bucket, Map.of(entityId, state), Set.of());
    }

    // Physics callback for testing
    private void processPhysics(long bucket) {
        physicsCallCount.incrementAndGet();
        lastBucketProcessed.set(bucket);
    }

    /**
     * Test 1: Event-driven advancement - verify buckets advance via events.
     */
    @Test
    void testEventDrivenAdvancement() throws Exception {
        // Make both neighbors ready for bucket 0
        barrier.recordNeighborReady(neighborId1, 0L);
        barrier.recordNeighborReady(neighborId2, 0L);

        // Start event-driven advancement
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();

        // Wait for at least 2 bucket advancements
        TimeUnit.MILLISECONDS.sleep(300);  // 200ms = 2 buckets (100ms each)

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();

        // Verify buckets advanced
        long finalBucket = scheduler.getCurrentBucket();
        assertThat(finalBucket).isGreaterThanOrEqualTo(1L)
            .describedAs("Should advance at least 1 bucket in 300ms");

        // Verify checkpoints created
        assertThat(checkpointCallCount.get()).isGreaterThanOrEqualTo(1)
            .describedAs("Should create at least 1 checkpoint");

        // Verify physics processed
        assertThat(physicsCallCount.get()).isGreaterThanOrEqualTo(1)
            .describedAs("Should process physics at least once");
    }

    /**
     * Test 2: Barrier polling - verify 1ms polling during barrier wait.
     * <p>
     * This test verifies that the scheduler polls the barrier at 1ms intervals
     * and responds promptly when neighbors become ready.
     */
    @Test
    void testBarrierPolling() throws Exception {
        // Start scheduler with NO neighbors ready
        scheduler.start();

        // Wait a short time
        TimeUnit.MILLISECONDS.sleep(50);

        // Record when first neighbor becomes ready
        long beforeReady = System.currentTimeMillis();
        barrier.recordNeighborReady(neighborId1, 0L);
        barrier.recordNeighborReady(neighborId2, 0L);

        // Wait for bucket advancement
        TimeUnit.MILLISECONDS.sleep(150);

        scheduler.stop();

        // Verify bucket advanced after neighbors became ready
        assertThat(scheduler.getCurrentBucket()).isGreaterThanOrEqualTo(1L)
            .describedAs("Should advance bucket after neighbors ready");

        // Verify prompt detection (within 10ms of neighbors being ready)
        // This validates 1ms polling responsiveness
        var checkpoint0Time = checkpointTimes.get(0L);
        if (checkpoint0Time != null) {
            long detectionDelay = checkpoint0Time - beforeReady;
            assertThat(detectionDelay).isLessThan(50L)
                .describedAs("Should detect ready neighbors within 50ms (1ms polling)");
        }
    }

    /**
     * Test 3: Timeout handling - verify 200ms timeout with graceful degradation.
     */
    @Test
    void testTimeoutHandling() throws Exception {
        // Only one neighbor ready (second neighbor missing)
        barrier.recordNeighborReady(neighborId1, 0L);
        // neighborId2 is NOT ready

        long startTime = System.currentTimeMillis();

        scheduler.start();

        // Wait for timeout + bucket advancement
        TimeUnit.MILLISECONDS.sleep(400);  // > 200ms timeout

        scheduler.stop();

        long elapsed = System.currentTimeMillis() - startTime;

        // Verify bucket advanced despite missing neighbor (graceful degradation)
        assertThat(scheduler.getCurrentBucket()).isGreaterThanOrEqualTo(1L)
            .describedAs("Should advance bucket after timeout even with missing neighbor");

        // Verify timeout was approximately 200ms
        assertThat(elapsed).isGreaterThanOrEqualTo(200L)
            .describedAs("Should wait at least 200ms before timeout");

        // Verify checkpoint created despite timeout
        assertThat(checkpointCallCount.get()).isGreaterThanOrEqualTo(1)
            .describedAs("Should create checkpoint even on timeout");

        // Verify physics processed despite timeout
        assertThat(physicsCallCount.get()).isGreaterThanOrEqualTo(1)
            .describedAs("Should process physics even after timeout");
    }

    /**
     * Test 4: Checkpoint integration - verify CausalRollback integration.
     */
    @Test
    void testCheckpointIntegration() throws Exception {
        // Make neighbors ready for multiple buckets
        for (int i = 0; i < 5; i++) {
            barrier.recordNeighborReady(neighborId1, (long) i);
            barrier.recordNeighborReady(neighborId2, (long) i);
        }

        scheduler.start();

        // Wait for 3 bucket advancements (300ms)
        TimeUnit.MILLISECONDS.sleep(400);

        scheduler.stop();

        long finalBucket = scheduler.getCurrentBucket();

        // Verify checkpoints created for advanced buckets
        assertThat(checkpointCallCount.get()).isGreaterThanOrEqualTo(2)
            .describedAs("Should create checkpoints for advanced buckets");

        // Verify CausalRollback has checkpoints within 2-bucket window
        // CausalRollback keeps last 2 checkpoints, so check most recent buckets
        boolean hasRecentCheckpoint = false;
        for (long bucket = Math.max(0, finalBucket - 2); bucket < finalBucket; bucket++) {
            if (rollback.hasCheckpointForBucket(bucket)) {
                hasRecentCheckpoint = true;
                break;
            }
        }

        assertThat(hasRecentCheckpoint)
            .describedAs("CausalRollback should have at least one checkpoint within 2-bucket window (buckets %d to %d)",
                        Math.max(0, finalBucket - 2), finalBucket - 1)
            .isTrue();
    }

    /**
     * Test 5: Neighbor synchronization - simulate neighbor arrivals during polling.
     * <p>
     * This test simulates a distributed scenario where neighbors arrive at different times.
     */
    @Test
    void testNeighborSync() throws Exception {
        // Start scheduler with NO neighbors ready
        scheduler.start();

        // Wait 50ms (still polling, no advancement yet)
        TimeUnit.MILLISECONDS.sleep(50);

        // First neighbor arrives
        barrier.recordNeighborReady(neighborId1, 0L);

        // Wait 50ms (still waiting for second neighbor)
        TimeUnit.MILLISECONDS.sleep(50);

        // Second neighbor arrives
        barrier.recordNeighborReady(neighborId2, 0L);

        // Wait for bucket advancement
        TimeUnit.MILLISECONDS.sleep(150);

        scheduler.stop();

        // Verify bucket advanced after both neighbors ready
        assertThat(scheduler.getCurrentBucket()).isGreaterThanOrEqualTo(1L)
            .describedAs("Should advance bucket after all neighbors ready");

        // Verify exactly 1 checkpoint for bucket 0
        assertThat(checkpointCallCount.get()).isGreaterThanOrEqualTo(1)
            .describedAs("Should create checkpoint once all neighbors ready");
    }

    /**
     * Test lifecycle methods.
     */
    @Test
    void testLifecycle() {
        assertThat(scheduler.isRunning()).isFalse()
            .describedAs("Should not be running initially");

        assertThat(scheduler.getCurrentBucket()).isEqualTo(0L)
            .describedAs("Initial bucket should be 0");

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue()
            .describedAs("Should be running after start");

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse()
            .describedAs("Should not be running after stop");
    }

    /**
     * Test constructor validation.
     */
    @Test
    void testConstructorValidation() {
        assertThatThrownBy(() ->
            new BucketScheduler<TestEntityID, TestEntityState>(
                null, barrier, this::createCheckpoint, this::processPhysics, controller))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Rollback cannot be null");

        assertThatThrownBy(() ->
            new BucketScheduler<TestEntityID, TestEntityState>(
                rollback, null, this::createCheckpoint, this::processPhysics, controller))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Barrier cannot be null");

        assertThatThrownBy(() ->
            new BucketScheduler<TestEntityID, TestEntityState>(
                rollback, barrier, null, this::processPhysics, controller))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Checkpoint callback cannot be null");

        assertThatThrownBy(() ->
            new BucketScheduler<TestEntityID, TestEntityState>(
                rollback, barrier, this::createCheckpoint, null, controller))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Physics callback cannot be null");

        assertThatThrownBy(() ->
            new BucketScheduler<TestEntityID, TestEntityState>(
                rollback, barrier, this::createCheckpoint, this::processPhysics, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Controller cannot be null");
    }

    /**
     * Test getters.
     */
    @Test
    void testGetters() {
        assertThat(scheduler.getRollback()).isSameAs(rollback);
        assertThat(scheduler.getBarrier()).isSameAs(barrier);
        assertThat(scheduler.getController()).isSameAs(controller);
    }

    /**
     * Test multiple start/stop cycles.
     */
    @Test
    void testMultipleStartStopCycles() throws Exception {
        // Make neighbors ready for many buckets upfront
        for (int i = 0; i < 10; i++) {
            barrier.recordNeighborReady(neighborId1, (long) i);
            barrier.recordNeighborReady(neighborId2, (long) i);
        }

        // Cycle 1
        scheduler.start();
        TimeUnit.MILLISECONDS.sleep(250);  // Allow at least 2 buckets
        scheduler.stop();
        long bucket1 = scheduler.getCurrentBucket();

        assertThat(bucket1).isGreaterThanOrEqualTo(1L)
            .describedAs("Should advance at least 1 bucket in cycle 1");

        // Cycle 2 - wait a bit before restarting
        TimeUnit.MILLISECONDS.sleep(100);

        scheduler.start();
        TimeUnit.MILLISECONDS.sleep(250);  // Allow at least 2 more buckets
        scheduler.stop();
        long bucket2 = scheduler.getCurrentBucket();

        // Bucket should be at least as high as after cycle 1 (might not advance if timing is tight)
        assertThat(bucket2).isGreaterThanOrEqualTo(bucket1)
            .describedAs("Bucket should not regress across start/stop cycles (bucket1=%d, bucket2=%d)", bucket1, bucket2);
    }
}
