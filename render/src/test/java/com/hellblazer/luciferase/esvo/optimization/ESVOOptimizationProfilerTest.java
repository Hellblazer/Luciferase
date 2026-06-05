package com.hellblazer.luciferase.esvo.optimization;

import com.hellblazer.luciferase.esvo.optimization.ESVOOptimizationProfiler.ProfileResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies deterministic time injection in {@link ESVOOptimizationProfiler}.
 *
 * Covers Luciferase-7wzml.155: no direct System.nanoTime() calls;
 * all timestamps come from the injected LongSupplier so tests can
 * assert exact elapsed values.
 */
class ESVOOptimizationProfilerTest {

    /**
     * Monotonic counter-based nano clock for tests — no wall-clock dependency.
     */
    static class StepClock implements LongSupplier {
        private final AtomicLong value;

        StepClock(long initial) {
            this.value = new AtomicLong(initial);
        }

        /** Advance and return the new value. */
        long advance(long delta) {
            return value.addAndGet(delta);
        }

        @Override
        public long getAsLong() {
            return value.get();
        }
    }

    private StepClock clock;
    private ESVOOptimizationProfiler profiler;

    @BeforeEach
    void setUp() {
        clock = new StepClock(1_000_000L);
        profiler = new ESVOOptimizationProfiler(clock);
    }

    // -------------------------------------------------------------------------
    // totalProfilingTime is deterministic and exact
    // -------------------------------------------------------------------------

    @Test
    void totalTime_reflectsDeltaBetweenStartAndStop() {
        // clock = 1_000_000 before startProfiling
        profiler.startProfiling();
        // startProfiling reads clock → 1_000_000 ns

        clock.advance(5_000_000L); // advance 5 ms worth of nanos
        // stopProfiling reads clock → 6_000_000 ns
        ProfileResult result = profiler.stopProfiling();

        // totalTime = (6_000_000 - 1_000_000) / 1_000_000.0 = 5.0 ms
        assertEquals(5.0, result.getTotalProfilingTime(), 1e-9,
                "totalProfilingTime must be exactly 5.0 ms");
    }

    @Test
    void totalTime_zeroWhenStopImmediatelyAfterStart() {
        profiler.startProfiling();
        // clock not advanced — start and stop read the same value
        ProfileResult result = profiler.stopProfiling();

        assertEquals(0.0, result.getTotalProfilingTime(), 1e-9,
                "No clock advance → totalProfilingTime must be 0");
    }

    // -------------------------------------------------------------------------
    // firstAccessTime in MemoryAccessData is pinned to first-call nanoTime
    // -------------------------------------------------------------------------

    @Test
    void memoryAccess_firstAccessTime_pinnedToFirstCall() {
        profiler.startProfiling();
        long t1 = clock.advance(1_000L); // clock now = 1_001_000

        profiler.recordMemoryAccess("op", 10, 1024L);
        long firstExpected = clock.getAsLong(); // 1_001_000

        clock.advance(500L);
        // Second call should NOT update firstAccessTime
        profiler.recordMemoryAccess("op", 5, 512L);

        profiler.stopProfiling(); // stop reads clock
        // Re-start to capture a new result
        profiler.startProfiling();
        clock.advance(0);
        profiler.recordMemoryAccess("op2", 1, 64L);
        long secondAccessTs = clock.getAsLong();
        ProfileResult r = profiler.stopProfiling();

        // Verify accumulated fields
        var memAccess = r.getMemoryAccessProfiles().stream()
                          .filter(m -> "op2".equals(m.operationName))
                          .findFirst().orElseThrow();
        assertEquals(secondAccessTs, memAccess.firstAccessTime,
                "firstAccessTime must match the injected nanoTime at first call");
    }

    @Test
    void memoryAccess_aggregatesCountsAndBytes() {
        profiler.startProfiling();
        profiler.recordMemoryAccess("read", 100, 4096L);
        profiler.recordMemoryAccess("read", 50, 2048L);
        ProfileResult r = profiler.stopProfiling();

        var access = r.getMemoryAccessProfiles().get(0);
        assertEquals("read", access.operationName);
        assertEquals(150, access.accessCount,   "access counts must accumulate");
        assertEquals(6144, access.bytesAccessed, "bytes must accumulate");
    }

    // -------------------------------------------------------------------------
    // firstExecutionTime in KernelExecutionData is pinned to first-call nanoTime
    // -------------------------------------------------------------------------

    @Test
    void kernelExecution_firstExecutionTime_pinnedToFirstCall() {
        profiler.startProfiling();
        clock.advance(2_000L);
        long expected = clock.getAsLong();

        profiler.recordKernelExecution("kernel-A", 10.0f);
        clock.advance(1_000L);
        profiler.recordKernelExecution("kernel-A", 20.0f); // secondcall must not update firstExecutionTime

        ProfileResult r = profiler.stopProfiling();
        var kd = r.getKernelProfiles().get(0);

        assertEquals(expected, kd.firstExecutionTime,
                "firstExecutionTime must match the injected nanoTime at first invocation");
        assertEquals(30.0f, kd.totalExecutionTimeMs, 0.001f, "execution times accumulate");
        assertEquals(2, kd.executionCount);
    }

    // -------------------------------------------------------------------------
    // No-op when not profiling
    // -------------------------------------------------------------------------

    @Test
    void recordMemoryAccess_ignoredWhenNotProfiling() {
        // Not started
        profiler.recordMemoryAccess("ignored", 1, 1L);

        profiler.startProfiling();
        profiler.stopProfiling();

        // No entries should appear in the next result
        profiler.startProfiling();
        ProfileResult r = profiler.stopProfiling();
        assertTrue(r.getMemoryAccessProfiles().isEmpty(), "No accesses recorded while not profiling");
    }

    @Test
    void stopProfiling_throwsIfNotStarted() {
        assertThrows(IllegalStateException.class, profiler::stopProfiling,
                "stopProfiling() must throw when called without startProfiling()");
    }

    // -------------------------------------------------------------------------
    // Default constructor does not call System.nanoTime() after injection
    // -------------------------------------------------------------------------

    @Test
    void defaultConstructor_canBeReplacedBySetNanoTime() {
        ESVOOptimizationProfiler p = new ESVOOptimizationProfiler();
        StepClock controlled = new StepClock(100_000_000L);
        p.setNanoTime(controlled);

        p.startProfiling();
        controlled.advance(3_000_000L);
        ProfileResult r = p.stopProfiling();

        assertEquals(3.0, r.getTotalProfilingTime(), 1e-9,
                "After setNanoTime() the injected supplier must be used, not System.nanoTime()");
    }

    // -------------------------------------------------------------------------
    // identifyBottlenecks smoke-test (pure logic, no time dependency)
    // -------------------------------------------------------------------------

    @Test
    void identifyBottlenecks_detectsLowBandwidth() {
        profiler.startProfiling();
        clock.advance(1_000_000_000L); // 1 s in nanos → 1000 ms total profiling time
        profiler.recordMemoryAccess("slowOp", 1, 1024L); // 1 KB in 1 s → <<1 GB/s
        ProfileResult r = profiler.stopProfiling();

        var bottlenecks = profiler.identifyBottlenecks(r);
        assertFalse(bottlenecks.isEmpty(), "Low bandwidth must be detected as a bottleneck");
        assertTrue(bottlenecks.stream().anyMatch(
                b -> b.getType() == ESVOOptimizationProfiler.BottleneckType.MEMORY_BANDWIDTH),
                "Bottleneck type must be MEMORY_BANDWIDTH");
    }
}
