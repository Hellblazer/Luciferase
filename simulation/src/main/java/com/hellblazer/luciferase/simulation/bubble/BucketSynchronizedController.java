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

import com.hellblazer.luciferase.common.time.Clock;
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
        // Deadline-based scheduling: the next tick is due tickPeriodNs after the
        // previous deadline, NOT tickPeriodNs after the tick work finishes. This
        // subtracts per-tick work time from the sleep, matching the parent
        // RealTimeController and preventing monotonic drift proportional to load
        // (Luciferase-0frcy.56). A fixed-period sleep would accumulate the work
        // duration as drift on every tick.
        long nextDeadlineNs = startNs + tickPeriodNs;

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

            // Check if we've crossed a bucket boundary. Compute the expected
            // bucket from elapsed wall-clock time, then advance currentBucket via
            // a single CAS so only one thread (this tick loop or a racing
            // advanceBucket) applies a given transition. Reading currentBucket
            // and then unconditionally set()-ing it left a TOCTOU window where an
            // interleaved advanceBucket could be silently overwritten with a
            // stale bucket number (Luciferase-0frcy.56).
            long nowNs = clock.nanoTime();
            long elapsedNs = nowNs - startTimeNs.get();
            long currentBucketNum = elapsedNs / BUCKET_DURATION_NS;
            long lastBucketNum = currentBucket.get();

            // If bucket changed, synchronize (CAS guards against a racing
            // advanceBucket applying the same or a newer transition first).
            if (currentBucketNum > lastBucketNum
                && currentBucket.compareAndSet(lastBucketNum, currentBucketNum)) {
                synchronizeAtBucket(currentBucketNum, currentSimTime);
            }

            // Sleep only the remainder of this tick's deadline; if the tick work
            // already overran the period, do not sleep (and re-anchor below).
            long sleepNs = nextDeadlineNs - clock.nanoTime();
            if (sleepNs > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(sleepNs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            nextDeadlineNs += tickPeriodNs;
            // If we have fallen more than a full period behind (heavy overrun),
            // re-anchor the deadline to now so we do not spin without sleeping
            // trying to "catch up".
            long behindNs = clock.nanoTime() - nextDeadlineNs;
            if (behindNs > tickPeriodNs) {
                nextDeadlineNs = clock.nanoTime() + tickPeriodNs;
            }
        }

        org.slf4j.LoggerFactory.getLogger(getClass())
            .debug("Tick loop exited: bubble={}, finalTime={}", bubbleId, simulationTime.get());
    }

    /**
     * Synchronize at bucket boundary.
     * Applies: simulationTime = max(current, target)
     */
    // Visible-for-testing: deterministic regression testing of the synthetic tick
    // emission on a bucket-boundary jump (Luciferase-0frcy.83). Public because the
    // @Entity PrimeMover transformation prevents package-private access from the test
    // (same widening rationale as VolumeAnimator.frameSleepNs).
    public void synchronizeAtBucket(long bucketNum, long currentSimTime) {
        long targetSimTime = bucketNum * TICKS_PER_BUCKET;
        long alignedSimTime = Math.max(currentSimTime, targetSimTime);

        // Apply the alignment if needed. The forward jump from currentSimTime
        // to alignedSimTime skips intermediate tick values; emit a synthetic
        // tick event for each so monotonic listeners (e.g. ghost dead-reckoning
        // interpolation) never see a simulation-time discontinuity across the
        // bucket boundary (Luciferase-0frcy.83).
        if (alignedSimTime > currentSimTime) {
            for (long t = currentSimTime + 1; t <= alignedSimTime; t++) {
                setSimulationTime(t);
                emitLocalTickEvent(t, clockGenerator.tick());
            }
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
