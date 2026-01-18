package com.hellblazer.luciferase.simulation.scheduling;

import com.hellblazer.luciferase.simulation.scheduling.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Synchronization barrier for bucket-based simulation coordination across distributed nodes.
 * <p>
 * BucketBarrier ensures that a node waits for its neighbors to reach the same bucket before
 * proceeding, preventing causality violations from processing ahead of neighbors.
 * <p>
 * Key features:
 * - Timeout-based waiting (prevents deadlock)
 * - Tracks missing neighbors on timeout
 * - Resets after each bucket for continuous operation
 * <p>
 * Usage:
 * <pre>
 * // Create barrier with expected neighbors
 * var barrier = new BucketBarrier(Set.of(neighborA, neighborB, neighborC));
 *
 * // When neighbor message arrives
 * barrier.recordNeighborReady(neighborId, currentBucket);
 *
 * // Wait for all neighbors before processing bucket
 * var outcome = barrier.waitForNeighbors(currentBucket, 200); // 200ms timeout
 * if (outcome.allReady()) {
 *     // Process bucket
 * } else {
 *     // Handle timeout, proceed with available neighbors
 *     log.warn("Missing neighbors: {}", outcome.missingNeighbors());
 * }
 * </pre>
 *
 * @author hal.hildebrand
 */
public class BucketBarrier {

    /**
     * Timeout for neighbor synchronization (milliseconds).
     */
    public static final long TIMEOUT_MS = 200;

    /**
     * Result of waiting for neighbors.
     *
     * @param allReady          Whether all neighbors were ready within timeout
     * @param missingNeighbors  Set of neighbors that didn't respond (empty if allReady)
     */
    public record WaitOutcome(
        boolean allReady,
        Set<UUID> missingNeighbors
    ) {
        /**
         * All neighbors ready (no timeout).
         */
        public static WaitOutcome success() {
            return new WaitOutcome(true, Set.of());
        }

        /**
         * Timeout occurred with missing neighbors.
         */
        public static WaitOutcome timeout(Set<UUID> missing) {
            return new WaitOutcome(false, Set.copyOf(missing));
        }
    }

    private final Set<UUID> expectedNeighbors;
    private final Map<UUID, Long> neighborBuckets;  // neighbor -> bucket they're on
    private final AtomicReference<CountDownLatch> latchRef;
    private final AtomicLong currentBucketRef;
    private final ReentrantLock latchLock;

    /**
     * Create a bucket barrier for the specified neighbors.
     *
     * @param expectedNeighbors Set of neighbor UUIDs to wait for
     */
    public BucketBarrier(Set<UUID> expectedNeighbors) {
        this.expectedNeighbors = Set.copyOf(expectedNeighbors);
        this.neighborBuckets = new ConcurrentHashMap<>();
        this.currentBucketRef = new AtomicLong(-1);
        this.latchRef = new AtomicReference<>(new CountDownLatch(expectedNeighbors.size()));
        this.latchLock = new ReentrantLock();
    }

    /**
     * Record that a neighbor has reached the specified bucket.
     *
     * @param neighborId Neighbor UUID
     * @param bucket     Bucket the neighbor has reached
     */
    public void recordNeighborReady(UUID neighborId, long bucket) {
        if (!expectedNeighbors.contains(neighborId)) {
            // Ignore unexpected neighbors
            return;
        }

        neighborBuckets.put(neighborId, bucket);

        // Lock-free countdown: read atomic references and countdown if appropriate
        var latch = latchRef.get();
        if (latch != null && bucket >= currentBucketRef.get()) {
            latch.countDown();
        }
    }

    /**
     * Wait for all neighbors to reach the specified bucket.
     * <p>
     * Blocks until all neighbors have reached the bucket or timeout occurs.
     *
     * @param bucket     Bucket to wait for
     * @param timeoutMs  Maximum time to wait in milliseconds
     * @return WaitOutcome indicating success or missing neighbors
     */
    public WaitOutcome waitForNeighbors(long bucket, long timeoutMs) {
        CountDownLatch latch;

        // Short lock for latch creation only
        latchLock.lock();
        try {
            // Reset for new bucket
            if (bucket != currentBucketRef.get()) {
                currentBucketRef.set(bucket);
                var newLatch = new CountDownLatch(expectedNeighbors.size());
                latchRef.set(newLatch);

                // Count down for neighbors already at this bucket
                for (var entry : neighborBuckets.entrySet()) {
                    if (entry.getValue() >= bucket) {
                        newLatch.countDown();
                    }
                }
            }
            latch = latchRef.get();
        } finally {
            latchLock.unlock();
        }

        // Await outside lock to allow concurrent countdowns
        try {
            boolean allReady = latch.await(timeoutMs, TimeUnit.MILLISECONDS);

            if (allReady) {
                return WaitOutcome.success();
            } else {
                // Identify missing neighbors
                var missing = new HashSet<UUID>();
                for (var neighbor : expectedNeighbors) {
                    Long neighborBucket = neighborBuckets.get(neighbor);
                    if (neighborBucket == null || neighborBucket < bucket) {
                        missing.add(neighbor);
                    }
                }
                return WaitOutcome.timeout(missing);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Treat interruption as timeout
            return WaitOutcome.timeout(new HashSet<>(expectedNeighbors));
        }
    }

    /**
     * Wait for neighbors with default timeout.
     *
     * @param bucket Bucket to wait for
     * @return WaitOutcome indicating success or missing neighbors
     */
    public WaitOutcome waitForNeighbors(long bucket) {
        return waitForNeighbors(bucket, TIMEOUT_MS);
    }

    /**
     * Get the set of expected neighbors.
     *
     * @return Unmodifiable set of neighbor UUIDs
     */
    public Set<UUID> getExpectedNeighbors() {
        return expectedNeighbors;
    }

    /**
     * Get the current bucket being synchronized.
     *
     * @return Current bucket number
     */
    public long getCurrentBucket() {
        return currentBucketRef.get();
    }

    /**
     * Check if a specific neighbor is ready for the current bucket.
     *
     * @param neighborId Neighbor to check
     * @return true if neighbor has reached current bucket
     */
    public boolean isNeighborReady(UUID neighborId) {
        Long bucket = neighborBuckets.get(neighborId);
        return bucket != null && bucket >= currentBucketRef.get();
    }

    /**
     * Get the bucket a neighbor has reached.
     *
     * @param neighborId Neighbor to query
     * @return Bucket number, or -1 if neighbor hasn't reported yet
     */
    public long getNeighborBucket(UUID neighborId) {
        return neighborBuckets.getOrDefault(neighborId, -1L);
    }

    @Override
    public String toString() {
        long bucket = currentBucketRef.get();
        return String.format("BucketBarrier{bucket=%d, neighbors=%d/%d}",
                            bucket,
                            neighborBuckets.values().stream().filter(b -> b >= bucket).count(),
                            expectedNeighbors.size());
    }
}
