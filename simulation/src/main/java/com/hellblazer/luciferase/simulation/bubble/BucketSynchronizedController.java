/**
 * BucketSynchronizedController - RealTimeController with Bucket Boundary Synchronization
 *
 * Extends RealTimeController to synchronize simulation time at bucket boundaries (100ms intervals).
 * This bounds clock drift to one bucket window, solving the timing architecture validation issue.
 *
 * STRATEGY:
 * - Override tickLoop() to apply synchronization at bucket boundaries
 * - Track wall-clock time to detect bucket transitions
 * - At each boundary, align simulation time to target: simulationTime = max(current, target)
 * - Target simulation time: bucket * TICKS_PER_BUCKET (10 ticks per bucket at 100Hz)
 *
 * EFFECT:
 * - Maximum drift bounded to ~40ms (one bucket)
 * - Maintains autonomy within buckets
 * - Enables distributed coordination across bucket boundaries
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class BucketSynchronizedController extends RealTimeController {

    private static final long BUCKET_DURATION_MS = 100;
    private static final long TICKS_PER_BUCKET = 10;  // 100Hz ticks * 100ms = 10 ticks
    private static final long BUCKET_DURATION_NS = BUCKET_DURATION_MS * 1_000_000L;

    private volatile Clock clock = Clock.system();

    private final AtomicLong currentBucket = new AtomicLong(0);
    private final AtomicLong startTimeNs = new AtomicLong(0);

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public BucketSynchronizedController(UUID bubbleId, String name) {
        super(bubbleId, name, 100);  // 100Hz
    }

    public BucketSynchronizedController(UUID bubbleId, String name, int tickRate) {
        super(bubbleId, name, tickRate);
    }

    /**
     * Override tickLoop to apply bucket boundary synchronization.
     * Mostly replicates parent logic, but adds synchronization at bucket boundaries.
     */
    @Override
    protected void tickLoop() {
        long startNs = clock.nanoTime();
        startTimeNs.set(startNs);

        while (running.get()) {
            var currentSimTime = simulationTime.incrementAndGet();
            var currentLamportClock = clockGenerator.tick();

            // Emit local tick event for entity updates
            emitLocalTickEvent(currentSimTime, currentLamportClock);

            if (currentSimTime % 100 == 0) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .debug("Tick: bubble={}, simTime={}, lamportClock={}",
                           bubbleId, currentSimTime, currentLamportClock);
            }

            // Check if we've crossed a bucket boundary
            long nowNs = clock.nanoTime();
            long elapsedNs = nowNs - startTimeNs.get();
            long currentBucketNum = elapsedNs / BUCKET_DURATION_NS;
            long lastBucketNum = currentBucket.get();

            // If bucket changed, synchronize
            if (currentBucketNum > lastBucketNum) {
                synchronizeAtBucket(currentBucketNum, currentSimTime);
            }

            // Sleep for tick period
            try {
                TimeUnit.NANOSECONDS.sleep(tickPeriodNs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        org.slf4j.LoggerFactory.getLogger(getClass())
            .debug("Tick loop exited: bubble={}, finalTime={}", bubbleId, simulationTime.get());
    }

    /**
     * Synchronize at bucket boundary.
     * Applies: simulationTime = max(current, target)
     */
    private void synchronizeAtBucket(long bucketNum, long currentSimTime) {
        long targetSimTime = bucketNum * TICKS_PER_BUCKET;
        long alignedSimTime = Math.max(currentSimTime, targetSimTime);

        // Apply the alignment if needed
        if (alignedSimTime > currentSimTime) {
            setSimulationTime(alignedSimTime);
        }

        currentBucket.set(bucketNum);

        org.slf4j.LoggerFactory.getLogger(getClass())
            .debug("Bucket sync: bubble={}, bucket={}, targetSimTime={}, currentSimTime={}, alignedSimTime={}",
                   bubbleId, bucketNum, targetSimTime, currentSimTime, alignedSimTime);
    }

    /**
     * Advance to next bucket (called by test framework at bucket boundaries).
     * This provides external hint about bucket advancement.
     */
    public void advanceBucket(long newBucket) {
        currentBucket.set(newBucket);
    }

    /**
     * Get current bucket number.
     */
    public long getCurrentBucket() {
        return currentBucket.get();
    }

    /**
     * Get drift from bucket target.
     * Target = bucket * TICKS_PER_BUCKET
     */
    public long getDriftFromTarget() {
        long targetSimTime = currentBucket.get() * TICKS_PER_BUCKET;
        return getSimulationTime() - targetSimTime;
    }
}
